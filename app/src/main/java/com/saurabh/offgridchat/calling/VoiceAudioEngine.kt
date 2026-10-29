package com.saurabh.offgridchat.calling

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/** Bounded queues discard old audio rather than accumulating conversational delay. */
class VoiceAudioEngine(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private var focus: AudioFocusRequest? = null
    private var job: Job? = null
    private var incoming: Channel<ByteArray>? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    @Volatile var muted = false

    @SuppressLint("MissingPermission")
    fun start(onFrame: suspend (ByteArray) -> Boolean, onFailure: () -> Unit) {
        check(job == null)
        previousMode = audio.mode
        previousSpeaker = audio.isSpeakerphoneOn
        try {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener({ change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onFailure()
                }, Handler(Looper.getMainLooper())).build()
            focus = request
            check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeaker(false)
            val recordMin = AudioRecord.getMinBufferSize(CallProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val playMin = AudioTrack.getMinBufferSize(CallProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(recordMin > 0 && playMin > 0)
            val input = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, CallProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(recordMin, CallProtocol.FRAME_BYTES * 4))
            recorder = input
            check(input.state == AudioRecord.STATE_INITIALIZED)
            val output = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(CallProtocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(playMin, CallProtocol.FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            player = output
            check(output.state == AudioTrack.STATE_INITIALIZED)
            if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(input.audioSessionId)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(input.audioSessionId)?.apply { enabled = true }
            val parent = SupervisorJob()
            job = parent
            val scope = CoroutineScope(parent + Dispatchers.IO)
            val received = Channel<ByteArray>(4, BufferOverflow.DROP_OLDEST)
            val captured = Channel<ByteArray>(3, BufferOverflow.DROP_OLDEST)
            incoming = received
            output.play()
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            fun worker(block: suspend CoroutineScope.() -> Unit) = scope.launch {
                try { block() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (isActive) onFailure() }
            }
            worker {
                try {
                    while (isActive) {
                        val frame = ByteArray(CallProtocol.FRAME_BYTES)
                        var offset = 0
                        while (offset < frame.size && isActive) {
                            val count = input.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                            check(count > 0)
                            offset += count
                        }
                        if (muted) frame.fill(0)
                        captured.trySend(frame)
                    }
                } finally { captured.close() }
            }
            worker { for (frame in captured) check(onFrame(if (muted) ByteArray(frame.size) else frame)) }
            worker {
                for (frame in received) {
                    var offset = 0
                    while (offset < frame.size && isActive) {
                        val count = output.write(frame, offset, frame.size - offset, AudioTrack.WRITE_BLOCKING)
                        check(count > 0)
                        offset += count
                    }
                }
            }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun receive(bytes: ByteArray) { incoming?.trySend(bytes) }

    @Suppress("DEPRECATION")
    fun setSpeaker(enabled: Boolean): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val type = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val device = audio.availableCommunicationDevices.firstOrNull { it.type == type }
        if (device != null) audio.setCommunicationDevice(device) else {
            audio.clearCommunicationDevice()
            !enabled
        }
    } else {
        audio.isSpeakerphoneOn = enabled
        true
    }

    @Suppress("DEPRECATION")
    fun stop() {
        job?.cancel(); job = null
        incoming?.close(); incoming = null
        runCatching { recorder?.stop() }
        runCatching { player?.pause(); player?.flush() }
        runCatching { echo?.release(); noise?.release() }
        echo = null; noise = null
        runCatching { recorder?.release(); player?.release() }
        recorder = null; player = null
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
            else audio.isSpeakerphoneOn = previousSpeaker
            audio.mode = previousMode
        }
        muted = false
    }
}
