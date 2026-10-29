package com.saurabh.offgridchat.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** Space reserved inside scrolling content for the shared floating navigation bar. */
val LocalBottomNavigationPadding = compositionLocalOf { 0.dp }

/** Backdrop only: place sharp foreground content in a separate layer above this composable. */
@Composable
fun HazeBlurBackground(
    state: HazeState,
    shape: Shape,
    tint: Color,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 12.dp,
) {
    Box(
        modifier.clip(shape).hazeBlur(
            input = HazeInput.Sources(state),
            style = HazeBlurStyle {
                blurRadius(blurRadius)
                colorEffects(listOf(HazeColorEffect.tint(tint)))
            },
        )
    )
}
