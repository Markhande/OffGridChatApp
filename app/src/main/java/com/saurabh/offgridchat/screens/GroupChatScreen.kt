package com.saurabh.offgridchat.screens

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.saurabh.offgridchat.tools.PeerReachability
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.saurabh.offgridchat.database.GroupMessageEntity
import com.saurabh.offgridchat.database.MESSAGE_STATUS_DELIVERED
import com.saurabh.offgridchat.database.MESSAGE_STATUS_READ
import com.saurabh.offgridchat.database.MESSAGE_STATUS_SENT
import com.saurabh.offgridchat.database.MESSAGE_STATUS_SENDING
import com.saurabh.offgridchat.database.MESSAGE_STATUS_FAILED
import com.saurabh.offgridchat.ui.theme.LocalDarkTheme
import com.saurabh.offgridchat.ui.theme.LocalOgAccent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.Lifecycle
import com.saurabh.offgridchat.protocol.GroupEnvelope
import com.saurabh.offgridchat.tools.formatTime
import com.saurabh.offgridchat.ui.components.*
import com.saurabh.offgridchat.ui.theme.LocalOgColors
import com.saurabh.offgridchat.viewmodel.BluetoothViewModel
import com.saurabh.offgridchat.viewmodel.GroupViewModel

@Composable
fun CreateGroupDialog(vm: GroupViewModel, onDismiss: () -> Unit, onCreated: (String) -> Unit,
    bt: BluetoothViewModel = hiltViewModel()) {
    val peers by vm.eligible.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val reachability by bt.reachability.collectAsStateWithLifecycle()
    val colors = LocalOgColors.current
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { vm.loadEligible() }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false
    )) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                contentColor = colors.text) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The form can scroll too when the keyboard leaves very little height.
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text("Create group", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(value = name, onValueChange = { if (it.length <= 80) name = it },
                                label = { Text("Group name") }, placeholder = { Text("Enter group name…") },
                                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(16.dp))
                            Text("Select members", fontWeight = FontWeight.SemiBold)
                            Text("Up to ${GroupEnvelope.MAX_MEMBERS - 1} peers. Unreachable invitations can be retried.",
                                fontSize = 12.sp, color = colors.textMuted)
                        }
                        items(peers, key = { it.nodeId }) { peer ->
                            val checked = peer.nodeId in selected
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .toggleable(value = checked, enabled = !busy && (checked || selected.size < GroupEnvelope.MAX_MEMBERS - 1),
                                    role = Role.Checkbox, onValueChange = { want ->
                                        selected = if (want) selected + peer.nodeId else selected - peer.nodeId
                                    }).padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OgAvatar(peer.displayName, size = 36.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(peer.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.SemiBold)
                                    Text(when (reachability[peer.nodeId]) {
                                        PeerReachability.DIRECT -> "Connected"
                                        PeerReachability.MESH -> "Reachable via mesh"
                                        PeerReachability.AUTHENTICATING -> "Connecting…"
                                        else -> "Currently unreachable"
                                    }, fontSize = 12.sp, color = colors.textMuted)
                                }
                                Checkbox(checked, onCheckedChange = null,
                                    enabled = !busy && (checked || selected.size < GroupEnvelope.MAX_MEMBERS - 1))
                            }
                        }
                        if (peers.isEmpty() && !busy) item {
                            Text("No eligible peers. Connect to peers or wait for mesh announcements first.", color = colors.textMuted)
                        }
                        error?.let { message -> item { Text(message, color = colors.danger) } }
                    }
                    Text("${selected.size} members selected", fontSize = 12.sp, color = colors.textMuted)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                        TextButton(enabled = !busy && name.isNotBlank() && selected.isNotEmpty(),
                            onClick = { vm.create(name, selected, onCreated) }) { Text("Create group") }
                    }
                }
            }
        }
    }
}
@Composable
fun GroupChatScreen(groupId: String, onBack: () -> Unit,
    vm: GroupViewModel = hiltViewModel(), bt: BluetoothViewModel = hiltViewModel()) {
    val colors = LocalOgColors.current
    val groups by vm.groups.collectAsStateWithLifecycle()
    val group = groups.firstOrNull { it.groupId == groupId }
    val members by vm.members.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val deliveries by vm.deliveries.collectAsStateWithLifecycle()
    val reachability by bt.reachability.collectAsStateWithLifecycle()
    val blocked by bt.blockedDevices.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var showMembers by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var text by remember(groupId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(groupId) { vm.open(groupId) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
    // Read receipts only for messages actually visible in the foreground group conversation.
    val visibleIds by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() } }
    LaunchedEffect(messages, visibleIds, lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        messages.filter { !it.fromMe && !it.readLocally && it.messageId in visibleIds }
            .forEach { vm.read(groupId, it.messageId) }
    }
    AuroraBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GlassButton("Back", onClick = onBack)
                Text(group?.groupName ?: "Group", color = colors.text, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2)
                GlassButton("Members", onClick = { showMembers = true })
            }
            Text(if (group?.left == true) "You left this group" else "Encrypted • delivery is tracked per member",
                color = colors.textMuted, fontSize = 12.sp)
            if (deliveries.any { it.status !in listOf("DELIVERED", "READ") }) {
                GlassButton("Retry pending / unconfirmed deliveries", onClick = { vm.retry(groupId) })
            }
            error?.let { Text(it, color = colors.danger) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages, key = { it.messageId }) { message ->
                    val states = deliveries.filter {
                        it.itemId == message.messageId && it.kind == GroupEnvelope.MESSAGE
                    }
                    // Keep unreachable recipients in the aggregate; partial delivery is not complete.
                    val status = when {
                        states.isNotEmpty() && states.all { it.status == "READ" } -> MESSAGE_STATUS_READ
                        states.isNotEmpty() && states.all { it.status in listOf("DELIVERED", "READ") } -> MESSAGE_STATUS_DELIVERED
                        states.any { it.status in listOf("SENT", "DELIVERED", "READ") } -> MESSAGE_STATUS_SENT
                        states.isNotEmpty() && states.all { it.status == "FAILED" } -> MESSAGE_STATUS_FAILED
                        else -> MESSAGE_STATUS_SENDING
                    }
                    GroupMessageBubble(message, status)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { if (it.length <= GroupEnvelope.MAX_TEXT) text = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("Group message…") }, maxLines = 4,
                    enabled = group != null && !group.left && !busy)
                TextButton(enabled = group != null && !group.left && text.isNotBlank() && !busy,
                    onClick = { vm.send(groupId, text) { text = "" } }) { Text("Send") }
            }
        }
    }
    if (showMembers) AlertDialog(onDismissRequest = { showMembers = false }, title = { Text("Group members") },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(members, key = { it.nodeId }) { member ->
                val invitation = deliveries.firstOrNull { it.kind == GroupEnvelope.INVITE && it.recipientNodeId == member.nodeId }
                val status = when {
                    member.left -> "Left"
                    blocked.any { it.nodeId == member.nodeId } -> "Blocked"
                    member.nodeId == vm.localNode -> "You"
                    else -> reachability[member.nodeId]?.name?.lowercase() ?: "Unreachable"
                }
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(member.displayName, fontWeight = FontWeight.SemiBold)
                    Text(member.nodeId, fontSize = 10.sp)
                    Text(status, fontSize = 12.sp)
                    invitation?.let { Text("Invitation: ${it.status.lowercase()}", fontSize = 12.sp) }
                }
            }
        } }, confirmButton = { TextButton(onClick = { showMembers = false }) { Text("Close") } },
        dismissButton = { if (group?.left == false) TextButton(onClick = { confirmLeave = true }) { Text("Leave group") } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("Leave group?") },
        text = { Text("Your history stays on this phone. You will stop accepting group messages. Unreachable members may not receive the leave notice until you retry it.") },
        confirmButton = { TextButton(onClick = { vm.leave(groupId); confirmLeave = false; showMembers = false }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } })
}

@Composable
private fun GroupMessageBubble(message: GroupMessageEntity, status: String) {
    val colors = LocalOgColors.current
    val accent = LocalOgAccent.current
    val isDark = LocalDarkTheme.current
    val shape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomStart = if (message.fromMe) 20.dp else 4.dp,
        bottomEnd = if (message.fromMe) 4.dp else 20.dp
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .align(if (message.fromMe) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = maxWidth * 0.8f)
                .clip(shape)
                .background(if (message.fromMe) accent.gradient else SolidColor(if (isDark) colors.glass else colors.pill))
                .border(1.dp, if (message.fromMe) Color.Transparent else colors.border, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (!message.fromMe) {
                Text(message.senderName, color = colors.text, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
            }
            Text(message.text, color = if (message.fromMe) accent.on else colors.text,
                fontSize = 15.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(formatTime(message.timestamp), fontSize = 11.sp,
                    color = if (message.fromMe) accent.on.copy(alpha = 0.75f) else colors.textMuted)
                if (message.fromMe) MessageDeliveryStatus(status)
            }
        }
    }
}