package com.saurabh.offgridchat.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.offgridchat.datastore.Preferences
import com.saurabh.offgridchat.ui.components.AuroraBackground
import com.saurabh.offgridchat.ui.components.LocalBottomNavigationPadding
import com.saurabh.offgridchat.ui.components.GlassButton
import com.saurabh.offgridchat.ui.components.GlassCard
import com.saurabh.offgridchat.ui.components.OgSwitch
import com.saurabh.offgridchat.ui.theme.*
import com.saurabh.offgridchat.viewmodel.BluetoothViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Preferences,
    vm: BluetoothViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val c = LocalOgColors.current
    val accent = LocalOgAccent.current

    val mode by prefs.themeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
    val savedHue by prefs.customHueFlow.collectAsStateWithLifecycle(initialValue = 210f)

    val autoReconnect by prefs.autoReconnectFlow.collectAsStateWithLifecycle(initialValue = true)
    val runInBackground by prefs.runInBackgroundFlow.collectAsStateWithLifecycle(initialValue = true)
    val notificationsEnabled by prefs.notificationsEnabledFlow.collectAsStateWithLifecycle(initialValue = true)

    val relayMode by vm.relayMode.collectAsStateWithLifecycle()
    val requireApproval by vm.requireApproval.collectAsStateWithLifecycle()

    val blockedDevices by vm.blockedDevices.collectAsStateWithLifecycle()

    var customOpen by remember { mutableStateOf(false) }
    var draftHue by remember { mutableFloatStateOf(210f) }
    var showBlockedBottomSheet by remember { mutableStateOf(false) }
    var showExportBottomSheet by remember { mutableStateOf(false) }
    var exportStatusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(savedHue) { draftHue = savedHue }

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalBottomNavigationPadding.current)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Settings",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = c.text,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
            )

            exportStatusMessage?.let { status ->
                GlassCard(Modifier.fillMaxWidth(), radius = 16.dp) {
                    Text(
                        text = status,
                        fontSize = 13.sp,
                        color = c.ok,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // ---------- 1. Theme Card ----------
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                radius = 22.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Theme",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(c.fill)
                            .border(1.dp, c.border, RoundedCornerShape(18.dp))
                            .padding(4.dp)
                    ) {
                        ThemeMode.entries.forEach { m ->
                            val sel = m == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (sel) c.pill else Color.Transparent)
                                    .clickable { scope.launch { prefs.saveTheme(m) } }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = m.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (sel) c.text else c.textMuted
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 2. Accent Color Card ----------
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                radius = 22.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Accent color",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text
                        )
                        Text(
                            text = accent.name,
                            fontSize = 13.sp,
                            color = c.textMuted
                        )
                    }

                    // 2x2 Grid for Preset Accents
                    PresetAccents.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowItems.forEach { a ->
                                AccentTile(
                                    name = a.name,
                                    preview = a.gradient,
                                    selected = !customOpen && accent.name == a.name,
                                    selectedColor = a.a1,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    customOpen = false
                                    scope.launch { prefs.savePreset(a) }
                                }
                            }
                        }
                    }

                    // Custom theme tile (full width, rainbow spectrum)
                    val rainbow = Brush.horizontalGradient((0..6).map { Color.hsl(it * 60f, 0.85f, 0.55f) })
                    val draft = customAccent(draftHue)
                    AccentTile(
                        name = "Custom theme",
                        preview = rainbow,
                        previewHeight = 36.dp,
                        showBars = false,
                        selected = customOpen || accent.name == "Custom",
                        selectedColor = draft.a1,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        draftHue = savedHue
                        customOpen = true
                    }

                    AnimatedVisibility(customOpen) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(draft.gradient),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Preview", color = draft.on, fontWeight = FontWeight.Bold)
                            }

                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(rainbow)
                                )
                                Slider(
                                    value = draftHue,
                                    onValueChange = { draftHue = it },
                                    valueRange = 0f..360f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color.Transparent,
                                        inactiveTrackColor = Color.Transparent
                                    )
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(c.fill)
                                        .border(1.dp, c.border, RoundedCornerShape(14.dp))
                                        .clickable { customOpen = false; draftHue = savedHue }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Cancel", fontWeight = FontWeight.SemiBold, color = c.text)
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(draft.gradient)
                                        .clickable {
                                            scope.launch { prefs.saveCustom(draftHue) }
                                            customOpen = false
                                        }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("OK", fontWeight = FontWeight.SemiBold, color = draft.on)
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 3. Mesh & Relay Settings ----------
            SettingCard(
                title = "Relay Mode",
                subtitle = "Act as encrypted relay node for mesh"
            ) {
                OgSwitch(checked = relayMode) {
                    vm.setRelayMode(it)
                }
            }

            SettingCard(
                title = "Require Connection Approval",
                subtitle = "Require approval before new peers can chat"
            ) {
                OgSwitch(checked = requireApproval) {
                    vm.setRequireApproval(it)
                }
            }

            // ---------- 4. Auto-reconnect Card ----------
            SettingCard(
                title = "Auto-reconnect",
                subtitle = "Reconnect automatically when in range"
            ) {
                OgSwitch(checked = autoReconnect) {
                    scope.launch { prefs.saveAutoReconnect(it) }
                }
            }

            // ---------- 5. Run in background Card ----------
            SettingCard(
                title = "Run in background",
                subtitle = "Keep the foreground service running"
            ) {
                OgSwitch(checked = runInBackground) {
                    scope.launch { prefs.saveRunInBackground(it) }
                }
            }

            // ---------- 6. Notifications Card ----------
            SettingCard(
                title = "Notifications",
                subtitle = null
            ) {
                OgSwitch(checked = notificationsEnabled) {
                    scope.launch { prefs.saveNotificationsEnabled(it) }
                }
            }

            // ---------- 7. Export & Share Chats ----------
            SettingCard(
                title = "Share Chat History",
                subtitle = "Share chats directly via WhatsApp or System Share",
                onClick = { showExportBottomSheet = true }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Share Chats",
                    tint = c.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            // ---------- 8. Blocked devices Card ----------
            SettingCard(
                title = "Blocked devices",
                subtitle = if (blockedDevices.isNotEmpty()) "${blockedDevices.size} blocked" else null,
                onClick = { showBlockedBottomSheet = true }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Blocked devices",
                    tint = c.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(100.dp)) // Bottom bar padding
        }
    }

    // ---------- Modal Bottom Sheet: Share Chats ----------
    if (showExportBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showExportBottomSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.page,
            contentColor = c.text,
            scrimColor = Color.Black.copy(alpha = 0.65f),
            dragHandle = {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(c.textMuted.copy(alpha = 0.4f))
                )
            },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            val context = LocalContext.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Share Chat History",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.text
                )
                Text(
                    text = "Share chat transcripts directly with other apps:",
                    fontSize = 13.sp,
                    color = c.textMuted
                )

                // 1. Direct Share via WhatsApp
                GlassButton("💬 Share directly on WhatsApp", modifier = Modifier.fillMaxWidth()) {
                    showExportBottomSheet = false
                    vm.shareToWhatsApp(context, address = null) {
                        exportStatusMessage = "WhatsApp is not installed on this device."
                    }
                }

                // 2. Open Native System Share Sheet
                GlassButton("📤 Open System Share Sheet", modifier = Modifier.fillMaxWidth()) {
                    showExportBottomSheet = false
                    vm.shareGeneral(context, address = null)
                }
            }
        }
    }

    // ---------- Modal Bottom Sheet: Blocked Devices ----------
    if (showBlockedBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBlockedBottomSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.page,
            contentColor = c.text,
            scrimColor = Color.Black.copy(alpha = 0.65f),
            dragHandle = {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(c.textMuted.copy(alpha = 0.4f))
                )
            },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Blocked Devices",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.text
                    )
                    Text(
                        text = "${blockedDevices.size} blocked",
                        fontSize = 12.sp,
                        color = c.textMuted
                    )
                }

                if (blockedDevices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No blocked devices.",
                            fontSize = 14.sp,
                            color = c.textMuted
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.heightIn(max = 280.dp)
                    ) {
                        items(blockedDevices) { item ->
                            GlassCard(modifier = Modifier.fillMaxWidth(), radius = 16.dp) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            color = c.text,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "FP: ${item.fingerprint}",
                                            color = c.textMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                    GlassButton("Unblock", danger = true) {
                                        vm.unblockDevice(item.nodeId)
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

@Composable
private fun SettingCard(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    val c = LocalOgColors.current
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        radius = 22.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.text
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = c.textMuted
                    )
                }
            }
            trailing()
        }
    }
}

@Composable
private fun AccentTile(
    name: String,
    preview: Brush,
    selected: Boolean,
    selectedColor: Color,
    modifier: Modifier = Modifier,
    previewHeight: Dp = 60.dp,
    showBars: Boolean = true,
    onClick: () -> Unit
) {
    val c = LocalOgColors.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(c.fill)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) selectedColor else c.border,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(preview)
                .padding(10.dp)
        ) {
            if (showBars) {
                Column(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.35f))
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.85f)
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f))
                    )
                }
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.Black,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
        Text(
            text = name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = c.text,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
    }
}
