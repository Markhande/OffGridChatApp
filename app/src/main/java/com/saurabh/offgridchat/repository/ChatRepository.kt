package com.saurabh.offgridchat.repository

import com.saurabh.offgridchat.database.BlockedDeviceEntity
import com.saurabh.offgridchat.database.ChatDao
import com.saurabh.offgridchat.database.ConnectionRequestEntity
import com.saurabh.offgridchat.database.ConversationEntity
import com.saurabh.offgridchat.database.ConversationSummary
import com.saurabh.offgridchat.database.MessageEntity
import com.saurabh.offgridchat.database.IncomingMessageResult
import com.saurabh.offgridchat.database.PeerEntity
import com.saurabh.offgridchat.database.RouteEntity
import com.saurabh.offgridchat.database.toChatMessage
import com.saurabh.offgridchat.tools.ChatMessage
import com.saurabh.offgridchat.protocol.ConnectionPolicy
import com.saurabh.offgridchat.protocol.ConnectionAdmission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val dao: ChatDao
) {
    suspend fun reconnectablePeers(): List<PeerEntity> = dao.reconnectablePeers()
    fun observeRoutes(): Flow<List<RouteEntity>> = dao.observeRoutes()
    suspend fun canConnectIdentity(nodeId: String, allowNew: Boolean, respectRemoteDecision: Boolean = true): Boolean =
        dao.canConnectIdentity(nodeId, allowNew, respectRemoteDecision)
    suspend fun reopenRemoteApprovalOnIncomingConnection(nodeId: String) = dao.reopenRemoteApprovalOnIncomingConnection(nodeId)
    suspend fun expireUnapprovedRequests() = dao.expireUnapprovedRequests()
    suspend fun knownNodeAtAddress(address: String): String? =
        dao.knownNodeAtAddress(address) ?: dao.getConversation(address)?.nodeId

    /* ============================================================
       CONVERSATIONS
       ============================================================ */

    fun conversations(): Flow<List<ConversationSummary>> =
        dao.observeConversations()

    fun messages(
        address: String
    ): Flow<List<ChatMessage>> {

        return dao.observeMessages(address)
            .map { entities ->
                entities.map {
                    it.toChatMessage()
                }
            }
    }

    suspend fun getConversation(
        address: String
    ): ConversationEntity? {

        if (address.isBlank()) {
            return null
        }

        return dao.getConversation(address)
    }

    suspend fun getConversationByNodeId(
        nodeId: String
    ): ConversationEntity? {

        if (nodeId.isBlank()) {
            return null
        }

        return dao.getConversationByNodeId(nodeId)
    }

    /**
     * Resolves a peer using the conversation's logical node identity.
     *
     * Bluetooth address is treated only as transport information.
     */
    suspend fun getPeerForConversation(
        address: String
    ): PeerEntity? {

        if (address.isBlank()) {
            return null
        }

        val conversation =
            dao.getConversation(address)
                ?: return null

        val nodeId =
            conversation.nodeId

        if (!nodeId.isNullOrBlank()) {

            dao.getPeerByNodeId(
                nodeId
            )?.let {
                return it
            }
        }

        return null
    }

    suspend fun saveConversation(
        address: String,
        name: String,
        nodeId: String? = null,
        fingerprint: String? = null,
        isOnline: Boolean? = null,
        isApproved: Boolean? = null,
        isBlocked: Boolean? = null,
        lastSeen: Long = System.currentTimeMillis()
    ) {

        require(
            address.isNotBlank()
        ) {
            "Conversation address must not be blank"
        }

        dao.mergeConversation(
            ConversationEntity(
                address =
                    address,

                name =
                    name,

                nodeId =
                    nodeId,

                fingerprint =
                    fingerprint,

                isOnline =
                    isOnline ?: false,

                lastSeen = lastSeen
            ),
            approval = isApproved,
            blocked = isBlocked,
            online = isOnline
        )
    }

    suspend fun setConversationApproval(
        nodeId: String,
        approved: Boolean
    ) {
        if (nodeId.isBlank()) {
            return
        }

        dao.updateConversationApproval(
            nodeId = nodeId,
            approved = approved
        )
    }

    /* ============================================================
       MESSAGES
       ============================================================ */

    /**
     * Idempotent message insert.
     *
     * true  = inserted
     * false = messageId already exists
     */
    suspend fun saveMessage(
        msg: ChatMessage,
        encrypted: Boolean = false,
        encryptionVersion: Int? = null
    ): Boolean {

        require(
            msg.messageId.isNotBlank()
        ) {
            "Message ID must not be blank"
        }

        require(
            msg.address.isNotBlank()
        ) {
            "Message address must not be blank"
        }

        require(
            msg.time > 0L
        ) {
            "Message timestamp must be valid"
        }

        require(
            msg.status.isNotBlank()
        ) {
            "Message status must not be blank"
        }

        val insertedRowId =
            dao.insertMessage(
                MessageEntity(
                    messageId = msg.messageId,
                    address = msg.address,
                    nodeId = msg.nodeId,
                    text = msg.text,
                    fromMe = msg.fromMe,
                    time = msg.time,
                    status = msg.status,
                    encrypted = encrypted,
                    encryptionVersion = encryptionVersion
                )
            )

        return insertedRowId != -1L
    }

    suspend fun persistIncomingMessage(msg: ChatMessage, encryptionVersion: Int,
        policy: ConnectionPolicy, request: ConnectionRequestEntity): IncomingMessageResult {
        require(msg.messageId.isNotBlank() && msg.address.isNotBlank() &&
            !msg.nodeId.isNullOrBlank() && msg.time > 0 && !msg.fromMe)
        return dao.persistIncomingMessage(MessageEntity(
            messageId = msg.messageId, address = msg.address, nodeId = msg.nodeId,
            text = msg.text, fromMe = false, time = msg.time, status = msg.status,
            encrypted = true, encryptionVersion = encryptionVersion,
            deliveredAt = System.currentTimeMillis()
        ), policy, request)
    }

    suspend fun admitIncomingIdentity(request: ConnectionRequestEntity, policy: ConnectionPolicy): ConnectionAdmission =
        dao.admitIncomingIdentity(request, policy)

    suspend fun decideConnectionRequest(id: Long, accepted: Boolean): Boolean =
        dao.decideConnectionRequest(id, accepted)

    suspend fun getMessageById(
        messageId: String
    ): MessageEntity? {

        if (messageId.isBlank()) {
            return null
        }

        return dao.getMessageById(
            messageId
        )
    }

    suspend fun messageExists(
        messageId: String
    ): Boolean {

        if (messageId.isBlank()) {
            return false
        }

        return dao.messageExists(
            messageId
        )
    }

    suspend fun updateMessageStatus(
        messageId: String,
        status: String
    ) {

        if (
            messageId.isBlank() ||
            status.isBlank()
        ) {
            return
        }

        dao.updateMessageStatus(
            messageId = messageId,
            status = status
        )
    }

    fun messageCount(): Flow<Int> =
        dao.observeMessageCount()

    fun totalBytes(): Flow<Long?> =
        dao.observeTotalBytes()

    /* ============================================================
       PEERS
       ============================================================ */

    suspend fun upsertPeer(
        peer: PeerEntity
    ) {

        require(
            peer.nodeId.isNotBlank()
        ) {
            "Peer nodeId must not be blank"
        }

        require(
            peer.address.isNotBlank()
        ) {
            "Peer address must not be blank"
        }

        require(
            peer.publicKeyHex.isNotBlank()
        ) {
            "Peer public key must not be blank"
        }

        require(
            peer.fingerprint.isNotBlank()
        ) {
            "Peer fingerprint must not be blank"
        }

        dao.upsertPeer(
            peer
        )
    }

    suspend fun getPeerByNodeId(
        nodeId: String
    ): PeerEntity? {

        if (nodeId.isBlank()) {
            return null
        }

        return dao.getPeerByNodeId(
            nodeId
        )
    }

    suspend fun clearPresence() = dao.clearPresence()

    suspend fun updatePeerPresence(
        nodeId: String,
        isOnline: Boolean
    ) {

        if (nodeId.isBlank()) {
            return
        }

        val now =
            System.currentTimeMillis()

        dao.updatePeerPresence(
            nodeId =
                nodeId,

            isOnline =
                isOnline,

            lastSeen =
                now
        )

        /*
         * Keep conversation state synchronized with peer state.
         */
        dao.updateConversationPresence(
            nodeId =
                nodeId,

            isOnline =
                isOnline,

            lastSeen =
                now
        )
    }

    /* ============================================================
       CONNECTION REQUESTS
       ============================================================ */

    suspend fun addConnectionRequest(
        request: ConnectionRequestEntity
    ): Boolean {

        require(
            request.requesterNodeId.isNotBlank()
        ) {
            "Requester nodeId must not be blank"
        }

        require(
            request.address.isNotBlank()
        ) {
            "Request address must not be blank"
        }

        require(
            request.timestamp > 0L
        ) {
            "Request timestamp must be valid"
        }

        require(
            request.status.isNotBlank()
        ) {
            "Request status must not be blank"
        }

        /*
         * Prevent multiple pending requests from the same identity.
         */
        return dao.insertPendingRequest(request)
    }

    fun observePendingRequests():
            Flow<List<ConnectionRequestEntity>> =
        dao.observePendingRequests()

    suspend fun updateRequestStatus(
        id: Long,
        status: String
    ) {

        if (
            id <= 0L ||
            status.isBlank()
        ) {
            return
        }

        dao.updateRequestStatus(
            id = id,
            status = status
        )
    }

    suspend fun updatePendingRequestsForNode(
        requesterNodeId: String,
        status: String
    ) {

        if (
            requesterNodeId.isBlank() ||
            status.isBlank()
        ) {
            return
        }

        dao.updatePendingRequestsForNode(
            requesterNodeId =
                requesterNodeId,

            status =
                status
        )
    }

    /* ============================================================
       BLOCKED DEVICES
       ============================================================ */

    suspend fun blockDevice(
        blocked: BlockedDeviceEntity
    ) {

        require(
            blocked.nodeId.isNotBlank()
        ) {
            "Blocked nodeId must not be blank"
        }

        require(
            blocked.address.isNotBlank()
        ) {
            "Blocked address must not be blank"
        }

        require(
            blocked.fingerprint.isNotBlank()
        ) {
            "Blocked fingerprint must not be blank"
        }

        dao.blockIdentity(blocked)
    }

    suspend fun unblockDevice(
        nodeId: String
    ) {

        if (nodeId.isBlank()) {
            return
        }

        dao.unblockIdentity(nodeId)
    }

    suspend fun isBlocked(
        nodeId: String
    ): Boolean {

        if (nodeId.isBlank()) {
            return false
        }

        return dao.getBlockedDevice(
            nodeId
        ) != null
    }

    suspend fun isBlockedByAddress(
        address: String
    ): Boolean {

        if (address.isBlank()) {
            return false
        }

        return dao.getBlockedDeviceByAddress(
            address
        ) != null
    }

    suspend fun isDeviceBlocked(
        nodeId: String?,
        address: String
    ): Boolean {

        if (address.isBlank()) {
            return false
        }

        val normalizedNodeId =
            nodeId
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }

        return if (
            normalizedNodeId != null
        ) {

            dao.isDeviceBlocked(
                nodeId =
                    normalizedNodeId,

                address =
                    address
            )

        } else {

            dao.getBlockedDeviceByAddress(
                address
            ) != null
        }
    }

    fun observeBlockedDevices():
            Flow<List<BlockedDeviceEntity>> =
        dao.observeBlockedDevices()

    /* ============================================================
       ROUTES
       ============================================================ */

    suspend fun upsertRoute(
        route: RouteEntity
    ) {

        require(
            route.destinationNodeId.isNotBlank()
        ) {
            "Route destinationNodeId must not be blank"
        }

        require(
            route.nextHopAddress.isNotBlank()
        ) {
            "Route nextHopAddress must not be blank"
        }

        require(
            route.hopCount > 0
        ) {
            "Route hopCount must be greater than zero"
        }

        require(
            route.lastUpdated > 0L
        ) {
            "Route lastUpdated must be valid"
        }

        require(
            route.expiresAt >
                    route.lastUpdated
        ) {
            "Route expiresAt must be after lastUpdated"
        }

        dao.upsertRoute(
            route
        )
    }

    suspend fun getRoute(
        destinationNodeId: String
    ): RouteEntity? {

        if (
            destinationNodeId.isBlank()
        ) {
            return null
        }

        val route =
            dao.getRoute(
                destinationNodeId
            )
                ?: return null

        if (
            route.expiresAt <=
            System.currentTimeMillis()
        ) {

            dao.deleteRoute(
                destinationNodeId
            )

            return null
        }

        return route
    }

    suspend fun deleteRoute(
        destinationNodeId: String
    ) {

        if (
            destinationNodeId.isBlank()
        ) {
            return
        }

        dao.deleteRoute(
            destinationNodeId
        )
    }

    suspend fun clearRoutes() {
        dao.clearRoutes()
    }

    /* ============================================================
       GLOBAL DATA
       ============================================================ */

    suspend fun clearAll() {
        dao.clearAllData()
    }
}
