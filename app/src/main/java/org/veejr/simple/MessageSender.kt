package org.veejr.simple

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.veejr.core.crypto.VeejrCrypto
import org.veejr.core.network.AuthSessionManager
import org.veejr.core.network.MessageBatchRequest
import org.veejr.core.network.MessageEnvelopeRequest

/**
 * Sends one sealed text message to the chosen friend: the same v1 `message`
 * payload and envelope-per-recipient batch the main veejr app sends, using the
 * identity key already held on this device.
 */
class MessageSender(
    private val store: SimpleStore,
    private val sessions: () -> AuthSessionManager?,
) {
    private val crypto = VeejrCrypto()

    suspend fun send(text: String) {
        val body = text.trim()
        require(body.isNotEmpty()) { "Type a message first." }
        val person = requireNotNull(store.myPerson) { "This phone is not set up." }
        val manager = requireNotNull(sessions()) { "This phone is not set up." }
        val secret = requireNotNull(store.identitySecret()) { "This phone is not set up." }
        try {
            val resolved = manager.resolveRecipients("contact", person.id)
            require(resolved.missingKeys.isEmpty()) { "That person has no encryption key." }
            require(resolved.recipients.size >= 2) { "That person is no longer available." }

            val payload = buildJsonObject {
                put("v", 1)
                put("kind", "message")
                put("text", body)
                put("attachments", buildJsonArray {})
                put("to", buildJsonArray { resolved.recipients.forEach { add(JsonPrimitive(it.handle)) } })
                put("sent_at", Instant.now().toString())
            }.toString().toByteArray(Charsets.UTF_8)

            val envelopes = try {
                resolved.recipients.map { recipient ->
                    val sealed = crypto.sealBox(payload, Base64.getDecoder().decode(recipient.publicKey), secret)
                    MessageEnvelopeRequest(
                        recipientId = recipient.id,
                        ciphertext = Base64.getEncoder().encodeToString(sealed.ciphertext),
                        nonce = Base64.getEncoder().encodeToString(sealed.nonce),
                    )
                }
            } finally {
                payload.fill(0)
            }
            manager.sendMessageBatch(idempotencyKey(), MessageBatchRequest(envelopes = envelopes))
        } finally {
            secret.fill(0)
        }
    }

    private fun idempotencyKey(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
}
