package org.veejr.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
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
import org.veejr.core.network.PendingNotification
import org.veejr.core.network.VeejrApi
import org.veejr.core.network.VeejrApiClient
import org.veejr.core.network.VeejrApiException

enum class AppScreen { INSTANCE, LOGIN, KEY_SETUP, KEY_UNLOCK, HOME }

data class AppUiState(
    val screen: AppScreen = AppScreen.INSTANCE,
    val endpoint: String = "",
    val account: Account? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val notifications: List<PendingNotification> = emptyList(),
    val messages: List<InboxMessage> = emptyList(),
)

data class InboxMessage(
    val publicId: String,
    val senderHandle: String,
    val text: String,
    val createdAt: String,
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
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var sessionManager: AuthSessionManager? = null
    private var identitySecret: ByteArray? = null

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

    fun login(email: String, password: String) = viewModelScope.launch {
        val manager = sessionManager ?: return@launch
        mutableState.update { it.copy(loading = true, error = null) }
        val passwordChars = password.toCharArray()
        try {
            val account = manager.login(
                email = email.trim(),
                password = passwordChars,
                device = deviceInfo(),
            )
            showAccount(account)
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error, loginAttempt = true)) }
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    fun logout() = viewModelScope.launch {
        mutableState.update { it.copy(loading = true, error = null) }
        runCatching { sessionManager?.logout() }
        clearIdentity()
        mutableState.update {
            it.copy(
                screen = AppScreen.LOGIN,
                account = null,
                loading = false,
                notifications = emptyList(),
                messages = emptyList(),
            )
        }
    }

    fun changeInstance() = viewModelScope.launch {
        runCatching { sessionManager?.logout() }
        storage.clear()
        storage.endpoint = null
        sessionManager = null
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

    fun acceptNotification(id: String) = viewModelScope.launch {
        val manager = sessionManager ?: return@launch
        val secret = identitySecret ?: return@launch
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val envelope = manager.acceptNotification(id)
            val text = withContext(Dispatchers.Default) {
                identityCoordinator.openMessage(envelope, secret)
            }
            if (text == null) {
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
                    text = text,
                    createdAt = envelope.createdAt,
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

    private fun restoreSession() = viewModelScope.launch {
        val storedEndpoint = storage.endpoint
        if (storedEndpoint == null) {
            mutableState.value = AppUiState(loading = false)
            return@launch
        }

        try {
            val endpoint = parseEndpoint(storedEndpoint)
            val manager = AuthSessionManager(apiFactory(endpoint), storage)
            sessionManager = manager
            if (!manager.hasSession()) {
                mutableState.value = AppUiState(AppScreen.LOGIN, storedEndpoint, loading = false)
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
                "The email or password was not accepted."
            } else {
                "Your session expired. Please sign in again."
            }
            else -> error.apiError.message
        }
        is IOException -> "The instance could not be reached. Check your connection and try again."
        is IllegalArgumentException -> error.message ?: "That instance URL is not valid."
        else -> "Something went wrong. Please try again."
    }

    private fun parseEndpoint(value: String): ApiEndpoint =
        ApiEndpoint.parse(value, allowHttp = BuildConfig.DEBUG)

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

    private suspend fun loadInbox() {
        val manager = sessionManager ?: return
        try {
            val notifications = manager.pendingNotifications()
            mutableState.update { it.copy(loading = false, notifications = notifications) }
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error)) }
        }
    }

    override fun onCleared() {
        clearIdentity()
        super.onCleared()
    }

    class Factory(private val storage: AppSessionStorage) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            VeejrViewModel(storage) as T
    }
}
