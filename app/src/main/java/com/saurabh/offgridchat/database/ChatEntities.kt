package com.saurabh.offgridchat.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.saurabh.offgridchat.tools.ChatMessage
import java.util.UUID

/* ================================================================
   CONVERSATIONS
   ================================================================ */

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["nodeId"]),
        Index(value = ["isBlocked"]),
        Index(value = ["isApproved"])
    ]
)
data class ConversationEntity(

    /**
     * This is kept as the existing local conversation key so the
     * current UI/message queries remain compatible.
     *
     * For direct Bluetooth contacts this may be the Bluetooth address.
     * For logical mesh contacts it may be a node:<nodeId> reference.
     */
    @PrimaryKey
    val address: String,

    val name: String,

    /**
     * Logical/cryptographic peer identity.
     *
     * This must not be treated as a Bluetooth MAC address.
     */
    val nodeId: String? = null,

    /**
     * Short human-readable SHA-256 fingerprint of the peer public key.
     */
    val fingerprint: String? = null,

    val isOnline: Boolean = false,

    /**
     * Last known presence timestamp.
     */
    val lastSeen: Long = 0L,

    /**
     * Whether application-level communication with this identity
     * is approved.
     */
    val isApproved: Boolean = false,

    /**
     * Local conversation-level block state.
     *
     * Actual security enforcement also happens in the protocol layer.
     */
    val isBlocked: Boolean = false
)

/* ================================================================
   MESSAGES
   ================================================================ */

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["address"]),
        Index(value = ["messageId"], unique = true),
        Index(value = ["nodeId"]),
        Index(value = ["status"]),
        Index(value = ["time"])
    ]
)
data class MessageEntity(

    /**
     * Kept for compatibility with the existing UI/model.
     */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /**
     * Immutable protocol-level message identity.
     *
     * This is the ID used for:
     * - duplicate detection
     * - ACK
     * - READ_RECEIPT
     * - retry/reconciliation
     */
    val messageId: String = UUID.randomUUID().toString(),

    /**
     * Local conversation key.
     */
    val address: String,

    /**
     * Logical source/destination peer identity associated with
     * this local conversation.
     */
    val nodeId: String? = null,

    /**
     * Local plaintext for rendering the user's own chat history.
     *
     * IMPORTANT:
     * This field is never used as the Bluetooth wire payload.
     */
    val text: String,

    val fromMe: Boolean,

    /**
     * Original creation timestamp.
     */
    val time: Long,

    /**
     * Message lifecycle:
     *
     * SENDING
     * SENT
     * DELIVERED
     * READ
     * FAILED
     */
    val status: String = MESSAGE_STATUS_SENDING,

    /**
     * Remaining TTL known for this message locally.
     *
     * This is informational/local state and is not used as the
     * authoritative wire-level hop count.
     */
    val ttl: Int = 4,

    /**
     * True only when the outgoing/incoming wire message was actually
     * processed through the application-level encryption pipeline.
     */
    val encrypted: Boolean = false,

    /**
     * Encryption envelope version.
     */
    val encryptionVersion: Int? = null,

    /**
     * Timestamp when destination acknowledged persistence.
     */
    val deliveredAt: Long? = null,

    /**
     * Timestamp when destination acknowledged that the message
     * was actually read.
     */
    val readAt: Long? = null,

    /**
     * Timestamp when delivery ultimately failed.
     */
    val failedAt: Long? = null,

    /**
     * Number of protocol-level delivery retries.
     */
    val retryCount: Int = 0
)

/* ================================================================
   PEERS
   ================================================================ */

@Entity(
    tableName = "peers",
    indices = [
        Index(value = ["address"]),
        Index(value = ["fingerprint"]),
        Index(value = ["isOnline"])
    ]
)
data class PeerEntity(

    /**
     * Cryptographic/logical node identity.
     */
    @PrimaryKey
    val nodeId: String,

    /**
     * Current Bluetooth transport address.
     *
     * This is NOT the permanent identity.
     */
    val address: String,

    val name: String,

    /**
     * Short SHA-256 fingerprint derived from publicKeyHex.
     */
    val fingerprint: String,

    /**
     * Base64 encoded X.509 public-key representation.
     */
    val publicKeyHex: String,

    val lastSeen: Long,

    val isOnline: Boolean = false,

    /**
     * Whether this identity is authorized for normal messaging.
     */
    val isApproved: Boolean = false,

    /**
     * Whether this identity is blocked.
     */
    val isBlocked: Boolean = false,

    /**
     * Whether the public-key identity has been explicitly verified
     * by the local user/application.
     */
    val identityVerified: Boolean = false
)

/* ================================================================
   CONNECTION REQUESTS
   ================================================================ */

@Entity(
    tableName = "connection_requests",
    indices = [
        Index(value = ["requesterNodeId"]),
        Index(value = ["requesterNodeId", "status"]),
        Index(value = ["timestamp"])
    ]
)
data class ConnectionRequestEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /**
     * Cryptographic identity of the requesting device.
     */
    val requesterNodeId: String,

    /**
     * Current transport address from which the request arrived.
     */
    val address: String,

    val name: String,

    val fingerprint: String,

    /**
     * Public key supplied during the identity handshake.
     *
     * Optional default keeps compatibility with older stored/request
     * construction paths.
     */
    val publicKeyHex: String? = null,

    val timestamp: Long = System.currentTimeMillis(),

    /**
     * PENDING
     * ACCEPTED
     * REJECTED
     * BLOCKED
     * EXPIRED
     */
    val status: String = REQUEST_STATUS_PENDING,

    /** Signed CONNECT_REQUEST message ID; null for pre-upgrade/local DATA prompts. */
    val requestId: String? = null
)

/* ================================================================
   BLOCKED DEVICES
   ================================================================ */

@Entity(
    tableName = "blocked_devices",
    indices = [
        Index(value = ["address"]),
        Index(value = ["fingerprint"])
    ]
)
data class BlockedDeviceEntity(

    /**
     * Cryptographic/logical identity.
     */
    @PrimaryKey
    val nodeId: String,

    /**
     * Last known/current Bluetooth address.
     *
     * This is useful for pre-authentication filtering but is not
     * sufficient to identify a device permanently.
     */
    val address: String,

    val name: String,

    val fingerprint: String,

    /**
     * Public key associated with the blocked identity, when known.
     */
    val publicKeyHex: String? = null,

    val blockedAt: Long = System.currentTimeMillis()
)

/* ================================================================
   ROUTES
   ================================================================ */

@Entity(
    tableName = "routes",
    indices = [
        Index(value = ["nextHopAddress"]),
        Index(value = ["lastUpdated"])
    ]
)
data class RouteEntity(

    /**
     * Final logical destination.
     */
    @PrimaryKey
    val destinationNodeId: String,

    /**
     * Current directly-connected Bluetooth peer used as the
     * next hop toward destinationNodeId.
     */
    val nextHopAddress: String,

    /**
     * Estimated number of hops from this device to destination.
     */
    val hopCount: Int,

    /**
     * Public key of the final destination.
     *
     * This is necessary when the sender needs to derive the
     * destination-specific E2EE key before sending.
     */
    val publicKeyHex: String? = null,

    /**
     * Route creation/update timestamp.
     */
    val lastUpdated: Long = System.currentTimeMillis(),

    /**
     * Expiration timestamp.
     */
    val expiresAt: Long = System.currentTimeMillis() + ROUTE_DEFAULT_TTL_MILLIS
)

/* ================================================================
   CONVERSATION SUMMARY
   ================================================================ */

/**
 * Chats list ke liye: conversation + uska last message.
 */
data class ConversationSummary(
    val address: String,
    val name: String,
    val lastMessage: String?,
    val lastTime: Long?,
    val isOnline: Boolean = false,
    val isApproved: Boolean = false,
    val isBlocked: Boolean = false,
    val fingerprint: String? = null,
    val nodeId: String? = null
)

/* ================================================================
   ENTITY → UI MODEL
   ================================================================ */

fun MessageEntity.toChatMessage(): ChatMessage =
    ChatMessage(
        id = id,
        messageId = messageId,
        address = address,
        nodeId = nodeId,
        text = text,
        fromMe = fromMe,
        time = time,
        status = status
    )

/* ================================================================
   CONSTANTS
   ================================================================ */

const val MESSAGE_STATUS_SENDING = "SENDING"
const val MESSAGE_STATUS_SENT = "SENT"
const val MESSAGE_STATUS_DELIVERED = "DELIVERED"
const val MESSAGE_STATUS_READ = "READ"
const val MESSAGE_STATUS_FAILED = "FAILED"

const val REQUEST_STATUS_PENDING = "PENDING"
const val REQUEST_STATUS_ACCEPTED = "ACCEPTED"
const val REQUEST_STATUS_REJECTED = "REJECTED"
const val REQUEST_STATUS_BLOCKED = "BLOCKED"
const val REQUEST_STATUS_EXPIRED = "EXPIRED"

private const val ROUTE_DEFAULT_TTL_MILLIS = 120_000L
