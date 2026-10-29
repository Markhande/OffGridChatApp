package com.saurabh.offgridchat.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.offgridchat.ui.theme.LocalDarkTheme
import com.saurabh.offgridchat.ui.theme.LocalOgAccent
import com.saurabh.offgridchat.ui.theme.LocalOgColors

/** Peeche ka aurora glow (HTML ke .blob jaisa). Accent badalte hi ye bhi badalta hai. */

@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val c = LocalOgColors.current
    val a = LocalOgAccent.current
    val k = if (LocalDarkTheme.current) 1f else 0.55f
    Box(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .drawBehind {
                fun blob(color: Color, cx: Float, cy: Float, r: Float) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(color, Color.Transparent), center = Offset(cx, cy), radius = r
                        ),
                        radius = r, center = Offset(cx, cy)
                    )
                }
                blob(
                    a.a1.copy(alpha = 0.50f * k),
                    size.width * 0.05f,
                    size.height * 0.02f,
                    size.width * 0.75f
                )
                blob(
                    a.a2.copy(alpha = 0.38f * k),
                    size.width,
                    size.height * 0.40f,
                    size.width * 0.70f
                )
                blob(a.a1.copy(alpha = 0.28f * k), 0f, size.height * 0.85f, size.width * 0.65f)
            },
        content = content
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = LocalOgColors.current
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .clip(shape)
            .background(c.glass)
            .border(1.dp, c.border, shape)
            .drawBehind {
                val r = radius.toPx()
                drawLine(c.highlight, Offset(r, 0.5f), Offset(size.width - r, 0.5f), 1.dp.toPx())
            }
            .then(if (onLongClick != null) Modifier.combinedClickable(
                onClick = onClick ?: {}, onLongClick = onLongClick,
                onLongClickLabel = "Device actions"
            ) else if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content
    )
}

@Composable
fun OgScreen(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalOgColors.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomNavigationPadding.current)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.text,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalOgColors.current.textMuted,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp)
    )
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    val c = LocalOgColors.current
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick, onLongClick = onLongClick) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = c.textMuted)
            }
            trailing()
        }
    }
}

@Composable
fun OgAvatar(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    muted: Boolean = false
) {
    val c = LocalOgColors.current
    val a = LocalOgAccent.current
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(brush = if (muted) SolidColor(c.fill) else a.gradient),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text.trim().firstOrNull()?.uppercase() ?: "?",
            color = if (muted) c.textMuted else a.on,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.38f).sp
        )
    }
}

@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val c = LocalOgColors.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(c.fill)
            .border(1.dp, if (danger) c.danger.copy(alpha = 0.4f) else c.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (danger) c.danger else c.text
        )
    }
}

/** HTML wala "Off | On" sliding glass switch */
@Composable
fun OgSwitch(
    modifier: Modifier = Modifier,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = LocalOgColors.current
    val a = LocalOgAccent.current
    val x by animateDpAsState(
        targetValue = if (checked) 43.dp else 0.dp,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
        label = "swX"
    )
    Box(
        modifier
            .size(width = 90.dp, height = 38.dp)
            .clip(CircleShape)
            .background(c.fill)
            .border(1.dp, c.border, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch
            ) { onCheckedChange(!checked) }
            .padding(3.dp)
    ) {
        Row(Modifier.fillMaxSize()) {
            listOf("Off", "On").forEach {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(), Alignment.Center
                ) {
                    Text(
                        it,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textMuted
                    )
                }
            }
        }
        Box(
            Modifier
                .offset {
                    IntOffset(x.roundToPx(), 0)
                }
                .width(41.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(brush = if (checked) a.gradient else SolidColor(c.pill)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (checked) "On" else "Off", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = if (checked) a.on else c.text
            )
        }
    }
}
