package com.saurabh.offgridchat.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.saurabh.offgridchat.datastore.Preferences
import com.saurabh.offgridchat.routes.MainRoute
import com.saurabh.offgridchat.screens.BottomNavigation
import com.saurabh.offgridchat.screens.ChatDetailScreen
import com.saurabh.offgridchat.screens.GroupChatScreen
import com.saurabh.offgridchat.screens.SplashScreen

@Composable
fun MainNavigation(themePreferences: Preferences) {

    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = MainRoute.SplashScreen.route) {
        composable(MainRoute.SplashScreen.route) {
            SplashScreen(onCompletion = {
                navController.navigate(MainRoute.BottomNavigation.route) {
                    popUpTo(MainRoute.SplashScreen.route) { inclusive = true }
                }
            })
        }
        composable(MainRoute.BottomNavigation.route) {
            BottomNavigation(themePreferences,navController)
        }

        composable(MainRoute.ChatDetailScreen.route) {
            val address = it.arguments?.getString("address") ?: return@composable
            if (address.startsWith("group:")) {
                GroupChatScreen(groupId = address.removePrefix("group:"), onBack = { navController.popBackStack() })
            } else {
                ChatDetailScreen(address = address) { navController.popBackStack() }
            }
        }
    }
}
