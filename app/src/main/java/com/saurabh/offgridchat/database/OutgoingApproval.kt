package com.saurabh.offgridchat.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Remote consent to our sends. Never grants local consent to receive. */
@Entity(tableName = "outgoing_approvals")
data class OutgoingApproval(
    @PrimaryKey val nodeId: String,
    val publicKeyHex: String,
    val requestId: String,
    val timestamp: Long,
    val status: String = REQUEST_STATUS_PENDING
)

const val APPROVAL_TIMEOUT_MS = 300_000L
