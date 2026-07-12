package org.veejr.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VeejrApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: VeejrApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val endpoint = ApiEndpoint.parse(server.url("/").toString(), allowHttp = true)
        api = VeejrApiClient(endpoint)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `loads capabilities from API v1`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(CAPABILITIES_JSON))

        val capabilities = api.capabilities()

        assertEquals(listOf(1), capabilities.apiVersions)
        assertEquals(26_214_400, capabilities.maxBlobBytes)
        assertFalse(capabilities.androidPush)
        server.takeRequest().also { request ->
            assertEquals("GET", request.method)
            assertEquals("/api/v1/capabilities", request.path)
            assertEquals("application/json", request.getHeader("Accept"))
        }
    }

    @Test
    fun `logs in with protocol device fields and redacts returned tokens`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(LOGIN_JSON, "Cache-Control" to "no-store"))

        val result = api.login(
            email = "alice@example.test",
            password = "account password".toCharArray(),
            device = DeviceInfo(name = "Alice's Pixel", appVersion = "0.1.0-alpha01"),
        )

        assertEquals("alice", result.account.username)
        assertEquals("access-secret", result.tokens.accessToken)
        assertEquals("SessionTokens(<redacted>)", result.tokens.toString())

        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/login", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("alice@example.test", body.getValue("email").jsonPrimitive.content)
        assertEquals("account password", body.getValue("password").jsonPrimitive.content)
        assertEquals(
            "Alice's Pixel",
            body.getValue("device").jsonObject.getValue("name").jsonPrimitive.content,
        )
        assertEquals(
            "0.1.0-alpha01",
            body.getValue("device").jsonObject.getValue("app_version").jsonPrimitive.content,
        )
    }

    @Test
    fun `sends bearer token only on authenticated calls`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(ME_JSON, "Cache-Control" to "no-store"))
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals("alice", api.me("access-secret").account.username)
        api.logout("access-secret")

        repeat(2) {
            val request = server.takeRequest()
            assertEquals("Bearer access-secret", request.getHeader("Authorization"))
        }
    }

    @Test
    fun `rotates a refresh token with the expected JSON field`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(REFRESH_JSON, "Cache-Control" to "no-store"))

        val response = api.refresh("old-refresh-secret")

        assertEquals("new-access-secret", response.tokens.accessToken)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("old-refresh-secret", body.getValue("refresh_token").jsonPrimitive.content)
    }

    @Test
    fun `uploads portable identity material with bearer authentication`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(CONFIGURED_ME_JSON, "Cache-Control" to "no-store"))
        val wrapped = WrappedKey(
            ciphertext = "wrapped-secret",
            salt = "salt",
            nonce = "nonce",
            kdf = WrappedKeyKdf("PBKDF2-SHA256", 310_000),
            wrap = "XSalsa20-Poly1305",
        )

        val account = api.setupKeys("access-secret", KeySetupRequest("public-key", wrapped)).account

        assertTrue(account.keysConfigured)
        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/keys", request.path)
        assertEquals("Bearer access-secret", request.getHeader("Authorization"))
        assertEquals("public-key", body.getValue("public_key").jsonPrimitive.content)
        assertEquals(
            "310000",
            body.getValue("wrapped_key").jsonObject
                .getValue("kdf").jsonObject
                .getValue("iterations").jsonPrimitive.content,
        )
    }

    @Test
    fun `decodes stable API errors without exposing response bodies`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody(ERROR_JSON),
        )

        val error = assertThrows(VeejrApiException::class.java) {
            runBlocking { api.me("expired-token") }
        }

        assertEquals(401, error.statusCode)
        assertEquals("authentication_required", error.apiError.code)
        assertEquals("req-123", error.apiError.requestId)
        assertFalse(error.message.orEmpty().contains("expired-token"))
    }

    @Test
    fun `does not follow redirects that could cross instance origins`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://attacker.invalid/api/v1/me")
                .setBody(ERROR_JSON),
        )

        val error = assertThrows(VeejrApiException::class.java) {
            runBlocking { api.me("access-secret") }
        }

        assertEquals(302, error.statusCode)
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().path == "/api/v1/me")
    }

    private fun jsonResponse(body: String, vararg headers: Pair<String, String>): MockResponse {
        val response = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
        headers.forEach { (name, value) -> response.setHeader(name, value) }
        return response
    }

    private companion object {
        const val CAPABILITIES_JSON = """
            {
              "api_versions": [1],
              "payload_versions": [1],
              "max_blob_bytes": 26214400,
              "message_kinds": ["message", "location", "note"],
              "instance_mode": "community",
              "android_push": false
            }
        """

        const val TOKENS_JSON = """
            {
              "access_token": "access-secret",
              "access_token_expires_at": "2026-07-12T14:45:00Z",
              "refresh_token": "refresh-secret",
              "refresh_token_expires_at": "2026-08-11T14:30:00Z",
              "device_session_id": "9"
            }
        """

        val LOGIN_JSON = """
            {
              "account": {
                "id": "42",
                "email": "alice@example.test",
                "username": "alice",
                "display_name": "Alice",
                "handle": "@alice",
                "confirmed": true,
                "keys_configured": false,
                "public_key": null,
                "wrapped_key": null
              },
              "tokens": $TOKENS_JSON
            }
        """

        const val ME_JSON = """
            {
              "account": {
                "id": "42",
                "email": "alice@example.test",
                "username": "alice",
                "display_name": "Alice",
                "handle": "@alice",
                "confirmed": true,
                "keys_configured": false,
                "public_key": null,
                "wrapped_key": null
              }
            }
        """

        const val CONFIGURED_ME_JSON = """
            {
              "account": {
                "id": "42",
                "email": "alice@example.test",
                "username": "alice",
                "display_name": "Alice",
                "handle": "@alice",
                "confirmed": true,
                "keys_configured": true,
                "public_key": "public-key",
                "wrapped_key": {
                  "ciphertext": "wrapped-secret",
                  "salt": "salt",
                  "nonce": "nonce",
                  "kdf": {"name": "PBKDF2-SHA256", "iterations": 310000},
                  "wrap": "XSalsa20-Poly1305"
                }
              }
            }
        """

        const val REFRESH_JSON = """
            {
              "tokens": {
                "access_token": "new-access-secret",
                "access_token_expires_at": "2026-07-12T15:00:00Z",
                "refresh_token": "new-refresh-secret",
                "refresh_token_expires_at": "2026-08-11T14:45:00Z",
                "device_session_id": "9"
              }
            }
        """

        const val ERROR_JSON = """
            {
              "error": {
                "code": "authentication_required",
                "message": "Authentication is required.",
                "request_id": "req-123",
                "details": {}
              }
            }
        """
    }
}
