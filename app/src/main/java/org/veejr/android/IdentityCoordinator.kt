package org.veejr.android

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.veejr.core.crypto.CryptoBoundary
import org.veejr.core.crypto.VeejrCrypto
import org.veejr.core.network.KeySetupRequest
import org.veejr.core.network.WrappedKey
import org.veejr.core.network.WrappedKeyKdf

data class PreparedIdentity(
    val request: KeySetupRequest,
    val secretKey: ByteArray,
)

class IdentityCoordinator(
    private val crypto: VeejrCrypto = VeejrCrypto(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun prepare(passphrase: CharArray): PreparedIdentity {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Use at least $MIN_PASSPHRASE_LENGTH characters."
        }
        val identity = crypto.generateIdentity()
        val salt = ByteArray(CryptoBoundary.WRAP_SALT_BYTES).also(secureRandom::nextBytes)
        val wrappingKey = crypto.deriveWrappingKey(passphrase, salt)
        return try {
            val sealed = crypto.wrapSecretKey(identity.secretKey, wrappingKey)
            PreparedIdentity(
                request = KeySetupRequest(
                    publicKey = identity.publicKey.base64(),
                    wrappedKey = WrappedKey(
                        ciphertext = sealed.ciphertext.base64(),
                        salt = salt.base64(),
                        nonce = sealed.nonce.base64(),
                        kdf = WrappedKeyKdf("PBKDF2-SHA256", CryptoBoundary.PBKDF2_ITERATIONS),
                        wrap = "XSalsa20-Poly1305",
                    ),
                ),
                secretKey = identity.secretKey,
            )
        } catch (error: Exception) {
            identity.secretKey.fill(0)
            throw error
        } finally {
            wrappingKey.fill(0)
        }
    }

    fun unlock(publicKey: String, wrappedKey: WrappedKey, passphrase: CharArray): ByteArray? {
        if (
            wrappedKey.kdf.name != "PBKDF2-SHA256" ||
            wrappedKey.kdf.iterations != CryptoBoundary.PBKDF2_ITERATIONS ||
            wrappedKey.wrap != "XSalsa20-Poly1305"
        ) return null

        return runCatching {
            val salt = wrappedKey.salt.base64Bytes(CryptoBoundary.WRAP_SALT_BYTES)
            val nonce = wrappedKey.nonce.base64Bytes(CryptoBoundary.NONCE_BYTES)
            val ciphertext = wrappedKey.ciphertext.base64Bytes(CIPHERTEXT_BYTES)
            val expectedPublic = publicKey.base64Bytes(CryptoBoundary.IDENTITY_KEY_BYTES)
            val wrappingKey = crypto.deriveWrappingKey(passphrase, salt)
            try {
                val secret = crypto.unwrapSecretKey(ciphertext, nonce, wrappingKey)
                    ?: return@runCatching null
                if (MessageDigest.isEqual(crypto.publicKeyFromSecret(secret), expectedPublic)) {
                    secret
                } else {
                    secret.fill(0)
                    null
                }
            } finally {
                wrappingKey.fill(0)
            }
        }.getOrNull()
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private fun String.base64Bytes(expectedBytes: Int): ByteArray =
        Base64.getDecoder().decode(this).also { require(it.size == expectedBytes) }

    companion object {
        const val MIN_PASSPHRASE_LENGTH = 8
        private const val CIPHERTEXT_BYTES = CryptoBoundary.IDENTITY_KEY_BYTES + 16
    }
}
