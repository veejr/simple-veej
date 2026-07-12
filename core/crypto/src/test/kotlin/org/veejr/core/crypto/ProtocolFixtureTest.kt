package org.veejr.core.crypto

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.veejr.core.model.Protocol

class ProtocolFixtureTest {
    private val crypto = VeejrCrypto()
    private val fixture by lazy {
        val directory = checkNotNull(System.getProperty("veejr.fixtures.dir"))
        Json.parseToJsonElement(File(directory, "v1.json").readText()).jsonObject
    }

    @Test
    fun `fixture declares the supported protocol versions`() {
        val protocol = fixture.getValue("protocol").jsonObject

        assertEquals(Protocol.API_VERSION, protocol.getValue("api_version").jsonPrimitive.int)
        assertEquals(Protocol.PAYLOAD_VERSION, protocol.getValue("payload_version").jsonPrimitive.int)
        assertEquals("standard-padded-base64", fixture.getValue("encoding").jsonPrimitive.content)
    }

    @Test
    fun `PBKDF2 output matches the browser fixture`() {
        val wrapping = fixture.getValue("wrapping").jsonObject
        val passphrase = wrapping.getValue("passphrase").jsonPrimitive.content
        val iterations = wrapping.getValue("iterations").jsonPrimitive.int
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(wrapping.getValue("salt").jsonPrimitive.content)
        val expected = decoder.decode(wrapping.getValue("derived_key").jsonPrimitive.content)
        val actual = crypto.deriveWrappingKey(passphrase.toCharArray(), salt, iterations)

        assertArrayEquals(expected, actual)
    }

    @Test
    fun `opens and reproduces the browser wrapped identity key`() {
        val decoder = Base64.getDecoder()
        val wrapping = fixture.getValue("wrapping").jsonObject
        val key = decoder.decode(wrapping.getValue("derived_key").jsonPrimitive.content)
        val nonce = decoder.decode(wrapping.getValue("nonce").jsonPrimitive.content)
        val ciphertext = decoder.decode(wrapping.getValue("wrapped_secret_key").jsonPrimitive.content)
        val plaintext = decoder.decode(wrapping.getValue("plaintext_secret_key").jsonPrimitive.content)

        assertArrayEquals(plaintext, crypto.unwrapSecretKey(ciphertext, nonce, key))
        assertArrayEquals(
            ciphertext,
            crypto.sealSecretBoxWithNonce(plaintext, nonce, key).ciphertext,
        )
    }

    @Test
    fun `opens and reproduces the browser envelope`() {
        val decoder = Base64.getDecoder()
        val envelope = fixture.getValue("envelope").jsonObject
        val payload = envelope.getValue("payload_json").jsonPrimitive.content.encodeToByteArray()
        val nonce = decoder.decode(envelope.getValue("nonce").jsonPrimitive.content)
        val ciphertext = decoder.decode(envelope.getValue("ciphertext").jsonPrimitive.content)
        val senderSecret = decoder.decode(envelope.getValue("sender_secret_key").jsonPrimitive.content)
        val senderPublic = decoder.decode(envelope.getValue("sender_public_key").jsonPrimitive.content)
        val recipientSecret = decoder.decode(envelope.getValue("recipient_secret_key").jsonPrimitive.content)
        val recipientPublic = decoder.decode(envelope.getValue("recipient_public_key").jsonPrimitive.content)

        assertArrayEquals(
            payload,
            crypto.openBox(ciphertext, nonce, senderPublic, recipientSecret),
        )
        assertArrayEquals(
            ciphertext,
            crypto.sealBoxWithNonce(payload, nonce, recipientPublic, senderSecret).ciphertext,
        )
        assertArrayEquals(senderPublic, crypto.publicKeyFromSecret(senderSecret))
    }

    @Test
    fun `opens and reproduces the browser attachment`() {
        val decoder = Base64.getDecoder()
        val attachment = fixture.getValue("attachment").jsonObject
        val plaintext = attachment.getValue("plaintext_utf8").jsonPrimitive.content.encodeToByteArray()
        val key = decoder.decode(attachment.getValue("key").jsonPrimitive.content)
        val nonce = decoder.decode(attachment.getValue("nonce").jsonPrimitive.content)
        val ciphertext = decoder.decode(attachment.getValue("ciphertext").jsonPrimitive.content)

        assertArrayEquals(plaintext, crypto.openSecretBox(ciphertext, nonce, key))
        assertArrayEquals(
            ciphertext,
            crypto.sealSecretBoxWithNonce(plaintext, nonce, key).ciphertext,
        )
    }

    @Test
    fun `rejects tampered authenticated ciphertext`() {
        val decoder = Base64.getDecoder()
        val envelope = fixture.getValue("envelope").jsonObject
        val ciphertext = decoder.decode(envelope.getValue("ciphertext").jsonPrimitive.content)
        val nonce = decoder.decode(envelope.getValue("nonce").jsonPrimitive.content)
        val senderPublic = decoder.decode(envelope.getValue("sender_public_key").jsonPrimitive.content)
        val recipientSecret = decoder.decode(envelope.getValue("recipient_secret_key").jsonPrimitive.content)
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()

        assertNull(crypto.openBox(ciphertext, nonce, senderPublic, recipientSecret))
    }

    @Test
    fun `fixture key and nonce lengths match protocol v1`() {
        val decoder = Base64.getDecoder()
        val identity = fixture.getValue("identity").jsonObject
        val envelope = fixture.getValue("envelope").jsonObject

        assertEquals(
            CryptoBoundary.IDENTITY_KEY_BYTES,
            decoder.decode(identity.getValue("sender_secret_key").jsonPrimitive.content).size,
        )
        assertEquals(
            CryptoBoundary.NONCE_BYTES,
            decoder.decode(envelope.getValue("nonce").jsonPrimitive.content).size,
        )
    }
}
