package com.saurabh.offgridchat.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.offgridchat.tools.formatTime
import com.saurabh.offgridchat.ui.components.AuroraBackground
import com.saurabh.offgridchat.ui.components.LocalBottomNavigationPadding
import com.saurabh.offgridchat.ui.components.GlassCard
import com.saurabh.offgridchat.ui.components.OgAvatar
import com.saurabh.offgridchat.ui.theme.LocalOgAccent
import com.saurabh.offgridchat.ui.theme.LocalOgColors
import com.saurabh.offgridchat.viewmodel.BluetoothViewModel
import com.saurabh.offgridchat.viewmodel.GroupViewModel
import com.saurabh.offgridchat.ui.components.GlassButton

@Composable
fun ChatsScreen(
    vm: BluetoothViewModel = hiltViewModel(),
    onChatClick: (String) -> Unit,
    onNavigateToNearby: (() -> Unit)? = null,
    groupVm: GroupViewModel = hiltViewModel()
) {
    val c = LocalOgColors.current
    val a = LocalOgAccent.current
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val peerStates by vm.reachability.collectAsStateWithLifecycle()
    val groups by groupVm.groups.collectAsStateWithLifecycle()
    var createGroup by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    val filteredGroups = groups.filter { it.groupName.contains(searchQuery, ignoreCase = true) }
    if (createGroup) CreateGroupDialog(groupVm,
        onDismiss = { createGroup = false; groupVm.clearError() },
        onCreated = { createGroup = false; onChatClick("group:$it") })

    val filteredList = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) {
            conversations
        } else {
            conversations.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        (it.lastMessage?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
    }

    val connectedCount = conversations.count { chat ->
        !chat.isBlocked && (peerStates[chat.nodeId] ?: peerStates[chat.address])?.reachable == true
    }

    AuroraBackground {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {

            // Header Row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Chats",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.text
                    )
                    Text(
                        text = "Offline Bluetooth Messaging",
                        fontSize = 12.sp,
                        color = c.textMuted
                    )
                }

                // Status Badge / Connected indicator
                if (connectedCount > 0) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.fill)
                            .border(1.dp, a.a1.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(c.ok)
                            )
                            Text(
                                text = "$connectedCount Active",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = c.ok
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            GlassButton("Create group", modifier = Modifier.padding(bottom = 10.dp), onClick = { createGroup = true })
            // Search Bar (if conversations exist)
            if (conversations.isNotEmpty() || groups.isNotEmpty()) {
                GlassCard(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    radius = 18.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = c.textMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(
                                color = c.text,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(a.a1),
                            decorationBox = { inner ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search conversations…",
                                        color = c.textMuted,
                                        fontSize = 14.sp
                                    )
                                }
                                inner()
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            Text(
                                "Clear",
                                fontSize = 12.sp,
                                color = a.a1,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { searchQuery = "" }
                            )
                        }
                    }
                }
            }

            // Conversations List / Empty State
            if (filteredList.isEmpty() && filteredGroups.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    GlassCard(
                        Modifier.fillMaxWidth(),
                        radius = 24.dp
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(a.gradient),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sensors,
                                    contentDescription = "Radar",
                                    tint = a.on,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Text(
                                text = if (searchQuery.isNotBlank()) "No matching chats" else "No Conversations Yet",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.text,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = if (searchQuery.isNotBlank())
                                    "Try searching with a different name."
                                else
                                    "Connect with nearby devices using Bluetooth to start chatting without internet.",
                                fontSize = 13.sp,
                                color = c.textMuted,
                                textAlign = TextAlign.Center
                            )

                            if (onNavigateToNearby != null && searchQuery.isBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(a.gradient)
                                        .clickable(onClick = onNavigateToNearby)
                                        .padding(horizontal = 20.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "Find Nearby Devices",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = a.on
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp + LocalBottomNavigationPadding.current)
                ) {
                    items(filteredGroups, key = { "group:${it.groupId}" }) { group ->
                        GlassCard(Modifier.fillMaxWidth(), radius = 20.dp,
                            onClick = { onChatClick("group:${group.groupId}") }) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                OgAvatar(group.groupName)
                                Column(Modifier.weight(1f)) {
                                    Text(group.groupName, color = c.text, fontWeight = FontWeight.SemiBold)
                                    Text(if (group.left) "Group • You left" else "Group chat", color = c.textMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    items(filteredList, key = { it.address }) { chat ->
                        val isConnected = !chat.isBlocked &&
                            (peerStates[chat.nodeId] ?: peerStates[chat.address])?.reachable == true
                        val lastMsg = chat.lastMessage

                        GlassCard(
                            Modifier.fillMaxWidth(),
                            radius = 20.dp,
                            onClick = { onChatClick(chat.address) }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Avatar with status indicator
                                Box {
                                    OgAvatar(chat.name, size = 48.dp)
                                    if (chat.isOnline) {
                                        Box(
                                            Modifier
                                                .size(12.dp)
                                                .align(Alignment.BottomEnd)
                                                .clip(CircleShape)
                                                .background(c.ok)
                                                .border(2.dp, c.bg, CircleShape)
                                        )
                                    }
                                }

                                Column(Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = chat.name,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = c.text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        if (chat.lastTime != null && chat.lastTime > 0L) {
                                            Text(
                                                text = formatTime(chat.lastTime),
                                                fontSize = 12.sp,
                                                color = if (isConnected) c.ok else c.textMuted,
                                                fontWeight = if (isConnected) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(3.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = lastMsg ?: (if (isConnected) "Connected • Ready to chat" else "Offline"),
                                            fontSize = 13.sp,
                                            color = if (lastMsg != null) c.textMuted else (if (isConnected) c.ok else c.textMuted),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )

                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = c.textMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
