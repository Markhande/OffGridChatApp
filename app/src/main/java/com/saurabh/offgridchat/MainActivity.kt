package com.saurabh.offgridchat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.offgridchat.datastore.Preferences
import com.saurabh.offgridchat.navigation.MainNavigation
import com.saurabh.offgridchat.service.BluetoothService
import com.saurabh.offgridchat.tools.BtPermissions
import com.saurabh.offgridchat.ui.theme.DefaultAccent
import com.saurabh.offgridchat.ui.theme.OffGridChatTheme
import com.saurabh.offgridchat.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.saurabh.offgridchat.calling.VoiceCallManager
import com.saurabh.offgridchat.calling.CallOverlay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var calls: VoiceCallManager

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val context = LocalContext.current
            val prefs = remember { Preferences(applicationContext) }
            val mode by prefs.themeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val accent by prefs.accentFlow.collectAsStateWithLifecycle(initialValue = DefaultAccent)
            val runInBackground by prefs.runInBackgroundFlow.collectAsStateWithLifecycle(
                initialValue = true
            )

            var hasBtPermission by remember { mutableStateOf(BtPermissions.granted(context)) }
            val lifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        hasBtPermission = BtPermissions.granted(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            LaunchedEffect(runInBackground, hasBtPermission) {
                val serviceIntent = Intent(context, BluetoothService::class.java)
                if (runInBackground && hasBtPermission) {
                    try {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else if (!runInBackground) {
                    context.stopService(serviceIntent)
                }
            }

            OffGridChatTheme(mode = mode, accent = accent) {
                MainNavigation(prefs)
                CallOverlay(calls)
            }
        }
    }
}
