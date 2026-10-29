package com.saurabh.offgridchat.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import com.saurabh.offgridchat.protocol.ConnectionPolicy
import com.saurabh.offgridchat.protocol.ConnectionAdmission

@Dao
interface ChatDao {
    @Query("SELECT * FROM routes")
    fun observeRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT nodeId FROM peers WHERE address = :address ORDER BY lastSeen DESC LIMIT 1")
    suspend fun knownNodeAtAddress(address: String): String?

    /** Approval belongs to the authenticated node identity, not a Bluetooth address/name. */
    @Transaction
    suspend fun canConnectIdentity(nodeId: String, allowNew: Boolean, respectRemoteDecision: Boolean = true): Boolean {
        val conversation = getConversationByNodeId(nodeId)
        if (getBlockedDevice(nodeId) != null || conversation?.isBlocked == true ||
            getLatestConnectionRequest(nodeId)?.status in listOf(REQUEST_STATUS_REJECTED, REQUEST_STATUS_BLOCKED) ||
            (respectRemoteDecision && outgoingApproval(nodeId)?.status in listOf(REQUEST_STATUS_REJECTED, REQUEST_STATUS_BLOCKED))) return false
        return allowNew || conversation?.isApproved == true
    }

    @Query("""UPDATE connection_requests SET status = 'EXPIRED' WHERE status = 'PENDING'
        AND NOT EXISTS (SELECT 1 FROM conversations c WHERE c.nodeId = connection_requests.requesterNodeId
            AND c.isApproved = 1 AND c.isBlocked = 0)""")
    suspend fun expireUnapprovedRequests()

    @Query("""
        SELECT DISTINCT p.* FROM peers p
        JOIN conversations c ON c.nodeId = p.nodeId
        JOIN outgoing_approvals o ON o.nodeId = p.nodeId AND o.publicKeyHex = p.publicKeyHex
        WHERE c.isApproved = 1 AND c.isBlocked = 0 AND o.status NOT IN ('REJECTED', 'BLOCKED')
        AND NOT EXISTS (SELECT 1 FROM blocked_devices b WHERE b.nodeId = p.nodeId OR b.address = p.address)
        AND NOT EXISTS (SELECT 1 FROM connection_requests r WHERE r.requesterNodeId = p.nodeId
            AND r.id = (SELECT MAX(id) FROM connection_requests WHERE requesterNodeId = p.nodeId)
            AND r.status IN ('REJECTED', 'BLOCKED'))
    """)
    suspend fun reconnectablePeers(): List<PeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOutgoingApproval(approval: OutgoingApproval)

    @Query("SELECT * FROM outgoing_approvals WHERE nodeId = :nodeId")
    suspend fun outgoingApproval(nodeId: String): OutgoingApproval?

    @Query("SELECT * FROM outgoing_approvals WHERE nodeId = :nodeId")
    fun observeOutgoingApproval(nodeId: String): Flow<OutgoingApproval?>

    @Query("SELECT * FROM connection_requests WHERE requesterNodeId = :nodeId AND requestId = :requestId LIMIT 1")
    suspend fun wireRequest(nodeId: String, requestId: String): ConnectionRequestEntity?

    @Query("UPDATE connection_requests SET status = 'EXPIRED' WHERE status = 'PENDING' AND timestamp <= :cutoff")
    suspend fun expireIncomingApprovals(cutoff: Long)

    @Query("UPDATE outgoing_approvals SET status = 'EXPIRED' WHERE status = 'PENDING' AND timestamp <= :cutoff")
    suspend fun expireOutgoingApprovals(cutoff: Long)

    @Transaction
    suspend fun beginOutgoingApproval(candidate: OutgoingApproval, now: Long): OutgoingApproval {
        val old = outgoingApproval(candidate.nodeId)
        if (old != null && old.publicKeyHex == candidate.publicKeyHex &&
            now - old.timestamp in 0 until APPROVAL_TIMEOUT_MS && old.status != REQUEST_STATUS_EXPIRED) return old
        val next = if (getBlockedDevice(candidate.nodeId) != null)
            candidate.copy(status = REQUEST_STATUS_BLOCKED) else candidate
        putOutgoingApproval(next)
        return next
    }

    @Transaction
    suspend fun applyApprovalResponse(nodeId: String, key: String, requestId: String, status: String, now: Long): Boolean {
        if (status !in listOf(REQUEST_STATUS_PENDING, REQUEST_STATUS_ACCEPTED, REQUEST_STATUS_REJECTED,
                REQUEST_STATUS_BLOCKED, REQUEST_STATUS_EXPIRED)) return false
        val old = outgoingApproval(nodeId) ?: return false
        if (old.requestId != requestId || old.publicKeyHex != key || getBlockedDevice(nodeId) != null) return false
        if (now - old.timestamp !in 0 until APPROVAL_TIMEOUT_MS) {
            putOutgoingApproval(old.copy(status = REQUEST_STATUS_EXPIRED))
            return false
        }
        // A block revokes an earlier acceptance; otherwise terminal responses cannot be reversed.
        if (old.status != REQUEST_STATUS_PENDING && status != REQUEST_STATUS_BLOCKED) return old.status == status
        putOutgoingApproval(old.copy(status = status))
        return true
    }

    @Transaction
    suspend fun receiveApprovalRequest(request: ConnectionRequestEntity, policy: ConnectionPolicy, now: Long): String {
        val wireId = requireNotNull(request.requestId)
        require(wireId.isNotBlank() && !request.publicKeyHex.isNullOrBlank())
        if (getBlockedDevice(request.requesterNodeId) != null ||
            getConversationByNodeId(request.requesterNodeId)?.isBlocked == true) return REQUEST_STATUS_BLOCKED
        // Closing admission is temporary policy, not a permanent user rejection.
        if (!policy.allowNewConnections && getConversationByNodeId(request.requesterNodeId)?.isApproved != true) {
            updatePendingRequestsForNode(request.requesterNodeId, REQUEST_STATUS_EXPIRED)
            return REQUEST_STATUS_EXPIRED
        }
        wireRequest(request.requesterNodeId, wireId)?.let { old ->
            if (old.publicKeyHex != request.publicKeyHex) return REQUEST_STATUS_REJECTED
            if (old.timestamp <= now - APPROVAL_TIMEOUT_MS) {
                if (old.status == REQUEST_STATUS_PENDING) updateRequestStatus(old.id, REQUEST_STATUS_EXPIRED)
                return REQUEST_STATUS_EXPIRED
            }
            return old.status
        }
        // Reject replayed or implausibly future-dated requests without replacing the current prompt.
        if (request.timestamp <= now - APPROVAL_TIMEOUT_MS || request.timestamp > now + 60_000)
            return REQUEST_STATUS_EXPIRED
        val latest = getLatestConnectionRequest(request.requesterNodeId)
        if (latest != null && latest.timestamp > request.timestamp) return REQUEST_STATUS_EXPIRED
        updatePendingRequestsForNode(request.requesterNodeId, REQUEST_STATUS_EXPIRED)
        val status = when (admitIncomingIdentity(request, policy)) {
            ConnectionAdmission.ALLOW -> REQUEST_STATUS_ACCEPTED
            ConnectionAdmission.REJECT -> REQUEST_STATUS_REJECTED
            ConnectionAdmission.NEEDS_APPROVAL -> REQUEST_STATUS_PENDING
        }
        if (status != REQUEST_STATUS_PENDING) insertConnectionRequest(request.copy(status = status))
        return status
    }

    /* ============================================================
       CONVERSATIONS
       ============================================================ */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(
        conversation: ConversationEntity
    )

    @Transaction
    suspend fun mergeConversation(conversation: ConversationEntity, approval: Boolean?, blocked: Boolean?, online: Boolean? = null) {
        val existing = getConversation(conversation.address)
        upsertConversation(conversation.copy(
            isOnline = online ?: existing?.isOnline ?: false,
            name = com.saurabh.offgridchat.tools.PeerNames.resolve(conversation.address, conversation.name, existing?.name),
            nodeId = conversation.nodeId ?: existing?.nodeId,
            fingerprint = conversation.fingerprint ?: existing?.fingerprint,
            isApproved = approval ?: (existing?.isApproved == true &&
                (conversation.nodeId == null || conversation.nodeId == existing.nodeId)),
            isBlocked = blocked ?: existing?.isBlocked ?: false
        ))
    }

    @Query(
        """
        SELECT *
        FROM conversations
        WHERE address = :address
        LIMIT 1
        """
    )
    suspend fun getConversation(
        address: String
    ): ConversationEntity?

    @Query(
        """
        SELECT *
        FROM conversations
        WHERE nodeId = :nodeId
        LIMIT 1
        """
    )
    suspend fun getConversationByNodeId(
        nodeId: String
    ): ConversationEntity?

    @Query(
        """
        SELECT
            c.address AS address,
            c.name AS name,

            (
                SELECT m.text
                FROM messages m
                WHERE m.address = c.address
                ORDER BY m.time DESC
                LIMIT 1
            ) AS lastMessage,

            (
                SELECT m.time
                FROM messages m
                WHERE m.address = c.address
                ORDER BY m.time DESC
                LIMIT 1
            ) AS lastTime,

            c.isOnline AS isOnline,
            c.isApproved AS isApproved,
            c.isBlocked AS isBlocked,
            c.fingerprint AS fingerprint,
            c.nodeId AS nodeId

        FROM conversations c

        ORDER BY
            COALESCE(lastTime, c.lastSeen) DESC
        """
    )
    fun observeConversations():
            Flow<List<ConversationSummary>>

    @Query(
        """
        UPDATE conversations
        SET
            isOnline = :isOnline,
            lastSeen = :lastSeen
        WHERE nodeId = :nodeId
        """
    )
    suspend fun updateConversationPresence(
        nodeId: String,
        isOnline: Boolean,
        lastSeen: Long
    )

    @Query("UPDATE conversations SET isOnline = 0")
    suspend fun clearConversationPresence()

    @Query("UPDATE peers SET isOnline = 0")
    suspend fun clearPeerPresence()

    @Transaction
    suspend fun clearPresence() {
        clearConversationPresence()
        clearPeerPresence()
    }

    @Query(
        """
        UPDATE conversations
        SET isApproved = :approved
        WHERE nodeId = :nodeId
        """
    )
    suspend fun updateConversationApproval(
        nodeId: String,
        approved: Boolean
    )

    @Query(
        """
        UPDATE conversations
        SET isBlocked = :blocked
        WHERE nodeId = :nodeId
        """
    )
    suspend fun updateConversationBlocked(
        nodeId: String,
        blocked: Boolean
    )

    /* ============================================================
       MESSAGES
       ============================================================ */

    /**
     * Atomic duplicate protection is provided by the unique
     * messageId index on MessageEntity.
     *
     * Returns:
     * > 0  -> inserted
     * -1   -> duplicate / ignored
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(
        message: MessageEntity
    ): Long

    /** Only a matching, persisted incoming message is eligible for an acknowledgement. */
    @Transaction
    suspend fun persistIncomingMessage(
        message: MessageEntity,
        policy: ConnectionPolicy,
        request: ConnectionRequestEntity
    ): IncomingMessageResult {
        require(!message.fromMe && message.encrypted && message.encryptionVersion != null)
        require(message.nodeId == request.requesterNodeId && !request.publicKeyHex.isNullOrBlank())
        val admission = admitIncomingIdentity(request, policy)
        if (admission == ConnectionAdmission.REJECT) return IncomingMessageResult.REJECTED
        if (admission == ConnectionAdmission.NEEDS_APPROVAL) return IncomingMessageResult.NEEDS_APPROVAL
        if (insertMessage(message) != -1L) return IncomingMessageResult.INSERTED
        val existing = getMessageById(message.messageId) ?: return IncomingMessageResult.CONFLICT
        return if (!existing.fromMe && existing.nodeId == message.nodeId &&
            existing.time == message.time && existing.text == message.text &&
            existing.encrypted && existing.encryptionVersion == message.encryptionVersion
        ) IncomingMessageResult.DUPLICATE else IncomingMessageResult.CONFLICT
    }

    @Transaction
    suspend fun admitIncomingIdentity(request: ConnectionRequestEntity, policy: ConnectionPolicy): ConnectionAdmission {
        require(request.requesterNodeId.isNotBlank() && request.address.isNotBlank() &&
            !request.publicKeyHex.isNullOrBlank())
        val conversation = getConversationByNodeId(request.requesterNodeId)
        if (getBlockedDevice(request.requesterNodeId) != null || conversation?.isBlocked == true)
            return ConnectionAdmission.REJECT
        if (getLatestConnectionRequest(request.requesterNodeId)?.status == REQUEST_STATUS_REJECTED)
            return ConnectionAdmission.REJECT
        if (conversation?.isApproved == true) return ConnectionAdmission.ALLOW
        if (!policy.allowNewConnections) return ConnectionAdmission.REJECT
        if (policy.requireApproval) {
            insertPendingRequest(request)
            return ConnectionAdmission.NEEDS_APPROVAL
        }
        // The user currently allows new peers without prompting. Remember this established contact.
        mergeConversation(ConversationEntity(
            address = conversation?.address ?: request.address,
            name = request.name, nodeId = request.requesterNodeId, fingerprint = request.fingerprint
        ), approval = true, blocked = null)
        return ConnectionAdmission.ALLOW
    }

    @Transaction
    suspend fun insertPendingRequest(request: ConnectionRequestEntity): Boolean {
        if (getPendingConnectionRequest(request.requesterNodeId) != null) return false
        return insertConnectionRequest(request) != -1L
    }

    @Query("SELECT * FROM connection_requests WHERE id = :id LIMIT 1")
    suspend fun getConnectionRequest(id: Long): ConnectionRequestEntity?

    @Query("SELECT * FROM connection_requests WHERE requesterNodeId = :nodeId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestConnectionRequest(nodeId: String): ConnectionRequestEntity?

    @Transaction
    suspend fun decideConnectionRequest(id: Long, accepted: Boolean, allowNew: Boolean = true): Boolean {
        val request = getConnectionRequest(id) ?: return false
        if (request.timestamp <= System.currentTimeMillis() - APPROVAL_TIMEOUT_MS) {
            if (request.status == REQUEST_STATUS_PENDING) updateRequestStatus(id, REQUEST_STATUS_EXPIRED)
            return false
        }
        if (request.status != REQUEST_STATUS_PENDING || request.publicKeyHex.isNullOrBlank() ||
            getBlockedDevice(request.requesterNodeId) != null) return false
        val conversation = getConversationByNodeId(request.requesterNodeId)
        if (conversation?.isBlocked == true) return false
        if (accepted && !allowNew && conversation?.isApproved != true) {
            updateRequestStatus(id, REQUEST_STATUS_EXPIRED)
            return false
        }
        mergeConversation(ConversationEntity(
            address = conversation?.address ?: request.address,
            name = request.name, nodeId = request.requesterNodeId, fingerprint = request.fingerprint
        ), approval = accepted, blocked = null)
        updateRequestStatus(id, if (accepted) REQUEST_STATUS_ACCEPTED else REQUEST_STATUS_REJECTED)
        return true
    }

    @Transaction
    suspend fun blockIdentity(blocked: BlockedDeviceEntity) {
        insertBlockedDevice(blocked)
        updateConversationBlocked(blocked.nodeId, true)
        updateConversationApproval(blocked.nodeId, false)
        updateConversationPresence(blocked.nodeId, false, System.currentTimeMillis())
        setPeerBlocked(blocked.nodeId, true)
        updatePendingRequestsForNode(blocked.nodeId, REQUEST_STATUS_BLOCKED)
        outgoingApproval(blocked.nodeId)?.let { putOutgoingApproval(it.copy(status = REQUEST_STATUS_BLOCKED)) }
    }

    @Query("UPDATE peers SET isBlocked = :blocked, isApproved = 0, isOnline = 0 WHERE nodeId = :nodeId")
    suspend fun setPeerBlocked(nodeId: String, blocked: Boolean)

    @Query("UPDATE connection_requests SET status = 'EXPIRED' WHERE requesterNodeId = :nodeId")
    suspend fun retireRequestsForUnblockedIdentity(nodeId: String)

    @Query("DELETE FROM outgoing_approvals WHERE nodeId = :nodeId")
    suspend fun deleteOutgoingApproval(nodeId: String)

    @Transaction
    suspend fun unblockIdentity(nodeId: String) {
        if (getBlockedDevice(nodeId) == null) return
        deleteBlockedDevice(nodeId)
        updateConversationBlocked(nodeId, false)
        updateConversationApproval(nodeId, false)
        updateConversationPresence(nodeId, false, System.currentTimeMillis())
        setPeerBlocked(nodeId, false)
        retireRequestsForUnblockedIdentity(nodeId)
        deleteOutgoingApproval(nodeId)
    }

    @Query("""UPDATE outgoing_approvals SET status = 'EXPIRED' WHERE nodeId = :nodeId
        AND status IN ('BLOCKED', 'REJECTED')""")
    suspend fun reopenRemoteApprovalOnIncomingConnection(nodeId: String)

    @Query(
        """
        SELECT *
        FROM messages
        WHERE address = :address
        ORDER BY time ASC
        """
    )
    fun observeMessages(
        address: String
    ): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT *
        FROM messages
        WHERE messageId = :messageId
        LIMIT 1
        """
    )
    suspend fun getMessageById(
        messageId: String
    ): MessageEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM messages
            WHERE messageId = :messageId
        )
        """
    )
    suspend fun messageExists(
        messageId: String
    ): Boolean

    @Query(
        """
        UPDATE messages
        SET status = :status,
            deliveredAt = CASE WHEN :status IN ('DELIVERED', 'READ')
                THEN COALESCE(deliveredAt, :now) ELSE deliveredAt END,
            readAt = CASE WHEN :status = 'READ' THEN COALESCE(readAt, :now) ELSE readAt END,
            failedAt = CASE WHEN :status = 'FAILED' THEN COALESCE(failedAt, :now)
                WHEN :status IN ('DELIVERED', 'READ') THEN NULL ELSE failedAt END
        WHERE messageId = :messageId
          AND (
              (:status = 'SENT' AND status = 'SENDING') OR
              (:status = 'FAILED' AND status IN ('SENDING', 'SENT')) OR
              (:status = 'DELIVERED' AND status IN ('SENDING', 'SENT', 'FAILED')) OR
              (:status = 'READ' AND status IN ('SENDING', 'SENT', 'FAILED', 'DELIVERED'))
          )
        """
    )
    suspend fun updateMessageStatus(
        messageId: String,
        status: String,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        """
        SELECT COUNT(*)
        FROM messages
        """
    )
    fun observeMessageCount():
            Flow<Int>

    @Query(
        """
        SELECT SUM(LENGTH(text))
        FROM messages
        """
    )
    fun observeTotalBytes():
            Flow<Long?>

    /* ============================================================
       GLOBAL DATA
       ============================================================ */

    @Query(
        """
        DELETE FROM messages
        """
    )
    suspend fun clearAllMessages()

    @Query(
        """
        DELETE FROM conversations
        """
    )
    suspend fun clearAllConversations()

    @Transaction
    suspend fun clearAllData() {
        clearAllMessages()
        clearAllConversations()
    }

    /* ============================================================
       PEERS
       ============================================================ */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(
        peer: PeerEntity
    )

    @Query(
        """
        SELECT *
        FROM peers
        WHERE nodeId = :nodeId
        LIMIT 1
        """
    )
    suspend fun getPeerByNodeId(
        nodeId: String
    ): PeerEntity?

    @Query(
        """
        UPDATE peers
        SET
            isOnline = :isOnline,
            lastSeen = :lastSeen
        WHERE nodeId = :nodeId
        """
    )
    suspend fun updatePeerPresence(
        nodeId: String,
        isOnline: Boolean,
        lastSeen: Long
    )

    /* ============================================================
       CONNECTION REQUESTS
       ============================================================ */

    /**
     * Multiple pending requests from the same requester are
     * prevented by repository validation before insertion.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConnectionRequest(
        request: ConnectionRequestEntity
    ): Long

    @Query(
        """
        SELECT *
        FROM connection_requests
        WHERE status = 'PENDING'
        ORDER BY timestamp DESC
        """
    )
    fun observePendingRequests():
            Flow<List<ConnectionRequestEntity>>

    @Query(
        """
        SELECT *
        FROM connection_requests
        WHERE requesterNodeId = :requesterNodeId
        AND status = 'PENDING'
        ORDER BY timestamp DESC
        LIMIT 1
        """
    )
    suspend fun getPendingConnectionRequest(
        requesterNodeId: String
    ): ConnectionRequestEntity?

    @Query(
        """
        UPDATE connection_requests
        SET status = :status
        WHERE id = :id
        """
    )
    suspend fun updateRequestStatus(
        id: Long,
        status: String
    )

    @Query(
        """
        UPDATE connection_requests
        SET status = :status
        WHERE requesterNodeId = :requesterNodeId
        AND status = 'PENDING'
        """
    )
    suspend fun updatePendingRequestsForNode(
        requesterNodeId: String,
        status: String
    )

    /* ============================================================
       BLOCKED DEVICES
       ============================================================ */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedDevice(
        blocked: BlockedDeviceEntity
    )

    @Query(
        """
        DELETE FROM blocked_devices
        WHERE nodeId = :nodeId
        """
    )
    suspend fun deleteBlockedDevice(
        nodeId: String
    )

    @Query(
        """
        SELECT *
        FROM blocked_devices
        WHERE nodeId = :nodeId
        LIMIT 1
        """
    )
    suspend fun getBlockedDevice(
        nodeId: String
    ): BlockedDeviceEntity?

    @Query(
        """
        SELECT *
        FROM blocked_devices
        WHERE address = :address
        LIMIT 1
        """
    )
    suspend fun getBlockedDeviceByAddress(
        address: String
    ): BlockedDeviceEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM blocked_devices
            WHERE nodeId = :nodeId
               OR address = :address
        )
        """
    )
    suspend fun isDeviceBlocked(
        nodeId: String,
        address: String
    ): Boolean

    @Query(
        """
        SELECT *
        FROM blocked_devices
        """
    )
    fun observeBlockedDevices():
            Flow<List<BlockedDeviceEntity>>

    /* ============================================================
       ROUTES
       ============================================================ */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoute(
        route: RouteEntity
    )

    @Query(
        """
        SELECT *
        FROM routes
        WHERE destinationNodeId = :destinationNodeId
        LIMIT 1
        """
    )
    suspend fun getRoute(
        destinationNodeId: String
    ): RouteEntity?

    @Query(
        """
        DELETE FROM routes
        WHERE destinationNodeId = :destinationNodeId
        """
    )
    suspend fun deleteRoute(
        destinationNodeId: String
    )

    @Query(
        """
        DELETE FROM routes
        """
    )
    suspend fun clearRoutes()
}
