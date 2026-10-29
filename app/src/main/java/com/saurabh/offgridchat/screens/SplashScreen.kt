package com.saurabh.offgridchat.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.offgridchat.BuildConfig
import com.saurabh.offgridchat.ui.components.AuroraBackground
import com.saurabh.offgridchat.ui.theme.LocalOgAccent
import com.saurabh.offgridchat.ui.theme.LocalOgColors
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SplashScreen(onCompletion: () -> Unit) {
    val c = LocalOgColors.current
    val a = LocalOgAccent.current

    // Animations for Scale & Alpha on launch
    val scaleAnim = remember { Animatable(0.7f) }
    val alphaAnim = remember { Animatable(0f) }

    // Infinite radar pulse / rotation animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val rotateAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotateAngle"
    )

    LaunchedEffect(Unit) {
        // Start smooth entrance animations
        scaleAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
        )
        alphaAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 700)
        )

        // Hold splash screen briefly before transitioning
        delay(2200.milliseconds)
        onCompletion()
    }

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(40.dp))

            // Center Content: Logo + Brand Title + Subtitle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .scale(scaleAnim.value)
                    .alpha(alphaAnim.value)
            ) {
                // Outer Pulse & Radar Container
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(170.dp)
                ) {
                    // Outer expanding pulse ring
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val ctr = center
                        drawCircle(
                            color = a.a1.copy(alpha = pulseAlpha),
                            radius = (size.minDimension / 2f) * pulseScale,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        rotate(rotateAngle, pivot = ctr) {
                            drawCircle(
                                color = a.a2.copy(alpha = 0.25f),
                                radius = size.minDimension / 2.3f,
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }

                    // Inner Glass Circle Container
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(c.glass)
                            .border(1.5.dp, a.gradient, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(74.dp)
                                .clip(CircleShape)
                                .background(a.gradient),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = "OffGrid Radar Logo",
                                tint = a.on,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }

                // App Title
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "OffGrid",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.text
                        )
                        Text(
                            text = "Chat",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = a.a1
                        )
                    }

                    // Tagline Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.fill)
                            .border(1.dp, c.border, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Offline • Mesh • Encrypted",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textMuted,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Bottom Footer Status Indicator
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .alpha(alphaAnim.value)
                    .padding(bottom = 20.dp)
            ) {
                // Pulsing Status Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(c.fill)
                        .border(1.dp, c.border, CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(c.ok)
                    )
                    Text(
                        text = "Initializing Bluetooth Mesh…",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.textMuted
                    )
                }

                Text(
                    text = "v${BuildConfig.VERSION_NAME} • OffGrid P2P Network",
                    fontSize = 11.sp,
                    color = c.textMuted.copy(alpha = 0.6f)
                )
            }
        }
    }
}
