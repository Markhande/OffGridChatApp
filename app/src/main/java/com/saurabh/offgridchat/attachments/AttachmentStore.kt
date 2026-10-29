package com.saurabh.offgridchat.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class Attachment(val id: String, val name: String, val mime: String, val size: Long, val sha256: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("mime", mime)
        .put("size", size).put("sha256", sha256)
    fun message(): String = PREFIX + json().toString()
    companion object {
        const val PREFIX = "offgrid-file-v1:"
        fun fromMessage(text: String): Attachment? = if (!text.startsWith(PREFIX)) null else
            runCatching { parse(JSONObject(text.removePrefix(PREFIX))) }.getOrNull()
        fun parse(json: JSONObject): Attachment {
            val result = Attachment(json.getString("id"), json.getString("name"), json.getString("mime"),
                json.getLong("size"), json.getString("sha256"))
            require(runCatching { UUID.fromString(result.id).toString() == result.id }.getOrDefault(false))
            require(result.name.isNotBlank() && result.name.length <= 200 && result.name.none { it.isISOControl() })
            require(result.mime.length in 1..150 && result.mime.matches(Regex("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+")))
            require(result.size in 0..AttachmentStore.MAX_BYTES && result.sha256.matches(Regex("[a-f0-9]{64}")))
            return result
        }
    }
}

/** Files stay private; names supplied by peers are never used as filesystem paths. */
@Singleton
class AttachmentStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val root get() = File(context.filesDir, "attachments").apply { mkdirs() }
    private data class Receiving(val attachment: Attachment, var next: Int, var touched: Long)
    private val receiving = mutableMapOf<String, Receiving>()

    fun file(attachment: Attachment, sender: String? = null): File = File(root,
        if (sender == null) attachment.id else key(sender, attachment.id))

    fun prepare(uri: Uri): Attachment {
        val resolver = context.contentResolver
        var name = "Attachment"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = it.getString(nameIndex) ?: name
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) require(it.getLong(sizeIndex) <= MAX_BYTES) { "Files must be 256 MB or smaller." }
            }
        }
        name = name.filterNot { it.isISOControl() }.take(200).ifBlank { "Attachment" }
        val id = UUID.randomUUID().toString()
        val output = File(root, id)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            requireNotNull(resolver.openInputStream(uri)) { "Cannot read this file." }.use { input ->
                output.outputStream().use { out ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_BYTES) { "Files must be 256 MB or smaller." }
                        digest.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                    }
                }
            }
            val mime = resolver.getType(uri)?.takeIf { it.length <= 150 && it.matches(Regex("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+")) }
                ?: "application/octet-stream"
            return Attachment(id, name, mime, size, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (e: Exception) {
            output.delete()
            throw e
        }
    }

    /** Returns the attachment only after its last chunk and whole-file hash have been verified. */
    @Synchronized
    fun receive(sender: String, payload: String): Attachment? {
        val json = JSONObject(payload)
        val attachment = Attachment.parse(json)
        val index = json.getInt("index")
        val count = chunkCount(attachment.size)
        require(index in 0 until count)
        val encoded = json.getString("data")
        require(encoded.length <= ((CHUNK_BYTES + 2) / 3) * 4)
        val bytes = Base64.getDecoder().decode(encoded)
        val expected = minOf(CHUNK_BYTES.toLong(), attachment.size - index.toLong() * CHUNK_BYTES).toInt()
        require(bytes.size == expected)
        val key = key(sender, attachment.id)
        val destination = file(attachment, sender)
        if (destination.exists()) {
            require(destination.length() == attachment.size)
            return if (index == count - 1) attachment else null
        }
        val now = System.currentTimeMillis()
        val expired = receiving.filterValues { now - it.touched > 10 * 60_000 }.keys.toList()
        expired.forEach { File(root, "$it.part").delete(); receiving.remove(it) }
        val state = receiving[key] ?: run {
            require(index == 0 && receiving.size < 4) { "Transfer is unavailable. Please resend the file." }
            // Clear abandoned partial bytes for this exact transfer before starting again.
            File(root, "$key.part").outputStream().close()
            Receiving(attachment, 0, now).also { receiving[key] = it }
        }
        require(state.attachment == attachment && index <= state.next)
        state.touched = now
        val partial = File(root, "$key.part")
        if (index == state.next) {
            RandomAccessFile(partial, "rw").use { it.seek(index.toLong() * CHUNK_BYTES); it.write(bytes) }
            state.next++
        }
        if (state.next != count) return null
        val hash = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        if (hash.digest().joinToString("") { "%02x".format(it) } != attachment.sha256) {
            partial.delete(); receiving.remove(key)
            error("File verification failed.")
        }
        check(partial.renameTo(destination)) { "Cannot save received file." }
        receiving.remove(key)
        return attachment
    }

    private fun key(sender: String, id: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$sender:$id".toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_BYTES = 256L * 1024 * 1024
        const val CHUNK_BYTES = 16 * 1024
        fun chunkCount(size: Long): Int = maxOf(1, ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt())
    }
}
