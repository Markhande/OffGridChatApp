package com.saurabh.offgridchat.protocol

import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class MeshPacket(

    val protocolVersion: Int =
        CURRENT_PROTOCOL_VERSION,

    val messageId: String =
        UUID.randomUUID().toString(),

    val sourceNodeId: String,

    val destinationNodeId: String,

    val previousHopId: String,

    val ttl: Int =
        DEFAULT_TTL,

    val packetType: PacketType,

    val timestamp: Long =
        System.currentTimeMillis(),

    val payload: String = "",

    val sourceName: String? = null,

    val sourcePublicKey: String? = null,

    val ackMessageId: String? = null,

    val status: String? = null,

    val encrypted: Boolean = false,

    val encryptionVersion: Int? = null,

    val signature: String? = null
) {

    /** Fixed field order and explicit nulls make signing independent of JSON object ordering. */
    fun signatureInput(): ByteArray = JSONArray().apply {
        put(protocolVersion)
        put(messageId)
        put(sourceNodeId)
        put(destinationNodeId)
        put(packetType.name)
        put(timestamp)
        put(payload)
        put(sourceName ?: JSONObject.NULL)
        put(sourcePublicKey ?: JSONObject.NULL)
        put(ackMessageId ?: JSONObject.NULL)
        put(status ?: JSONObject.NULL)
        put(encrypted)
        put(encryptionVersion ?: JSONObject.NULL)
    }.toString().toByteArray(Charsets.UTF_8)

    fun toJson(): String {

        val json =
            JSONObject()

        json.put(
            "v",
            protocolVersion
        )

        json.put(
            "id",
            messageId
        )

        json.put(
            "src",
            sourceNodeId
        )

        json.put(
            "dst",
            destinationNodeId
        )

        json.put(
            "prev",
            previousHopId
        )

        json.put(
            "ttl",
            ttl
        )

        json.put(
            "type",
            packetType.name
        )

        json.put(
            "ts",
            timestamp
        )

        json.put(
            "payload",
            payload
        )

        json.put(
            "encrypted",
            encrypted
        )

        encryptionVersion?.let {
            json.put(
                "encV",
                it
            )
        }

        sourceName?.let {
            json.put(
                "srcName",
                it
            )
        }

        sourcePublicKey?.let {
            json.put(
                "srcPubKey",
                it
            )
        }

        ackMessageId?.let {
            json.put(
                "ackId",
                it
            )
        }

        status?.let {
            json.put(
                "status",
                it
            )
        }

        signature?.let {
            json.put(
                "sig",
                it
            )
        }

        return json.toString()
    }

    fun authenticatedHeader(): String {

        val json =
            JSONObject()

        /*
         * These fields are immutable across relay hops.
         *
         * previousHopId and ttl are intentionally excluded because
         * they change during forwarding.
         */
        json.put(
            "v",
            protocolVersion
        )

        json.put(
            "id",
            messageId
        )

        json.put(
            "src",
            sourceNodeId
        )

        json.put(
            "dst",
            destinationNodeId
        )

        json.put(
            "type",
            packetType.name
        )

        json.put(
            "ts",
            timestamp
        )

        json.put(
            "encrypted",
            encrypted
        )

        encryptionVersion?.let {
            json.put(
                "encV",
                it
            )
        }

        return json.toString()
    }

    fun copyForForward(
        forwardingNodeId: String
    ): MeshPacket? {

        if (ttl <= 1) {
            return null
        }

        require(
            forwardingNodeId.isNotBlank()
        ) {
            "Forwarding node ID must not be empty"
        }

        return copy(
            previousHopId =
                forwardingNodeId,

            ttl =
                ttl - 1
        )
    }

    fun isValidDestinationForType(): Boolean {

        if (
            destinationNodeId.isBlank()
        ) {
            return false
        }

        if (
            destinationNodeId !=
            BROADCAST_NODE_ID
        ) {
            return true
        }

        return packetType.name in
                BROADCAST_ALLOWED_TYPES
    }

    fun isStructurallyValid(
        nowMillis: Long =
            System.currentTimeMillis()
    ): Boolean {

        if (
            protocolVersion !=
            CURRENT_PROTOCOL_VERSION
        ) {
            return false
        }

        if (
            messageId.isBlank()
        ) {
            return false
        }

        if (
            sourceNodeId.isBlank()
        ) {
            return false
        }

        if (
            destinationNodeId.isBlank()
        ) {
            return false
        }

        if (
            previousHopId.isBlank()
        ) {
            return false
        }

        if (
            ttl !in 0..MAX_TTL
        ) {
            return false
        }

        if (
            timestamp <= 0L
        ) {
            return false
        }

        if (
            timestamp >
            nowMillis +
            MAX_FUTURE_TIMESTAMP_SKEW_MILLIS
        ) {
            return false
        }

        if (
            !isValidDestinationForType()
        ) {
            return false
        }

        if (
            payload
                .toByteArray(
                    Charsets.UTF_8
                )
                .size >
            MAX_PAYLOAD_BYTES
        ) {
            return false
        }

        if (
            packetType.name ==
            DATA_PACKET_TYPE || packetType == PacketType.GROUP_DATA || packetType == PacketType.FILE_CHUNK ||
                    packetType == PacketType.CALL_SIGNAL || packetType == PacketType.CALL_AUDIO
        ) {

            if (!encrypted) {
                return false
            }

            if (
                encryptionVersion == null
            ) {
                return false
            }

            if (
                payload.isBlank()
            ) {
                return false
            }
        }

        if (packetType == PacketType.CALL_SIGNAL || packetType == PacketType.CALL_AUDIO) {
            if (ttl != 1 || payload.length > 8_192 || destinationNodeId == BROADCAST_NODE_ID) return false
        }

        if (
            !sourcePublicKey.isNullOrBlank()
        ) {

            if (
                !isNodeIdBoundToPublicKey()
            ) {
                return false
            }
        }

        return true
    }

    fun isNodeIdBoundToPublicKey(): Boolean {

        val publicKeyValue =
            sourcePublicKey
                ?: return false

        return try {

            val publicKeyBytes =
                Base64.getDecoder()
                    .decode(
                        publicKeyValue
                    )

            if (
                publicKeyBytes.isEmpty()
            ) {
                return false
            }

            val digest =
                MessageDigest.getInstance(
                    "SHA-256"
                )
                    .digest(
                        publicKeyBytes
                    )

            val fingerprint =
                digest.joinToString("") {
                    "%02x".format(it)
                }

            val expectedNodeId =
                NODE_ID_PREFIX +
                        fingerprint.take(
                            NODE_ID_HEX_LENGTH
                        )

            sourceNodeId ==
                    expectedNodeId

        } catch (_: Exception) {

            false
        }
    }

    companion object {

        const val CURRENT_PROTOCOL_VERSION =
            3

        const val DEFAULT_TTL =
            4

        const val MAX_TTL =
            16

        const val BROADCAST_NODE_ID =
            "*"

        private const val NODE_ID_PREFIX =
            "node_"

        private const val NODE_ID_HEX_LENGTH =
            32

        private const val DATA_PACKET_TYPE =
            "DATA"

        private const val MAX_PAYLOAD_BYTES =
            512 * 1024

        private const val MAX_FUTURE_TIMESTAMP_SKEW_MILLIS =
            5 * 60 * 1000L

        private val BROADCAST_ALLOWED_TYPES =
            setOf(
                "AUTH_HELLO",
                "PRESENCE_PING",
                "PRESENCE_PONG",
                "ROUTE_ANNOUNCE",
                "CONNECT_REQUEST"
            )

        fun textToPacket(
            text: String
        ): MeshPacket? {

            return try {

                if (
                    text.isBlank()
                ) {
                    return null
                }

                val json =
                    JSONObject(text)

                val protocolVersion =
                    json.optInt(
                        "v",
                        CURRENT_PROTOCOL_VERSION
                    )

                if (
                    protocolVersion !=
                    CURRENT_PROTOCOL_VERSION
                ) {
                    return null
                }

                val packetType =
                    try {

                        PacketType.valueOf(
                            json.getString(
                                "type"
                            )
                        )

                    } catch (
                        _: IllegalArgumentException
                    ) {

                        return null
                    }

                val packet =
                    MeshPacket(

                        protocolVersion =
                            protocolVersion,

                        messageId =
                            json.getString(
                                "id"
                            ),

                        sourceNodeId =
                            json.getString(
                                "src"
                            ),

                        destinationNodeId =
                            json.getString(
                                "dst"
                            ),

                        previousHopId =
                            json.getString(
                                "prev"
                            ),

                        ttl =
                            json.optInt(
                                "ttl",
                                DEFAULT_TTL
                            ),

                        packetType =
                            packetType,

                        timestamp =
                            json.optLong(
                                "ts",
                                System.currentTimeMillis()
                            ),

                        payload =
                            json.optString(
                                "payload",
                                ""
                            ),

                        sourceName =
                            json.optionalString(
                                "srcName"
                            ),

                        sourcePublicKey =
                            json.optionalString(
                                "srcPubKey"
                            ),

                        ackMessageId =
                            json.optionalString(
                                "ackId"
                            ),

                        status =
                            json.optionalString(
                                "status"
                            ),

                        encrypted =
                            json.optBoolean(
                                "encrypted",
                                false
                            ),

                        encryptionVersion =
                            if (
                                json.has("encV") &&
                                !json.isNull("encV")
                            ) {
                                json.getInt(
                                    "encV"
                                )
                            } else {
                                null
                            },

                        signature =
                            json.optionalString(
                                "sig"
                            )
                    )

                if (
                    !packet.isStructurallyValid()
                ) {
                    return null
                }

                packet

            } catch (_: Exception) {

                null
            }
        }

        /**
         * org.json's Java overloads can produce Kotlin
         * Nothing/String type inference problems when null is
         * passed as the default value.
         */
        private fun JSONObject.optionalString(
            key: String
        ): String? {

            if (
                !has(key) ||
                isNull(key)
            ) {
                return null
            }

            return getString(key)
                .takeIf {
                    it.isNotBlank()
                }
        }
    }
}
