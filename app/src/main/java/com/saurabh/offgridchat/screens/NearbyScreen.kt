package com.saurabh.offgridchat.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.offgridchat.tools.BtDevice
import com.saurabh.offgridchat.tools.BtPermissions
import com.saurabh.offgridchat.tools.ConnState
import com.saurabh.offgridchat.tools.signalLabel
import com.saurabh.offgridchat.ui.components.*
import com.saurabh.offgridchat.ui.theme.LocalOgAccent
import com.saurabh.offgridchat.ui.theme.LocalOgColors
import com.saurabh.offgridchat.viewmodel.BluetoothViewModel
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private data class PeerActions(val nodeId: String?, val address: String, val name: String, val fingerprint: String)

@Composable
fun NearbyScreen(
    vm: BluetoothViewModel = hiltViewModel(),
    isActive: Boolean = true,
    onOpenChat: (String) -> Unit
) {
    val ctx = LocalContext.current
    val c = LocalOgColors.current

    val btOn by vm.bluetoothOn.collectAsStateWithLifecycle()
    val visible by vm.discoverable.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val sockets by vm.socketStates.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val pendingRequests by vm.pendingRequests.collectAsStateWithLifecycle()
    val blockedDevices by vm.blockedDevices.collectAsStateWithLifecycle()
    var actionPeer by remember { mutableStateOf<PeerActions?>(null) }
    var confirmBlock by remember { mutableStateOf<PeerActions?>(null) }

    var hasPerms by remember { mutableStateOf(BtPermissions.granted(ctx)) }
    var pendingEnable by remember { mutableStateOf(false) }
    var openAfterConnect by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    val enableLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode != Activity.RESULT_OK) note = "Bluetooth was not turned on"
        }
    val discoverLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_CANCELED) note = "Device was not made discoverable"
        }
    val permLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            hasPerms = res.values.all { it }
            if (!hasPerms) {
                pendingEnable = false
                note = "Bluetooth permission is required"
            } else if (pendingEnable && !btOn) {
                pendingEnable = false
                enableLauncher.launch(vm.enableIntent())
            }
        }

    LaunchedEffect(isActive) {
        if (isActive && !hasPerms) permLauncher.launch(BtPermissions.required)
    }

    LaunchedEffect(isActive, btOn, hasPerms) {
        if (!isActive) return@LaunchedEffect
        if (btOn && hasPerms) {
            vm.startServer(); vm.startScan()
        } else vm.stopScan()
    }
    DisposableEffect(isActive) {
        onDispose { if (isActive) vm.stopScan() }
    }

    LaunchedEffect(note) {
        if (note != null) {
            delay(4000.milliseconds); note = null
        }
    }

    LaunchedEffect(isActive, sockets, conversations, openAfterConnect) {
        if (!isActive) return@LaunchedEffect
        val socket = sockets[openAfterConnect]
        if (socket?.authenticated == true) {
            openAfterConnect = null
            onOpenChat(conversations.firstOrNull { it.nodeId == socket.nodeId }?.address ?: socket.address)
        }
    }
    LaunchedEffect(conn) {
        val s = conn
        if (s is ConnState.Failed) {
            openAfterConnect = null; note = s.message
        }
    }

    val sorted = remember(devices) {
        devices.sortedWith(
            compareByDescending<BtDevice> { it.capable == true }.thenByDescending {
                it.rssi ?: -200
            }
        )
    }
    val cs = conn

    AuroraBackground {
        OgScreen("Nearby") {
            if (!vm.isSupported) {
                SettingRow("Bluetooth Hardware", "Not supported on this device")
            }
            note?.let { SettingRow(it) }

            SettingRow("Bluetooth", if (btOn) "On" else "Off") {
                OgSwitch(checked = btOn, onCheckedChange = { want ->
                    if (want) {
                        if (!hasPerms) {
                            pendingEnable = true; permLauncher.launch(BtPermissions.required)
                        } else enableLauncher.launch(vm.enableIntent())
                    } else {
                        vm.turnOff {
                            note = "On Android 13+, turn off Bluetooth in Settings. Opening Settings…"
                            ctx.startActivity(vm.settingsIntent())
                        }
                    }
                })
            }

            SettingRow(
                "Visible to nearby devices",
                if (visible) "Visible to other devices (5 min)" else "Off"
            ) {
                OgSwitch(checked = visible, onCheckedChange = { want ->
                    when {
                        !btOn -> note = "Turn on Bluetooth first"
                        !hasPerms -> permLauncher.launch(BtPermissions.required)
                        want -> discoverLauncher.launch(vm.discoverableIntent())
                        else -> note = "Discoverability turns off automatically after 5 minutes"
                    }
                })
            }

            if (pendingRequests.isNotEmpty()) {
                SectionLabel("Pending Connection Requests")
                pendingRequests.forEach { req ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(req.name, fontWeight = FontWeight.Bold, color = c.text, fontSize = 15.sp)
                                Text("FP: ${req.fingerprint}", color = c.textMuted, fontSize = 12.sp)
                            }
                            GlassButton("Accept") { vm.acceptRequest(req) }
                            GlassButton("Block", danger = true) {
                                vm.blockDevice(req.requesterNodeId, req.address, req.name, req.fingerprint)
                            }
                        }
                    }
                }
            }

            if (sockets.isNotEmpty() || sorted.isNotEmpty()) {
                Text("Long-press a device for actions", color = c.textMuted, fontSize = 12.sp)
            }
            sockets.values.filter { it.authenticated }.forEach { socket ->
                val conversation = conversations.firstOrNull { it.nodeId == socket.nodeId }
                val chatAddress = conversation?.address ?: socket.address
                SettingRow(
                    conversation?.name ?: vm.nameOf(socket.address),
                    "Connected • tap to open chat",
                    onLongClick = { actionPeer = PeerActions(socket.nodeId, socket.address,
                        conversation?.name ?: vm.nameOf(socket.address), conversation?.fingerprint.orEmpty()) },
                    onClick = { onOpenChat(chatAddress) }) {
                    GlassButton("Open") { onOpenChat(chatAddress) }
                }
            }

            Radar(
                scanning = scanning,
                label = when {
                    !btOn -> "Bluetooth is off"
                    scanning -> "Scanning… (${devices.size})"
                    else -> "Devices found: ${devices.size} • tap to rescan"
                },
                onClick = { if (btOn && hasPerms) vm.startScan() }
            )

            sorted.forEach { d ->
                val sub = when {
                    sockets[d.address]?.authenticated == true -> "Connected • tap to open chat"
                    sockets[d.address] != null -> "Connecting…"
                    cs is ConnState.Connecting && cs.address == d.address -> "Connecting…"
                    d.capable == true -> "OffGrid ready" + (d.rssi?.let {
                        " • signal ${signalLabel(it)}"
                    } ?: "")
                    d.capable == false -> "Chat not supported"
                    else -> "Checking…"
                }
                GlassCard(Modifier.fillMaxWidth(), onLongClick = {
                    val conversation = conversations.firstOrNull { it.address == d.address }
                    val request = pendingRequests.firstOrNull { it.address == d.address }
                    actionPeer = PeerActions(sockets[d.address]?.nodeId ?: conversation?.nodeId ?: request?.requesterNodeId,
                        d.address, conversation?.name ?: d.name, conversation?.fingerprint ?: request?.fingerprint.orEmpty())
                }, onClick = {
                    if (d.capable == false) note = "This device is not running OffGrid"
                    else {
                        openAfterConnect = d.address; vm.connect(d.address)
                    }
                }) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OgAvatar(d.name, muted = d.capable != true)
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = c.text,
                                maxLines = 1
                            )
                            Text(sub, fontSize = 13.sp, color = c.textMuted, maxLines = 1)
                        }
                        Text("›", fontSize = 20.sp, color = c.textMuted)
                    }
                }
            }
        }
    }
    actionPeer?.let { peer ->
        val blocked = blockedDevices.any { it.nodeId == peer.nodeId }
        AlertDialog(onDismissRequest = { actionPeer = null }, title = { Text(peer.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sockets[peer.address] != null) TextButton(onClick = {
                        vm.disconnect(peer.address); actionPeer = null
                    }) { Text("Disconnect") }
                    TextButton(enabled = !peer.nodeId.isNullOrBlank() && !blocked, onClick = {
                        confirmBlock = peer; actionPeer = null
                    }) { Text(if (blocked) "Already blocked" else "Block device", color = c.danger) }
                    if (peer.nodeId.isNullOrBlank()) Text("Connect first to verify this device's identity before blocking it.", color = c.textMuted)
                    if (blocked) Text("Manage this device in Settings → Blocked Devices.", color = c.textMuted)
                }
            }, confirmButton = { TextButton(onClick = { actionPeer = null }) { Text("Cancel") } })
    }
    confirmBlock?.let { peer ->
        AlertDialog(onDismissRequest = { confirmBlock = null }, title = { Text("Block ${peer.name}?") },
            text = { Text("This device won't be able to connect or message you until you unblock it.") },
            confirmButton = { TextButton(onClick = {
                peer.nodeId?.let { vm.blockDevice(it, peer.address, peer.name, peer.fingerprint) }
                openAfterConnect = null
                confirmBlock = null
            }) { Text("Block", color = c.danger) } },
            dismissButton = { TextButton(onClick = { confirmBlock = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Radar(scanning: Boolean, label: String, onClick: () -> Unit) {
    val a = LocalOgAccent.current
    val c = LocalOgColors.current
    val angle by rememberInfiniteTransition(label = "radar").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )
    GlassCard(
        Modifier
            .fillMaxWidth()
            .height(170.dp), radius = 24.dp, onClick = onClick
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val ctr = center
                listOf(75.dp, 50.dp, 25.dp).forEach { r ->
                    drawCircle(a.ring, radius = r.toPx(), center = ctr, style = Stroke(1.dp.toPx()))
                }
                if (scanning) {
                    rotate(angle, pivot = ctr) {
                        drawCircle(
                            brush = Brush.sweepGradient(
                                0f to a.sweep,
                                0.25f to Color.Transparent,
                                1f to Color.Transparent,
                                center = ctr
                            ),
                            radius = 75.dp.toPx(), center = ctr
                        )
                    }
                }
            }
            Text(
                label, fontSize = 13.sp, color = c.textMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.labelBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
