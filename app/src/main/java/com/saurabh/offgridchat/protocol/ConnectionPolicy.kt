package com.saurabh.offgridchat.protocol

/** One snapshot of the user's admission settings, read before processing a packet. */
data class ConnectionPolicy(val requireApproval: Boolean, val allowNewConnections: Boolean)

enum class ConnectionAdmission { ALLOW, REJECT, NEEDS_APPROVAL }
