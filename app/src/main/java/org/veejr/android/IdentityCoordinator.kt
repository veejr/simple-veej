package org.veejr.android

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.veejr.core.crypto.CryptoBoundary
import org.veejr.core.crypto.VeejrCrypto
import org.veejr.core.network.KeySetupRequest
import org.veejr.core.network.Envelope
import org.veejr.core.network.MessageEnvelopeRequest
import org.veejr.core.network.Recipient
import org.veejr.core.network.WrappedKey
import org.veejr.core.network.WrappedKeyKdf

data class PreparedIdentity(
    val request: KeySetupRequest,
    val secretKey: ByteArray,
)

data class OpenedMessage(
    val kind: String,
    val text: String,
    val recipientHandles: List<String>,
    val title: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val attachments: List<MessageAttachment> = emptyList(),
)

data class MessageAttachment(
    val id: String,
    val origin: String?,
    val key: String,
    val nonce: String,
    val name: String,
    val mime: String,
    val size: Long,
    val durationMs: Long? = null,
)

data class OutgoingAttachment(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
    val durationMs: Long? = null,
)

data class EncryptedAttachment(
    val ciphertext: ByteArray,
    val key: ByteArray,
    val nonce: ByteArray,
    val name: String,
    val mime: String,
    val size: Long,
    val durationMs: Long? = null,
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

    fun openMessage(envelope: Envelope, secretKey: ByteArray): String? =
        openMessagePayload(envelope, secretKey)?.text

    fun openMessagePayload(envelope: Envelope, secretKey: ByteArray): OpenedMessage? = runCatching {
        val plaintext = crypto.openBox(
            ciphertext = envelope.ciphertext.base64BytesAtLeast(16),
            nonce = envelope.nonce.base64Bytes(CryptoBoundary.NONCE_BYTES),
            senderPublicKey = envelope.peerKey.base64Bytes(CryptoBoundary.IDENTITY_KEY_BYTES),
            recipientSecretKey = secretKey,
        ) ?: return@runCatching null
        try {
            val payload = Json.parseToJsonElement(plaintext.toString(Charsets.UTF_8)).jsonObject
            val kind = payload["kind"]?.jsonPrimitive?.content ?: return@runCatching null
            if (payload["v"]?.jsonPrimitive?.content != "1" || kind !in SUPPORTED_KINDS) {
                null
            } else {
                OpenedMessage(
                    kind = kind,
                    text = payload["text"]?.jsonPrimitive?.content.orEmpty(),
                    title = payload["title"]?.jsonPrimitive?.content,
                    latitude = payload["lat"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                    longitude = payload["lng"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                    recipientHandles = payload["to"]?.jsonArray
                        ?.map { it.jsonPrimitive.content }
                        .orEmpty(),
                    attachments = payload["attachments"]?.jsonArray
                        ?.take(MAX_ATTACHMENTS_PER_MESSAGE)
                        ?.mapNotNull { element ->
                            runCatching {
                                val attachment = element.jsonObject
                                MessageAttachment(
                                    id = attachment.getValue("id").jsonPrimitive.content,
                                    origin = attachment["origin"]?.jsonPrimitive?.contentOrNull,
                                    key = attachment.getValue("key").jsonPrimitive.content,
                                    nonce = attachment.getValue("nonce").jsonPrimitive.content,
                                    name = attachment["name"]?.jsonPrimitive?.contentOrNull
                                        ?.take(MAX_ATTACHMENT_NAME_LENGTH)
                                        .orEmpty()
                                        .ifBlank { "attachment" },
                                    mime = attachment["mime"]?.jsonPrimitive?.contentOrNull
                                        ?.take(MAX_MIME_LENGTH)
                                        .orEmpty()
                                        .ifBlank { "application/octet-stream" },
                                    size = attachment["size"]?.jsonPrimitive?.longOrNull
                                        ?.coerceAtLeast(0L) ?: 0L,
                                    durationMs = attachment["duration_ms"]?.jsonPrimitive?.longOrNull
                                        ?.coerceAtLeast(0L),
                                )
                            }.getOrNull()
                        }
                        .orEmpty(),
                )
            }
        } finally {
            plaintext.fill(0)
        }
    }.getOrNull()

    fun openAttachment(ciphertext: ByteArray, attachment: MessageAttachment): ByteArray? =
        runCatching {
            crypto.openSecretBox(
                ciphertext = ciphertext,
                nonce = attachment.nonce.base64Bytes(CryptoBoundary.NONCE_BYTES),
                key = attachment.key.base64Bytes(CryptoBoundary.SECRETBOX_KEY_BYTES),
            )
        }.getOrNull()

    fun encryptAttachment(attachment: OutgoingAttachment): EncryptedAttachment {
        require(attachment.bytes.isNotEmpty()) { "An attachment cannot be empty." }
        val key = ByteArray(CryptoBoundary.SECRETBOX_KEY_BYTES).also(secureRandom::nextBytes)
        return try {
            val sealed = crypto.sealSecretBox(attachment.bytes, key)
            EncryptedAttachment(
                ciphertext = sealed.ciphertext,
                key = key,
                nonce = sealed.nonce,
                name = attachment.name,
                mime = attachment.mime,
                size = attachment.bytes.size.toLong(),
                durationMs = attachment.durationMs,
            )
        } catch (error: Exception) {
            key.fill(0)
            throw error
        }
    }

    fun sealMessage(
        text: String,
        recipients: List<Recipient>,
        secretKey: ByteArray,
        attachments: List<MessageAttachment> = emptyList(),
    ): List<MessageEnvelopeRequest> {
        require(text.isNotBlank() || attachments.isNotEmpty()) { "A message cannot be empty." }
        val payload = buildJsonObject {
            put("v", 1)
            put("kind", "message")
            put("text", text)
            put("attachments", buildJsonArray {
                attachments.forEach { attachment ->
                    add(buildJsonObject {
                        put("id", attachment.id)
                        attachment.origin?.let { put("origin", it) }
                        put("key", attachment.key)
                        put("nonce", attachment.nonce)
                        put("name", attachment.name)
                        put("mime", attachment.mime)
                        put("size", attachment.size)
                        attachment.durationMs?.let { put("duration_ms", it) }
                    })
                }
            })
            put("to", buildJsonArray { recipients.forEach { add(JsonPrimitive(it.handle)) } })
            put("sent_at", Instant.now().toString())
        }.toString().toByteArray(Charsets.UTF_8)

        return try {
            recipients.map { recipient ->
                val sealed = crypto.sealBox(
                    payload,
                    recipient.publicKey.base64Bytes(CryptoBoundary.IDENTITY_KEY_BYTES),
                    secretKey,
                )
                MessageEnvelopeRequest(
                    recipientId = recipient.id,
                    ciphertext = sealed.ciphertext.base64(),
                    nonce = sealed.nonce.base64(),
                )
            }
        } finally {
            payload.fill(0)
        }
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private fun String.base64Bytes(expectedBytes: Int): ByteArray =
        Base64.getDecoder().decode(this).also { require(it.size == expectedBytes) }

    private fun String.base64BytesAtLeast(minimumBytes: Int): ByteArray =
        Base64.getDecoder().decode(this).also { require(it.size >= minimumBytes) }

    companion object {
        private val SUPPORTED_KINDS = setOf("message", "location", "note")
        const val MIN_PASSPHRASE_LENGTH = 8
        private const val CIPHERTEXT_BYTES = CryptoBoundary.IDENTITY_KEY_BYTES + 16
        private const val MAX_ATTACHMENTS_PER_MESSAGE = 20
        private const val MAX_ATTACHMENT_NAME_LENGTH = 240
        private const val MAX_MIME_LENGTH = 120
    }
}
