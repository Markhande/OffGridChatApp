package com.saurabh.offgridchat.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.offgridchat.viewmodel.BluetoothViewModel
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.platform.LocalLayoutDirection
import com.saurabh.offgridchat.ui.components.HazeBlurBackground
import com.saurabh.offgridchat.ui.components.AuroraBackground
import com.saurabh.offgridchat.ui.components.LocalBottomNavigationPadding
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.saurabh.offgridchat.datastore.Preferences
import com.saurabh.offgridchat.routes.MainRoute
import com.saurabh.offgridchat.ui.components.OgIcons
import com.saurabh.offgridchat.ui.theme.LocalDarkTheme
import com.saurabh.offgridchat.ui.theme.LocalOgAccent
import com.saurabh.offgridchat.ui.theme.ThemeMode
import kotlin.math.abs
import kotlin.math.roundToInt

// -----------------------------------------------------------------------------
// 1. DATA MODEL
// -----------------------------------------------------------------------------

sealed class BottomNavItem(
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val route: String
) {
    object Home : BottomNavItem(
        "Chats",
        OgIcons.Chat,
        OgIcons.Chat,
        "Chat"
    )

    object Search : BottomNavItem(
        "Nearby",
        Icons.Outlined.Sensors,
        Icons.Filled.Sensors,
        "Nearby"
    )

    object Profile : BottomNavItem(
        "Profile",
        Icons.Outlined.Person,
        Icons.Filled.Person,
        "profile"
    )

    object Settings : BottomNavItem(
        "Settings",
        Icons.Outlined.Settings,
        Icons.Filled.Settings,
        "settings"
    )
}

// -----------------------------------------------------------------------------
// 2. ROOT BOTTOM NAVIGATION (Theme-Aware Scaffold & Screens)
// -----------------------------------------------------------------------------

@Composable
fun BottomNavigation(
    themePreferences: Preferences,
    rootNavController: NavHostController,
    vm: BluetoothViewModel = hiltViewModel()
) {
    val currentTheme by themePreferences.themeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.System)

    val isDark = when (currentTheme) {
        ThemeMode.System -> LocalDarkTheme.current
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val hazeState = rememberHazeState()
    val layoutDirection = LocalLayoutDirection.current

    val navItems = remember {
        listOf(
            BottomNavItem.Home,
            BottomNavItem.Search,
            BottomNavItem.Profile,
            BottomNavItem.Settings
        )
    }

    // Pager state is the only source of truth for main-page selection.
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    // Keep the existing backdrop outside destination animations, including under system bars.
    AuroraBackground {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            bottomBar = {
                LiquidGlassBottomBar(
                    hazeState = hazeState,
                    items = navItems,
                    selectedIndex = pagerState.currentPage,
                    isDark = isDark,
                    onTabTapped = { targetIndex ->
                        scope.launch { pagerState.animateScrollToPage(targetIndex) }
                    }
                )
            },
        ) { paddingValues ->
            // Keep the viewport behind the bar; reserve its measured height inside each scroll area.
            val viewportPadding = PaddingValues(
                start = paddingValues.calculateStartPadding(layoutDirection),
                top = paddingValues.calculateTopPadding(),
                end = paddingValues.calculateEndPadding(layoutDirection),
            )
            CompositionLocalProvider(
                LocalBottomNavigationPadding provides paddingValues.calculateBottomPadding()
            ) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 0,
                    key = { navItems[it].route },
                    modifier = Modifier
                        .padding(viewportPadding)
                        .consumeWindowInsets(viewportPadding)
                        .fillMaxSize()
                        .hazeSource(hazeState)
                ) { page ->
                    when (page) {
                        0 -> ChatsScreen(
                            vm = vm,
                            onChatClick = {
                                rootNavController.navigate(MainRoute.ChatDetailScreen.createRoute(it))
                            },
                            onNavigateToNearby = {
                                scope.launch { pagerState.animateScrollToPage(1) }
                            }
                        )
                        1 -> NearbyScreen(
                            vm = vm,
                            isActive = pagerState.settledPage == 1,
                            onOpenChat = {
                                rootNavController.navigate(MainRoute.ChatDetailScreen.createRoute(it))
                            }
                        )
                        2 -> ProfileScreen(vm = vm, prefs = themePreferences)
                        3 -> SettingsScreen(themePreferences, vm = vm)
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 3. LIQUID GLASS BAR (Drag-to-Select + Tap + Apple 3D Glass)
// -----------------------------------------------------------------------------

@Composable
fun LiquidGlassBottomBar(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onTabTapped: (Int) -> Unit,
    isDark: Boolean
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val currentOnTabTapped by rememberUpdatedState(onTabTapped)

    var isTouching by remember { mutableStateOf(false) }
    var touchX by remember { mutableFloatStateOf(0f) }
    var barWidth by remember { mutableFloatStateOf(0f) }

    val tabCount = items.size.coerceAtLeast(1)
    fun getTabCenterPx(index: Int): Float {
        if (barWidth <= 0f) return 0f
        val itemWidth = barWidth / tabCount
        return (index + 0.5f) * itemWidth
    }

    // Lens reveal & scale animations
    val lensAlpha by animateFloatAsState(
        targetValue = if (isTouching) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "lensAlpha"
    )
    val lensScale by animateFloatAsState(
        targetValue = if (isTouching) 1f else 0.35f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "lensScale"
    )

    // Pill geometry
    val pillWidthDp = 70.dp
    val pillHeightDp = 52.dp
    val pillWidthPx = with(density) { pillWidthDp.toPx() }

    val pillX = remember { Animatable(0f) }

    LaunchedEffect(selectedIndex, barWidth) {
        if (barWidth > 0f) {
            val center = getTabCenterPx(selectedIndex)
            launch {
                pillX.animateTo(
                    targetValue = center - (pillWidthPx / 2f),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // THEME OPTICAL CONFIGURATIONS
    // -------------------------------------------------------------------------

    // PERFORMANCE OPTIMIZATION: Wrap Brushes in remember so they aren't re-instantiated on every recomposition.
    // 3D Bevel Border
    val glassBorder3DBrush = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.65f),
                0.28f to Color.White.copy(alpha = 0.22f),
                0.75f to Color.White.copy(alpha = 0.04f),
                1.00f to Color.White.copy(alpha = 0.20f)
            )
        } else {
            Brush.verticalGradient(
                0.00f to Color.White,
                0.25f to Color.White.copy(alpha = 0.80f),
                0.70f to Color.Black.copy(alpha = 0.08f),
                1.00f to Color.Black.copy(alpha = 0.12f)
            )
        }
    }

    // Active Pill Styling
    val pillBrush = remember(isDark) {
        Brush.verticalGradient(
            if (isDark) {
                listOf(
                    Color.White.copy(alpha = 0.14f),
                    Color.White.copy(alpha = 0.08f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.55f),
                    Color.White.copy(alpha = 0.25f)
                )
            }
        )
    }

    val pillBorderBrush = remember(isDark) {
        Brush.verticalGradient(
            if (isDark) {
                listOf(
                    Color.White.copy(alpha = 0.38f),
                    Color.Transparent
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.75f),
                    Color.White.copy(alpha = 0.15f)
                )
            }
        )
    }

    val unselectedColor =
        remember(isDark) { if (isDark) Color.White.copy(alpha = 0.60f) else Color(0xFF6B7280) }
    val activeColor = LocalOgAccent.current.a1

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Outer Capsule Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .shadow(
                    elevation = if (isDark) 24.dp else 14.dp,
                    shape = RoundedCornerShape(percent = 50),
                    ambientColor = if (isDark) Color.Black.copy(alpha = 0.65f) else Color.Black.copy(
                        alpha = 0.10f
                    ),
                    spotColor = if (isDark) Color.Black.copy(alpha = 0.85f) else Color.Black.copy(
                        alpha = 0.18f
                    )
                )
                .clip(RoundedCornerShape(percent = 50))
                .border(
                    width = 1.3.dp,
                    brush = glassBorder3DBrush,
                    shape = RoundedCornerShape(percent = 50)
                )
                .drawWithCache {
                    // PERFORMANCE OPTIMIZATION: Cache the brush allocation OUTSIDE onDrawWithContent.
                    // Doing this inside onDrawWithContent causes a new Brush instantiation on every single frame drawn.
                    val highlightBrush = Brush.horizontalGradient(
                        0.0f to Color.Transparent,
                        0.2f to (if (isDark) Color.White.copy(alpha = 0.50f) else Color.White.copy(
                            alpha = 0.95f
                        )),
                        0.5f to (if (isDark) Color.White.copy(alpha = 0.85f) else Color.White),
                        0.8f to (if (isDark) Color.White.copy(alpha = 0.50f) else Color.White.copy(
                            alpha = 0.95f
                        )),
                        1.0f to Color.Transparent
                    )

                    onDrawWithContent {
                        drawContent()
                        drawLine(
                            brush = highlightBrush,
                            start = Offset(x = size.width * 0.15f, y = 1.2.dp.toPx()),
                            end = Offset(x = size.width * 0.85f, y = 1.2.dp.toPx()),
                            strokeWidth = 1.4.dp.toPx()
                        )
                    }
                }
                .onGloballyPositioned { barWidth = it.size.width.toFloat() }
                // Gesture Logic: Dragging tracks lens visually, Release selects the nearest tab
                .pointerInput(tabCount) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isTouching = true
                        touchX = down.position.x.coerceIn(0f, barWidth)

                        var lastHoveredTab =
                            ((touchX / (barWidth / tabCount)).toInt()).coerceIn(0, tabCount - 1)

                        // Drag loop
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                // Pointer up event: update final position
                                touchX = change.position.x.coerceIn(0f, barWidth)
                                break
                            }

                            touchX = change.position.x.coerceIn(0f, barWidth)

                            // Haptic tick when passing each tab
                            val currentHoveredTab =
                                ((touchX / (barWidth / tabCount)).toInt()).coerceIn(0, tabCount - 1)
                            if (currentHoveredTab != lastHoveredTab) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                lastHoveredTab = currentHoveredTab
                            }
                        }

                        // Finger released: Select whichever tab the finger was released near
                        isTouching = false
                        if (barWidth > 0f) {
                            val finalSelectedTab =
                                ((touchX / (barWidth / tabCount)).toInt()).coerceIn(0, tabCount - 1)
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            currentOnTabTapped(finalSelectedTab)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            HazeBlurBackground(
                state = hazeState,
                shape = RoundedCornerShape(percent = 50),
                tint = if (isDark) Color(0xFF14161C).copy(alpha = 0.30f)
                else Color(0xFFF0F2F6).copy(alpha = 0.30f),
                modifier = Modifier.matchParentSize(),
            )
            // -----------------------------------------------------------------
            // A. FROSTED GLASS PILL
            // -----------------------------------------------------------------
            if (barWidth > 0f) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(pillX.value.roundToInt(), 0) }
                        .size(width = pillWidthDp, height = pillHeightDp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(pillBrush)
                        .border(
                            width = 0.9.dp,
                            brush = pillBorderBrush,
                            shape = RoundedCornerShape(percent = 50)
                        )
                )
            }

            // -----------------------------------------------------------------
            // B. NAVIGATION ITEMS (Center-Locked & Optical Bulge)
            // -----------------------------------------------------------------
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = (index == selectedIndex)
                    val itemColor = if (isSelected) activeColor else unselectedColor

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer {
                                // PERFORMANCE OPTIMIZATION: State reads (touchX and isTouching)
                                // are deferred to the graphicsLayer layout/draw phase!
                                // Previously, reading them in the composition caused the entire Row
                                // to recompose on every single frame during the drag gesture.
                                val tabCenterX = getTabCenterPx(index)
                                val distanceToTouch = abs(tabCenterX - touchX)
                                val maxInfluenceDistance = 56.dp.toPx()

                                val proximity = if (isTouching) {
                                    (1f - (distanceToTouch / maxInfluenceDistance)).coerceIn(0f, 1f)
                                } else 0f

                                val scale = 1f + (0.24f * proximity)
                                val liftY = -6f * proximity

                                scaleX = scale
                                scaleY = scale
                                translationY = liftY
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.icon,
                            contentDescription = item.title,
                            tint = itemColor,
                            modifier = Modifier.size(23.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.title,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = itemColor,
                            maxLines = 1
                        )
                    }
                }
            }

            // -----------------------------------------------------------------
            // C. CHROMATIC ABERRATION LIQUID LENS
            // -----------------------------------------------------------------
            if (lensAlpha > 0.01f) {
                val lensDiameterDp = 58.dp
                val lensDiameterPx = with(density) { lensDiameterDp.toPx() }
                val halfLens = lensDiameterPx / 2f

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (touchX - halfLens).roundToInt(),
                                y = 0
                            )
                        }
                        .size(lensDiameterDp)
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            scaleX = lensScale
                            scaleY = lensScale
                            alpha = lensAlpha
                        }
                        .drawWithCache {
                            val chromaticSweep = Brush.sweepGradient(
                                listOf(
                                    Color(0xFF00F0FF),
                                    Color(0xFF387BFF),
                                    Color(0xFFA855F7),
                                    Color(0xFFFF2E93),
                                    Color(0xFFFFB020),
                                    Color(0xFF10E57A),
                                    Color(0xFF00F0FF)
                                )
                            )

                            val glassSpecular = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (isDark) 0.35f else 0.55f),
                                    Color.White.copy(alpha = 0.08f),
                                    Color.Transparent
                                ),
                                center = Offset(size.width * 0.35f, size.height * 0.28f),
                                radius = size.minDimension * 0.65f
                            )

                            onDrawWithContent {
                                drawContent()

                                val center = Offset(size.width / 2f, size.height / 2f)
                                val radius = (size.minDimension / 2f) - 2f

                                drawCircle(
                                    brush = glassSpecular,
                                    radius = radius,
                                    center = center
                                )

                                drawCircle(
                                    brush = chromaticSweep,
                                    radius = radius,
                                    center = center,
                                    style = Stroke(width = 3.5.dp.toPx()),
                                    alpha = if (isDark) 0.45f else 0.40f,
                                    blendMode = if (isDark) BlendMode.Screen else BlendMode.SrcOver
                                )

                                drawCircle(
                                    brush = chromaticSweep,
                                    radius = radius,
                                    center = center,
                                    style = Stroke(width = 1.8.dp.toPx())
                                )

                                drawArc(
                                    color = Color.White.copy(alpha = 0.85f),
                                    startAngle = 200f,
                                    sweepAngle = 100f,
                                    useCenter = false,
                                    topLeft = Offset(4.dp.toPx(), 4.dp.toPx()),
                                    size = Size(
                                        size.width - 8.dp.toPx(),
                                        size.height - 8.dp.toPx()
                                    ),
                                    style = Stroke(width = 1.2.dp.toPx())
                                )
                            }
                        }
                )
            }
        }
    }
}
