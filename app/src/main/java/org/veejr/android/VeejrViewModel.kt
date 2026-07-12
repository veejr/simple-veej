package org.veejr.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.veejr.core.network.Account
import org.veejr.core.network.ApiEndpoint
import org.veejr.core.network.AuthSessionManager
import org.veejr.core.network.DeviceInfo
import org.veejr.core.network.VeejrApi
import org.veejr.core.network.VeejrApiClient
import org.veejr.core.network.VeejrApiException

enum class AppScreen { INSTANCE, LOGIN, HOME }

data class AppUiState(
    val screen: AppScreen = AppScreen.INSTANCE,
    val endpoint: String = "",
    val account: Account? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class VeejrViewModel(
    private val storage: AppSessionStorage,
    private val apiFactory: (ApiEndpoint) -> VeejrApi = ::VeejrApiClient,
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

    init {
        restoreSession()
    }

    fun connect(endpointValue: String) = viewModelScope.launch {
        mutableState.update { it.copy(loading = true, error = null) }
        val endpoint = runCatching { ApiEndpoint.parse(endpointValue) }.getOrElse {
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
            mutableState.update {
                it.copy(screen = AppScreen.HOME, account = account, loading = false)
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = messageFor(error, loginAttempt = true)) }
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    fun logout() = viewModelScope.launch {
        mutableState.update { it.copy(loading = true, error = null) }
        runCatching { sessionManager?.logout() }
        mutableState.update {
            it.copy(screen = AppScreen.LOGIN, account = null, loading = false)
        }
    }

    fun changeInstance() = viewModelScope.launch {
        runCatching { sessionManager?.logout() }
        storage.clear()
        storage.endpoint = null
        sessionManager = null
        mutableState.value = AppUiState(loading = false)
    }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    private fun restoreSession() = viewModelScope.launch {
        val storedEndpoint = storage.endpoint
        if (storedEndpoint == null) {
            mutableState.value = AppUiState(loading = false)
            return@launch
        }

        try {
            val endpoint = ApiEndpoint.parse(storedEndpoint)
            val manager = AuthSessionManager(apiFactory(endpoint), storage)
            sessionManager = manager
            if (!manager.hasSession()) {
                mutableState.value = AppUiState(AppScreen.LOGIN, storedEndpoint, loading = false)
                return@launch
            }
            val account = manager.currentAccount()
            mutableState.value = AppUiState(
                screen = AppScreen.HOME,
                endpoint = storedEndpoint,
                account = account,
                loading = false,
            )
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

    class Factory(private val storage: AppSessionStorage) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            VeejrViewModel(storage) as T
    }
}
