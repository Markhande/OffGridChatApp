package com.saurabh.offgridchat.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.saurabh.offgridchat.attachments.Attachment
import com.saurabh.offgridchat.attachments.AttachmentStore
import com.saurabh.offgridchat.tools.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AttachmentMessage(attachment: Attachment, message: ChatMessage, color: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(attachment, message.nodeId, message.fromMe) {
        AttachmentStore(context).file(attachment, if (message.fromMe) null else message.nodeId)
    }
    var saving by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(attachment.mime)) { uri ->
        if (uri != null) scope.launch {
            saving = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                }
            }
            saving = false
            Toast.makeText(context, if (result.isSuccess) "File saved" else "Could not save file", Toast.LENGTH_SHORT).show()
        }
    }
    Row {
        Column(Modifier.weight(1f).clickable {
            try {
                check(file.exists())
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file, attachment.name)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(context, "No app can open this file. Use the download button to save it.", Toast.LENGTH_LONG).show()
            }
        }.padding(vertical = 4.dp)) {
            Icon(Icons.Default.AttachFile, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Text(attachment.name, color = color, fontSize = 15.sp)
            Text(android.text.format.Formatter.formatShortFileSize(context, attachment.size), color = color.copy(alpha = .75f), fontSize = 12.sp)
            Text("Tap to open", color = color.copy(alpha = .75f), fontSize = 11.sp)
        }
        IconButton(onClick = { save.launch(attachment.name) }, enabled = !saving) {
            Icon(Icons.Default.Download, contentDescription = "Save ${attachment.name}", tint = color)
        }
    }
}
