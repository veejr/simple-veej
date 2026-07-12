package org.veejr.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.veejr.core.network.SessionTokenStore
import org.veejr.core.network.SessionTokens

interface AppSessionStorage : SessionTokenStore {
    var endpoint: String?
}

class SessionVault(context: Context) : AppSessionStorage {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override var endpoint: String?
        get() = preferences.getString(ENDPOINT_KEY, null)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(ENDPOINT_KEY) else putString(ENDPOINT_KEY, value)
            }.apply()
        }

    override suspend fun load(): SessionTokens? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(TOKENS_KEY, null) ?: return@withContext null
        runCatching { decode(decrypt(Base64.decode(encoded, Base64.NO_WRAP))) }
            .getOrElse {
                preferences.edit().remove(TOKENS_KEY).apply()
                null
            }
    }

    override suspend fun save(tokens: SessionTokens) = withContext(Dispatchers.IO) {
        val encrypted = encrypt(encode(tokens))
        preferences.edit()
            .putString(TOKENS_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
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

    private fun encode(tokens: SessionTokens): ByteArray = JSONObject()
        .put("access_token", tokens.accessToken)
        .put("access_token_expires_at", tokens.accessTokenExpiresAt)
        .put("refresh_token", tokens.refreshToken)
        .put("refresh_token_expires_at", tokens.refreshTokenExpiresAt)
        .put("device_session_id", tokens.deviceSessionId)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): SessionTokens = JSONObject(bytes.toString(Charsets.UTF_8)).run {
        SessionTokens(
            accessToken = getString("access_token"),
            accessTokenExpiresAt = getString("access_token_expires_at"),
            refreshToken = getString("refresh_token"),
            refreshTokenExpiresAt = getString("refresh_token_expires_at"),
            deviceSessionId = getString("device_session_id"),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "secure_session"
        const val ENDPOINT_KEY = "endpoint"
        const val TOKENS_KEY = "tokens"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "veejr.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
