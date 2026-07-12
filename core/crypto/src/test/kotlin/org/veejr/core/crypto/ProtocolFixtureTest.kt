package org.veejr.core.crypto

import java.io.File
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.veejr.core.model.Protocol

class ProtocolFixtureTest {
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
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, expected.size * 8)

        val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded

        assertArrayEquals(expected, actual)
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
