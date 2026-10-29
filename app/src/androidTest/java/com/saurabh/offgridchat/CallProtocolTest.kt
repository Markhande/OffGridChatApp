package com.saurabh.offgridchat

import com.saurabh.offgridchat.calling.CallAction
import com.saurabh.offgridchat.calling.CallProtocol
import com.saurabh.offgridchat.protocol.MeshPacket
import com.saurabh.offgridchat.protocol.PacketType
import com.saurabh.offgridchat.crypto.CryptoManager
import com.saurabh.offgridchat.crypto.EncryptedResult
import java.security.KeyPairGenerator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class CallProtocolTest {
    private val id = UUID.randomUUID().toString()

    @Test fun allCallSignalsRoundTrip() {
        CallAction.entries.forEach { assertEquals(id to it, CallProtocol.parseSignal(CallProtocol.signal(id, it))) }
    }

    @Test fun audioRoundTripsWithoutLosingPcmBytes() {
        val bytes = ByteArray(CallProtocol.FRAME_BYTES) { (it % 256).toByte() }
        val frame = CallProtocol.parseAudio(CallProtocol.audio(id, 42, bytes))
        assertEquals(id, frame.id)
        assertEquals(42L, frame.sequence)
        assertArrayEquals(bytes, frame.bytes)
    }

    @Test fun malformedAndOversizedAudioIsRejected() {
        val frame = JSONObject(CallProtocol.audio(id, 1, ByteArray(CallProtocol.FRAME_BYTES)))
        assertTrue(runCatching { CallProtocol.parseAudio(frame.put("seq", -1).toString()) }.isFailure)
        frame.put("seq", 1).put("pcm", Base64.getEncoder().encodeToString(ByteArray(2)))
        assertTrue(runCatching { CallProtocol.parseAudio(frame.toString()) }.isFailure)
        assertTrue(runCatching { CallProtocol.parseAudio("x".repeat(2049)) }.isFailure)
        assertTrue(runCatching { CallProtocol.parseSignal("{\"v\":1,\"callId\":\"invalid\",\"action\":\"INVITE\"}") }.isFailure)
    }

    @Test fun unsupportedProtocolCannotStartCall() {
        val signal = JSONObject(CallProtocol.signal(id, CallAction.INVITE)).put("v", 999)
        assertTrue(runCatching { CallProtocol.parseSignal(signal.toString()) }.isFailure)
        signal.put("v", 1).put("action", "UNKNOWN")
        assertTrue(runCatching { CallProtocol.parseSignal(signal.toString()) }.isFailure)
    }

    @Test fun voicePacketsMustBeEncryptedAndDirect() {
        val packet = MeshPacket(sourceNodeId = "alice", destinationNodeId = "bob", previousHopId = "alice",
            packetType = PacketType.CALL_AUDIO, ttl = 1, encrypted = true, encryptionVersion = 1, payload = "ciphertext")
        assertTrue(packet.isStructurallyValid())
        assertFalse(packet.copy(encrypted = false).isStructurallyValid())
        assertFalse(packet.copy(ttl = 2).isStructurallyValid())
        assertFalse(packet.copy(destinationNodeId = "*").isStructurallyValid())
        assertFalse(packet.copy(payload = "x".repeat(8193)).isStructurallyValid())
        assertNull(packet.copyForForward("relay"))
    }

    @Test fun encryptedAudioUsesPeerKeyAndAuthenticatedHeaderWithinTransportLimit() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val alice = generator.generateKeyPair()
        val bob = generator.generateKeyPair()
        val crypto = CryptoManager()
        val sendingKey = crypto.deriveSessionKey(alice.private, alice.public, bob.public)
        val receivingKey = crypto.deriveSessionKey(bob.private, bob.public, alice.public)
        val plaintext = CallProtocol.audio(id, 1, ByteArray(CallProtocol.FRAME_BYTES) { it.toByte() })
        val header = MeshPacket(sourceNodeId = "alice", destinationNodeId = "bob", previousHopId = "alice",
            packetType = PacketType.CALL_AUDIO, ttl = 1, encrypted = true, encryptionVersion = 1)
        val encrypted = crypto.encryptPayload(plaintext, sendingKey, header.authenticatedHeader())
        val packet = header.copy(payload = encrypted.toJson())
        val decoded = requireNotNull(MeshPacket.textToPacket(packet.toJson()))
        assertTrue(packet.toJson().toByteArray().size < 60_000)
        assertEquals(plaintext, crypto.decryptPayload(requireNotNull(EncryptedResult.fromJson(decoded.payload)),
            receivingKey, decoded.authenticatedHeader()))
        assertTrue(runCatching { crypto.decryptPayload(encrypted, receivingKey,
            header.copy(messageId = "tampered").authenticatedHeader()) }.isFailure)
    }
}
