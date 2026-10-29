package com.saurabh.offgridchat

import com.saurabh.offgridchat.calling.CallPhase
import com.saurabh.offgridchat.calling.CallSession
import org.junit.Assert.*
import org.junit.Test

class CallSessionTest {
    @Test fun incomingCallCannotActivateBeforeUserAnswers() {
        val call = CallSession()
        assertTrue(call.begin("alice", "call-1", "Alice", false))
        assertEquals(CallPhase.INCOMING, call.state.phase)
        assertFalse(call.activate("alice", "call-1", 100))
        assertFalse(call.acceptAudio("alice", "call-1", 0))
        assertTrue(call.answer("call-1"))
        assertTrue(call.activate("alice", "call-1", 100))
        assertTrue(call.acceptAudio("alice", "call-1", 0))
    }

    @Test fun outgoingCallRequiresMatchingPeerAndCallId() {
        val call = CallSession()
        call.begin("alice", "call-1", "Alice", true)
        assertFalse(call.activate("mallory", "call-1", 100))
        assertFalse(call.activate("alice", "stale-call", 100))
        assertFalse(call.answer("call-1"))
        assertTrue(call.activate("alice", "call-1", 100))
        assertEquals(100L, call.state.startedAt)
        assertFalse(call.activate("alice", "call-1", 200))
        assertEquals(100L, call.state.startedAt)
    }

    @Test fun secondCallerCannotReplaceExistingCall() {
        val call = CallSession()
        call.begin("alice", "call-1", "Alice", false)
        assertFalse(call.begin("bob", "call-2", "Bob", false))
        assertEquals("alice", call.state.peer)
        assertFalse(call.finish("bob", "call-1", "ended"))
        assertTrue(call.state.ongoing)
    }

    @Test fun duplicateStaleAndOtherPeerAudioIsRejected() {
        val call = CallSession()
        call.begin("alice", "call-1", "Alice", true)
        call.activate("alice", "call-1", 100)
        assertTrue(call.acceptAudio("alice", "call-1", 2))
        assertFalse(call.acceptAudio("alice", "call-1", 2))
        assertFalse(call.acceptAudio("alice", "call-1", 1))
        assertFalse(call.acceptAudio("bob", "call-1", 3))
        assertFalse(call.acceptAudio("alice", "old-call", 3))
        assertTrue(call.acceptAudio("alice", "call-1", 3))
    }

    @Test fun hangupEndsAudioAndStaleHangupCannotEndNewCall() {
        val call = CallSession()
        call.begin("alice", "call-1", "Alice", true)
        call.activate("alice", "call-1", 100)
        assertTrue(call.finish("alice", "call-1", "Call ended"))
        assertFalse(call.acceptAudio("alice", "call-1", 0))
        assertTrue(call.begin("alice", "call-2", "Alice", true))
        assertFalse(call.finish("alice", "call-1", "late end"))
        assertFalse(call.answer("call-1"))
        assertTrue(call.state.ongoing)
    }

    @Test fun controlsApplyOnlyDuringAnActiveCallAndReset() {
        val call = CallSession()
        call.begin("alice", "call-1", "Alice", true)
        call.mute(true)
        assertFalse(call.state.muted)
        call.activate("alice", "call-1", 100)
        call.mute(true); call.speaker(true)
        assertTrue(call.state.muted)
        assertTrue(call.state.speaker)
        call.dismiss()
        assertEquals(CallPhase.ACTIVE, call.state.phase)
        call.finish("alice", "call-1", "ended")
        call.dismiss()
        assertEquals(CallPhase.IDLE, call.state.phase)
        call.begin("bob", "call-2", "Bob", true)
        assertFalse(call.state.muted)
        assertFalse(call.state.speaker)
    }
}
