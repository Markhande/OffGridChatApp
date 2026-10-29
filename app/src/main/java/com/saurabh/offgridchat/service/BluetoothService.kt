package com.saurabh.offgridchat.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.saurabh.offgridchat.MainActivity
import com.saurabh.offgridchat.R
import com.saurabh.offgridchat.core.BluetoothController
import com.saurabh.offgridchat.datastore.Preferences
import com.saurabh.offgridchat.repository.ChatRepository
import com.saurabh.offgridchat.tools.BtPermissions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BluetoothService : Service() {

    @Inject lateinit var btController: BluetoothController
    @Inject lateinit var chatRepo: ChatRepository
    @Inject lateinit var groupRepo: com.saurabh.offgridchat.repository.GroupRepository
    @Inject lateinit var prefs: Preferences

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CHANNEL_SERVICE = "offgrid_service_channel"
        const val CHANNEL_MESSAGES = "offgrid_messages_channel"
        const val NOTIF_ID_SERVICE = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BtPermissions.granted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildServiceNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(NOTIF_ID_SERVICE, notification, type)
            } else {
                startForeground(NOTIF_ID_SERVICE, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return START_NOT_STICKY
        }

        // Bluetooth server listening chalu rakho
        btController.startServer()

        // Incoming messages ko observe karo aur notification bhejo
        serviceScope.launch {
            btController.incoming.collect { msg ->
                val notifsEnabled = prefs.notificationsEnabledFlow.first()
                if (notifsEnabled) {
                    val hidePreviews = prefs.hidePreviewsFlow.first()
                    val convos = chatRepo.conversations().first()
                    val senderName = if (msg.address.startsWith("group:")) {
                        val groupName = groupRepo.dao.group(msg.address.removePrefix("group:"))?.groupName ?: "Group"
                        val sender = groupRepo.dao.message(msg.messageId)?.senderName ?: msg.nodeId.orEmpty()
                        "$groupName • $sender"
                    } else convos.firstOrNull { it.address == msg.address }?.name ?: msg.address
                    showIncomingNotification(senderName, msg.text, hidePreviews, msg.address)
                }
            }
        }

        // The singleton controller owns one settings-aware reconnect loop.

        return START_STICKY
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Background Listening",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps OffGrid Chat active for incoming Bluetooth messages"
        }

        val messageChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Incoming Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for incoming Bluetooth messages"
            enableVibration(true)
        }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(messageChannel)
    }

    private fun buildServiceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("OffGrid Chat Active")
            .setContentText("Listening for nearby Bluetooth messages")
            .setSmallIcon(R.mipmap.ic_app_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun showIncomingNotification(sender: String, text: String, hidePreview: Boolean, address: String) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("address", address)
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                address.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val messageContent = if (hidePreview) "New offline message received" else text

            val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setContentTitle(sender)
                .setContentText(messageContent)
                .setSmallIcon(R.mipmap.ic_app_logo)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()

            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(address.hashCode(), notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
