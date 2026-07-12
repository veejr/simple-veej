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
    suspend fun setupKeys(accessToken: String, request: KeySetupRequest): AccountResponse
    suspend fun pendingNotifications(accessToken: String): NotificationsResponse
    suspend fun acceptNotification(accessToken: String, id: String): EnvelopeResponse
    suspend fun declineNotification(accessToken: String, id: String)
    suspend fun contacts(accessToken: String): ContactsResponse
    suspend fun resolveRecipients(
        accessToken: String,
        request: ResolveRecipientsRequest,
    ): ResolveRecipientsResponse
    suspend fun sendMessageBatch(
        accessToken: String,
        idempotencyKey: String,
        request: MessageBatchRequest,
    ): MessageBatchResponse
    suspend fun logout(accessToken: String)
}

class VeejrApiClient(
    endpoint: ApiEndpoint,
    baseClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
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

    override suspend fun setupKeys(
        accessToken: String,
        request: KeySetupRequest,
    ): AccountResponse = put("keys", request, accessToken)

    override suspend fun pendingNotifications(accessToken: String): NotificationsResponse =
        get("notifications?state=pending", accessToken)

    override suspend fun acceptNotification(accessToken: String, id: String): EnvelopeResponse =
        postAuthenticated("notifications/$id/accept", accessToken)

    override suspend fun declineNotification(accessToken: String, id: String) {
        val request = request("notifications/$id/decline", accessToken)
            .post(EMPTY_JSON_BODY)
            .build()
        executeNoContent(request)
    }

    override suspend fun contacts(accessToken: String): ContactsResponse = get("contacts", accessToken)

    override suspend fun resolveRecipients(
        accessToken: String,
        request: ResolveRecipientsRequest,
    ): ResolveRecipientsResponse = postAuthenticated("recipients/resolve", request, accessToken)

    override suspend fun sendMessageBatch(
        accessToken: String,
        idempotencyKey: String,
        request: MessageBatchRequest,
    ): MessageBatchResponse {
        val httpRequest = request("message-batches", accessToken)
            .header("Idempotency-Key", idempotencyKey)
            .post(json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(httpRequest)
    }

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

    private suspend inline fun <reified RequestType, reified ResponseType> put(
        path: String,
        body: RequestType,
        accessToken: String,
    ): ResponseType {
        val request = request(path, accessToken)
            .put(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    private suspend inline fun <reified ResponseType> postAuthenticated(
        path: String,
        accessToken: String,
    ): ResponseType {
        val request = request(path, accessToken).post(EMPTY_JSON_BODY).build()
        return execute(request)
    }

    private suspend inline fun <reified RequestType, reified ResponseType> postAuthenticated(
        path: String,
        body: RequestType,
        accessToken: String,
    ): ResponseType {
        val request = request(path, accessToken)
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
        val EMPTY_JSON_BODY = "{}".toRequestBody(JSON_MEDIA_TYPE)
    }
}
