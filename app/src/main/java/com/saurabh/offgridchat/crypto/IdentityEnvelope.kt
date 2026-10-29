package com.saurabh.offgridchat.crypto

import java.util.Base64

/** Versioned storage codec, including recovery of the original missing-separator writer. */
internal object IdentityEnvelope {
    private const val PREFIX = "enc:v1"

    data class Contents(val iv: ByteArray, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Contents

            if (!iv.contentEquals(other.iv)) return false
            if (!ciphertext.contentEquals(other.ciphertext)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = iv.contentHashCode()
            result = 31 * result + ciphertext.contentHashCode()
            return result
        }
    }

    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        require(iv.size == 12) { "Invalid private-key IV" }
        require(ciphertext.size >= 16) { "Invalid wrapped private key" }
        val encoder = Base64.getEncoder()
        return "$PREFIX:${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"
    }

    fun decode(value: String): Contents {
        require(value.startsWith(PREFIX)) { "Unsupported private-key format" }
        // The original writer emitted enc:v1<iv>:<ciphertext>. Both forms preserve
        // the same authenticated ciphertext; only normalize after successful decryption.
        val parts = value.removePrefix(PREFIX).removePrefix(":").split(':')
        require(parts.size == 2) { "Invalid encrypted private-key format" }
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])
        require(iv.size == 12) { "Invalid private-key IV" }
        require(ciphertext.size >= 16) { "Invalid wrapped private key" }
        return Contents(iv, ciphertext)
    }

    fun normalize(value: String): String = decode(value).let { encode(it.iv, it.ciphertext) }
}
