package com.saurabh.offgridchat.calling

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.saurabh.offgridchat.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceCallManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = CallSession()
    private val mutableState = MutableStateFlow(session.state)
    val state = mutableState.asStateFlow()
    private var transport: (suspend (String, String, Boolean) -> Boolean)? = null
    private var engine: VoiceAudioEngine? = null
    private var timer: Job? = null
    private var serviceId: String? = null
    private var lastAudioAt = 0L
    private val ended = LinkedHashSet<String>()

    fun bindTransport(send: suspend (peer: String, payload: String, audio: Boolean) -> Boolean) { transport = send }

    fun start(peer: String, name: String) = scope.launch {
        if (!session.begin(peer, UUID.randomUUID().toString(), name, outgoing = true)) return@launch
        publish()
        startService()
        scheduleTimeout("No answer")
    }

    fun answer(id: String) = scope.launch {
        if (!session.answer(id)) return@launch
        cancelIncomingNotification()
        publish()
        startService()
        scheduleTimeout("Could not connect the call")
    }

    private fun startService() {
        val call = session.state
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finish(call.id, "Microphone permission is needed to call")
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, VoiceCallService::class.java).putExtra("callId", call.id))
        } catch (_: Exception) { finish(call.id, "Open the app and try calling again") }
    }

    fun serviceReady(id: String) = scope.launch {
        val call = session.state
        if (!call.ongoing || call.id != id || serviceId == id) return@launch
        serviceId = id
        val action = if (call.phase == CallPhase.OUTGOING) CallAction.INVITE else CallAction.ACCEPT
        if (!send(call.peer, CallProtocol.signal(id, action))) finish(id, "Could not reach this person. Connect directly and try again.")
    }

    fun serviceFailed(id: String) = scope.launch { finish(id, "Call service stopped") }

    suspend fun receiveSignal(peer: String, name: String, payload: String) = withContext(Dispatchers.Main.immediate) {
        val (id, action) = try { CallProtocol.parseSignal(payload) } catch (_: Exception) { return@withContext }
        when (action) {
            CallAction.INVITE -> {
                if ("$peer:$id" in ended || session.state.matches(peer, id)) return@withContext
                if (!session.begin(peer, id, name, outgoing = false)) {
                    scope.launch { send(peer, CallProtocol.signal(id, CallAction.BUSY)) }
                    return@withContext
                }
                publish()
                showIncomingNotification()
                scheduleTimeout("Missed call")
            }
            CallAction.ACCEPT -> {
                if (serviceId != id || !session.state.outgoing || !session.activate(peer, id, SystemClock.elapsedRealtime())) return@withContext
                publish()
                scope.launch {
                    if (send(peer, CallProtocol.signal(id, CallAction.READY))) startAudio(id)
                    else finish(id, "Connection lost")
                }
            }
            CallAction.READY -> {
                if (serviceId != id || session.state.outgoing || !session.activate(peer, id, SystemClock.elapsedRealtime())) return@withContext
                publish()
                startAudio(id)
            }
            CallAction.DECLINE, CallAction.BUSY, CallAction.END -> {
                if (session.state.matches(peer, id)) finish(id, when (action) {
                    CallAction.DECLINE -> "Call declined"
                    CallAction.BUSY -> "This person is on another call"
                    else -> "Call ended"
                }, notifyPeer = false)
            }
        }
    }

    suspend fun receiveAudio(peer: String, payload: String) = withContext(Dispatchers.Main.immediate) {
        val frame = try { CallProtocol.parseAudio(payload) } catch (_: Exception) { return@withContext }
        if (session.acceptAudio(peer, frame.id, frame.sequence)) {
            lastAudioAt = SystemClock.elapsedRealtime()
            engine?.receive(frame.bytes)
        }
    }

    private fun startAudio(id: String) {
        val call = session.state
        if (call.id != id || call.phase != CallPhase.ACTIVE || engine != null) return
        timer?.cancel()
        lastAudioAt = SystemClock.elapsedRealtime()
        var sequence = 0L
        try {
            val audio = VoiceAudioEngine(context)
            engine = audio
            audio.start(onFrame = { frame ->
                if (state.value.id != id || state.value.phase != CallPhase.ACTIVE) false
                else send(call.peer, CallProtocol.audio(id, sequence++, frame), audio = true)
            }, onFailure = { scope.launch { finish(id, "Audio interrupted or connection lost") } })
            timer = scope.launch {
                while (isActive && session.state.matches(call.peer, id)) {
                    delay(1_000)
                    if (SystemClock.elapsedRealtime() - lastAudioAt > 12_000) finish(id, "Connection lost")
                }
            }
        } catch (_: Exception) { finish(id, "Microphone or audio is unavailable. Try again.") }
    }

    private suspend fun send(peer: String, payload: String, audio: Boolean = false): Boolean = try {
        transport?.invoke(peer, payload, audio) == true
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    fun hangUp(id: String) = scope.launch {
        finish(id, "Call ended", action = if (session.state.phase == CallPhase.INCOMING) CallAction.DECLINE else CallAction.END)
    }

    fun peerDisconnected(peer: String) = scope.launch {
        if (session.state.peer == peer) finish(session.state.id, "Connection lost", notifyPeer = false)
    }

    fun toggleMute() = scope.launch {
        if (session.state.phase != CallPhase.ACTIVE) return@launch
        session.mute(!session.state.muted)
        engine?.muted = session.state.muted
        publish()
    }

    fun toggleSpeaker() = scope.launch {
        val enabled = !session.state.speaker
        if (session.state.phase == CallPhase.ACTIVE && engine?.setSpeaker(enabled) == true) {
            session.speaker(enabled); publish()
        }
    }

    fun dismiss() = scope.launch { session.dismiss(); publish() }

    private fun finish(id: String, reason: String, notifyPeer: Boolean = true, action: CallAction = CallAction.END) {
        val call = session.state
        if (!session.finish(call.peer, id, reason)) return
        ended.add("${call.peer}:$id")
        while (ended.size > 64) ended.remove(ended.first())
        timer?.cancel(); timer = null
        engine?.stop(); engine = null
        serviceId = null
        cancelIncomingNotification()
        context.stopService(Intent(context, VoiceCallService::class.java))
        publish()
        if (notifyPeer) scope.launch { send(call.peer, CallProtocol.signal(id, action)) }
    }

    private fun scheduleTimeout(reason: String) {
        timer?.cancel()
        val id = session.state.id
        if (!session.state.ongoing) return
        timer = scope.launch { delay(30_000); finish(id, reason) }
    }

    private fun publish() { mutableState.value = session.state }

    private fun showIncomingNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(INCOMING_CHANNEL, "Incoming voice calls", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        })
        val call = session.state
        val open = PendingIntent.getActivity(context, 2002, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val decline = PendingIntent.getBroadcast(context, call.id.hashCode(),
            Intent(context, CallActionReceiver::class.java).putExtra("callId", call.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            manager.notify(INCOMING_NOTIFICATION, NotificationCompat.Builder(context, INCOMING_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle("${call.name} is calling")
                .setContentText("Incoming OffGrid voice call")
                .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open).setOngoing(true).setTimeoutAfter(30_000)
                .addAction(0, "Decline", decline).addAction(0, "Answer", open).build())
        }
    }

    private fun cancelIncomingNotification() { context.getSystemService(NotificationManager::class.java).cancel(INCOMING_NOTIFICATION) }

    companion object {
        const val INCOMING_CHANNEL = "offgrid_voice_calls"
        const val INCOMING_NOTIFICATION = 2002
    }
}
