package com.saurabh.offgridchat.tools

/** Transport state is independent of optional online presence and connection approval. */
enum class PeerReachability {
    UNREACHABLE, AUTHENTICATING, DIRECT, MESH;

    val reachable: Boolean get() = this == DIRECT || this == MESH
}

data class PeerSocketState(
    val address: String,
    val nodeId: String?,
    val authenticated: Boolean,
    val connectedAt: Long,
)
