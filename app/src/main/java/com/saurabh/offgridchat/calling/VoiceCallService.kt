package com.saurabh.offgridchat.calling

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.saurabh.offgridchat.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class VoiceCallService : Service() {
    @Inject lateinit var calls: VoiceCallManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var callId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("callId")
        val call = calls.state.value
        if (id == null || !call.ongoing || call.id != id) { stopSelf(); return START_NOT_STICKY }
        callId = id
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Voice call in progress", NotificationManager.IMPORTANCE_LOW))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                startForeground(NOTIFICATION, notification(call), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification(call))
        } catch (_: Exception) {
            calls.serviceFailed(id); stopSelf(); return START_NOT_STICKY
        }
        calls.serviceReady(id)
        observer?.cancel()
        observer = scope.launch {
            calls.state.collect { state ->
                if (state.id != id || !state.ongoing) stopSelf()
                else notifications.notify(NOTIFICATION, notification(state))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(call: VoiceCallState): Notification {
        val open = PendingIntent.getActivity(this, 2001, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hangup = PendingIntent.getBroadcast(this, call.id.hashCode(),
            Intent(this, CallActionReceiver::class.java).putExtra("callId", call.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("OffGrid call · ${call.name}")
            .setContentText(if (call.phase == CallPhase.ACTIVE) "Voice call in progress" else "Connecting…")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open).addAction(0, "Hang up", hangup).build()
    }

    override fun onDestroy() {
        scope.cancel()
        callId?.let { calls.serviceFailed(it) }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "offgrid_active_voice_call"
        const val NOTIFICATION = 2001
    }
}

@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {
    @Inject lateinit var calls: VoiceCallManager
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("callId")?.let { calls.hangUp(it) }
    }
}
