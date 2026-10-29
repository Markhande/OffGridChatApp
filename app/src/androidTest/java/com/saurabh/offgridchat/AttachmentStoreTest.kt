package com.saurabh.offgridchat

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.saurabh.offgridchat.attachments.Attachment
import com.saurabh.offgridchat.attachments.AttachmentStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class AttachmentStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "attachment-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = AttachmentStore(object : ContextWrapper(context) {
        override fun getFilesDir(): File = directory
    })
    private val sender = "test-sender"

    @After fun cleanUp() { directory.deleteRecursively() }

    private fun attachment(bytes: ByteArray, name: String = "report.pdf") = Attachment(
        UUID.randomUUID().toString(), name, "application/pdf", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun chunk(file: Attachment, bytes: ByteArray, index: Int): String {
        val start = index * AttachmentStore.CHUNK_BYTES
        return file.json().put("index", index).put("data", Base64.getEncoder()
            .encodeToString(bytes.copyOfRange(start, minOf(bytes.size, start + AttachmentStore.CHUNK_BYTES)))).toString()
    }

    @Test fun assemblesBinaryFileAndAcceptsDuplicateChunks() {
        val bytes = ByteArray(AttachmentStore.CHUNK_BYTES * 2 + 37) { (it % 251).toByte() }
        val file = attachment(bytes, "file-图片.apk")
        assertNull(store.receive(sender, chunk(file, bytes, 0)))
        assertNull(store.receive(sender, chunk(file, bytes, 0)))
        assertNull(store.receive(sender, chunk(file, bytes, 1)))
        assertEquals(file, store.receive(sender, chunk(file, bytes, 2)))
        assertEquals(file, store.receive(sender, chunk(file, bytes, 2)))
        assertArrayEquals(bytes, store.file(file, sender).readBytes())
        assertEquals(file, Attachment.fromMessage(file.message()))
    }

    @Test fun handlesEmptyFiles() {
        val bytes = byteArrayOf()
        val file = attachment(bytes)
        assertEquals(file, store.receive(sender, chunk(file, bytes, 0)))
        assertEquals(0L, store.file(file, sender).length())
    }

    @Test fun rejectsCorruptFilesWithoutPublishingThem() {
        val file = attachment(byteArrayOf(1, 2, 3))
        assertTrue(runCatching { store.receive(sender, chunk(file, byteArrayOf(1, 2, 4), 0)) }.isFailure)
        assertFalse(store.file(file, sender).exists())
    }

    @Test fun rejectsMissingAndOversizedChunks() {
        val bytes = ByteArray(AttachmentStore.CHUNK_BYTES + 1)
        val file = attachment(bytes)
        assertTrue(runCatching { store.receive(sender, chunk(file, bytes, 1)) }.isFailure)
        val oversized = file.json().put("index", 0).put("data",
            Base64.getEncoder().encodeToString(ByteArray(AttachmentStore.CHUNK_BYTES + 1))).toString()
        assertTrue(runCatching { store.receive(sender, oversized) }.isFailure)
        assertFalse(store.file(file, sender).exists())
    }

    @Test fun isolatesFilesByAuthenticatedSender() {
        val bytes = byteArrayOf(42)
        val file = attachment(bytes, "../../private.pdf")
        store.receive(sender, chunk(file, bytes, 0))
        assertTrue(store.file(file, sender).canonicalPath.startsWith(File(directory, "attachments").canonicalPath + "/"))
        assertFalse(store.file(file, "another-sender").exists())
    }

    @Test fun rejectsInvalidMetadata() {
        val file = attachment(byteArrayOf(1))
        assertNull(Attachment.fromMessage(Attachment.PREFIX + file.json().put("id", "../escape")))
        assertNull(Attachment.fromMessage(Attachment.PREFIX + file.json().put("size", AttachmentStore.MAX_BYTES + 1)))
        assertNull(Attachment.fromMessage("ordinary chat message"))
    }
}
