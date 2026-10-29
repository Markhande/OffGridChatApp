package com.saurabh.offgridchat.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.saurabh.offgridchat.datastore.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import java.security.Signature
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceIdentity(
    val nodeId: String,
    val publicKeyHex: String,
    val privateKeyHex: String,
    val fingerprintShort: String,
    val fingerprintFull: String,
    val privateKeyAlias: String =
        IdentityManager.KEYSTORE_WRAP_KEY_ALIAS
)

@Singleton
class IdentityManager @Inject constructor(
    private val prefs: Preferences
) {

    @Volatile
    private var cachedIdentity: DeviceIdentity? =
        null

    @Volatile
    private var cachedKeyPair: KeyPair? =
        null

    @Synchronized
    fun getIdentity(): DeviceIdentity {

        cachedIdentity?.let {
            return it
        }

        val storedNodeId =
            runBlocking {
                prefs.nodeIdFlow.first()
            }

        val storedPublicKey =
            runBlocking {
                prefs.publicKeyFlow.first()
            }

        val storedPrivateValue =
            runBlocking {
                prefs.privateKeyFlow.first()
            }

        check(storedNodeId == null || (storedPublicKey != null && storedPrivateValue != null)) {
            "Stored identity is incomplete; refusing to replace it"
        }

        val keyPair =
            loadOrCreateIdentityKeyPair(
                storedPublicKey =
                    storedPublicKey,

                storedPrivateValue =
                    storedPrivateValue
            )

        val publicKeyBase64 =
            Base64.getEncoder()
                .encodeToString(
                    keyPair.public.encoded
                )

        /*
         * The node identity is derived ONLY from the public key.
         *
         * Therefore, the same public key always produces the same
         * node ID and fingerprint.
         */
        val fingerprintFull =
            computeFingerprintFull(
                publicKeyBase64
            )

        val fingerprintShort =
            computeFingerprintShort(
                publicKeyBase64
            )

        val expectedNodeId =
            buildNodeId(
                fingerprintFull
            )

        /*
         * The private key itself is never stored in plaintext.
         *
         * This string contains:
         *
         * enc:v1:<ivBase64>:<ciphertextBase64>
         *
         * The AES wrapping key remains inside Android Keystore.
         */
        val encryptedPrivateKey =
            if (storedPrivateValue != null) IdentityEnvelope.normalize(storedPrivateValue)
            else encryptPrivateKey(keyPair.private)

        val identity =
            DeviceIdentity(

                nodeId =
                    expectedNodeId,

                publicKeyHex =
                    publicKeyBase64,

                privateKeyHex =
                    encryptedPrivateKey,

                fingerprintShort =
                    fingerprintShort,

                fingerprintFull =
                    fingerprintFull,

                privateKeyAlias =
                    KEYSTORE_WRAP_KEY_ALIAS
            )

        /*
         * Persist when:
         *
         * - no identity existed
         * - identity format changed
         * - old legacy keystore reference existed
         * - public key changed
         * - node ID changed
         *
         * The encrypted private-key value is generated once and
         * kept stable in DataStore.
         */
        val needsPersistence =
            storedNodeId != expectedNodeId ||
                    storedPublicKey != publicKeyBase64 ||
                    storedPrivateValue != encryptedPrivateKey

        if (needsPersistence) {

            runBlocking {

                prefs.saveIdentity(
                    nodeId =
                        expectedNodeId,

                    publicKey =
                        publicKeyBase64,

                    privateKey =
                        encryptedPrivateKey
                )
            }
        }

        cachedKeyPair = keyPair
        cachedIdentity =
            identity

        return identity
    }

    /**
     * Returns the actual EC identity key pair.
     *
     * The same key pair is used for:
     *
     * - ECDH
     * - ECDSA signing
     *
     * This is intentionally a software EC key pair whose private
     * material is encrypted at rest using an Android Keystore
     * AES wrapping key.
     */
    @Synchronized
    fun getIdentityKeyPair(): KeyPair {

        cachedKeyPair?.let {
            return it
        }

        getIdentity()

        return cachedKeyPair
            ?: throw IllegalStateException(
                "Identity key pair was not initialized"
            )
    }

    fun getIdentityPrivateKey():
            PrivateKey {

        return getIdentityKeyPair()
            .private
    }

    fun getIdentityPublicKey():
            PublicKey {

        return getIdentityKeyPair()
            .public
    }

    /**
     * Decode a peer public key received over the protocol.
     */
    fun decodePublicKey(
        pubKeyBase64: String
    ): PublicKey {

        require(
            pubKeyBase64.isNotBlank()
        ) {
            "Public key must not be empty"
        }

        val bytes =
            try {

                Base64.getDecoder()
                    .decode(
                        pubKeyBase64
                    )

            } catch (
                e: IllegalArgumentException
            ) {

                throw IllegalArgumentException(
                    "Invalid Base64 public key",
                    e
                )
            }

        require(
            bytes.isNotEmpty()
        ) {
            "Public key must not be empty"
        }

        return KeyFactory
            .getInstance("EC")
            .generatePublic(
                X509EncodedKeySpec(
                    bytes
                )
            )
    }

    /**
     * Compatibility helper.
     *
     * New code should use getIdentityPrivateKey().
     *
     * Raw plaintext PKCS#8 private keys are deliberately rejected.
     */
//    fun decodePrivateKey(
//        privateKeyReference: String
//    ): PrivateKey {
//
//        require(
//            privateKeyReference.isNotBlank()
//        ) {
//            "Private key reference must not be empty"
//        }
//
//        if (
//            privateKeyReference ==
//            cachedIdentity?.privateKeyHex
//        ) {
//            return getIdentityPrivateKey()
//        }
//
//        if (
//            isEncryptedPrivateKey(
//                privateKeyReference
//            )
//        ) {
//
//            return decryptPrivateKey(
//                privateKeyReference
//            )
//        }
//
//        throw IllegalArgumentException(
//            "Raw private-key material is not supported"
//        )
//    }

    /* ============================================================
       IDENTITY KEYPAIR
       ============================================================ */

    private fun loadOrCreateIdentityKeyPair(
        storedPublicKey: String?,
        storedPrivateValue: String?
    ): KeyPair {

        /*
         * Existing encrypted identity.
         *
         * Restore the exact same key pair instead of generating
         * a new identity on every application restart.
         */
        if (
            storedPrivateValue != null
        ) {

            try {

                val privateKey =
                    decryptPrivateKey(
                        storedPrivateValue
                    )

                val publicKeyBase64 =
                    storedPublicKey
                        ?.trim()
                        ?.takeIf {
                            it.isNotEmpty()
                        }
                        ?: throw IllegalStateException(
                            "Stored identity public key is missing"
                        )

                val publicKey =
                    decodePublicKey(
                        publicKeyBase64
                    )

                /*
                 * Verify that the persisted public key actually
                 * belongs to the decrypted private key.
                 */
                val testData =
                    "OffGridChat-Identity-KeyCheck-v1"
                        .toByteArray(
                            Charsets.UTF_8
                        )

                val signer =
                    Signature.getInstance(
                        ECDSA_SIGNATURE_ALGORITHM
                    )

                signer.initSign(
                    privateKey
                )

                signer.update(
                    testData
                )

                val signature =
                    signer.sign()

                val verifier =
                    Signature.getInstance(
                        ECDSA_SIGNATURE_ALGORITHM
                    )

                verifier.initVerify(
                    publicKey
                )

                verifier.update(
                    testData
                )

                check(
                    verifier.verify(
                        signature
                    )
                ) {
                    "Stored identity public/private key mismatch"
                }

                val pair =
                    KeyPair(
                        publicKey,
                        privateKey
                    )

                return pair

            } catch (e: Exception) {
                throw IllegalStateException(
                    "Cannot restore stored identity; existing keys were preserved", e
                )
            }
        }

        // Existing partial or legacy identities require recovery, never automatic replacement.
        check(storedPublicKey == null) {
            "Stored private key is missing; refusing to replace identity"
        }
        check(!loadKeyStore().containsAlias(LEGACY_IDENTITY_KEY_ALIAS)) {
            "Legacy identity requires explicit migration; existing key was preserved"
        }

        val newPair =
            generateSoftwareIdentityKeyPair()

        return newPair
    }

    private fun generateSoftwareIdentityKeyPair(): KeyPair {

        val generator =
            KeyPairGenerator.getInstance(
                "EC"
            )

        generator.initialize(
            ECGenParameterSpec(
                EC_CURVE
            ),
            SecureRandom()
        )

        return generator.generateKeyPair()
    }

    /* ============================================================
       PRIVATE KEY ENCRYPTION
       ============================================================ */

    private fun encryptPrivateKey(
        privateKey: PrivateKey
    ): String {

        val encoded =
            privateKey.encoded
                ?: throw IllegalStateException(
                    "Identity private key is not encodable"
                )

        val secretKey =
            getOrCreateWrappingKey()

        val cipher =
            Cipher.getInstance(
                GCM_TRANSFORMATION
            )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey
        )

        val iv =
            cipher.iv
                ?: throw IllegalStateException(
                    "Cipher IV was not generated by KeyStore"
                )

        cipher.updateAAD(
            PRIVATE_KEY_AAD
        )

        val ciphertext =
            cipher.doFinal(
                encoded
            )

        return IdentityEnvelope.encode(iv, ciphertext)
    }

    private fun decryptPrivateKey(
        encryptedValue: String
    ): PrivateKey {

        val envelope = IdentityEnvelope.decode(encryptedValue)
        val iv = envelope.iv
        val ciphertext = envelope.ciphertext
        val secretKey = loadKeyStore().getKey(KEYSTORE_WRAP_KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Identity wrapping key is missing")

        val cipher =
            Cipher.getInstance(
                GCM_TRANSFORMATION
            )

        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(
                GCM_TAG_BITS,
                iv
            )
        )

        cipher.updateAAD(
            PRIVATE_KEY_AAD
        )

        val pkcs8 =
            cipher.doFinal(
                ciphertext
            )

        return KeyFactory
            .getInstance("EC")
            .generatePrivate(
                PKCS8EncodedKeySpec(
                    pkcs8
                )
            )
    }

    private fun getOrCreateWrappingKey():
            SecretKey {

        val keyStore =
            loadKeyStore()

        val existing =
            keyStore.getKey(
                KEYSTORE_WRAP_KEY_ALIAS,
                null
            ) as? SecretKey

        if (
            existing != null
        ) {
            return existing
        }

        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE_PROVIDER
            )

        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_WRAP_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(
                    AES_KEY_SIZE_BITS
                )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(
                    true
                )
                .build()
        )

        return keyGenerator.generateKey()
    }

    /* ============================================================
       KEY CONVERSION
       ============================================================ */

//    private fun derivePublicKeyFromPrivate(
//        privateKey: PrivateKey
//    ): PublicKey {
//
//        /*
//         * A PKCS#8 EC private key doesn't directly expose a
//         * portable public key through Java's standard interface.
//         *
//         * Therefore, the public key is read from persisted identity
//         * and separately validated before constructing the pair.
//         *
//         * This function is intentionally NOT used for restoration.
//         */
//        throw UnsupportedOperationException(
//            "Public key must be restored from the persisted identity"
//        )
//    }

    /* ============================================================
       KEYSTORE
       ============================================================ */

    private fun loadKeyStore():
            KeyStore {

        return KeyStore.getInstance(
            ANDROID_KEYSTORE_PROVIDER
        ).apply {

            load(null)
        }
    }

    private fun deleteLegacyIdentityAlias() {

        try {

            val keyStore =
                loadKeyStore()

            if (
                keyStore.containsAlias(
                    LEGACY_IDENTITY_KEY_ALIAS
                )
            ) {

                keyStore.deleteEntry(
                    LEGACY_IDENTITY_KEY_ALIAS
                )
            }

        } catch (_: Exception) {
            // Best effort cleanup only.
        }

        /*
         * The cache must be invalidated whenever the identity is
         * regenerated.
         */
        cachedIdentity =
            null

        cachedKeyPair =
            null
    }

    /* ============================================================
       VALIDATION
       ============================================================ */

    private fun isEncryptedPrivateKey(
        value: String?
    ): Boolean {

        return !value.isNullOrBlank() &&
                value.startsWith(
                    "$ENCRYPTED_PRIVATE_PREFIX:"
                )
    }

    /* ============================================================
       FINGERPRINT
       ============================================================ */

    companion object {

        /*
         * Android Keystore key used ONLY to encrypt/wrap the
         * software EC private identity key.
         */
        const val KEYSTORE_WRAP_KEY_ALIAS =
            "offgridchat_identity_wrap_v1"

        private const val ECDSA_SIGNATURE_ALGORITHM =
            "SHA256withECDSA"

        /*
         * Alias from the old implementation.
         *
         * It used PURPOSE_AGREE_KEY and is no longer used.
         */
        private const val LEGACY_IDENTITY_KEY_ALIAS =
            "offgridchat_device_identity_v1"

        private const val ANDROID_KEYSTORE_PROVIDER =
            "AndroidKeyStore"

        private const val EC_CURVE =
            "secp256r1"

        private const val NODE_ID_PREFIX =
            "node_"

        private const val NODE_ID_HEX_LENGTH =
            32

        private const val ENCRYPTED_PRIVATE_PREFIX =
            "enc:v1"

        private const val AES_KEY_SIZE_BITS =
            256

        private const val GCM_TAG_BITS =
            128

        private const val GCM_IV_BYTES =
            12

        private const val GCM_TRANSFORMATION =
            "AES/GCM/NoPadding"

        private val PRIVATE_KEY_AAD =
            "OffGridChat-Identity-PrivateKey-v1"
                .toByteArray(
                    Charsets.UTF_8
                )

        fun computeFingerprintFull(
            pubKeyBase64: String
        ): String {

            val bytes =
                try {

                    Base64.getDecoder()
                        .decode(
                            pubKeyBase64
                        )

                } catch (
                    e: IllegalArgumentException
                ) {

                    throw IllegalArgumentException(
                        "Invalid Base64 public key",
                        e
                    )
                }

            require(
                bytes.isNotEmpty()
            ) {
                "Public key must not be empty"
            }

            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        bytes
                    )

            return digest.joinToString(":") {
                "%02X".format(it)
            }
        }

        fun computeFingerprintShort(
            pubKeyBase64: String
        ): String {

            val full =
                computeFingerprintFull(
                    pubKeyBase64
                )
                    .replace(
                        ":",
                        ""
                    )

            require(
                full.length >= 8
            ) {
                "Fingerprint is unexpectedly short"
            }

            val firstEight =
                full.take(8)

            return "${firstEight.substring(0, 4)}-" +
                    firstEight.substring(4, 8)
        }

        private fun buildNodeId(
            fingerprintFull: String
        ): String {

            val normalized =
                fingerprintFull
                    .replace(
                        ":",
                        ""
                    )
                    .lowercase()

            return NODE_ID_PREFIX +
                    normalized.take(
                        NODE_ID_HEX_LENGTH
                    )
        }
    }
}
