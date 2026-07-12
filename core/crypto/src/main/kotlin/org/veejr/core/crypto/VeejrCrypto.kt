package org.veejr.core.crypto

import com.iwebpp.crypto.TweetNaclFast
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class IdentityKeyPair(
    val publicKey: ByteArray,
    val secretKey: ByteArray,
)

data class SealedPayload(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
)

/**
 * Protocol-v1 cryptography compatible with the browser's TweetNaCl client.
 *
 * This class accepts and returns raw byte arrays only. Base64 and JSON belong
 * at serialization boundaries; passphrases and raw secret keys must never be
 * represented by network DTOs.
 */
class VeejrCrypto(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun generateIdentity(): IdentityKeyPair {
        val keyPair = TweetNaclFast.Box.keyPair()
        return IdentityKeyPair(
            publicKey = keyPair.publicKey.copyOf(),
            secretKey = keyPair.secretKey.copyOf(),
        )
    }

    fun publicKeyFromSecret(secretKey: ByteArray): ByteArray {
        requireLength("secret key", secretKey, CryptoBoundary.IDENTITY_KEY_BYTES)
        return TweetNaclFast.Box.keyPair_fromSecretKey(secretKey.copyOf()).publicKey.copyOf()
    }

    fun deriveWrappingKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = CryptoBoundary.PBKDF2_ITERATIONS,
    ): ByteArray {
        require(salt.size == CryptoBoundary.WRAP_SALT_BYTES) {
            "salt must be ${CryptoBoundary.WRAP_SALT_BYTES} bytes"
        }
        require(iterations > 0) { "iterations must be positive" }

        val spec = PBEKeySpec(
            passphrase,
            salt,
            iterations,
            CryptoBoundary.IDENTITY_KEY_BYTES * Byte.SIZE_BITS,
        )

        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun wrapSecretKey(secretKey: ByteArray, wrappingKey: ByteArray): SealedPayload {
        requireLength("secret key", secretKey, CryptoBoundary.IDENTITY_KEY_BYTES)
        return sealSecretBox(secretKey, wrappingKey)
    }

    fun unwrapSecretKey(
        ciphertext: ByteArray,
        nonce: ByteArray,
        wrappingKey: ByteArray,
    ): ByteArray? = openSecretBox(ciphertext, nonce, wrappingKey)

    fun sealBox(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray,
        senderSecretKey: ByteArray,
    ): SealedPayload = sealBoxWithNonce(
        plaintext = plaintext,
        nonce = randomNonce(),
        recipientPublicKey = recipientPublicKey,
        senderSecretKey = senderSecretKey,
    )

    fun openBox(
        ciphertext: ByteArray,
        nonce: ByteArray,
        senderPublicKey: ByteArray,
        recipientSecretKey: ByteArray,
    ): ByteArray? {
        requireLength("nonce", nonce, CryptoBoundary.NONCE_BYTES)
        requireLength("sender public key", senderPublicKey, CryptoBoundary.IDENTITY_KEY_BYTES)
        requireLength("recipient secret key", recipientSecretKey, CryptoBoundary.IDENTITY_KEY_BYTES)

        return TweetNaclFast.Box(senderPublicKey.copyOf(), recipientSecretKey.copyOf())
            .open(ciphertext, nonce)
    }

    fun sealSecretBox(plaintext: ByteArray, key: ByteArray): SealedPayload =
        sealSecretBoxWithNonce(plaintext, randomNonce(), key)

    fun openSecretBox(
        ciphertext: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray? {
        requireLength("nonce", nonce, CryptoBoundary.NONCE_BYTES)
        requireLength("secretbox key", key, CryptoBoundary.SECRETBOX_KEY_BYTES)
        return TweetNaclFast.SecretBox(key.copyOf()).open(ciphertext, nonce)
    }

    internal fun sealBoxWithNonce(
        plaintext: ByteArray,
        nonce: ByteArray,
        recipientPublicKey: ByteArray,
        senderSecretKey: ByteArray,
    ): SealedPayload {
        requireLength("nonce", nonce, CryptoBoundary.NONCE_BYTES)
        requireLength("recipient public key", recipientPublicKey, CryptoBoundary.IDENTITY_KEY_BYTES)
        requireLength("sender secret key", senderSecretKey, CryptoBoundary.IDENTITY_KEY_BYTES)

        val ciphertext = TweetNaclFast.Box(recipientPublicKey.copyOf(), senderSecretKey.copyOf())
            .box(plaintext, nonce)
        return SealedPayload(ciphertext = checkNotNull(ciphertext), nonce = nonce.copyOf())
    }

    internal fun sealSecretBoxWithNonce(
        plaintext: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): SealedPayload {
        requireLength("nonce", nonce, CryptoBoundary.NONCE_BYTES)
        requireLength("secretbox key", key, CryptoBoundary.SECRETBOX_KEY_BYTES)

        val ciphertext = TweetNaclFast.SecretBox(key.copyOf()).box(plaintext, nonce)
        return SealedPayload(ciphertext = checkNotNull(ciphertext), nonce = nonce.copyOf())
    }

    private fun randomNonce(): ByteArray = ByteArray(CryptoBoundary.NONCE_BYTES).also {
        secureRandom.nextBytes(it)
    }

    private fun requireLength(name: String, value: ByteArray, expected: Int) {
        require(value.size == expected) { "$name must be $expected bytes" }
    }
}
