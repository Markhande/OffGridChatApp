package com.saurabh.offgridchat.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/* ---------- Dark / Light palettes ---------- */
data class OgColors(
    val bg: Color,          // screen background
    val page: Color,        // window background
    val text: Color,
    val textMuted: Color,
    val glass: Color,       // glass card fill
    val border: Color,      // glass border
    val highlight: Color,   // top inner highlight line
    val fill: Color,        // soft fills (switch track, selected nav pill)
    val pill: Color,        // raised pill (switch thumb, segmented selected)
    val shadow: Color,
    val labelBg: Color,     // radar label
    val ok: Color,
    val warn: Color,
    val danger: Color,
)

val OgDarkColors = OgColors(
    bg = Color(0xFF12131A),
    page = Color(0xFF0A0B10),
    text = Color(0xFFE8EAF1),
    textMuted = Color(0xFF9AA0AE),
    glass = Color(0x12FFFFFF),
    border = Color(0x29FFFFFF),
    highlight = Color(0x40FFFFFF),
    fill = Color(0x1AFFFFFF),
    pill = Color(0x26FFFFFF),
    shadow = Color(0x59000000),
    labelBg = Color(0x9912131A),
    ok = Color(0xFF34D399),
    warn = Color(0xFFFBBF24),
    danger = Color(0xFFEF4444),
)

val OgLightColors = OgColors(
    bg = Color(0xFFEEF1F8),
    page = Color(0xFFDDE2EE),
    text = Color(0xFF14171F),
    textMuted = Color(0xFF5B6476),
    glass = Color(0x99FFFFFF),
    border = Color(0x1F141A28),
    highlight = Color(0xF2FFFFFF),
    fill = Color(0x12141A28),
    pill = Color(0xFFFFFFFF),
    shadow = Color(0x2E28375A),
    labelBg = Color(0xBFFFFFFF),
    ok = Color(0xFF34D399),
    warn = Color(0xFFFBBF24),
    danger = Color(0xFFEF4444),
)

/* ---------- Accent themes ---------- */
data class OgAccent(
    val name: String,
    val a1: Color,   // primary accent
    val a2: Color,   // gradient partner
    val on: Color,   // text/icon on accent
) {
    val gradient: Brush get() = Brush.linearGradient(listOf(a1, a2))
    val bubbleFill: Color get() = a1.copy(alpha = 0.28f)
    val bubbleBorder: Color get() = a1.copy(alpha = 0.50f)
    val ring: Color get() = a1.copy(alpha = 0.35f)
    val sweep: Color get() = a1.copy(alpha = 0.50f)
    val glow: Color get() = a1.copy(alpha = 0.55f)
    val chipFill: Color get() = a1.copy(alpha = 0.18f)
}

val OceanAccent = OgAccent("Ocean", Color(0xFF1A8CFF), Color(0xFF22D3EE), Color.White)
val VioletAccent = OgAccent("Violet", Color(0xFF8B5CF6), Color(0xFFD946EF), Color.White)
val GoldAccent = OgAccent("Gold", Color(0xFFE8A317), Color(0xFFFF7A3D), Color(0xFF1B1300))
val EmeraldAccent = OgAccent("Emerald", Color(0xFF10B981), Color(0xFF2DD4BF), Color(0xFF052E1F))

val DefaultAccent = OceanAccent
val PresetAccents = listOf(OceanAccent, VioletAccent, GoldAccent, EmeraldAccent)

/* ---------- Custom theme (hue slider 0..360) ---------- */
fun customAccent(hue: Float): OgAccent {
    val a1 = Color.hsl(hue, 0.85f, 0.55f)
    val a2 = Color.hsl((hue + 40f) % 360f, 0.90f, 0.60f)
    val lum = 0.299f * a1.red + 0.587f * a1.green + 0.114f * a1.blue
    return OgAccent("Custom", a1, a2, if (lum > 0.55f) Color(0xFF1B1B1B) else Color.White)
}

enum class ThemeMode { System, Light, Dark }