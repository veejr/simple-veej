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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.veejr.core.network.Account
import org.veejr.core.network.AccountResponse
import org.veejr.core.network.Capabilities
import org.veejr.core.network.BlobUploadResponse
import org.veejr.core.network.DeviceInfo
import org.veejr.core.network.LoginResponse
import org.veejr.core.network.KeySetupRequest
import org.veejr.core.network.EnvelopeResponse
import org.veejr.core.network.EnvelopePage
import org.veejr.core.network.NotificationsResponse
import org.veejr.core.network.PendingNotification
import org.veejr.core.network.PrivateNoteRequest
import org.veejr.core.network.PrivateNote
import org.veejr.core.network.PrivateNoteResponse
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
import org.veejr.core.crypto.VeejrCrypto

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
    fun `legacy emulator endpoint migrates to debug loopback`() = runTest(dispatcher) {
        val storage = FakeStorage()
        val viewModel = viewModel(storage, FakeApi())
        advanceUntilIdle()

        viewModel.connect("http://10.0.2.2:4000")
        advanceUntilIdle()

        if (BuildConfig.DEBUG) {
            assertEquals(AppScreen.LOGIN, viewModel.state.value.screen)
            assertEquals("http://127.0.0.1:4000", storage.endpoint)
        } else {
            assertEquals(AppScreen.INSTANCE, viewModel.state.value.screen)
            assertNull(storage.endpoint)
        }
    }

    @Test
    fun `saved emulator endpoint migrates on debug startup`() = runTest(dispatcher) {
        val storage = FakeStorage(endpoint = "http://10.0.2.2:4000")
        val viewModel = viewModel(storage, FakeApi())
        advanceUntilIdle()

        if (BuildConfig.DEBUG) {
            assertEquals(AppScreen.LOGIN, viewModel.state.value.screen)
            assertEquals("http://127.0.0.1:4000", viewModel.state.value.endpoint)
            assertEquals("http://127.0.0.1:4000", storage.endpoint)
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
    fun `attachment ciphertext is downloaded and opened locally`() = runTest(dispatcher) {
        val storage = FakeStorage("https://chat.example", TOKENS)
        val api = FakeApi()
        val viewModel = viewModel(storage, api)
        advanceUntilIdle()
        viewModel.setupIdentity("long passphrase", "long passphrase").join()

        val plaintext = "private attachment".toByteArray()
        val key = ByteArray(32) { it.toByte() }
        val sealed = VeejrCrypto().sealSecretBox(plaintext, key)
        api.attachmentCiphertext = sealed.ciphertext
        val encoder = java.util.Base64.getEncoder()
        val attachment = MessageAttachment(
            id = "abcdefghijklmnop",
            origin = "https://files.example",
            key = encoder.encodeToString(key),
            nonce = encoder.encodeToString(sealed.nonce),
            name = "report.pdf",
            mime = "application/pdf",
            size = plaintext.size.toLong(),
        )

        viewModel.openAttachment(attachment).join()
        advanceUntilIdle()

        assertEquals("https://files.example" to attachment.id, api.attachmentRequest)
        assertEquals(emptyMap<String, String>(), viewModel.state.value.attachmentErrors)
        assertArrayEquals(plaintext, viewModel.state.value.openedAttachments[attachment.id]?.bytes)
        plaintext.fill(0)
        key.fill(0)
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
    fun `private contact note updates local expandable configuration data`() = runTest(dispatcher) {
        val storage = FakeStorage("https://chat.example", TOKENS)
        val contact = Recipient("7", "bob", "@bob", "key")
        val api = FakeApi().apply { contacts = listOf(contact) }
        val viewModel = viewModel(storage, api)
        advanceUntilIdle()
        viewModel.setupIdentity("long passphrase", "long passphrase").join()

        viewModel.savePrivateNote("contact", contact.id, "Met in Berlin").join()

        assertEquals("Met in Berlin", viewModel.state.value.contacts.single().note)
    }

    @Test
    fun `notes to yourself sends one encrypted self envelope`() = runTest(dispatcher) {
        val storage = FakeStorage("https://chat.example", TOKENS)
        val api = FakeApi()
        val viewModel = viewModel(storage, api)
        advanceUntilIdle()
        viewModel.setupIdentity("long passphrase", "long passphrase").join()
        api.resolveToSelf = true

        viewModel.sendMessage("self", ACCOUNT.id, "Remember this").join()

        assertEquals("self-copy", viewModel.state.value.messages.first().publicId)
        assertEquals(listOf(ACCOUNT.handle), viewModel.state.value.messages.first().recipientHandles)
        assertEquals("Remember this", viewModel.state.value.messages.first().text)
        assertEquals(1, api.sentBatch?.envelopes?.size)
    }

    @Test
    fun `attachment-only message encrypts and uploads before sending`() = runTest(dispatcher) {
        val storage = FakeStorage("https://chat.example", TOKENS)
        val api = FakeApi()
        val viewModel = viewModel(storage, api)
        advanceUntilIdle()
        viewModel.setupIdentity("long passphrase", "long passphrase").join()
        api.resolveToSelf = true
        val plaintext = "voice message bytes".toByteArray()

        viewModel.sendMessage(
            "self",
            ACCOUNT.id,
            "",
            listOf(OutgoingAttachment("voice.m4a", "audio/mp4", plaintext)),
        ).join()

        assertFalse(api.uploadedBlob.contentEquals("voice message bytes".toByteArray()))
        assertEquals("voice.m4a", viewModel.state.value.messages.first().attachments.single().name)
        assertEquals("https://chat.example", viewModel.state.value.messages.first().attachments.single().origin)
        assertEquals(1, api.sentBatch?.envelopes?.size)
        assertTrue(plaintext.all { it == 0.toByte() })
    }

    @Test
    fun `history pagination advances through server cursors`() = runTest(dispatcher) {
        val storage = FakeStorage("https://chat.example", TOKENS)
        val api = FakeApi().apply {
            historyPages[null] = EnvelopePage(emptyList(), nextCursor = "page-2")
            historyPages["page-2"] = EnvelopePage(emptyList(), nextCursor = null)
        }
        val viewModel = viewModel(storage, api)
        advanceUntilIdle()
        viewModel.setupIdentity("long passphrase", "long passphrase").join()

        assertEquals("page-2", viewModel.state.value.historyNextCursor)

        viewModel.loadMoreHistory().join()

        assertEquals(listOf<String?>(null, "page-2"), api.historyCursors)
        assertNull(viewModel.state.value.historyNextCursor)
        assertFalse(viewModel.state.value.historyLoadingMore)
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
        var resolveToSelf = false
        var sentBatch: MessageBatchRequest? = null
        val historyPages = mutableMapOf<String?, EnvelopePage>()
        val historyCursors = mutableListOf<String?>()
        var policyUpdate: Pair<String, String>? = null
        var attachmentRequest: Pair<String?, String>? = null
        var attachmentCiphertext: ByteArray = byteArrayOf()
        var uploadedBlob: ByteArray = byteArrayOf()
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

        override suspend fun putPrivateNote(
            accessToken: String,
            subjectType: String,
            subjectId: String,
            request: PrivateNoteRequest,
        ): PrivateNoteResponse {
            val note = PrivateNote(subjectId, request.body)
            if (subjectType == "contact") {
                contacts = contacts.map {
                    if (it.id == subjectId) it.copy(note = request.body) else it
                }
            }
            return PrivateNoteResponse(note)
        }

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
        ) = if (resolveToSelf) {
            ResolveRecipientsResponse(
                listOf(
                    Recipient(
                        ACCOUNT.id,
                        ACCOUNT.username,
                        ACCOUNT.handle,
                        checkNotNull(keySetupRequest).publicKey,
                    ),
                ),
                emptyList(),
            )
        } else {
            ResolveRecipientsResponse(emptyList(), emptyList())
        }

        override suspend fun sendMessageBatch(
            accessToken: String,
            idempotencyKey: String,
            request: MessageBatchRequest,
        ): MessageBatchResponse {
            sentBatch = request
            return MessageBatchResponse(
                "batch",
                listOf(org.veejr.core.network.MessageCopy(ACCOUNT.id, "self-copy")),
                emptyList(),
            )
        }
        override suspend fun messageHistory(accessToken: String, cursor: String?, kind: String?) =
            historyPages[cursor].also { historyCursors += cursor } ?: EnvelopePage(emptyList())
        override suspend fun attachmentBlob(origin: String?, id: String): ByteArray {
            attachmentRequest = origin to id
            return attachmentCiphertext.copyOf()
        }
        override suspend fun uploadBlob(
            accessToken: String,
            idempotencyKey: String,
            ciphertext: ByteArray,
        ): BlobUploadResponse {
            uploadedBlob = ciphertext.copyOf()
            return BlobUploadResponse("uploadedblob1234", ciphertext.size.toLong())
        }
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
