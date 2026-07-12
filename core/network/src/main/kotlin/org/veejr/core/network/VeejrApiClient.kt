package org.veejr.core.network

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync

interface VeejrApi {
    suspend fun capabilities(): Capabilities
    suspend fun login(email: String, password: CharArray, device: DeviceInfo): LoginResponse
    suspend fun refresh(refreshToken: String): RefreshResponse
    suspend fun me(accessToken: String): AccountResponse
    suspend fun logout(accessToken: String)
}

class VeejrApiClient(
    endpoint: ApiEndpoint,
    baseClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : VeejrApi {
    private val baseUrl = endpoint.uri.toString()
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun capabilities(): Capabilities = get("capabilities")

    override suspend fun login(email: String, password: CharArray, device: DeviceInfo): LoginResponse {
        val request = LoginRequest(email, password.concatToString(), device)
        return try {
            post("auth/login", request)
        } finally {
            request.clearPassword()
        }
    }

    override suspend fun refresh(refreshToken: String): RefreshResponse =
        post("auth/refresh", RefreshRequest(refreshToken))

    override suspend fun me(accessToken: String): AccountResponse = get("me", accessToken)

    override suspend fun logout(accessToken: String) {
        val request = request("auth/session", accessToken)
            .delete()
            .build()
        executeNoContent(request)
    }

    private suspend inline fun <reified T> get(path: String, accessToken: String? = null): T {
        val request = request(path, accessToken).get().build()
        return execute(request)
    }

    private suspend inline fun <reified RequestType, reified ResponseType> post(
        path: String,
        body: RequestType,
    ): ResponseType {
        val request = request(path)
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    private fun request(path: String, accessToken: String? = null): Request.Builder {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")

        if (accessToken != null) builder.header("Authorization", "Bearer $accessToken")
        return builder
    }

    private suspend inline fun <reified T> execute(request: Request): T {
        client.newCall(request).executeAsync().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw decodeError(response.code, body)
            return json.decodeFromString(body)
        }
    }

    private suspend fun executeNoContent(request: Request) {
        client.newCall(request).executeAsync().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw decodeError(response.code, body)
            if (response.code != 204) throw IOException("Expected HTTP 204, received ${response.code}")
        }
    }

    private fun decodeError(statusCode: Int, body: String): VeejrApiException {
        val error = runCatching { json.decodeFromString<ApiErrorEnvelope>(body).error }
            .getOrElse {
                ApiError(
                    code = "invalid_server_response",
                    message = "The server returned an unreadable error response.",
                )
            }
        return VeejrApiException(statusCode, error)
    }

    @Serializable
    private class LoginRequest(
        val email: String,
        private var password: String,
        val device: DeviceInfo,
    ) {
        fun clearPassword() {
            password = ""
        }

        override fun toString(): String = "LoginRequest(email=$email, password=<redacted>, device=$device)"
    }

    @Serializable
    private class RefreshRequest(
        @SerialName("refresh_token") private val refreshToken: String,
    ) {
        override fun toString(): String = "RefreshRequest(<redacted>)"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
