package com.saurabh.offgridchat.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.saurabh.offgridchat.repository.ChatRepository
import com.saurabh.offgridchat.tools.formatTime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class ExportFormat {
    TXT, JSON, ZIP
}

@Singleton
class ChatExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepo: ChatRepository
) {

    private suspend fun remoteName(address: String, storedName: String?): String =
        com.saurabh.offgridchat.tools.PeerNames.resolve(
            address, storedName, chatRepo.getPeerForConversation(address)?.name
        )

    suspend fun getFormattedChatText(address: String? = null): String = withContext(Dispatchers.IO) {
        val convos = chatRepo.conversations().first()
        val targetConvos = if (address != null) {
            convos.filter { it.address == address }
        } else {
            convos
        }

        if (targetConvos.isEmpty()) return@withContext "No chats found to export."

        val sb = StringBuilder()
        sb.appendLine("📱 OffGrid Chat Export")
        sb.appendLine("Exported At: ${formatTime(System.currentTimeMillis())}")
        sb.appendLine("========================================\n")

        for (c in targetConvos) {
            val msgs = chatRepo.messages(c.address).first()
            val convo = chatRepo.getConversation(c.address)
            val name = remoteName(c.address, convo?.name ?: c.name)

            sb.appendLine("=== Chat with $name (${c.address}) ===")
            if (msgs.isEmpty()) {
                sb.appendLine("(No messages in this chat)")
            } else {
                for (m in msgs) {
                    val sender = if (m.fromMe) "You" else name
                    sb.appendLine("[${formatTime(m.time)}] $sender: ${m.text}")
                }
            }
            sb.appendLine("\n----------------------------------------\n")
        }
        sb.toString().trim()
    }

    suspend fun shareToWhatsApp(
        context: Context,
        address: String? = null,
        onPackageNotFound: () -> Unit
    ) = withContext(Dispatchers.Main) {
        val text = getFormattedChatText(address)
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                setPackage("com.whatsapp")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val w4bIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    setPackage("com.whatsapp.w4b")
                }
                context.startActivity(w4bIntent)
            } catch (_: Exception) {
                onPackageNotFound()
            }
        }
    }

    suspend fun shareGeneral(
        context: Context,
        address: String? = null
    ) = withContext(Dispatchers.Main) {
        val text = getFormattedChatText(address)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, "Share OffGrid Chat Export")
        context.startActivity(chooser)
    }

    suspend fun exportChat(
        address: String?,
        uri: Uri,
        format: ExportFormat
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val convos = chatRepo.conversations().first()
            val targetConvos = if (address != null) {
                convos.filter { it.address == address }
            } else {
                convos
            }

            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open file for writing.")

            outputStream.use { os ->
                when (format) {
                    ExportFormat.TXT -> exportAsTxt(targetConvos.map { it.address }, os)
                    ExportFormat.JSON -> exportAsJson(targetConvos.map { it.address }, os)
                    ExportFormat.ZIP -> exportAsZip(targetConvos.map { it.address }, os)
                }
            }
        }
    }

    private suspend fun exportAsTxt(addresses: List<String>, os: OutputStream) {
        val sb = StringBuilder()
        for (addr in addresses) {
            val msgs = chatRepo.messages(addr).first()
            val convo = chatRepo.getConversation(addr)
            val name = remoteName(addr, convo?.name)

            sb.appendLine("=== Chat Export: $name ($addr) ===")
            sb.appendLine("Exported At: ${formatTime(System.currentTimeMillis())}")
            sb.appendLine("----------------------------------------")

            for (m in msgs) {
                val sender = if (m.fromMe) "You" else name
                sb.appendLine("[${formatTime(m.time)}] $sender: ${m.text}")
            }
            sb.appendLine("\n")
        }
        withContext(Dispatchers.IO) {
            os.write(sb.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private suspend fun exportAsJson(addresses: List<String>, os: OutputStream) {
        val rootArray = JSONArray()
        for (addr in addresses) {
            val msgs = chatRepo.messages(addr).first()
            val convo = chatRepo.getConversation(addr)
            val convoObj = JSONObject()
            convoObj.put("address", addr)
            convoObj.put("name", remoteName(addr, convo?.name))
            convoObj.put("nodeId", convo?.nodeId ?: "")
            convoObj.put("fingerprint", convo?.fingerprint ?: "")

            val msgArray = JSONArray()
            for (m in msgs) {
                val msgObj = JSONObject()
                msgObj.put("messageId", m.messageId)
                msgObj.put("text", m.text)
                msgObj.put("fromMe", m.fromMe)
                msgObj.put("time", m.time)
                msgObj.put("status", m.status)
                msgArray.put(msgObj)
            }
            convoObj.put("messages", msgArray)
            rootArray.put(convoObj)
        }
        withContext(Dispatchers.IO) {
            os.write(rootArray.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    private suspend fun exportAsZip(addresses: List<String>, os: OutputStream) {
        ZipOutputStream(os).use { zos ->
            for (addr in addresses) {
                val msgs = chatRepo.messages(addr).first()
                val convo = chatRepo.getConversation(addr)
                val name = remoteName(addr, convo?.name)
                val fileName = "${name.replace(" ", "_")}_$addr.json"

                val convoObj = JSONObject()
                convoObj.put("address", addr)
                convoObj.put("name", name)
                val msgArray = JSONArray()
                for (m in msgs) {
                    val msgObj = JSONObject()
                    msgObj.put("messageId", m.messageId)
                    msgObj.put("text", m.text)
                    msgObj.put("fromMe", m.fromMe)
                    msgObj.put("time", m.time)
                    msgObj.put("status", m.status)
                    msgArray.put(msgObj)
                }
                convoObj.put("messages", msgArray)

                val entry = ZipEntry(fileName)
                zos.putNextEntry(entry)
                zos.write(convoObj.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }
}
