package org.veejr.core.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionManagerTest {
    @Test
    fun `login persists tokens and returns the account`() = runBlocking<Unit> {
        val api = FakeApi()
        val store = FakeTokenStore()
        val manager = AuthSessionManager(api, store)

        val account = manager.login(
            email = "alice@example.test",
            password = "secret".toCharArray(),
            device = DeviceInfo("Alice's Pixel", appVersion = "0.1.0-alpha01"),
        )

        assertSame(ACCOUNT, account)
        assertSame(INITIAL_TOKENS, store.tokens)
        assertTrue(manager.hasSession())
    }

    @Test
    fun `authenticated request refreshes once and retries with the new token`() = runBlocking<Unit> {
        val api = FakeApi().apply { rejectAccessToken = INITIAL_TOKENS.accessToken }
        val store = FakeTokenStore(INITIAL_TOKENS)
        val manager = AuthSessionManager(api, store)

        assertSame(ACCOUNT, manager.currentAccount())

        assertEquals(listOf("old-access", "new-access"), api.meTokens)
        assertEquals(1, api.refreshCalls)
        assertSame(REFRESHED_TOKENS, store.tokens)
    }

    @Test
    fun `concurrent expiry responses share one refresh`() = runBlocking<Unit> {
        val twoExpiredRequests = CompletableDeferred<Unit>()
        val expiredRequestCount = AtomicInteger()
        val api = FakeApi().apply {
            rejectAccessToken = INITIAL_TOKENS.accessToken
            beforeRejectingToken = {
                if (expiredRequestCount.incrementAndGet() == 2) twoExpiredRequests.complete(Unit)
                twoExpiredRequests.await()
            }
        }
        val store = FakeTokenStore(INITIAL_TOKENS)
        val manager = AuthSessionManager(api, store)

        listOf(
            async { manager.currentAccount() },
            async { manager.currentAccount() },
        ).awaitAll()

        assertEquals(1, api.refreshCalls)
        assertEquals(2, api.meTokens.count { it == "new-access" })
    }

    @Test
    fun `invalid refresh clears the local session`() {
        val api = FakeApi().apply {
            rejectAccessToken = INITIAL_TOKENS.accessToken
            refreshError = unauthorized("invalid_refresh_token")
        }
        val store = FakeTokenStore(INITIAL_TOKENS)
        val manager = AuthSessionManager(api, store)

        val error = assertThrows(VeejrApiException::class.java) {
            runBlocking { manager.currentAccount() }
        }

        assertEquals("invalid_refresh_token", error.apiError.code)
        assertNull(store.tokens)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun `logout clears local tokens even when the server is unreachable`() {
        val api = FakeApi().apply { logoutError = IOException("offline") }
        val store = FakeTokenStore(INITIAL_TOKENS)
        val manager = AuthSessionManager(api, store)

        assertThrows(IOException::class.java) { runBlocking { manager.logout() } }

        assertNull(store.tokens)
        assertEquals(1, store.clearCalls)
        assertFalse(runBlocking { manager.hasSession() })
    }

    private class FakeTokenStore(initialTokens: SessionTokens? = null) : SessionTokenStore {
        var tokens = initialTokens
        var clearCalls = 0

        override suspend fun load(): SessionTokens? = tokens

        override suspend fun save(tokens: SessionTokens) {
            this.tokens = tokens
        }

        override suspend fun clear() {
            tokens = null
            clearCalls += 1
        }
    }

    private class FakeApi : VeejrApi {
        var rejectAccessToken: String? = null
        var beforeRejectingToken: suspend () -> Unit = {}
        var refreshError: VeejrApiException? = null
        var logoutError: IOException? = null
        var refreshCalls = 0
        val meTokens = mutableListOf<String>()

        override suspend fun capabilities(): Capabilities = error("not used")

        override suspend fun login(
            email: String,
            password: CharArray,
            device: DeviceInfo,
        ): LoginResponse = LoginResponse(ACCOUNT, INITIAL_TOKENS)

        override suspend fun refresh(refreshToken: String): RefreshResponse {
            refreshCalls += 1
            refreshError?.let { throw it }
            return RefreshResponse(REFRESHED_TOKENS)
        }

        override suspend fun me(accessToken: String): AccountResponse {
            synchronized(meTokens) { meTokens += accessToken }
            if (accessToken == rejectAccessToken) {
                beforeRejectingToken()
                throw unauthorized("authentication_required")
            }
            return AccountResponse(ACCOUNT)
        }

        override suspend fun logout(accessToken: String) {
            logoutError?.let { throw it }
        }
    }

    private companion object {
        val ACCOUNT = Account(
            id = "42",
            email = "alice@example.test",
            username = "alice",
            displayName = "Alice",
            handle = "@alice",
            confirmed = true,
            keysConfigured = false,
        )

        val INITIAL_TOKENS = tokens("old-access", "old-refresh")
        val REFRESHED_TOKENS = tokens("new-access", "new-refresh")

        fun tokens(access: String, refresh: String) = SessionTokens(
            accessToken = access,
            accessTokenExpiresAt = "2026-07-12T15:00:00Z",
            refreshToken = refresh,
            refreshTokenExpiresAt = "2026-08-11T15:00:00Z",
            deviceSessionId = "9",
        )

        fun unauthorized(code: String) = VeejrApiException(
            statusCode = 401,
            apiError = ApiError(code = code, message = "Unauthorized"),
        )
    }
}
