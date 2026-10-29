package com.saurabh.offgridchat.repository

import com.saurabh.offgridchat.database.*
import com.saurabh.offgridchat.protocol.ConnectionPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Durable, directional approval. Transport calls supply already-authenticated peer identities. */
@Singleton
class ConnectionApproval @Inject constructor(private val dao: ChatDao) {
    fun observe(nodeId: String) = dao.observeOutgoingApproval(nodeId)
    suspend fun receive(request: ConnectionRequestEntity, policy: ConnectionPolicy): String =
        dao.receiveApprovalRequest(request, policy, System.currentTimeMillis())

    suspend fun response(nodeId: String, key: String, requestId: String, status: String): Boolean =
        dao.applyApprovalResponse(nodeId, key, requestId, status, System.currentTimeMillis())

    suspend fun decide(id: Long, accepted: Boolean, allowNew: Boolean = true): ConnectionRequestEntity? {
        if (!dao.decideConnectionRequest(id, accepted, allowNew)) return null
        return dao.getConnectionRequest(id)
    }

    suspend fun latest(nodeId: String) = dao.getLatestConnectionRequest(nodeId)

    suspend fun expire() {
        val cutoff = System.currentTimeMillis() - APPROVAL_TIMEOUT_MS
        dao.expireIncomingApprovals(cutoff)
        dao.expireOutgoingApprovals(cutoff)
    }

    suspend fun awaitRemoteApproval(nodeId: String, key: String, transmit: suspend (OutgoingApproval) -> Boolean): Boolean {
        val initial = dao.beginOutgoingApproval(OutgoingApproval(nodeId, key, UUID.randomUUID().toString(),
            System.currentTimeMillis()), System.currentTimeMillis())
        while (true) {
            expire()
            val current = dao.outgoingApproval(nodeId) ?: return false
            if (current.requestId != initial.requestId) return false
            if (current.status != REQUEST_STATUS_PENDING) return current.status == REQUEST_STATUS_ACCEPTED
            // Reuse the correlation ID; each transport attempt gets a fresh packet ID for relays.
            if (!transmit(current)) return false
            withTimeoutOrNull(2_000) {
                observe(nodeId).first { it?.requestId != initial.requestId || it.status != REQUEST_STATUS_PENDING }
            }
        }
    }
}
