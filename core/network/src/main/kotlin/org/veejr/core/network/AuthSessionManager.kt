package org.veejr.core.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SessionTokenStore {
    suspend fun load(): SessionTokens?
    suspend fun save(tokens: SessionTokens)
    suspend fun clear()
}

class NoActiveSessionException : IllegalStateException("No active veejr session")

class AuthSessionManager(
    private val api: VeejrApi,
    private val tokenStore: SessionTokenStore,
) {
    private val refreshMutex = Mutex()

    suspend fun login(email: String, password: CharArray, device: DeviceInfo): Account {
        val response = api.login(email, password, device)
        tokenStore.save(response.tokens)
        return response.account
    }

    suspend fun requestOneTimeLogin(identifier: String) = api.requestOneTimeLogin(identifier)

    suspend fun exchangeOneTimeLogin(token: String, device: DeviceInfo): Account {
        val response = api.exchangeOneTimeLogin(token, device)
        tokenStore.save(response.tokens)
        return response.account
    }

    suspend fun currentAccount(): Account =
        withAccessToken { accessToken -> api.me(accessToken).account }

    suspend fun setupKeys(request: KeySetupRequest): Account =
        withAccessToken { accessToken -> api.setupKeys(accessToken, request).account }

    suspend fun pendingNotifications(): List<PendingNotification> =
        withAccessToken { accessToken -> api.pendingNotifications(accessToken).notifications }

    suspend fun acceptNotification(id: String): Envelope =
        withAccessToken { accessToken -> api.acceptNotification(accessToken, id).envelope }

    suspend fun declineNotification(id: String) =
        withAccessToken { accessToken -> api.declineNotification(accessToken, id) }

    suspend fun contacts(): List<Recipient> =
        withAccessToken { accessToken -> api.contacts(accessToken).contacts }

    suspend fun groups(): List<ContactGroup> =
        withAccessToken { accessToken -> api.groups(accessToken).groups }

    suspend fun messageDeliveryPolicies(): List<MessageDeliveryPolicy> =
        withAccessToken { accessToken -> api.messageDeliveryPolicies(accessToken).policies }

    suspend fun savePrivateNote(subjectType: String, subjectId: String, body: String): PrivateNote =
        withAccessToken { accessToken ->
            api.putPrivateNote(
                accessToken,
                subjectType,
                subjectId,
                PrivateNoteRequest(body),
            ).note
        }

    suspend fun setDeliveryPolicy(
        subjectType: String,
        subjectId: String,
        acceptance: String?,
    ): MessageDeliveryPolicy? =
        withAccessToken { accessToken ->
            if (acceptance == null) {
                api.deleteMessageDeliveryPolicy(accessToken, subjectType, subjectId)
                null
            } else {
                api.putMessageDeliveryPolicy(
                    accessToken,
                    subjectType,
                    subjectId,
                    MessageDeliveryPolicyRequest(acceptance = acceptance),
                ).policy
            }
        }

    suspend fun resolveRecipients(subjectType: String, subjectId: String): ResolveRecipientsResponse =
        withAccessToken { accessToken ->
            api.resolveRecipients(
                accessToken,
                ResolveRecipientsRequest(
                    friendIds = if (subjectType == "contact") listOf(subjectId) else emptyList(),
                    groupIds = if (subjectType == "group") listOf(subjectId) else emptyList(),
                ),
            )
        }

    suspend fun sendMessageBatch(
        idempotencyKey: String,
        request: MessageBatchRequest,
    ): MessageBatchResponse = withAccessToken { accessToken ->
        api.sendMessageBatch(accessToken, idempotencyKey, request)
    }

    suspend fun messageHistory(cursor: String? = null, kind: String? = null): EnvelopePage =
        withAccessToken { accessToken -> api.messageHistory(accessToken, cursor, kind) }

    suspend fun attachmentBlob(origin: String?, id: String): ByteArray =
        api.attachmentBlob(origin, id)

    suspend fun uploadBlob(idempotencyKey: String, ciphertext: ByteArray): BlobUploadResponse =
        withAccessToken { accessToken -> api.uploadBlob(accessToken, idempotencyKey, ciphertext) }

    suspend fun hasSession(): Boolean = tokenStore.load() != null

    suspend fun logout() {
        val tokens = tokenStore.load()
        try {
            if (tokens != null) api.logout(tokens.accessToken)
        } finally {
            tokenStore.clear()
        }
    }

    private suspend fun <T> withAccessToken(operation: suspend (String) -> T): T {
        val initialTokens = tokenStore.load() ?: throw NoActiveSessionException()

        return try {
            operation(initialTokens.accessToken)
        } catch (error: VeejrApiException) {
            if (error.statusCode != UNAUTHORIZED) throw error
            val refreshedTokens = refreshAfterUnauthorized(initialTokens)
            operation(refreshedTokens.accessToken)
        }
    }

    private suspend fun refreshAfterUnauthorized(failedTokens: SessionTokens): SessionTokens =
        refreshMutex.withLock {
            val currentTokens = tokenStore.load() ?: throw NoActiveSessionException()
            if (currentTokens.accessToken != failedTokens.accessToken) return@withLock currentTokens

            try {
                api.refresh(currentTokens.refreshToken).tokens.also { tokenStore.save(it) }
            } catch (error: VeejrApiException) {
                if (error.statusCode == UNAUTHORIZED) tokenStore.clear()
                throw error
            }
        }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
