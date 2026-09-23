package org.veejr.simple

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** One Phoenix Channels V2 frame: `[join_ref, ref, topic, event, payload]`. */
data class PhoenixFrame(
    val joinRef: String?,
    val ref: String?,
    val topic: String,
    val event: String,
    val payload: JsonObject,
)

object PhoenixFrames {
    fun encode(frame: PhoenixFrame): String = JsonArray(
        listOf(
            frame.joinRef?.let(::JsonPrimitive) ?: JsonNull,
            frame.ref?.let(::JsonPrimitive) ?: JsonNull,
            JsonPrimitive(frame.topic),
            JsonPrimitive(frame.event),
            frame.payload,
        ),
    ).toString()

    fun decode(text: String): PhoenixFrame? = runCatching {
        val parts = Json.parseToJsonElement(text).jsonArray
        PhoenixFrame(
            joinRef = parts[0].nullableString(),
            ref = parts[1].nullableString(),
            topic = parts[2].jsonPrimitive.content,
            event = parts[3].jsonPrimitive.content,
            payload = parts[4] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }.getOrNull()

    /** The websocket URL for an instance's `/api/v1/` endpoint URI. */
    fun socketUrl(apiBase: String, accessToken: String): String {
        val http = apiBase.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("socket/websocket")
            .addQueryParameter("vsn", "2.0.0")
            .addQueryParameter("access_token", accessToken)
            .build()
            .toString()
        return http.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
    }

    private fun JsonElement.nullableString(): String? =
        if (this is JsonNull) null else jsonPrimitive.contentOrNull
}

/** A reply to a pushed event. `response` is the server's payload. */
data class PhoenixReply(val ok: Boolean, val response: JsonObject) {
    val reason: String?
        get() = response["reason"]?.jsonPrimitive?.contentOrNull
}

/**
 * A single-topic Phoenix Channels client that keeps itself connected.
 *
 * It (re)connects with backoff, joins [topic] on every connection, and
 * surfaces the join reply through [joined] so callers know when pushes will
 * be accepted. Server pushes on the topic arrive on [events].
 */
class PhoenixSocket(
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val topic: String,
    /** Returns the websocket URL with a currently valid token, or null to stop. */
    private val connectUrl: suspend (refreshFirst: Boolean) -> String?,
) {
    private val refs = AtomicLong(0)
    private val pending = mutableMapOf<String, CompletableDeferred<PhoenixReply>>()
    private val mutableEvents = MutableSharedFlow<PhoenixFrame>(extraBufferCapacity = 64)
    private val mutableJoined = MutableStateFlow<JsonObject?>(null)

    val events: SharedFlow<PhoenixFrame> = mutableEvents.asSharedFlow()

    /** The join reply while joined, else null. */
    val joined: StateFlow<JsonObject?> = mutableJoined.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var joinRef: String? = null
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        mutableJoined.value = null
        failPending()
    }

    /** Pushes an event and waits for its reply; null on timeout or disconnect. */
    suspend fun push(event: String, payload: JsonObject, timeoutMs: Long = 10_000): PhoenixReply? {
        val ws = socket ?: return null
        val ref = nextRef()
        val deferred = CompletableDeferred<PhoenixReply>()
        synchronized(pending) { pending[ref] = deferred }
        ws.send(PhoenixFrames.encode(PhoenixFrame(joinRef, ref, topic, event, payload)))
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            .also { synchronized(pending) { pending.remove(ref) } }
    }

    private suspend fun runLoop() {
        var attempt = 0
        var refreshFirst = false

        while (scope.isActive) {
            val url = connectUrl(refreshFirst) ?: return
            val closed = CompletableDeferred<Boolean>()
            val ws = client.newWebSocket(Request.Builder().url(url).build(), listener(closed))
            socket = ws

            val heartbeat = scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    ws.send(
                        PhoenixFrames.encode(
                            PhoenixFrame(null, nextRef(), "phoenix", "heartbeat", JsonObject(emptyMap())),
                        ),
                    )
                }
            }

            // true when the server refused the upgrade: most often an expired
            // access token, so refresh before the next attempt.
            val refused = closed.await()
            heartbeat.cancel()
            socket = null
            mutableJoined.value = null
            failPending()

            refreshFirst = refused
            attempt = if (joinedOnce) 0 else attempt + 1
            joinedOnce = false
            delay(backoffMs(attempt))
        }
    }

    @Volatile private var joinedOnce = false

    private fun listener(closed: CompletableDeferred<Boolean>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val ref = nextRef()
            joinRef = ref
            webSocket.send(
                PhoenixFrames.encode(PhoenixFrame(ref, ref, topic, "phx_join", JsonObject(emptyMap()))),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = PhoenixFrames.decode(text) ?: return
            if (frame.event == "phx_reply") {
                val ok = frame.payload["status"]?.jsonPrimitive?.contentOrNull == "ok"
                val response = frame.payload["response"] as? JsonObject ?: JsonObject(emptyMap())

                if (frame.ref != null && frame.ref == frame.joinRef && frame.topic == topic) {
                    if (ok) {
                        joinedOnce = true
                        mutableJoined.value = response
                    } else {
                        webSocket.close(NORMAL_CLOSURE, "join refused")
                    }
                    return
                }

                frame.ref?.let { ref ->
                    synchronized(pending) { pending.remove(ref) }?.complete(PhoenixReply(ok, response))
                }
                return
            }

            if (frame.topic != topic) return
            when (frame.event) {
                // The channel crashed or closed server-side: reconnect to rejoin.
                "phx_error", "phx_close" -> webSocket.close(NORMAL_CLOSURE, frame.event)
                else -> mutableEvents.tryEmit(frame)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.complete(response?.code == FORBIDDEN)
        }
    }

    private fun failPending() {
        val waiting = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        waiting.forEach { it.cancel() }
    }

    private fun nextRef(): String = refs.incrementAndGet().toString()

    private fun backoffMs(attempt: Int): Long =
        (BASE_BACKOFF_MS shl attempt.coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS)

    companion object {
        private const val HEARTBEAT_MS = 30_000L
        private const val BASE_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 15_000L
        private const val NORMAL_CLOSURE = 1000
        private const val FORBIDDEN = 403

        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
