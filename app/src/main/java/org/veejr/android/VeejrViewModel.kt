package org.veejr.android

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.veejr.core.network.Account
import org.veejr.core.network.ApiEndpoint
import org.veejr.core.network.AuthSessionManager
import org.veejr.core.network.DeviceInfo
import org.veejr.core.network.Envelope
import org.veejr.core.network.ContactGroup
import org.veejr.core.network.MessageDeliveryPolicy
import org.veejr.core.network.PendingNotification
import org.veejr.core.network.MessageBatchRequest
import org.veejr.core.network.Recipient
import org.veejr.core.network.VeejrApi
import org.veejr.core.network.VeejrApiClient
import org.veejr.core.network.VeejrApiException

enum class AppScreen { INSTANCE, LOGIN, KEY_SETUP, KEY_UNLOCK, HOME }

enum class FcmRegistrationStatus { UNKNOWN, REGISTERED, NOT_REGISTERED }

data class AppUiState(
    val screen: AppScreen = AppScreen.INSTANCE,
    val endpoint: String = "",
    val account: Account? = null,
    val fcmRegistrationStatus: FcmRegistrationStatus = FcmRegistrationStatus.UNKNOWN,
    val loading: Boolean = true,
    val error: String? = null,
    val loginMessage: String? = null,
    val notifications: List<PendingNotification> = emptyList(),
    val messages: List<InboxMessage> = emptyList(),
    val unreadMessageIds: Set<String> = emptySet(),
    val newMessageIds: Set<String> = emptySet(),
    val contacts: List<Recipient> = emptyList(),
    val groups: List<ContactGroup> = emptyList(),
    val deliveryPolicies: List<MessageDeliveryPolicy> = emptyList(),
    val historyNextCursor: String? = null,
    val historyLoadingMore: Boolean = false,
    val historyLoaded: Boolean = false,
    val openedAttachments: Map<String, OpenedAttachment> = emptyMap(),
    val attachmentLoadingIds: Set<String> = emptySet(),
    val attachmentErrors: Map<String, String> = emptyMap(),
)

data class InboxMessage(
    val publicId: String,
    val senderHandle: String,
    val text: String,
    val createdAt: String,
    val recipientHandles: List<String> = emptyList(),
    val sentByMe: Boolean = false,
    val kind: String = "message",
    val title: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val attachments: List<MessageAttachment> = emptyList(),
)

data class OpenedAttachment(
    val attachment: MessageAttachment,
    val bytes: ByteArray,
)

class VeejrViewModel(
    private val storage: AppSessionStorage,
    private val apiFactory: (ApiEndpoint) -> VeejrApi = ::VeejrApiClient,
    private val identityCoordinator: IdentityCoordinator = IdentityCoordinator(),
    private val deviceInfo: () -> DeviceInfo = {
        DeviceInfo(
            name = android.os.Build.MODEL.ifBlank { "Android device" },
            appVersion = BuildConfig.VERSION_NAME,
        )
    },
) : ViewModel() {
    private val messageSendInFlight = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var sessionManager: AuthSessionManager? = null
    private var identitySecret: ByteArray? = null

    fun setFcmRegistrationStatus(registered: Boolean) {
        mutableState.update {
            it.copy(
                fcmRegistrationStatus = if (registered) {
                    FcmRegistrationStatus.REGISTERED
                } else {
                    FcmRegistrationStatus.NOT_REGISTERED
                },
            )
        }
    }

    init {
        restoreSession()
    }

    fun connect(endpointValue: String) = viewModelScope.launch {
        mutableState.update { it.copy(loading = true, error = null) }
        val endpoint = runCatching { parseEndpoint(endpointValue) }.getOrElse {
            mutableState.update { state -> state.copy(loading = false, error = it.message) }
            return@launch
        }
        try {
            val api = apiFactory(endpoint)
            val capabilities = api.capabilities()
            require(1 in capabilities.apiVersions) { "This instance does not support Android API v1." }
            val normalizedEndpoint = endpoint.uri.resolve("/").toString().trimEnd('/')
            if (storage.endpoint != normalizedEndpoint) storage.clear()
            storage.endpoint = normalizedEndpoint
            sessionManager = AuthSessionManager(api, storage)
            mutableState.value = AppUiState(
                screen = AppScreen.LOGIN,
                endpoint = normalizedEndpoint,
                loading = false,
            )
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error)) }
        }
    }

    fun login(identifier: String, password: String) = viewModelScope.launch {
        val manager = sessionManager ?: return@launch
        mutableState.update { it.copy(loading = true, error = null, loginMessage = null) }
        val passwordChars = password.toCharArray()
        try {
            val account = manager.login(identifier.trim(), passwordChars, deviceInfo())
            showAccount(account)
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error, loginAttempt = true)) }
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    fun requestOneTimeLogin(identifier: String) = viewModelScope.launch {
        val manager = sessionManager ?: return@launch
        mutableState.update { it.copy(loading = true, error = null, loginMessage = null) }
        try {
            manager.requestOneTimeLogin(identifier.trim())
            mutableState.update {
                it.copy(
                    loading = false,
                    loginMessage = "If that username or email exists, we sent a one-time sign-in link.",
                )
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error, loginAttempt = true)) }
        }
    }

    fun exchangeOneTimeLogin(token: String) = viewModelScope.launch {
        val manager = sessionManager ?: return@launch
        mutableState.update { it.copy(loading = true, error = null, loginMessage = null) }
        try {
            showAccount(manager.exchangeOneTimeLogin(token.trim(), deviceInfo()))
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error, loginAttempt = true)) }
        }
    }

    fun logout() = viewModelScope.launch {
        mutableState.update { it.copy(loading = true, error = null) }
        runCatching { sessionManager?.logout() }
        clearOpenedAttachments()
        clearIdentity()
        mutableState.update {
            it.copy(
                screen = AppScreen.LOGIN,
                account = null,
                loading = false,
                notifications = emptyList(),
                messages = emptyList(),
                contacts = emptyList(),
                groups = emptyList(),
                deliveryPolicies = emptyList(),
                historyNextCursor = null,
                historyLoadingMore = false,
                historyLoaded = false,
                openedAttachments = emptyMap(),
                attachmentLoadingIds = emptySet(),
                attachmentErrors = emptyMap(),
            )
        }
    }

    fun changeInstance() = viewModelScope.launch {
        runCatching { sessionManager?.logout() }
        storage.clear()
        storage.endpoint = null
        sessionManager = null
        clearOpenedAttachments()
        clearIdentity()
        mutableState.value = AppUiState(loading = false)
    }

    fun setupIdentity(passphrase: String, confirmation: String) = viewModelScope.launch {
        if (passphrase != confirmation) {
            mutableState.update { it.copy(error = "The passphrases do not match.") }
            return@launch
        }
        val manager = sessionManager ?: return@launch
        mutableState.update { it.copy(loading = true, error = null) }
        val chars = passphrase.toCharArray()
        var prepared: PreparedIdentity? = null
        try {
            prepared = withContext(Dispatchers.Default) { identityCoordinator.prepare(chars) }
            val account = manager.setupKeys(prepared.request)
            clearIdentity()
            identitySecret = prepared.secretKey
            prepared = null
            mutableState.update {
                it.copy(screen = AppScreen.HOME, account = account, loading = false)
            }
            loadInbox()
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error)) }
        } finally {
            prepared?.secretKey?.fill(0)
            chars.fill('\u0000')
        }
    }

    fun unlockIdentity(passphrase: String) = viewModelScope.launch {
        val account = mutableState.value.account ?: return@launch
        val publicKey = account.publicKey ?: return@launch
        val wrappedKey = account.wrappedKey ?: return@launch
        mutableState.update { it.copy(loading = true, error = null) }
        val chars = passphrase.toCharArray()
        try {
            val secret = withContext(Dispatchers.Default) {
                identityCoordinator.unlock(publicKey, wrappedKey, chars)
            }
            if (secret == null) {
                mutableState.update {
                    it.copy(loading = false, error = "That encryption passphrase is not correct.")
                }
            } else {
                clearIdentity()
                identitySecret = secret
                mutableState.update { it.copy(screen = AppScreen.HOME, loading = false) }
                loadInbox()
            }
        } finally {
            chars.fill('\u0000')
        }
    }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    fun refreshInbox() = viewModelScope.launch {
        mutableState.update { it.copy(loading = true, error = null) }
        loadInbox()
    }

    fun syncInbox() = viewModelScope.launch { loadInbox() }

    fun markMessagesRead(ids: Set<String>) {
        if (ids.isEmpty()) return
        mutableState.update { state ->
            state.copy(unreadMessageIds = state.unreadMessageIds - ids)
        }
    }

    fun consumeNewMessageFlash(ids: Set<String>) {
        if (ids.isEmpty()) return
        mutableState.update { state ->
            state.copy(newMessageIds = state.newMessageIds - ids)
        }
    }

    fun loadMoreHistory() = viewModelScope.launch {
        val manager = sessionManager ?: return@launch
        val secret = identitySecret ?: return@launch
        val cursor = mutableState.value.historyNextCursor ?: return@launch
        if (mutableState.value.historyLoadingMore) return@launch

        mutableState.update { it.copy(historyLoadingMore = true, error = null) }
        try {
            val page = manager.messageHistory(cursor = cursor)
            val olderMessages = openHistory(page.envelopes, secret)
            mutableState.update { state ->
                val knownIds = state.messages.mapTo(mutableSetOf(), InboxMessage::publicId)
                state.copy(
                    messages = state.messages + olderMessages.filterNot { it.publicId in knownIds },
                    historyNextCursor = page.nextCursor,
                    historyLoadingMore = false,
                )
            }
        } catch (error: Exception) {
            mutableState.update {
                it.copy(historyLoadingMore = false, error = messageFor(error))
            }
        }
    }

    fun acceptNotification(id: String) = viewModelScope.launch {
        val manager = sessionManager ?: return@launch
        val secret = identitySecret ?: return@launch
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val envelope = manager.acceptNotification(id)
            val opened = withContext(Dispatchers.Default) {
                identityCoordinator.openMessagePayload(envelope, secret)
            }
            if (opened == null) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        notifications = it.notifications.filterNot { item -> item.id == id },
                        error = "The accepted message could not be decrypted.",
                    )
                }
            } else {
                val message = InboxMessage(
                    publicId = envelope.publicId,
                    senderHandle = envelope.sender.handle,
                    text = opened.text,
                    createdAt = envelope.createdAt,
                    recipientHandles = opened.recipientHandles,
                    sentByMe = envelope.sentByMe,
                    kind = opened.kind,
                    title = opened.title,
                    latitude = opened.latitude,
                    longitude = opened.longitude,
                    attachments = opened.attachments,
                )
                mutableState.update {
                    it.copy(
                        loading = false,
                        notifications = it.notifications.filterNot { item -> item.id == id },
                        messages = listOf(message) + it.messages,
                    )
                }
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error)) }
        }
    }

    fun declineNotification(id: String) = viewModelScope.launch {
        val manager = sessionManager ?: return@launch
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            manager.declineNotification(id)
            mutableState.update {
                it.copy(
                    loading = false,
                    notifications = it.notifications.filterNot { item -> item.id == id },
                )
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error)) }
        }
    }

    fun sendMessage(
        subjectType: String,
        subjectId: String,
        text: String,
        attachments: List<OutgoingAttachment> = emptyList(),
    ) = viewModelScope.launch {
        if (!messageSendInFlight.compareAndSet(false, true)) return@launch

        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val manager = requireNotNull(sessionManager) { "Your session is no longer available." }
            val secret = requireNotNull(identitySecret) { "Unlock your encryption keys before sending." }
            val account = requireNotNull(mutableState.value.account) { "Your account is no longer available." }
            require(text.isNotBlank() || attachments.isNotEmpty()) { "A message cannot be empty." }
            val resolved = manager.resolveRecipients(subjectType, subjectId)
            require(resolved.missingKeys.isEmpty()) { "A recipient has not configured encryption keys." }
            if (subjectType == "self") {
                require(resolved.recipients.size == 1 && resolved.recipients.single().id == subjectId) {
                    "This account is no longer available."
                }
            } else {
                require(resolved.recipients.size >= 2) { "The recipient is no longer available." }
            }
            val attachmentDescriptors = attachments.map { attachment ->
                val encrypted = withContext(Dispatchers.Default) {
                    identityCoordinator.encryptAttachment(attachment)
                }
                try {
                    val blob = manager.uploadBlob(idempotencyKey(), encrypted.ciphertext)
                    MessageAttachment(
                        id = blob.id,
                        origin = storage.endpoint,
                        key = Base64.getEncoder().encodeToString(encrypted.key),
                        nonce = Base64.getEncoder().encodeToString(encrypted.nonce),
                        name = encrypted.name,
                        mime = encrypted.mime,
                        size = encrypted.size,
                        durationMs = encrypted.durationMs,
                    )
                } finally {
                    encrypted.ciphertext.fill(0)
                    encrypted.key.fill(0)
                    encrypted.nonce.fill(0)
                }
            }
            val envelopes = withContext(Dispatchers.Default) {
                identityCoordinator.sealMessage(
                    text.trim(),
                    resolved.recipients,
                    secret,
                    attachmentDescriptors,
                )
            }
            val batch = manager.sendMessageBatch(
                idempotencyKey(),
                MessageBatchRequest(
                    attachmentIds = attachmentDescriptors.map { it.id },
                    envelopes = envelopes,
                ),
            )
            val selfCopyId = batch.copies.firstOrNull { it.recipientId == account.id }?.publicId
            val message = InboxMessage(
                publicId = selfCopyId ?: "local-${System.nanoTime()}",
                senderHandle = "You",
                text = text.trim(),
                createdAt = java.time.Instant.now().toString(),
                recipientHandles = resolved.recipients.map { it.handle },
                sentByMe = true,
                attachments = attachmentDescriptors,
            )
            mutableState.update {
                it.copy(loading = false, messages = listOf(message) + it.messages)
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error)) }
        } finally {
            attachments.forEach { it.bytes.fill(0) }
            messageSendInFlight.set(false)
        }
    }

    fun savePrivateNote(subjectType: String, subjectId: String, body: String) = viewModelScope.launch {
        val manager = sessionManager ?: return@launch
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val note = manager.savePrivateNote(subjectType, subjectId, body)
            mutableState.update {
                it.copy(
                    loading = false,
                    contacts = it.contacts.map { contact ->
                        if (subjectType == "contact" && contact.id == note.subjectId) {
                            contact.copy(note = note.body)
                        } else {
                            contact
                        }
                    },
                    groups = it.groups.map { group ->
                        if (subjectType == "group" && group.id == note.subjectId) {
                            group.copy(note = note.body)
                        } else {
                            group
                        }
                    },
                )
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error)) }
        }
    }

    fun setDeliveryPolicy(subjectType: String, subjectId: String, acceptance: String?) =
        viewModelScope.launch {
            val manager = sessionManager ?: return@launch
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val policy = manager.setDeliveryPolicy(subjectType, subjectId, acceptance)
                mutableState.update {
                    it.copy(
                        loading = false,
                        deliveryPolicies = it.deliveryPolicies
                            .filterNot { item ->
                                item.subjectType == subjectType && item.subjectId == subjectId
                            }
                            .let { policies -> if (policy == null) policies else policies + policy },
                    )
                }
                loadInbox()
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, error = messageFor(error)) }
            }
        }

    fun openAttachment(attachment: MessageAttachment) = viewModelScope.launch {
        val manager = sessionManager ?: return@launch
        if (
            attachment.id in mutableState.value.openedAttachments ||
            attachment.id in mutableState.value.attachmentLoadingIds
        ) return@launch

        mutableState.update {
            it.copy(
                attachmentLoadingIds = it.attachmentLoadingIds + attachment.id,
                attachmentErrors = it.attachmentErrors - attachment.id,
            )
        }
        var ciphertext: ByteArray? = null
        try {
            val downloaded = manager.attachmentBlob(attachment.origin, attachment.id)
            ciphertext = downloaded
            val plaintext = withContext(Dispatchers.Default) {
                identityCoordinator.openAttachment(downloaded, attachment)
            } ?: throw IllegalArgumentException("The attachment could not be authenticated.")
            mutableState.update {
                it.copy(
                    openedAttachments = it.openedAttachments +
                        (attachment.id to OpenedAttachment(attachment, plaintext)),
                    attachmentLoadingIds = it.attachmentLoadingIds - attachment.id,
                )
            }
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    attachmentLoadingIds = it.attachmentLoadingIds - attachment.id,
                    attachmentErrors = it.attachmentErrors +
                        (attachment.id to (error.message ?: "The attachment could not be opened.")),
                )
            }
        } finally {
            ciphertext?.fill(0)
        }
    }

    private fun restoreSession() = viewModelScope.launch {
        val storedEndpoint = storage.endpoint
        if (storedEndpoint == null) {
            mutableState.value = AppUiState(loading = false)
            return@launch
        }

        try {
            val endpoint = parseEndpoint(storedEndpoint)
            val normalizedEndpoint = endpoint.uri.resolve("/").toString().trimEnd('/')
            if (storage.endpoint != normalizedEndpoint) storage.endpoint = normalizedEndpoint
            val manager = AuthSessionManager(apiFactory(endpoint), storage)
            sessionManager = manager
            if (!manager.hasSession()) {
                mutableState.value = AppUiState(AppScreen.LOGIN, normalizedEndpoint, loading = false)
                return@launch
            }
            val account = manager.currentAccount()
            showAccount(account)
        } catch (error: Exception) {
            mutableState.value = AppUiState(
                screen = AppScreen.LOGIN,
                endpoint = storedEndpoint,
                loading = false,
                error = messageFor(error),
            )
        }
    }

    private fun messageFor(error: Throwable, loginAttempt: Boolean = false): String = when (error) {
        is VeejrApiException -> when (error.statusCode) {
            401 -> if (loginAttempt) {
                "The username, email, or password was not accepted."
            } else {
                "Your session expired. Please sign in again."
            }
            else -> error.apiError.message
        }
        is IOException -> "The instance could not be reached. Check your connection and try again."
        is IllegalArgumentException -> error.message ?: "That instance URL is not valid."
        else -> "Something went wrong. Please try again."
    }

    private fun parseEndpoint(value: String): ApiEndpoint {
        val normalizedValue =
            if (BuildConfig.DEBUG && value.trim().trimEnd('/') == LEGACY_EMULATOR_ENDPOINT) {
                DEBUG_LOOPBACK_ENDPOINT
            } else {
                value
            }
        return ApiEndpoint.parse(normalizedValue, allowHttp = BuildConfig.DEBUG)
    }

    private fun showAccount(account: Account) {
        mutableState.value = AppUiState(
            screen = if (account.keysConfigured) AppScreen.KEY_UNLOCK else AppScreen.KEY_SETUP,
            endpoint = storage.endpoint.orEmpty(),
            account = account,
            loading = false,
        )
    }

    private fun clearIdentity() {
        identitySecret?.fill(0)
        identitySecret = null
    }

    private fun clearOpenedAttachments() {
        mutableState.value.openedAttachments.values.forEach { it.bytes.fill(0) }
    }

    private suspend fun loadInbox() {
        val manager = sessionManager ?: return
        val secret = identitySecret ?: return
        try {
            val notifications = manager.pendingNotifications()
            val contacts = manager.contacts()
            val groups = manager.groups()
            val policies = manager.messageDeliveryPolicies()
            val history = manager.messageHistory()
            val messages = openHistory(history.envelopes, secret)
            mutableState.update { state ->
                val refreshedIds = messages.mapTo(mutableSetOf(), InboxMessage::publicId)
                val newlyReceivedIds = if (state.historyLoaded) {
                    messages
                        .filter { !it.sentByMe && it.publicId !in state.messages.mapTo(mutableSetOf(), InboxMessage::publicId) }
                        .mapTo(mutableSetOf(), InboxMessage::publicId)
                } else {
                    emptySet()
                }
                val mergedMessages = if (state.historyLoaded) {
                    messages + state.messages.filterNot { it.publicId in refreshedIds }
                } else {
                    messages
                }
                state.copy(
                    loading = false,
                    notifications = notifications,
                    contacts = contacts,
                    groups = groups,
                    deliveryPolicies = policies,
                    messages = mergedMessages,
                    unreadMessageIds = state.unreadMessageIds + newlyReceivedIds,
                    newMessageIds = state.newMessageIds + newlyReceivedIds,
                    historyNextCursor = if (state.historyLoaded) {
                        state.historyNextCursor
                    } else {
                        history.nextCursor
                    },
                    historyLoaded = true,
                )
            }
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e("VeejrViewModel", "Inbox refresh failed: ${error.javaClass.simpleName}: ${error.message}")
            }
            mutableState.update { it.copy(loading = false, error = messageFor(error)) }
        }
    }

    private suspend fun openHistory(envelopes: List<Envelope>, secret: ByteArray): List<InboxMessage> =
        withContext(Dispatchers.Default) {
            envelopes.mapNotNull { envelope ->
                identityCoordinator.openMessagePayload(envelope, secret)?.let { opened ->
                    InboxMessage(
                        publicId = envelope.publicId,
                        senderHandle = if (envelope.sentByMe) "You" else envelope.sender.handle,
                        text = opened.text,
                        createdAt = envelope.createdAt,
                        recipientHandles = opened.recipientHandles,
                        sentByMe = envelope.sentByMe,
                        kind = opened.kind,
                        title = opened.title,
                        latitude = opened.latitude,
                        longitude = opened.longitude,
                        attachments = opened.attachments,
                    )
                }
            }
        }

    private fun idempotencyKey(): String = ByteArray(16)
        .also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    override fun onCleared() {
        clearOpenedAttachments()
        clearIdentity()
        super.onCleared()
    }

    class Factory(private val storage: AppSessionStorage) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            VeejrViewModel(storage) as T
    }

    private companion object {
        const val DEBUG_LOOPBACK_ENDPOINT = "http://127.0.0.1:4000"
        const val LEGACY_EMULATOR_ENDPOINT = "http://10.0.2.2:4000"
    }
}
