package org.veejr.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.veejr.core.network.Account
import org.veejr.core.network.AccountResponse
import org.veejr.core.network.Capabilities
import org.veejr.core.network.DeviceInfo
import org.veejr.core.network.LoginResponse
import org.veejr.core.network.KeySetupRequest
import org.veejr.core.network.EnvelopeResponse
import org.veejr.core.network.EnvelopePage
import org.veejr.core.network.NotificationsResponse
import org.veejr.core.network.PendingNotification
import org.veejr.core.network.SenderSummary
import org.veejr.core.network.ContactsResponse
import org.veejr.core.network.GroupsResponse
import org.veejr.core.network.MessageBatchRequest
import org.veejr.core.network.MessageBatchResponse
import org.veejr.core.network.MessageDeliveryPolicy
import org.veejr.core.network.MessageDeliveryPolicyRequest
import org.veejr.core.network.MessageDeliveryPolicyResponse
import org.veejr.core.network.MessageDeliveryPoliciesResponse
import org.veejr.core.network.Recipient
import org.veejr.core.network.ResolveRecipientsRequest
import org.veejr.core.network.ResolveRecipientsResponse
import org.veejr.core.network.RefreshResponse
import org.veejr.core.network.SessionTokens
import org.veejr.core.network.VeejrApi

@OptIn(ExperimentalCoroutinesApi::class)
class VeejrViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `fresh install starts at instance selection`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeStorage(), FakeApi())
        advanceUntilIdle()

        assertEquals(AppScreen.INSTANCE, viewModel.state.value.screen)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun `compatible instance advances to login and is persisted`() = runTest(dispatcher) {
        val storage = FakeStorage()
        val viewModel = viewModel(storage, FakeApi())
        advanceUntilIdle()

        viewModel.connect("https://chat.example")
        advanceUntilIdle()

        assertEquals(AppScreen.LOGIN, viewModel.state.value.screen)
        assertEquals("https://chat.example", storage.endpoint)
    }

    @Test
    fun `HTTP emulator bridge follows the build variant policy`() = runTest(dispatcher) {
        val storage = FakeStorage()
        val viewModel = viewModel(storage, FakeApi())
        advanceUntilIdle()

        viewModel.connect("http://10.0.2.2:4000")
        advanceUntilIdle()

        if (BuildConfig.DEBUG) {
            assertEquals(AppScreen.LOGIN, viewModel.state.value.screen)
            assertEquals("http://10.0.2.2:4000", storage.endpoint)
        } else {
            assertEquals(AppScreen.INSTANCE, viewModel.state.value.screen)
            assertNull(storage.endpoint)
        }
    }

    @Test
    fun `login persists session and displays account`() = runTest(dispatcher) {
        val storage = FakeStorage(endpoint = "https://chat.example")
        val api = FakeApi()
        val viewModel = viewModel(storage, api)
        advanceUntilIdle()

        viewModel.login("alice@example.test", "secret")
        advanceUntilIdle()

        assertEquals(AppScreen.KEY_SETUP, viewModel.state.value.screen)
        assertSame(ACCOUNT, viewModel.state.value.account)
        assertSame(TOKENS, storage.tokens)
    }

    @Test
    fun `startup routes an account without keys to setup`() = runTest(dispatcher) {
        val storage = FakeStorage("https://chat.example", TOKENS)
        val viewModel = viewModel(storage, FakeApi())
        advanceUntilIdle()

        assertEquals(AppScreen.KEY_SETUP, viewModel.state.value.screen)
        assertSame(ACCOUNT, viewModel.state.value.account)
    }

    @Test
    fun `key setup uploads only wrapped material and enters home`() = runTest(dispatcher) {
        val storage = FakeStorage("https://chat.example", TOKENS)
        val api = FakeApi()
        val viewModel = viewModel(storage, api)
        advanceUntilIdle()

        viewModel.setupIdentity("long passphrase", "long passphrase").join()
        advanceUntilIdle()

        assertEquals(AppScreen.HOME, viewModel.state.value.screen)
        assertEquals(true, viewModel.state.value.account?.keysConfigured)
        assertEquals("PBKDF2-SHA256", api.keySetupRequest?.wrappedKey?.kdf?.name)
    }

    @Test
    fun `unlocked inbox loads pending metadata and decline removes it`() = runTest(dispatcher) {
        val storage = FakeStorage("https://chat.example", TOKENS)
        val api = FakeApi().apply { notifications = listOf(NOTIFICATION) }
        val viewModel = viewModel(storage, api)
        advanceUntilIdle()
        viewModel.setupIdentity("long passphrase", "long passphrase").join()

        assertEquals(listOf(NOTIFICATION), viewModel.state.value.notifications)

        viewModel.declineNotification(NOTIFICATION.id)
        advanceUntilIdle()

        assertEquals(emptyList<PendingNotification>(), viewModel.state.value.notifications)
        assertEquals(listOf(NOTIFICATION.id), api.declinedIds)
    }

    @Test
    fun `contact automatic acceptance is updated through the authenticated API`() = runTest(dispatcher) {
        val storage = FakeStorage("https://chat.example", TOKENS)
        val contact = Recipient("7", "bob", "@bob", "key")
        val api = FakeApi().apply { contacts = listOf(contact) }
        val viewModel = viewModel(storage, api)
        advanceUntilIdle()
        viewModel.setupIdentity("long passphrase", "long passphrase").join()

        viewModel.setDeliveryPolicy("contact", contact.id, "automatic").join()
        advanceUntilIdle()

        assertEquals("automatic", viewModel.state.value.deliveryPolicies.single().acceptance)
        assertEquals("contact:7" to "automatic", api.policyUpdate)

        viewModel.setDeliveryPolicy("contact", contact.id, null).join()
        advanceUntilIdle()

        assertEquals(emptyList<MessageDeliveryPolicy>(), viewModel.state.value.deliveryPolicies)
    }

    @Test
    fun `logout returns to login and clears tokens`() = runTest(dispatcher) {
        val storage = FakeStorage("https://chat.example", TOKENS)
        val viewModel = viewModel(storage, FakeApi())
        advanceUntilIdle()

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(AppScreen.LOGIN, viewModel.state.value.screen)
        assertNull(storage.tokens)
    }

    private fun viewModel(storage: FakeStorage, api: FakeApi) = VeejrViewModel(
        storage = storage,
        apiFactory = { api },
        deviceInfo = { DeviceInfo("Test device", appVersion = "test") },
    )

    private class FakeStorage(
        override var endpoint: String? = null,
        var tokens: SessionTokens? = null,
    ) : AppSessionStorage {
        override suspend fun load(): SessionTokens? = tokens
        override suspend fun save(tokens: SessionTokens) { this.tokens = tokens }
        override suspend fun clear() { tokens = null }
    }

    private class FakeApi : VeejrApi {
        var keySetupRequest: KeySetupRequest? = null
        var notifications: List<PendingNotification> = emptyList()
        var contacts: List<Recipient> = emptyList()
        var policyUpdate: Pair<String, String>? = null
        var policies: List<MessageDeliveryPolicy> = emptyList()
        val declinedIds = mutableListOf<String>()
        override suspend fun capabilities() = Capabilities(
            apiVersions = listOf(1),
            payloadVersions = listOf(1),
            maxBlobBytes = 1_000_000,
            messageKinds = listOf("message"),
            instanceMode = "community",
            androidPush = false,
        )

        override suspend fun login(email: String, password: CharArray, device: DeviceInfo) =
            LoginResponse(ACCOUNT, TOKENS)

        override suspend fun refresh(refreshToken: String) = RefreshResponse(TOKENS)
        override suspend fun me(accessToken: String) = AccountResponse(ACCOUNT)
        override suspend fun setupKeys(accessToken: String, request: KeySetupRequest): AccountResponse {
            keySetupRequest = request
            return AccountResponse(
                ACCOUNT.copy(
                    keysConfigured = true,
                    publicKey = request.publicKey,
                    wrappedKey = request.wrappedKey,
                ),
            )
        }

        override suspend fun pendingNotifications(accessToken: String) =
            NotificationsResponse(notifications)

        override suspend fun acceptNotification(accessToken: String, id: String): EnvelopeResponse =
            error("not used")

        override suspend fun declineNotification(accessToken: String, id: String) {
            declinedIds += id
        }

        override suspend fun contacts(accessToken: String) = ContactsResponse(contacts)

        override suspend fun groups(accessToken: String) = GroupsResponse(emptyList())

        override suspend fun messageDeliveryPolicies(accessToken: String) =
            MessageDeliveryPoliciesResponse(policies)

        override suspend fun putMessageDeliveryPolicy(
            accessToken: String,
            subjectType: String,
            subjectId: String,
            request: MessageDeliveryPolicyRequest,
        ): MessageDeliveryPolicyResponse {
            policyUpdate = "$subjectType:$subjectId" to request.acceptance
            val policy = MessageDeliveryPolicy(
                subjectType,
                subjectId,
                request.acceptance,
                request.notification,
            )
            policies = policies.filterNot {
                it.subjectType == subjectType && it.subjectId == subjectId
            } + policy
            return MessageDeliveryPolicyResponse(policy)
        }

        override suspend fun deleteMessageDeliveryPolicy(
            accessToken: String,
            subjectType: String,
            subjectId: String,
        ) {
            policies = policies.filterNot {
                it.subjectType == subjectType && it.subjectId == subjectId
            }
        }

        override suspend fun resolveRecipients(
            accessToken: String,
            request: ResolveRecipientsRequest,
        ) = ResolveRecipientsResponse(emptyList(), emptyList())

        override suspend fun sendMessageBatch(
            accessToken: String,
            idempotencyKey: String,
            request: MessageBatchRequest,
        ): MessageBatchResponse = error("not used")
        override suspend fun messageHistory(accessToken: String, cursor: String?) =
            EnvelopePage(emptyList())
        override suspend fun logout(accessToken: String) = Unit
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
        val TOKENS = SessionTokens(
            accessToken = "access",
            accessTokenExpiresAt = "2026-07-12T15:00:00Z",
            refreshToken = "refresh",
            refreshTokenExpiresAt = "2026-08-12T15:00:00Z",
            deviceSessionId = "9",
        )
        val NOTIFICATION = PendingNotification(
            id = "17",
            kind = "message",
            sender = SenderSummary("7", "@bob"),
            createdAt = "2026-07-12T20:00:00Z",
        )
    }
}
