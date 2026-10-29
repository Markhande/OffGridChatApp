package com.saurabh.offgridchat.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalDarkTheme = compositionLocalOf { false }
val LocalOgColors = staticCompositionLocalOf { OgDarkColors }
val LocalOgAccent = staticCompositionLocalOf { DefaultAccent }

private fun buildScheme(dark: Boolean, c: OgColors, a: OgAccent): ColorScheme {
    return if (dark) {
        darkColorScheme(
            primary = a.a1, onPrimary = a.on,
            secondary = a.a2, onSecondary = a.on,
            tertiary = a.a2, onTertiary = a.on,
            background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text,
            surfaceVariant = c.fill, onSurfaceVariant = c.textMuted,
            outline = c.border, error = c.danger,
        )
    } else {
        lightColorScheme(
            primary = a.a1, onPrimary = a.on,
            secondary = a.a2, onSecondary = a.on,
            tertiary = a.a2, onTertiary = a.on,
            background = c.bg, onBackground = c.text,
            surface = c.bg, onSurface = c.text,
            surfaceVariant = c.fill, onSurfaceVariant = c.textMuted,
            outline = c.border, error = c.danger,
        )
    }
}

@Composable
fun OffGridChatTheme(
    mode: ThemeMode = ThemeMode.Dark,
    accent: OgAccent = DefaultAccent,       // Ocean
    // Dynamic color ON karega to apne accent themes ignore ho jayenge, isliye default false
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

    val context = LocalContext.current
    val view = LocalView.current
    val ogColors = if (darkTheme) OgDarkColors else OgLightColors

    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> buildScheme(darkTheme, ogColors, accent)
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)

            WindowCompat.setDecorFitsSystemWindows(window, false)

            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // Har child composable in teeno ko access kar sakta hai
    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalOgColors provides ogColors,
        LocalOgAccent provides accent,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}