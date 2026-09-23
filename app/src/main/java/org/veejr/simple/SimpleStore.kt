package org.veejr.simple

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.veejr.core.network.SessionTokenStore
import org.veejr.core.network.SessionTokens

/** The one friend this app calls. */
data class MyPerson(
    val id: String,
    val handle: String,
    val name: String,
    val publicKey: String,
)

/**
 * Everything simple-veej remembers between launches.
 *
 * Session tokens and the identity secret are sealed with a non-exportable
 * Android Keystore AES-GCM key. The identity secret is the device-local copy
 * client protocol v1 permits (section 8): it lets the app seal call signaling
 * without asking for the passphrase on every call. The portable wrapped key on
 * the server is never altered.
 */
class SimpleStore(context: Context) : SessionTokenStore {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var endpoint: String?
        get() = preferences.getString(ENDPOINT_KEY, null)
        set(value) = preferences.edit().run {
            if (value == null) remove(ENDPOINT_KEY) else putString(ENDPOINT_KEY, value)
        }.apply()

    var myUserId: String?
        get() = preferences.getString(USER_ID_KEY, null)
        set(value) = preferences.edit().run {
            if (value == null) remove(USER_ID_KEY) else putString(USER_ID_KEY, value)
        }.apply()

    var myPerson: MyPerson?
        get() = preferences.getString(PERSON_KEY, null)?.let { encoded ->
            runCatching {
                JSONObject(encoded).run {
                    MyPerson(
                        id = getString("id"),
                        handle = getString("handle"),
                        name = getString("name"),
                        publicKey = getString("public_key"),
                    )
                }
            }.getOrNull()
        }
        set(value) = preferences.edit().run {
            if (value == null) {
                remove(PERSON_KEY)
            } else {
                putString(
                    PERSON_KEY,
                    JSONObject()
                        .put("id", value.id)
                        .put("handle", value.handle)
                        .put("name", value.name)
                        .put("public_key", value.publicKey)
                        .toString(),
                )
            }
        }.apply()

    /** Returns a fresh copy of the identity secret; the caller zeroes it. */
    fun identitySecret(): ByteArray? =
        preferences.getString(IDENTITY_KEY, null)?.let { encoded ->
            runCatching { decrypt(Base64.getDecoder().decode(encoded)) }.getOrNull()
        }

    fun saveIdentitySecret(secret: ByteArray) {
        preferences.edit()
            .putString(IDENTITY_KEY, Base64.getEncoder().encodeToString(encrypt(secret)))
            .apply()
    }

    val isSetUp: Boolean
        get() = endpoint != null && myUserId != null && myPerson != null &&
            preferences.contains(IDENTITY_KEY) && preferences.contains(TOKENS_KEY)

    /** Forgets everything, as if freshly installed. */
    fun reset() {
        preferences.edit().clear().apply()
    }

    override suspend fun load(): SessionTokens? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(TOKENS_KEY, null) ?: return@withContext null
        runCatching { decodeTokens(decrypt(Base64.getDecoder().decode(encoded))) }.getOrNull()
    }

    override suspend fun save(tokens: SessionTokens) = withContext(Dispatchers.IO) {
        preferences.edit()
            .putString(TOKENS_KEY, Base64.getEncoder().encodeToString(encrypt(encodeTokens(tokens))))
            .apply()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        preferences.edit().remove(TOKENS_KEY).apply()
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, encrypted.copyOfRange(0, IV_BYTES)),
        )
        return cipher.doFinal(encrypted.copyOfRange(IV_BYTES, encrypted.size))
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encodeTokens(tokens: SessionTokens): ByteArray = JSONObject()
        .put("access_token", tokens.accessToken)
        .put("access_token_expires_at", tokens.accessTokenExpiresAt)
        .put("refresh_token", tokens.refreshToken)
        .put("refresh_token_expires_at", tokens.refreshTokenExpiresAt)
        .put("device_session_id", tokens.deviceSessionId)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun decodeTokens(bytes: ByteArray): SessionTokens =
        JSONObject(bytes.toString(Charsets.UTF_8)).run {
            SessionTokens(
                accessToken = getString("access_token"),
                accessTokenExpiresAt = getString("access_token_expires_at"),
                refreshToken = getString("refresh_token"),
                refreshTokenExpiresAt = getString("refresh_token_expires_at"),
                deviceSessionId = getString("device_session_id"),
            )
        }

    private companion object {
        const val PREFERENCES_NAME = "simple_veej"
        const val ENDPOINT_KEY = "endpoint"
        const val USER_ID_KEY = "user_id"
        const val PERSON_KEY = "my_person"
        const val IDENTITY_KEY = "identity"
        const val TOKENS_KEY = "tokens"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "simpleveej.store.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
