package com.saurabh.offgridchat.calling

enum class CallPhase { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE, ENDED }

data class VoiceCallState(
    val id: String = "",
    val peer: String = "",
    val name: String = "",
    val phase: CallPhase = CallPhase.IDLE,
    val outgoing: Boolean = false,
    val startedAt: Long = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val message: String = ""
) {
    val ongoing: Boolean get() = phase !in setOf(CallPhase.IDLE, CallPhase.ENDED)
    fun matches(peer: String, id: String) = ongoing && this.peer == peer && this.id == id
}

/** All changes are serialized by VoiceCallManager on the main dispatcher. */
class CallSession {
    var state = VoiceCallState()
        private set
    private var lastSequence = -1L

    fun begin(peer: String, id: String, name: String, outgoing: Boolean): Boolean {
        if (state.ongoing) return false
        state = VoiceCallState(id, peer, name, if (outgoing) CallPhase.OUTGOING else CallPhase.INCOMING, outgoing)
        lastSequence = -1
        return true
    }

    fun answer(id: String): Boolean {
        if (state.id != id || state.phase != CallPhase.INCOMING) return false
        state = state.copy(phase = CallPhase.CONNECTING)
        return true
    }

    fun activate(peer: String, id: String, now: Long): Boolean {
        if (!state.matches(peer, id) ||
            state.phase != if (state.outgoing) CallPhase.OUTGOING else CallPhase.CONNECTING) return false
        state = state.copy(phase = CallPhase.ACTIVE, startedAt = now)
        return true
    }

    fun acceptAudio(peer: String, id: String, sequence: Long): Boolean {
        if (!state.matches(peer, id) || state.phase != CallPhase.ACTIVE || sequence <= lastSequence) return false
        lastSequence = sequence
        return true
    }

    fun finish(peer: String, id: String, message: String): Boolean {
        if (!state.matches(peer, id)) return false
        state = state.copy(phase = CallPhase.ENDED, message = message, muted = false)
        return true
    }

    fun mute(value: Boolean) { if (state.phase == CallPhase.ACTIVE) state = state.copy(muted = value) }
    fun speaker(value: Boolean) { if (state.phase == CallPhase.ACTIVE) state = state.copy(speaker = value) }
    fun dismiss() { if (!state.ongoing) state = VoiceCallState() }
}
