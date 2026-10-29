package com.saurabh.offgridchat.routes

sealed class MainRoute(val route: String) {
    object SplashScreen : MainRoute("splash")
    object BottomNavigation : MainRoute("bottom_navigation")
    object ChatList : MainRoute("chatList")
    object ChatDetailScreen : MainRoute("chatDetailScreen/{address}") {
        fun createRoute(address: String) = "chatDetailScreen/$address"
    }
    object NearbyScreen : MainRoute("nearbyScreen")
    object ProfileScreen : MainRoute("profileScreen")
    object SettingsScreen : MainRoute("settingsScreen")
}