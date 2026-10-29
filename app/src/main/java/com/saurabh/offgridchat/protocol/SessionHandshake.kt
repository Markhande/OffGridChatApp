package com.saurabh.offgridchat.protocol

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** A fresh challenge for one socket. Signed advertisements alone never authenticate it. */
class SessionHandshake(
    private val localNodeId: String,
    private val localPublicKey: String,
    private val nowNanos: () -> Long = System::nanoTime
) {
    enum class Result { RESPONSE_REQUIRED, AUTHENTICATED, REJECTED }
    private val startedAt = nowNanos()
    private val challenge = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private var remoteChallenge: String? = null
    var remoteNodeId: String? = null
        private set
    var remotePublicKey: String? = null
        private set
    var authenticated: Boolean = false
        private set

    fun hello(): MeshPacket = MeshPacket(sourceNodeId = localNodeId,
        destinationNodeId = MeshPacket.BROADCAST_NODE_ID, previousHopId = localNodeId,
        ttl = 1, packetType = PacketType.AUTH_HELLO, payload = challenge, sourcePublicKey = localPublicKey)

    fun proof(): MeshPacket {
        val remote = checkNotNull(remoteChallenge)
        return MeshPacket(sourceNodeId = localNodeId, destinationNodeId = checkNotNull(remoteNodeId),
            previousHopId = localNodeId, ttl = 1, packetType = PacketType.AUTH_PROOF,
            payload = "$remote:$challenge", sourcePublicKey = localPublicKey)
    }

    @Synchronized
    fun receive(packet: MeshPacket): Result {
        if (authenticated || nowNanos() - startedAt >= TIMEOUT_MILLIS * 1_000_000L ||
            !packet.isStructurallyValid() || packet.ttl != 1 || packet.encrypted ||
            packet.sourceNodeId == localNodeId || packet.previousHopId != packet.sourceNodeId ||
            !validSignature(packet)) return Result.REJECTED
        return when (packet.packetType) {
            PacketType.AUTH_HELLO -> {
                if (remoteChallenge != null || packet.destinationNodeId != MeshPacket.BROADCAST_NODE_ID ||
                    !validChallenge(packet.payload)) return Result.REJECTED
                remoteChallenge = packet.payload
                remoteNodeId = packet.sourceNodeId
                remotePublicKey = packet.sourcePublicKey
                Result.RESPONSE_REQUIRED
            }
            PacketType.AUTH_PROOF -> {
                if (remoteChallenge == null || packet.destinationNodeId != localNodeId ||
                    packet.sourceNodeId != remoteNodeId || packet.sourcePublicKey != remotePublicKey ||
                    packet.payload != "$challenge:$remoteChallenge") return Result.REJECTED
                authenticated = true
                Result.AUTHENTICATED
            }
            else -> Result.REJECTED
        }
    }

    private fun validSignature(packet: MeshPacket): Boolean = try {
        if (!packet.isNodeIdBoundToPublicKey() || packet.signature == null) false else {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(packet.sourcePublicKey)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(packet.signatureInput())
                verify(Base64.getDecoder().decode(packet.signature))
            }
        }
    } catch (_: Exception) { false }

    private fun validChallenge(value: String): Boolean = try {
        value.length == 44 && Base64.getDecoder().decode(value).size == 32
    } catch (_: IllegalArgumentException) { false }

    companion object { const val TIMEOUT_MILLIS = 15_000L }
}
