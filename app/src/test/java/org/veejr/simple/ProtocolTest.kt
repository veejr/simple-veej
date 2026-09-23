package org.veejr.simple

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.veejr.core.crypto.VeejrCrypto

class ProtocolTest {
    private val crypto = VeejrCrypto()

    @Test
    fun `phoenix frames round trip in the V2 array format`() {
        val frame = PhoenixFrame("1", "2", "calls:v1", "start", buildJsonObject { put("callee_id", "7") })
        val encoded = PhoenixFrames.encode(frame)

        assertEquals("""["1","2","calls:v1","start",{"callee_id":"7"}]""", encoded)
        assertEquals(frame, PhoenixFrames.decode(encoded))
    }

    @Test
    fun `server pushes carry null refs`() {
        val frame = PhoenixFrames.decode("""["3",null,"calls:v1","ring",{"call_id":"abc"}]""")!!
        assertNull(frame.ref)
        assertEquals("ring", frame.event)
        assertNull(PhoenixFrames.decode("not json"))
    }

    @Test
    fun `socket url targets the native socket with the access token`() {
        assertEquals(
            "wss://veejr.example/api/v1/socket/websocket?vsn=2.0.0&access_token=tok",
            PhoenixFrames.socketUrl("https://veejr.example/api/v1/", "tok"),
        )
        assertEquals(
            "ws://127.0.0.1:4000/api/v1/socket/websocket?vsn=2.0.0&access_token=tok",
            PhoenixFrames.socketUrl("http://127.0.0.1:4000/api/v1/", "tok"),
        )
    }

    @Test
    fun `signals use the browser's plaintext shapes`() {
        val ice = CallSignal.Ice("candidate:1 1 udp 1 1.2.3.4 5 typ host", "0", 0)
        assertEquals(
            """{"kind":"ice","candidate":{"candidate":"candidate:1 1 udp 1 1.2.3.4 5 typ host","sdpMid":"0","sdpMLineIndex":0}}""",
            ice.toJson().toString(),
        )
        assertEquals(ice, CallSignal.fromJson(ice.toJson()))
        assertEquals(CallSignal.Offer("v=0"), CallSignal.fromJson(CallSignal.Offer("v=0").toJson()))
    }

    @Test
    fun `browser ice candidates with a username fragment still parse`() {
        val json = Json.parseToJsonElement(
            """{"kind":"ice","candidate":{"candidate":"c","sdpMid":"1","sdpMLineIndex":1,"usernameFragment":"u"}}""",
        ).jsonObject
        assertEquals(CallSignal.Ice("c", "1", 1), CallSignal.fromJson(json))
    }

    @Test
    fun `unknown browser signal kinds are ignored rather than rejected`() {
        val json = Json.parseToJsonElement("""{"kind":"share_state","sharing":true}""").jsonObject
        assertEquals(CallSignal.Other, CallSignal.fromJson(json))
    }

    @Test
    fun `politeness matches the browser's string comparison`() {
        // "9" > "10" as strings, so the numerically smaller id is polite here.
        assertTrue(isPolite(myUserId = "9", peerUserId = "10"))
        assertFalse(isPolite(myUserId = "10", peerUserId = "9"))
        assertTrue(isPolite("10", "9") != isPolite("9", "10"))
    }

    @Test
    fun `sealed signals open only for the pinned peer`() {
        val alice = crypto.generateIdentity()
        val bob = crypto.generateIdentity()
        val mallory = crypto.generateIdentity()

        val aliceToBob = SignalSealer(crypto, alice.secretKey.copyOf(), bob.publicKey)
        val bobFromAlice = SignalSealer(crypto, bob.secretKey.copyOf(), alice.publicKey)
        val bobFromMallory = SignalSealer(crypto, bob.secretKey.copyOf(), mallory.publicKey)

        val sealed = aliceToBob.seal(CallSignal.Answer("v=0 answer"))
        // Standard padded Base64, as the browser's btoa produces.
        assertEquals(24, Base64.getDecoder().decode(sealed.nonce).size)

        assertEquals(CallSignal.Answer("v=0 answer"), bobFromAlice.open(sealed))
        assertNull(bobFromMallory.open(sealed))
        assertNotNull(bobFromAlice.open(aliceToBob.seal(CallSignal.MediaState(audio = false, video = true))))
    }
}
