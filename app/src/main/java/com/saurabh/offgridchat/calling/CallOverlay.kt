package com.saurabh.offgridchat.calling

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.offgridchat.ui.components.OgAvatar
import com.saurabh.offgridchat.ui.theme.LocalOgColors
import kotlinx.coroutines.delay

@Composable
fun AestheticCallBackground(
    content: @Composable () -> Unit
) {
    val dark = isSystemInDarkTheme()

    val shape = RoundedCornerShape(28.dp)
    val baseColors = if (dark) {
        listOf(
            Color(0xFF192B54),
            Color(0xFF151A38),
            Color(0xFF102F39)
        )
    } else {
        listOf(
            Color(0xFFF3F2FF),
            Color(0xFFEAF5FF),
            Color(0xFFE8FAF7)
        )
    }

    Surface(
        shape = shape,
        color = Color.Transparent,
        shadowElevation = 20.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.linearGradient(baseColors)
                )
                .border(
                    1.dp,
                    Color.White.copy(
                        alpha = if (dark) 0.18f else 0.75f
                    ),
                    shape
                )
        ) {
            Canvas(
                modifier = Modifier.matchParentSize()
            ) {
                val purpleCenter = Offset(
                    size.width * 0.1f,
                    size.height * 0.05f
                )
                val purpleRadius = size.width * 0.9f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF9D72FF).copy(
                                alpha = if (dark) 0.30f else 0.18f
                            ),
                            Color.Transparent
                        ),
                        center = purpleCenter,
                        radius = purpleRadius
                    ),
                    radius = purpleRadius,
                    center = purpleCenter
                )

                val cyanCenter = Offset(
                    size.width * 0.95f,
                    size.height * 0.85f
                )
                val cyanRadius = size.width * 0.8f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF22D3EE).copy(
                                alpha = if (dark) 0.22f else 0.15f
                            ),
                            Color.Transparent
                        ),
                        center = cyanCenter,
                        radius = cyanRadius
                    ),
                    radius = cyanRadius,
                    center = cyanCenter
                )
            }

            content()
        }
    }
}

@Composable
fun CallOverlay(calls: VoiceCallManager) {
    val call by calls.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = LocalOgColors.current
    var answeringId by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) answeringId?.let { calls.answer(it) }
        else Toast.makeText(context, "Allow microphone access to answer the call.", Toast.LENGTH_LONG).show()
        answeringId = null
    }
    var seconds by remember(call.id) { mutableLongStateOf(0) }
    LaunchedEffect(call.phase, call.startedAt) {
        if (call.phase == CallPhase.ACTIVE) while (true) {
            seconds = (SystemClock.elapsedRealtime() - call.startedAt) / 1_000
            delay(1_000)
        }
    }
    if (call.phase == CallPhase.IDLE) return
    Dialog(onDismissRequest = { if (!call.ongoing) calls.dismiss() }) {
        AestheticCallBackground {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text("OffGrid voice call", color = colors.textMuted, fontSize = 13.sp)
                OgAvatar(call.name, size = 72.dp)
                Text(call.name, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                    textAlign = TextAlign.Center)
                Text(when (call.phase) {
                    CallPhase.INCOMING -> "Incoming call"
                    CallPhase.OUTGOING -> "Calling…"
                    CallPhase.CONNECTING -> "Connecting…"
                    CallPhase.ACTIVE -> "%02d:%02d".format(seconds / 60, seconds % 60)
                    CallPhase.ENDED -> call.message
                    else -> ""
                }, color = colors.textMuted, textAlign = TextAlign.Center)

                if (call.phase == CallPhase.INCOMING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = { calls.hangUp(call.id) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))) {
                            Icon(Icons.Default.CallEnd, null); Spacer(Modifier.width(6.dp)); Text("Decline")
                        }
                        Button(onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                calls.answer(call.id)
                            else { answeringId = call.id; permission.launch(Manifest.permission.RECORD_AUDIO) }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16803D))) {
                            Icon(Icons.Default.Call, null); Spacer(Modifier.width(6.dp)); Text("Answer")
                        }
                    }
                } else if (call.ongoing) {
                    if (call.phase == CallPhase.ACTIVE) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { calls.toggleMute() }, modifier = Modifier.size(52.dp)
                                .background(if (call.muted) colors.pill else colors.fill, CircleShape)) {
                                Icon(if (call.muted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = if (call.muted) "Unmute microphone" else "Mute microphone", tint = colors.text)
                            }
                            Text(if (call.muted) "Muted" else "Mute", color = colors.textMuted, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { calls.toggleSpeaker() }, modifier = Modifier.size(52.dp)
                                .background(if (call.speaker) colors.pill else colors.fill, CircleShape)) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = if (call.speaker) "Turn speaker off" else "Turn speaker on", tint = colors.text)
                            }
                            Text(if (call.speaker) "Speaker on" else "Speaker", color = colors.textMuted, fontSize = 12.sp)
                        }
                    }
                    Button(onClick = { calls.hangUp(call.id) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))) {
                        Icon(Icons.Default.CallEnd, null); Spacer(Modifier.width(8.dp)); Text("Hang up")
                    }
                } else Button(onClick = { calls.dismiss() }) { Text("Close") }
                if (call.ongoing) Text("Encrypted · Direct Bluetooth", color = colors.textMuted, fontSize = 11.sp)
            }
        }
    }
}
