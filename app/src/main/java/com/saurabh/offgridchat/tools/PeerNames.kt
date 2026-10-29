package com.saurabh.offgridchat.tools

/** "You" is a local UI label, never a remote identity. */
object PeerNames {
    fun resolve(fallback: String, vararg candidates: String?): String =
        candidates.asSequence().mapNotNull { it?.trim() }.firstOrNull {
            it.isNotEmpty() && !it.equals("You", ignoreCase = true) &&
                    !it.equals("Unknown", ignoreCase = true) && it != fallback
        } ?: fallback
}
