package org.veejr.core.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    suspend fun groups(accessToken: String): GroupsResponse
    suspend fun messageDeliveryPolicies(accessToken: String): MessageDeliveryPoliciesResponse
    suspend fun putPrivateNote(
        accessToken: String,
        subjectType: String,
        subjectId: String,
        request: PrivateNoteRequest,
    ): PrivateNoteResponse
    suspend fun putMessageDeliveryPolicy(
        accessToken: String,
        subjectType: String,
        subjectId: String,
        request: MessageDeliveryPolicyRequest,
    ): MessageDeliveryPolicyResponse
    suspend fun deleteMessageDeliveryPolicy(
        accessToken: String,
        subjectType: String,
        subjectId: String,
    )
    suspend fun resolveRecipients(
        accessToken: String,
        request: ResolveRecipientsRequest,
    ): ResolveRecipientsResponse
    suspend fun sendMessageBatch(
        accessToken: String,
        idempotencyKey: String,
        request: MessageBatchRequest,
    ): MessageBatchResponse
    suspend fun messageHistory(
        accessToken: String,
        cursor: String? = null,
        kind: String? = null,
    ): EnvelopePage
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

    override suspend fun groups(accessToken: String): GroupsResponse = get("groups", accessToken)

    override suspend fun messageDeliveryPolicies(
        accessToken: String,
    ): MessageDeliveryPoliciesResponse = get("message-delivery-policies", accessToken)

    override suspend fun putPrivateNote(
        accessToken: String,
        subjectType: String,
        subjectId: String,
        request: PrivateNoteRequest,
    ): PrivateNoteResponse = put(
        "${policyPath(subjectType)}/$subjectId/note",
        request,
        accessToken,
    )

    override suspend fun putMessageDeliveryPolicy(
        accessToken: String,
        subjectType: String,
        subjectId: String,
        request: MessageDeliveryPolicyRequest,
    ): MessageDeliveryPolicyResponse = put(
        "${policyPath(subjectType)}/$subjectId/message-delivery-policy",
        request,
        accessToken,
    )

    override suspend fun deleteMessageDeliveryPolicy(
        accessToken: String,
        subjectType: String,
        subjectId: String,
    ) {
        val request = request("${policyPath(subjectType)}/$subjectId/message-delivery-policy", accessToken)
            .delete()
            .build()
        executeNoContent(request)
    }

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

    override suspend fun messageHistory(
        accessToken: String,
        cursor: String?,
        kind: String?,
    ): EnvelopePage {
        val path = buildString {
            append("envelopes")
            val params = buildList {
                if (kind != null) add("kind=${java.net.URLEncoder.encode(kind, "UTF-8")}")
                if (cursor != null) add("cursor=${java.net.URLEncoder.encode(cursor, "UTF-8")}")
            }
            if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
        }
        return get(path, accessToken)
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

    private fun policyPath(subjectType: String): String = when (subjectType) {
        "contact" -> "contacts"
        "group" -> "groups"
        "conversation" -> "conversations"
        else -> throw IllegalArgumentException("Unsupported delivery policy subject")
    }

    private suspend inline fun <reified T> execute(request: Request): T {
        return withContext(Dispatchers.IO) {
            client.newCall(request).executeAsync().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw decodeError(response.code, body)
                json.decodeFromString(body)
            }
        }
    }

    private suspend fun executeNoContent(request: Request) {
        withContext(Dispatchers.IO) {
            client.newCall(request).executeAsync().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw decodeError(response.code, body)
                if (response.code != 204) {
                    throw IOException("Expected HTTP 204, received ${response.code}")
                }
            }
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
