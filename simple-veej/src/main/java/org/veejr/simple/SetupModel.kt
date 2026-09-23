package org.veejr.simple

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.veejr.core.crypto.CryptoBoundary
import org.veejr.core.crypto.VeejrCrypto
import org.veejr.core.network.Account
import org.veejr.core.network.ApiEndpoint
import org.veejr.core.network.Recipient
import org.veejr.core.network.VeejrApiException
import org.veejr.core.network.WrappedKey

sealed interface SetupStep {
    data object SignIn : SetupStep
    data class Unlock(val account: Account) : SetupStep
    data class ChoosePerson(val friends: List<Recipient>) : SetupStep
    data object Done : SetupStep
}

/**
 * The one-time setup: sign in, unlock the identity key once, pick the one
 * person this phone calls. Afterwards the app never asks for anything again.
 */
class SetupModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SimpleVeejApp
    private val crypto = VeejrCrypto()

    private val mutableStep = MutableStateFlow<SetupStep>(
        if (app.store.isSetUp) SetupStep.Done else SetupStep.SignIn,
    )
    val step: StateFlow<SetupStep> = mutableStep.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    val defaultServer: String = BuildConfig.DEFAULT_INSTANCE_URL

    fun signIn(server: String, email: String, password: CharArray) = work {
        val endpoint = runCatching { ApiEndpoint.parse(server, allowHttp = BuildConfig.ALLOW_HTTP) }
            .getOrElse { throw SetupError("That server address is not valid.") }
        app.store.reset()
        app.store.endpoint = endpoint.uri.resolve("/").toString().trimEnd('/')

        val account = try {
            app.sessions()!!.login(email.trim(), password, app.device)
        } catch (error: VeejrApiException) {
            throw SetupError(
                if (error.statusCode == 401) "Email or password is not right." else "Could not sign in.",
            )
        } finally {
            password.fill('\u0000')
        }

        if (!account.keysConfigured || account.publicKey == null || account.wrappedKey == null) {
            throw SetupError("Open veejr once to set up your encryption passphrase, then try again.")
        }
        app.store.myUserId = account.id
        mutableStep.value = SetupStep.Unlock(account)
    }

    fun unlock(account: Account, passphrase: CharArray) = work {
        val secret = withContext(Dispatchers.Default) {
            try {
                unwrap(account.publicKey!!, account.wrappedKey!!, passphrase)
            } finally {
                passphrase.fill('\u0000')
            }
        } ?: throw SetupError("That passphrase did not unlock your key.")

        try {
            app.store.saveIdentitySecret(secret)
        } finally {
            secret.fill(0)
        }

        val friends = app.sessions()!!.contacts().sortedBy { it.handle.lowercase() }
        if (friends.isEmpty()) throw SetupError("You have no veejr friends yet. Add one first.")
        mutableStep.value = SetupStep.ChoosePerson(friends)
    }

    fun choose(friend: Recipient, displayName: String) {
        app.store.myPerson = MyPerson(
            id = friend.id,
            handle = friend.handle,
            name = displayName.trim().ifEmpty { friend.username },
            publicKey = friend.publicKey,
        )
        app.calls.start()
        app.refreshPushToken()
        mutableStep.value = SetupStep.Done
    }

    fun startOver() = viewModelScope.launch {
        app.forget()
        mutableError.value = null
        mutableStep.value = SetupStep.SignIn
    }

    private fun work(block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        mutableError.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (error: SetupError) {
                mutableError.value = error.message
            } catch (error: Exception) {
                mutableError.value = "Something went wrong. Check the connection and try again."
            } finally {
                mutableBusy.value = false
            }
        }
    }

    // Same checks as the veejr app's IdentityCoordinator.unlock: only the
    // protocol-v1 wrapping is accepted, and the unwrapped secret must match
    // the account's published public key.
    private fun unwrap(publicKey: String, wrappedKey: WrappedKey, passphrase: CharArray): ByteArray? {
        if (
            wrappedKey.kdf.name != "PBKDF2-SHA256" ||
            wrappedKey.kdf.iterations != CryptoBoundary.PBKDF2_ITERATIONS ||
            wrappedKey.wrap != "XSalsa20-Poly1305"
        ) return null

        val decoder = Base64.getDecoder()
        val wrappingKey = crypto.deriveWrappingKey(passphrase, decoder.decode(wrappedKey.salt))
        return try {
            val secret = crypto.unwrapSecretKey(
                decoder.decode(wrappedKey.ciphertext),
                decoder.decode(wrappedKey.nonce),
                wrappingKey,
            ) ?: return null
            if (MessageDigest.isEqual(crypto.publicKeyFromSecret(secret), decoder.decode(publicKey))) {
                secret
            } else {
                secret.fill(0)
                null
            }
        } finally {
            wrappingKey.fill(0)
        }
    }

    private class SetupError(message: String) : Exception(message)
}
