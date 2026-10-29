package com.saurabh.offgridchat.screens

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.offgridchat.datastore.Preferences
import com.saurabh.offgridchat.ui.components.*
import com.saurabh.offgridchat.ui.theme.LocalOgAccent
import com.saurabh.offgridchat.ui.theme.LocalOgColors
import com.saurabh.offgridchat.viewmodel.BluetoothViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ProfileScreen(
    vm: BluetoothViewModel = hiltViewModel(),
    prefs: Preferences
) {
    val c = LocalOgColors.current
    val a = LocalOgAccent.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val identity = vm.identity

    // Dynamic preferences
    val userNameSaved by prefs.userNameFlow.collectAsStateWithLifecycle(initialValue = "You")
    val userBioSaved by prefs.userBioFlow.collectAsStateWithLifecycle(initialValue = "Offline, connect via Bluetooth")
    val onlineStatus by prefs.onlineStatusFlow.collectAsStateWithLifecycle(initialValue = true)
    val readReceipts by prefs.readReceiptsFlow.collectAsStateWithLifecycle(initialValue = true)
    val allowNewConnections by prefs.allowNewConnectionsFlow.collectAsStateWithLifecycle(
        initialValue = true
    )
    val hidePreviews by prefs.hidePreviewsFlow.collectAsStateWithLifecycle(initialValue = false)

    // Dynamic stats from Room DB & Bluetooth
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val totalMessages by vm.messageCount.collectAsStateWithLifecycle()
    val totalBytesCount by vm.totalBytes.collectAsStateWithLifecycle()
    val btOn by vm.bluetoothOn.collectAsStateWithLifecycle()

    var nameText by remember(userNameSaved) { mutableStateOf(userNameSaved) }
    var bioText by remember(userBioSaved) { mutableStateOf(userBioSaved) }

    // Dynamic Device Info
    val deviceModel = remember {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        "$manufacturer ${Build.MODEL}"
    }

    // Format Data Size
    val formattedData = remember(totalBytesCount) {
        val bytes = totalBytesCount ?: 0L
        when {
            bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576f)
            bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
            else -> "$bytes Bytes"
        }
    }

    var copied by remember { mutableStateOf(false) }
    var clearStage by remember { mutableIntStateOf(0) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500.milliseconds); copied = false
        }
    }
    LaunchedEffect(clearStage) {
        if (clearStage != 0) {
            delay(2500.milliseconds); clearStage = 0
        }
    }

    AuroraBackground {
        OgScreen("Profile") {
            // ---- Hero card ----
            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box {
                        OgAvatar(nameText.ifBlank { "You" }, size = 84.dp)
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(c.pill)
                                .border(1.dp, c.border, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Text("✎", fontSize = 13.sp, color = c.text) }
                    }
                    Spacer(Modifier.height(8.dp))

                    BasicTextField(
                        value = nameText,
                        onValueChange = {
                            val newName = it.take(24)
                            nameText = newName
                            scope.launch { prefs.saveUserName(newName) }
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = c.text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(a.a1),
                        modifier = Modifier.fillMaxWidth()
                    )

                    BasicTextField(
                        value = bioText,
                        onValueChange = {
                            val newBio = it.take(60)
                            bioText = newBio
                            scope.launch { prefs.saveUserBio(newBio) }
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = c.textMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(a.a1),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (btOn) c.ok else c.warn)
                        )
                        Text(
                            text = if (btOn) "Visible • Mesh Active" else "Bluetooth is off",
                            fontSize = 13.sp,
                            color = c.textMuted
                        )
                    }
                }
            }

            // ---- Real Stats from Database ----
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "$totalMessages" to "Messages",
                    "${conversations.size}" to "Peers",
                    formattedData to "Data"
                ).forEach { (value, label) ->
                    GlassCard(
                        Modifier.weight(1f)
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = value,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.text,
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(4.dp))

                            Text(
                                text = label,
                                fontSize = 11.sp,
                                color = c.textMuted,
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            SectionLabel("Cryptographic Identity")
            SettingRow("Device Name", deviceModel)
            SettingRow("Node ID", identity.nodeId)
            SettingRow("Public Key Fingerprint", identity.fingerprintShort) {
                GlassButton(if (copied) "Copied ✓" else "Copy") {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                ClipData.newPlainText("fingerprint", identity.fingerprintFull)
                            )
                        )
                        copied = true
                    }
                }
            }
            SettingRow("Encryption Engine", "AES-256-GCM / ECDH") {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(c.ok)
                )
            }

            SectionLabel("Privacy & Security")
            SettingRow(
                "Show online status",
                "Nearby users can see when you're active"
            ) {
                OgSwitch(checked = onlineStatus) {
                    scope.launch { prefs.saveOnlineStatus(it) }
                }
            }
            SettingRow("Read receipts", "Send read receipts") {
                OgSwitch(checked = readReceipts) {
                    scope.launch { prefs.saveReadReceipts(it) }
                }
            }
            SettingRow(
                "Allow new connections",
                "Allow new connection requests"
            ) {
                OgSwitch(checked = allowNewConnections) {
                    scope.launch { prefs.saveAllowNewConnections(it) }
                }
            }
            SettingRow("Hide message previews", "Hide message text in notifications") {
                OgSwitch(checked = hidePreviews) {
                    scope.launch { prefs.saveHidePreviews(it) }
                }
            }

            SectionLabel("Data Management")
            SettingRow("Clear all local chats", "Deletes only from this device") {
                GlassButton(
                    when (clearStage) {
                        1 -> "Tap to confirm"; 2 -> "Cleared ✓"; else -> "Clear"
                    },
                    danger = true
                ) {
                    if (clearStage == 1) {
                        vm.clearAllData()
                        clearStage = 2
                    } else {
                        clearStage = 1
                    }
                }
            }

            SectionLabel("Protocol Info")
            SettingRow("Version", "1.0.0 (Mesh Protocol v1)")
            SettingRow("Network Mode", "P2P Bluetooth Mesh + E2EE")
        }
    }
}
