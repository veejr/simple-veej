package org.veejr.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Capabilities(
    @SerialName("api_versions") val apiVersions: List<Int>,
    @SerialName("payload_versions") val payloadVersions: List<Int>,
    @SerialName("max_blob_bytes") val maxBlobBytes: Long,
    @SerialName("message_kinds") val messageKinds: List<String>,
    @SerialName("instance_mode") val instanceMode: String,
    @SerialName("android_push") val androidPush: Boolean,
)

@Serializable
data class DeviceInfo(
    val name: String,
    val platform: String = "android",
    @SerialName("app_version") val appVersion: String,
)

@Serializable
class SessionTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_token_expires_at") val accessTokenExpiresAt: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("refresh_token_expires_at") val refreshTokenExpiresAt: String,
    @SerialName("device_session_id") val deviceSessionId: String,
) {
    override fun toString(): String = "SessionTokens(<redacted>)"
}

@Serializable
data class WrappedKeyKdf(
    val name: String,
    val iterations: Int,
)

@Serializable
class WrappedKey(
    val ciphertext: String,
    val salt: String,
    val nonce: String,
    val kdf: WrappedKeyKdf,
    val wrap: String,
) {
    override fun toString(): String = "WrappedKey(<redacted>)"
}

@Serializable
data class Account(
    val id: String,
    val email: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    val handle: String,
    val confirmed: Boolean,
    @SerialName("keys_configured") val keysConfigured: Boolean,
    @SerialName("public_key") val publicKey: String? = null,
    @SerialName("wrapped_key") val wrappedKey: WrappedKey? = null,
)

@Serializable
data class LoginResponse(
    val account: Account,
    val tokens: SessionTokens,
)

@Serializable
data class RefreshResponse(val tokens: SessionTokens)

@Serializable
data class AccountResponse(val account: Account)

@Serializable
data class KeySetupRequest(
    @SerialName("public_key") val publicKey: String,
    @SerialName("wrapped_key") val wrappedKey: WrappedKey,
)

@Serializable
data class SenderSummary(val id: String, val handle: String)

@Serializable
data class PendingNotification(
    val id: String,
    val kind: String,
    val sender: SenderSummary,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("max_displays") val maxDisplays: Int? = null,
)

@Serializable
data class NotificationsResponse(val notifications: List<PendingNotification>)

@Serializable
data class Envelope(
    @SerialName("public_id") val publicId: String,
    @SerialName("batch_id") val batchId: String,
    val kind: String,
    val ciphertext: String,
    val nonce: String,
    @SerialName("peer_key") val peerKey: String,
    val sender: SenderSummary,
    @SerialName("sent_by_me") val sentByMe: Boolean,
    val resealed: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("max_displays") val maxDisplays: Int? = null,
    @SerialName("display_count") val displayCount: Int,
)

@Serializable
data class EnvelopeResponse(val envelope: Envelope)

@Serializable
data class Recipient(
    val id: String,
    val username: String,
    val handle: String,
    @SerialName("public_key") val publicKey: String,
)

@Serializable
data class ContactsResponse(val contacts: List<Recipient>)

@Serializable
data class ResolveRecipientsRequest(
    @SerialName("friend_ids") val friendIds: List<String>,
    @SerialName("group_ids") val groupIds: List<String> = emptyList(),
    @SerialName("include_self") val includeSelf: Boolean = true,
)

@Serializable
data class ResolveRecipientsResponse(
    val recipients: List<Recipient>,
    @SerialName("missing_keys") val missingKeys: List<String>,
)

@Serializable
data class MessageEnvelopeRequest(
    @SerialName("recipient_id") val recipientId: String,
    val ciphertext: String,
    val nonce: String,
)

@Serializable
data class MessageBatchRequest(
    val kind: String = "message",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("max_displays") val maxDisplays: Int? = null,
    val envelopes: List<MessageEnvelopeRequest>,
)

@Serializable
data class MessageCopy(
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("public_id") val publicId: String,
)

@Serializable
data class MessageBatchResponse(
    @SerialName("batch_id") val batchId: String,
    val copies: List<MessageCopy>,
    @SerialName("queued_recipients") val queuedRecipients: List<String>,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    @SerialName("request_id") val requestId: String? = null,
    val details: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class ApiErrorEnvelope(val error: ApiError)

class VeejrApiException(
    val statusCode: Int,
    val apiError: ApiError,
) : Exception("veejr API error ${apiError.code} ($statusCode)")
