package org.veejr.simple

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
    private val client: WebSocket.Factory,
    private val topic: String,
    /** Returns the websocket URL with a currently valid token, or null to stop. */
    private val connectUrl: suspend (refreshFirst: Boolean) -> String?,
) {
    private val refs = AtomicLong(0)
    private val pending = mutableMapOf<String, CompletableDeferred<PhoenixReply?>>()
    private val mutableEvents = MutableSharedFlow<PhoenixFrame>(extraBufferCapacity = 64)
    private val mutableJoined = MutableStateFlow<JsonObject?>(null)

    val events: SharedFlow<PhoenixFrame> = mutableEvents.asSharedFlow()

    /** The join reply while joined, else null. */
    val joined: StateFlow<JsonObject?> = mutableJoined.asStateFlow()

    private class Connection(val joinRef: String) {
        var socket: WebSocket? = null
        val closed = CompletableDeferred<Boolean>()
        var joined = false
        var heartbeatRef: String? = null
    }

    private var active: Connection? = null
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        active?.socket?.cancel()
        active = null
        mutableJoined.value = null
        failPending()
    }

    /** Pushes an event and waits for its reply; null on timeout or disconnect. */
    suspend fun push(event: String, payload: JsonObject, timeoutMs: Long = 10_000): PhoenixReply? {
        val connection = active?.takeIf { it.joined && !it.closed.isCompleted } ?: return null
        val ws = connection.socket ?: return null
        val ref = nextRef()
        val deferred = CompletableDeferred<PhoenixReply?>()
        synchronized(pending) { pending[ref] = deferred }
        return try {
            if (!ws.send(PhoenixFrames.encode(PhoenixFrame(connection.joinRef, ref, topic, event, payload)))) {
                connection.closed.complete(false)
                return null
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            synchronized(pending) { pending.remove(ref) }
        }
    }

    private suspend fun runLoop() {
        var attempt = 0
        var refreshFirst = false

        while (currentCoroutineContext().isActive) {
            val connection = Connection(nextRef())
            try {
                val url = connectUrl(refreshFirst) ?: return
                active = connection
                refreshFirst = coroutineScope {
                    val ws = client.newWebSocket(Request.Builder().url(url).build(), listener(connection, this))
                    connection.socket = ws
                    val joinTimeout = launch {
                        delay(JOIN_TIMEOUT_MS)
                        if (!connection.joined) connection.closed.complete(false)
                    }
                    val heartbeat = launch {
                        while (isActive) {
                            delay(HEARTBEAT_MS)
                            if (!connection.joined) continue
                            if (connection.heartbeatRef != null) {
                                connection.closed.complete(false)
                                break
                            }
                            val ref = nextRef()
                            connection.heartbeatRef = ref
                            if (!ws.send(PhoenixFrames.encode(
                                    PhoenixFrame(null, ref, "phoenix", "heartbeat", JsonObject(emptyMap())),
                                ))) connection.closed.complete(false)
                        }
                    }
                    try {
                        connection.closed.await()
                    } finally {
                        joinTimeout.cancel()
                        heartbeat.cancel()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A transient DNS, transport or token-store failure must not
                // permanently kill the reconnect loop or discard credentials.
            } finally {
                connection.socket?.cancel()
                if (active === connection) {
                    active = null
                    mutableJoined.value = null
                    failPending()
                }
            }
            attempt = if (connection.joined) 0 else attempt + 1
            delay(backoffMs(attempt))
        }
    }

    private fun listener(connection: Connection, connectionScope: CoroutineScope) = object : WebSocketListener() {
        // OkHttp callbacks arrive on worker threads. Serialize them with the
        // controller, and ignore callbacks from a stopped/replaced socket.
        private fun dispatch(action: () -> Unit) {
            connectionScope.launch {
                if (active === connection && !connection.closed.isCompleted) action()
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            dispatch {
                val ref = connection.joinRef
                if (!webSocket.send(
                        PhoenixFrames.encode(PhoenixFrame(ref, ref, topic, "phx_join", JsonObject(emptyMap()))),
                    )) connection.closed.complete(false)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            dispatch { receive(text) }
        }

        private fun receive(text: String) {
            val frame = PhoenixFrames.decode(text) ?: return
            if (frame.event == "phx_reply") {
                val ok = (frame.payload["status"] as? JsonPrimitive)?.contentOrNull == "ok"
                val response = frame.payload["response"] as? JsonObject ?: JsonObject(emptyMap())

                if (frame.topic == "phoenix" && frame.ref == connection.heartbeatRef && ok) {
                    connection.heartbeatRef = null
                    return
                }
                if (frame.topic != topic || frame.joinRef != connection.joinRef) return
                if (frame.ref == connection.joinRef) {
                    if (ok) {
                        connection.joined = true
                        mutableJoined.value = response
                    } else {
                        connection.closed.complete(false)
                    }
                    return
                }

                frame.ref?.let { ref ->
                    synchronized(pending) { pending.remove(ref) }?.complete(PhoenixReply(ok, response))
                }
                return
            }

            if (frame.topic != topic || !connection.joined) return
            if (frame.joinRef != null && frame.joinRef != connection.joinRef) return
            when (frame.event) {
                // The channel crashed or closed server-side: reconnect to rejoin.
                "phx_error", "phx_close" -> connection.closed.complete(false)
                else -> mutableEvents.tryEmit(frame)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            dispatch { connection.closed.complete(false) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dispatch { connection.closed.complete(false) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            dispatch { connection.closed.complete(response?.code == FORBIDDEN || response?.code == UNAUTHORIZED) }
        }
    }

    private fun failPending() {
        val waiting = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        waiting.forEach { it.complete(null) }
    }

    private fun nextRef(): String = refs.incrementAndGet().toString()

    private fun backoffMs(attempt: Int): Long =
        (BASE_BACKOFF_MS shl attempt.coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS)

    companion object {
        private const val HEARTBEAT_MS = 30_000L
        private const val JOIN_TIMEOUT_MS = 10_000L
        private const val BASE_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 15_000L
        private const val FORBIDDEN = 403
        private const val UNAUTHORIZED = 401

        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
