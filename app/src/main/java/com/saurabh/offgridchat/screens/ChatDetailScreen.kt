package com.saurabh.offgridchat.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.IconButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.saurabh.offgridchat.attachments.Attachment
import com.saurabh.offgridchat.ui.components.AttachmentMessage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.offgridchat.database.MESSAGE_STATUS_READ
import com.saurabh.offgridchat.tools.ChatMessage
import com.saurabh.offgridchat.tools.PeerReachability
import com.saurabh.offgridchat.tools.formatTime
import com.saurabh.offgridchat.ui.components.MessageDeliveryStatus
import com.saurabh.offgridchat.ui.components.AuroraBackground
import com.saurabh.offgridchat.ui.components.GlassCard
import com.saurabh.offgridchat.ui.components.OgAvatar
import com.saurabh.offgridchat.ui.theme.LocalDarkTheme
import com.saurabh.offgridchat.ui.theme.LocalOgAccent
import com.saurabh.offgridchat.ui.theme.LocalOgColors
import com.saurabh.offgridchat.viewmodel.BluetoothViewModel

@Composable
fun ChatDetailScreen(
    vm: BluetoothViewModel = hiltViewModel(),
    address: String,
    onBack: () -> Unit
) {
    val c = LocalOgColors.current
    val a = LocalOgAccent.current

    val context = LocalContext.current
    val callState by vm.callState.collectAsStateWithLifecycle()
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startCall(address)
        else Toast.makeText(context, "Allow microphone access to make voice calls.", Toast.LENGTH_LONG).show()
    }

    val convos by vm.conversations.collectAsStateWithLifecycle()
    val msgs by vm.messages.collectAsStateWithLifecycle()
    val peerStates by vm.reachability.collectAsStateWithLifecycle()
    val transfers by vm.fileTransfers.collectAsStateWithLifecycle()
    val transfer = transfers[address]
    val sendingFile = transfer == "Preparing file…" || transfer?.startsWith("Sending") == true
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.sendFile(address, uri)
    }

    val convo =
        convos.firstOrNull {
            it.address == address
        }

    val name =
        convo?.name
            ?: vm.nameOf(address)

    val peerState = peerStates[convo?.nodeId] ?: peerStates[address] ?: PeerReachability.UNREACHABLE
    val connected = peerState.reachable && convo?.isBlocked != true

    LaunchedEffect(address) {
        vm.openChat(address)
    }

    var text by remember {
        mutableStateOf("")
    }

    val listState =
        rememberLazyListState()

    /*
     * Scroll behavior.
     *
     * UI behavior remains exactly the same as before.
     */
    LaunchedEffect(msgs) {

        if (msgs.isNotEmpty()) {
            listState.animateScrollToItem(
                msgs.lastIndex
            )
        }

        /*
         * Mark incoming messages as READ locally and send
         * a read receipt using the message's authenticated
         * nodeId.
         *
         * IMPORTANT:
         * Do NOT fall back to Bluetooth address.
         */
        msgs.filter {
            !it.fromMe &&
                    it.status != MESSAGE_STATUS_READ
        }
            .forEach { unread ->

                vm.markMessageRead(
                    unread.messageId
                )
            }
    }

    AuroraBackground {

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {

            /* ====================================================
               HEADER
               ==================================================== */

            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 12.dp,
                        vertical = 6.dp
                    ),
                radius = 22.dp
            ) {

                Row(
                    Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 10.dp
                    ),
                    verticalAlignment =
                        Alignment.CenterVertically,
                    horizontalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {

                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(c.fill)
                            .border(
                                1.dp,
                                c.border,
                                CircleShape
                            )
                            .clickable(
                                onClick = onBack
                            ),
                        contentAlignment =
                            Alignment.Center
                    ) {

                        Icon(
                            imageVector =
                                Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription =
                                "Back",
                            tint =
                                c.text,
                            modifier =
                                Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (!connected || peerState != PeerReachability.DIRECT) {
                                Toast.makeText(context, "Voice calls need a direct Bluetooth connection.", Toast.LENGTH_LONG).show()
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                vm.startCall(address)
                            } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = !callState.ongoing,
                        modifier = Modifier.size(38.dp).clip(CircleShape).background(c.fill)
                            .border(1.dp, c.border, CircleShape)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Voice call", modifier = Modifier.size(20.dp),
                            tint = if (connected && peerState == PeerReachability.DIRECT && !callState.ongoing) a.a1 else c.textMuted)
                    }

                    OgAvatar(
                        name,
                        size = 44.dp
                    )

                    Column(
                        Modifier.weight(1f)
                    ) {

                        Text(
                            text = name,
                            fontSize = 16.sp,
                            fontWeight =
                                FontWeight.Bold,
                            color = c.text,
                            maxLines = 1
                        )

                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.spacedBy(6.dp)
                        ) {

                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (convo?.isOnline == true) {
                                            c.ok
                                        } else {
                                            c.warn
                                        }
                                    )
                            )

                            Text(
                                text =
                                    if (connected) {
                                        "Connected • E2EE Active"
                                    } else {
                                        "Disconnected"
                                    },
                                fontSize = 12.sp,
                                color =
                                    if (connected) {
                                        c.ok
                                    } else {
                                        c.textMuted
                                    },
                                fontWeight =
                                    FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }

                    if (connected && peerState == PeerReachability.DIRECT) {

                        Box(
                            Modifier
                                .clip(
                                    RoundedCornerShape(
                                        12.dp
                                    )
                                )
                                .background(c.fill)
                                .border(
                                    1.dp,
                                    c.border,
                                    RoundedCornerShape(
                                        12.dp
                                    )
                                )
                                .clickable {
                                    vm.disconnect(address)
                                }
                                .padding(
                                    horizontal = 8.dp,
                                    vertical = 6.dp
                                )
                        ) {

                            Icon(
                                imageVector =
                                    Icons.Default.Lock,
                                contentDescription =
                                    "E2EE Encrypted • Tap to disconnect",
                                tint =
                                    a.a1,
                                modifier =
                                    Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            /* ====================================================
               MESSAGE AREA
               ==================================================== */

            if (msgs.isEmpty()) {

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment =
                        Alignment.Center
                ) {

                    GlassCard(
                        Modifier.fillMaxWidth(),
                        radius = 24.dp
                    ) {

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment =
                                Alignment.CenterHorizontally,
                            verticalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {

                            OgAvatar(
                                name,
                                size = 56.dp
                            )

                            Text(
                                text =
                                    "Say Hello to $name 👋",
                                fontSize = 18.sp,
                                fontWeight =
                                    FontWeight.Bold,
                                color = c.text,
                                textAlign =
                                    TextAlign.Center
                            )

                            Text(
                                text =
                                    "Messages are end-to-end encrypted with AES-256-GCM and delivered via mesh network.",
                                fontSize = 13.sp,
                                color =
                                    c.textMuted,
                                textAlign =
                                    TextAlign.Center
                            )
                        }
                    }
                }

            } else {

                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding =
                        PaddingValues(
                            horizontal = 14.dp,
                            vertical = 10.dp
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {

                    items(
                        msgs,
                        key = {
                            it.id
                        }
                    ) { msg ->

                        Bubble(msg)
                    }
                }
            }

            transfer?.let {
                Text(it, color = c.textMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            }

            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    ),
                radius = 28.dp
            ) {

                Row(
                    Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 8.dp
                    ),
                    verticalAlignment =
                        Alignment.CenterVertically,
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    IconButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = connected && !sendingFile
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach file",
                            tint = if (connected && !sendingFile) a.a1 else c.textMuted)
                    }

                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text =
                                it.take(2000)
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(
                                    horizontal = 12.dp,
                                    vertical = 6.dp
                                ),
                        textStyle =
                            TextStyle(
                                color = c.text,
                                fontSize = 15.sp
                            ),
                        cursorBrush =
                            SolidColor(a.a1),
                        decorationBox = { inner ->

                            if (text.isEmpty()) {

                                Text(
                                    "Type a message…",
                                    color =
                                        c.textMuted,
                                    fontSize =
                                        15.sp
                                )
                            }

                            inner()
                        }
                    )

                    val canSend =
                        connected &&
                                text.isNotBlank()

                    Box(
                        Modifier
                            .size(42.dp)
                            .alpha(
                                if (canSend) {
                                    1f
                                } else {
                                    0.45f
                                }
                            )
                            .clip(CircleShape)
                            .background(a.gradient)
                            .clickable(
                                enabled = canSend
                            ) {

                                vm.send(
                                    address,
                                    text.trim()
                                )

                                text = ""
                            },
                        contentAlignment =
                            Alignment.Center
                    ) {

                        Icon(
                            imageVector =
                                Icons.AutoMirrored.Filled.Send,
                            contentDescription =
                                "Send",
                            tint =
                                a.on,
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .offset(
                                        x = 1.dp
                                    )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(
    m: ChatMessage
) {
    val c = LocalOgColors.current
    val a = LocalOgAccent.current
    val isDark = LocalDarkTheme.current

    val shape =
        RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart =
                if (m.fromMe) {
                    20.dp
                } else {
                    4.dp
                },
            bottomEnd =
                if (m.fromMe) {
                    4.dp
                } else {
                    20.dp
                }
        )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (m.fromMe) {
                Arrangement.End
            } else {
                Arrangement.Start
            }
    ) {

        Column(
            Modifier
                .widthIn(max = 290.dp)
                .clip(shape)
                .background(
                    brush =
                        if (m.fromMe) {
                            a.gradient
                        } else {
                            SolidColor(
                                if (isDark) {
                                    c.glass
                                } else {
                                    c.pill
                                }
                            )
                        }
                )
                .border(
                    width = 1.dp,
                    color =
                        if (m.fromMe) {
                            Color.Transparent
                        } else {
                            c.border
                        },
                    shape = shape
                )
                .padding(
                    horizontal = 14.dp,
                    vertical = 10.dp
                )
        ) {

            val attachment = remember(m.text) { Attachment.fromMessage(m.text) }
            if (attachment != null) {
                AttachmentMessage(attachment, m, if (m.fromMe) a.on else c.text)
            } else Text(
                text = m.text,
                color =
                    if (m.fromMe) {
                        a.on
                    } else {
                        c.text
                    },
                fontSize = 15.sp,
                lineHeight = 20.sp
            )

            Spacer(
                Modifier.height(4.dp)
            )

            Row(
                Modifier.align(
                    Alignment.End
                ),
                verticalAlignment =
                    Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.spacedBy(4.dp)
            ) {

                Text(
                    text =
                        formatTime(m.time),
                    fontSize = 11.sp,
                    color =
                        if (m.fromMe) {
                            a.on.copy(
                                alpha = 0.75f
                            )
                        } else {
                            c.textMuted
                        }
                )

                if (m.fromMe) {

                    MessageDeliveryStatus(m.status)
                }
            }
        }
    }
}
