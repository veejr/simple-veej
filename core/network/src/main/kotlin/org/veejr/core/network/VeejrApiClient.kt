package org.veejr.core.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    suspend fun attachmentBlob(origin: String?, id: String): ByteArray =
        throw UnsupportedOperationException("Attachment downloads are not available")
    suspend fun uploadBlob(
        accessToken: String,
        idempotencyKey: String,
        ciphertext: ByteArray,
    ): BlobUploadResponse =
        throw UnsupportedOperationException("Attachment uploads are not available")
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
    private val instanceRoot = endpoint.uri.resolve("/").toString().toHttpUrl()
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

    override suspend fun attachmentBlob(origin: String?, id: String): ByteArray {
        require(BLOB_ID.matches(id)) { "The attachment identifier is not valid." }
        val requestedRoot = origin?.trim()?.trimEnd('/')?.toHttpUrl() ?: instanceRoot
        val root = if (sameLocalDevelopmentInstance(requestedRoot)) instanceRoot else requestedRoot
        require(root.encodedPath == "/" && root.query == null && root.fragment == null) {
            "The attachment origin is not valid."
        }
        require(root.username.isEmpty() && root.password.isEmpty()) {
            "The attachment origin cannot include credentials."
        }
        require(root.scheme == "https" || instanceRoot.scheme == "http" && root.scheme == "http") {
            "The attachment origin must use HTTPS."
        }

        return try {
            executeBytes(attachmentRequest(root, id))
        } catch (error: AttachmentHttpException) {
            if (error.statusCode == 404 && shouldRetryMissingLocalBlob(requestedRoot, root)) {
                executeBytes(attachmentRequest(instanceRoot, id))
            } else {
                throw error
            }
        }
    }

    override suspend fun uploadBlob(
        accessToken: String,
        idempotencyKey: String,
        ciphertext: ByteArray,
    ): BlobUploadResponse {
        require(ciphertext.size <= MAX_ENCRYPTED_BLOB_BYTES) { "The attachment is too large" }
        require(idempotencyKey.length in 22..200) { "The idempotency key is not valid" }
        val request = request("blobs", accessToken)
            .header("Idempotency-Key", idempotencyKey)
            .post(ciphertext.toRequestBody(OCTET_STREAM_MEDIA_TYPE))
            .build()
        return execute(request)
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

    private fun sameLocalDevelopmentInstance(candidate: okhttp3.HttpUrl): Boolean =
        candidate.scheme == "http" && instanceRoot.scheme == "http" &&
            candidate.port == instanceRoot.port &&
            candidate.host in LOCAL_DEVELOPMENT_HOSTS && instanceRoot.host in LOCAL_DEVELOPMENT_HOSTS

    private fun shouldRetryMissingLocalBlob(
        requestedRoot: okhttp3.HttpUrl,
        resolvedRoot: okhttp3.HttpUrl,
    ): Boolean =
        resolvedRoot != instanceRoot &&
            requestedRoot.scheme == "http" &&
            instanceRoot.scheme == "http" &&
            requestedRoot.host in LOCAL_DEVELOPMENT_HOSTS &&
            instanceRoot.host in LOCAL_DEVELOPMENT_HOSTS

    private fun attachmentRequest(root: okhttp3.HttpUrl, id: String): Request {
        val url = root.newBuilder()
            .encodedPath("/api/blobs")
            .addPathSegment(id)
            .build()

        return Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .get()
            .build()
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

    private suspend fun executeBytes(request: Request): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw AttachmentHttpException(response.code)
            }
            val declaredSize = response.body.contentLength()
            if (declaredSize > MAX_ENCRYPTED_BLOB_BYTES) {
                throw IOException("The attachment is too large")
            }
            response.body.bytes().also { bytes ->
                if (bytes.size > MAX_ENCRYPTED_BLOB_BYTES) {
                    bytes.fill(0)
                    throw IOException("The attachment is too large")
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

    private class AttachmentHttpException(val statusCode: Int) : IOException(
        "Attachment download failed with HTTP $statusCode",
    )

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
        val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
        val EMPTY_JSON_BODY = "{}".toRequestBody(JSON_MEDIA_TYPE)
        val BLOB_ID = Regex("[A-Za-z0-9_-]{16,200}")
        const val MAX_ENCRYPTED_BLOB_BYTES = 25 * 1024 * 1024 + 16
        val LOCAL_DEVELOPMENT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
    }
}
