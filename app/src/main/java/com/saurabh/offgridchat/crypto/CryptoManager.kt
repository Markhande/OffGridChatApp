package com.saurabh.offgridchat.crypto

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private const val AES_ALGORITHM = "AES"

private const val GCM_TRANSFORMATION =
    "AES/GCM/NoPadding"

private const val HMAC_SHA256 =
    "HmacSHA256"

private const val SHA256_ALGORITHM =
    "SHA-256"

private const val SHA256_HASH_SIZE_BYTES =
    32

private const val AES_KEY_SIZE_BYTES =
    32

private const val GCM_IV_SIZE =
    12

private const val GCM_TAG_SIZE_BITS =
    128

private const val GCM_TAG_SIZE_BYTES =
    GCM_TAG_SIZE_BITS / 8

private const val MAX_PLAINTEXT_BYTES =
    1024 * 1024

private const val MAX_CIPHERTEXT_BYTES =
    MAX_PLAINTEXT_BYTES + GCM_TAG_SIZE_BYTES

private val HKDF_SALT_PREFIX =
    "OffGridChat-HKDF-SALT-v1"
        .toByteArray(Charsets.UTF_8)

private val HKDF_INFO_PREFIX =
    "OffGridChat-ECDH-AES256GCM-v1"
        .toByteArray(Charsets.UTF_8)


data class EncryptedResult(
    val ivHex: String,
    val ciphertextHex: String,
    val v: Int = CURRENT_VERSION
) {

    fun toJson(): String {

        val json = JSONObject()

        json.put(
            "iv",
            ivHex
        )

        json.put(
            "ct",
            ciphertextHex
        )

        json.put(
            "v",
            v
        )

        return json.toString()
    }

    companion object {

        const val CURRENT_VERSION = 1

        fun fromJson(
            jsonStr: String
        ): EncryptedResult? {

            return try {

                val json =
                    JSONObject(jsonStr)

                val iv =
                    json.getString("iv")

                val ciphertext =
                    json.getString("ct")

                val version =
                    json.optInt(
                        "v",
                        CURRENT_VERSION
                    )

                if (
                    version !=
                    CURRENT_VERSION
                ) {
                    return null
                }

                decodeAndValidateIv(iv)

                decodeAndValidateCiphertext(
                    ciphertext
                )

                EncryptedResult(
                    ivHex = iv,
                    ciphertextHex = ciphertext,
                    v = version
                )

            } catch (_: Exception) {

                null
            }
        }

        private fun decodeAndValidateIv(
            value: String
        ): ByteArray {

            val bytes =
                Base64.getDecoder()
                    .decode(value)

            require(
                bytes.size ==
                        GCM_IV_SIZE
            ) {
                "Invalid AES-GCM IV length"
            }

            return bytes
        }

        private fun decodeAndValidateCiphertext(
            value: String
        ): ByteArray {

            val bytes =
                Base64.getDecoder()
                    .decode(value)

            require(
                bytes.size >=
                        GCM_TAG_SIZE_BYTES
            ) {
                "Invalid AES-GCM ciphertext"
            }

            require(
                bytes.size <=
                        MAX_CIPHERTEXT_BYTES
            ) {
                "Ciphertext is too large"
            }

            return bytes
        }
    }
}


@Singleton
class CryptoManager @Inject constructor() {

    private val secureRandom =
        SecureRandom()

    fun deriveSessionKey(
        ourPrivateKey: PrivateKey,
        ourPublicKey: PublicKey,
        peerPublicKey: PublicKey
    ): SecretKey {

        require(
            ourPrivateKey.algorithm.equals(
                "EC",
                ignoreCase = true
            )
        ) {
            "Our private key must be EC"
        }

        require(
            ourPublicKey.algorithm.equals(
                "EC",
                ignoreCase = true
            )
        ) {
            "Our public key must be EC"
        }

        require(
            peerPublicKey.algorithm.equals(
                "EC",
                ignoreCase = true
            )
        ) {
            "Peer public key must be EC"
        }

        val sharedSecret =
            performEcdh(
                ourPrivateKey,
                peerPublicKey
            )

        val transcript =
            buildKeyTranscript(
                ourPublicKey,
                peerPublicKey
            )

        val salt =
            sha256(
                concat(
                    HKDF_SALT_PREFIX,
                    transcript
                )
            )

        val info =
            concat(
                HKDF_INFO_PREFIX,
                transcript
            )

        val derivedKey =
            hkdfSha256(
                ikm = sharedSecret,
                salt = salt,
                info = info
            )

        return SecretKeySpec(
            derivedKey,
            AES_ALGORITHM
        )
    }

    fun encryptPayload(
        plaintext: String,
        sessionKey: SecretKey,
        aadData: String? = null
    ): EncryptedResult {

        validateAesKey(
            sessionKey
        )

        require(
            plaintext.isNotEmpty()
        ) {
            "Plaintext must not be empty"
        }

        val plaintextBytes =
            plaintext.toByteArray(
                Charsets.UTF_8
            )

        require(
            plaintextBytes.size <=
                    MAX_PLAINTEXT_BYTES
        ) {
            "Plaintext is too large"
        }

        val iv =
            ByteArray(
                GCM_IV_SIZE
            )

        secureRandom.nextBytes(iv)

        val cipher =
            Cipher.getInstance(
                GCM_TRANSFORMATION
            )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            sessionKey,
            GCMParameterSpec(
                GCM_TAG_SIZE_BITS,
                iv
            )
        )

        aadData?.let { aad ->

            cipher.updateAAD(
                aad.toByteArray(
                    Charsets.UTF_8
                )
            )
        }

        val ciphertext =
            cipher.doFinal(
                plaintextBytes
            )

        return EncryptedResult(
            ivHex =
                Base64.getEncoder()
                    .encodeToString(iv),

            ciphertextHex =
                Base64.getEncoder()
                    .encodeToString(
                        ciphertext
                    ),

            v =
                EncryptedResult.CURRENT_VERSION
        )
    }

    fun decryptPayload(
        encryptedResult: EncryptedResult,
        sessionKey: SecretKey,
        aadData: String? = null
    ): String {

        validateAesKey(
            sessionKey
        )

        require(
            encryptedResult.v ==
                    EncryptedResult.CURRENT_VERSION
        ) {
            "Unsupported encrypted payload version: " +
                    encryptedResult.v
        }

        val iv =
            Base64.getDecoder()
                .decode(
                    encryptedResult.ivHex
                )

        require(
            iv.size ==
                    GCM_IV_SIZE
        ) {
            "Invalid AES-GCM IV"
        }

        val ciphertext =
            Base64.getDecoder()
                .decode(
                    encryptedResult.ciphertextHex
                )

        require(
            ciphertext.size >=
                    GCM_TAG_SIZE_BYTES
        ) {
            "Invalid AES-GCM ciphertext"
        }

        require(
            ciphertext.size <=
                    MAX_CIPHERTEXT_BYTES
        ) {
            "Ciphertext is too large"
        }

        val cipher =
            Cipher.getInstance(
                GCM_TRANSFORMATION
            )

        cipher.init(
            Cipher.DECRYPT_MODE,
            sessionKey,
            GCMParameterSpec(
                GCM_TAG_SIZE_BITS,
                iv
            )
        )

        aadData?.let { aad ->

            cipher.updateAAD(
                aad.toByteArray(
                    Charsets.UTF_8
                )
            )
        }

        val plaintextBytes =
            cipher.doFinal(
                ciphertext
            )

        return String(
            plaintextBytes,
            Charsets.UTF_8
        )
    }

    private fun performEcdh(
        ourPrivateKey: PrivateKey,
        peerPublicKey: PublicKey
    ): ByteArray {

        val keyAgreement =
            KeyAgreement.getInstance(
                "ECDH"
            )

        keyAgreement.init(
            ourPrivateKey
        )

        keyAgreement.doPhase(
            peerPublicKey,
            true
        )

        return keyAgreement.generateSecret()
    }

    private fun buildKeyTranscript(
        firstPublicKey: PublicKey,
        secondPublicKey: PublicKey
    ): ByteArray {

        val first =
            firstPublicKey.encoded
                ?: throw IllegalArgumentException(
                    "First public key is not encodable"
                )

        val second =
            secondPublicKey.encoded
                ?: throw IllegalArgumentException(
                    "Second public key is not encodable"
                )

        val ordered =
            if (
                compareBytes(first, second) <= 0
            ) {
                first to second
            } else {
                second to first
            }

        val buffer =
            ByteBuffer.allocate(
                4 + ordered.first.size +
                        4 + ordered.second.size
            )

        buffer.putInt(
            ordered.first.size
        )

        buffer.put(
            ordered.first
        )

        buffer.putInt(
            ordered.second.size
        )

        buffer.put(
            ordered.second
        )

        return buffer.array()
    }

    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray
    ): ByteArray {

        val outputLength =
            AES_KEY_SIZE_BYTES

        val normalizedSalt =
            if (salt.isNotEmpty()) {
                salt
            } else {
                ByteArray(
                    SHA256_HASH_SIZE_BYTES
                )
            }

        val extractMac =
            Mac.getInstance(
                HMAC_SHA256
            )

        extractMac.init(
            SecretKeySpec(
                normalizedSalt,
                HMAC_SHA256
            )
        )

        val pseudorandomKey =
            extractMac.doFinal(
                ikm
            )

        val expandMac =
            Mac.getInstance(
                HMAC_SHA256
            )

        expandMac.init(
            SecretKeySpec(
                pseudorandomKey,
                HMAC_SHA256
            )
        )

        val output =
            ByteArray(
                outputLength
            )

        var previousBlock =
            ByteArray(0)

        var outputOffset =
            0

        var counter =
            1

        while (
            outputOffset <
            outputLength
        ) {

            expandMac.reset()

            expandMac.update(
                previousBlock
            )

            expandMac.update(
                info
            )

            expandMac.update(
                counter.toByte()
            )

            previousBlock =
                expandMac.doFinal()

            val copyLength =
                minOf(
                    previousBlock.size,
                    outputLength -
                            outputOffset
                )

            System.arraycopy(
                previousBlock,
                0,
                output,
                outputOffset,
                copyLength
            )

            outputOffset +=
                copyLength

            counter++
        }

        return output
    }

    private fun sha256(
        input: ByteArray
    ): ByteArray {

        return java.security.MessageDigest
            .getInstance(
                SHA256_ALGORITHM
            )
            .digest(input)
    }

    private fun compareBytes(
        left: ByteArray,
        right: ByteArray
    ): Int {

        val length =
            minOf(
                left.size,
                right.size
            )

        for (
        index in 0 until length
        ) {

            val a =
                left[index]
                    .toInt() and 0xFF

            val b =
                right[index]
                    .toInt() and 0xFF

            if (a != b) {
                return a.compareTo(b)
            }
        }

        return left.size.compareTo(
            right.size
        )
    }

    private fun concat(
        first: ByteArray,
        second: ByteArray
    ): ByteArray {

        return ByteArray(
            first.size + second.size
        ).also { output ->

            System.arraycopy(
                first,
                0,
                output,
                0,
                first.size
            )

            System.arraycopy(
                second,
                0,
                output,
                first.size,
                second.size
            )
        }
    }

    private fun validateAesKey(
        key: SecretKey
    ) {

        require(
            key.algorithm.equals(
                AES_ALGORITHM,
                ignoreCase = true
            )
        ) {
            "Session key must use AES"
        }

        val encoded =
            key.encoded
                ?: throw IllegalArgumentException(
                    "Session key has no encoded key material"
                )

        require(
            encoded.size ==
                    AES_KEY_SIZE_BYTES
        ) {
            "AES-256 key required"
        }
    }
}