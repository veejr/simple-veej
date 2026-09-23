package org.veejr.simple

import android.content.Context
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.veejr.core.crypto.VeejrCrypto
import org.veejr.core.network.ApiEndpoint
import org.veejr.core.network.AuthSessionManager
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

/** Who is on the other end, as the server describes them. */
data class CallPeer(val id: String, val name: String, val publicKey: String)

sealed interface CallState {
    data object Idle : CallState
    data class Outgoing(val callId: String?, val peer: CallPeer) : CallState
    data class Incoming(
        val callId: String,
        val peer: CallPeer?,
        val callerLabel: String,
        val expiresAtMillis: Long,
    ) : CallState
    data class Active(val callId: String, val peer: CallPeer, val engine: CallEngine) : CallState
    data class Ended(val message: String) : CallState
}

/**
 * The whole calling lifecycle for this device: one [PhoenixSocket] on the
 * `calls:v1` topic, at most one call, and one [CallEngine] for its media.
 */
class CallController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: SimpleStore,
    private val sessions: () -> AuthSessionManager?,
) {
    private val crypto = VeejrCrypto()
    private val eglBase: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy { CallEngine.createFactory(context, eglBase) }

    private val mutableState = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = mutableState.asStateFlow()

    private val mutableMuted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = mutableMuted.asStateFlow()

    private val mutableSignedOut = MutableStateFlow(false)

    /** True once the session could not be refreshed: the phone needs setup again. */
    val signedOut: StateFlow<Boolean> = mutableSignedOut.asStateFlow()

    private var socket: PhoenixSocket? = null
    private var sealer: SignalSealer? = null
    private var clearEndedJob: Job? = null
    private var timeoutJob: Job? = null

    val rtcEglContext: EglBase.Context get() = eglBase.eglBaseContext

    /** Connects the calls socket; call again after setup or sign-in. */
    fun start() {
        if (socket != null || !store.isSetUp) return
        val endpoint = store.endpoint ?: return
        val apiBase = ApiEndpoint.parse(endpoint, allowHttp = BuildConfig.ALLOW_HTTP).uri.toString()

        mutableSignedOut.value = false
        socket = PhoenixSocket(scope, PhoenixSocket.httpClient(), TOPIC) { refreshFirst ->
            if (refreshFirst) runCatching { sessions()?.currentAccount() }
            val tokens = store.load()
            if (tokens == null) {
                // The refresh token was rejected (device sessions last at most
                // 90 days, or it was revoked). Nothing works until setup.
                mutableSignedOut.value = true
                socket = null
            }
            tokens?.let { PhoenixFrames.socketUrl(apiBase, it.accessToken) }
        }.also { phoenix ->
            phoenix.start()
            scope.launch { phoenix.events.collect(::onServerEvent) }
            scope.launch { phoenix.joined.filterNotNull().collect { reattach(phoenix) } }
        }
    }

    fun stop() {
        socket?.stop()
        socket = null
    }

    // Each (re)join is a fresh server channel that knows nothing of this
    // device's call. Re-accepting moves presence and signaling onto it before
    // the old channel's 25-second grace ends the call (protocol §26.5).
    private suspend fun reattach(phoenix: PhoenixSocket) {
        val callId = when (val current = state.value) {
            is CallState.Outgoing -> current.callId
            is CallState.Active -> current.callId
            else -> null
        } ?: return

        val reply = phoenix.push("accept", callIdPayload(callId))
        if (reply != null && !reply.ok) end(endedMessage(reply.reason))
    }

    /** The one button: ring my person. */
    fun callMyPerson() {
        val person = store.myPerson ?: return
        if (state.value !is CallState.Idle && state.value !is CallState.Ended) return

        val peer = CallPeer(person.id, person.name, person.publicKey)
        setState(CallState.Outgoing(callId = null, peer = peer))
        CallService.start(context, "Calling ${person.name}…")
        prepareEngine()

        scope.launch {
            val reply = pushWhenJoined("start", buildJsonObject { put("callee_id", person.id) })
            if (reply == null || !reply.ok) {
                end(startErrorMessage(reply?.reason))
                return@launch
            }
            val callId = reply.response.string("call_id") ?: return@launch end("Could not start the call.")
            val current = state.value
            if (current is CallState.Outgoing && current.callId == null) {
                setState(current.copy(callId = callId))
                timeoutJob = scope.launch {
                    delay(OUTGOING_TIMEOUT_MS)
                    val still = state.value
                    if (still is CallState.Outgoing && still.callId == callId) hangUp("No answer.")
                }
            } else {
                // Hung up while the start was in flight.
                socket?.push("hangup", callIdPayload(callId))
            }
        }
    }

    /** Answers a ring, here or from the notification. */
    fun answer(callId: String) {
        val ringing = state.value as? CallState.Incoming
        RingNotifier.cancel(context)

        scope.launch {
            val reply = pushWhenJoined("accept", callIdPayload(callId))
            if (reply == null || !reply.ok) {
                end(if (reply?.reason == "ended") "The call already ended." else "Could not answer.")
                return@launch
            }

            val peer = reply.response.peers().firstOrNull() ?: ringing?.peer
                ?: return@launch end("Could not answer.")
            CallService.start(context, "In a call with ${peer.name}")
            val engine = prepareEngine()
            setState(CallState.Active(callId, peer, engine))
            connectEngine(engine, peer)
        }
    }

    fun decline(callId: String) {
        RingNotifier.cancel(context)
        setState(CallState.Idle)
        scope.launch { pushWhenJoined("decline", callIdPayload(callId)) }
    }

    fun hangUp(message: String = "Call ended.") {
        val callId = when (val current = state.value) {
            is CallState.Outgoing -> current.callId
            is CallState.Active -> current.callId
            is CallState.Incoming -> return decline(current.callId)
            else -> null
        }
        end(message)
        if (callId != null) scope.launch { socket?.push("hangup", callIdPayload(callId)) }
    }

    fun setMuted(muted: Boolean) {
        mutableMuted.value = muted
        (state.value as? CallState.Active)?.engine?.setMicrophoneEnabled(!muted)
    }

    /** A ring delivered by push before the socket is connected. */
    fun onPushRing(callId: String, callerLabel: String, expiresAtUnix: Long?) {
        if (expiresAtUnix != null && expiresAtUnix * 1000 < System.currentTimeMillis()) return
        if (state.value is CallState.Idle || state.value is CallState.Ended) {
            ring(CallState.Incoming(callId, null, callerLabel, ringExpiry(expiresAtUnix)))
        }
        start()
    }

    fun onPushRingCancelled(callId: String) {
        val current = state.value
        if (current is CallState.Incoming && current.callId == callId) {
            RingNotifier.cancel(context)
            setState(CallState.Idle)
        }
    }

    private fun onServerEvent(frame: PhoenixFrame) {
        val payload = frame.payload
        val callId = payload.string("call_id") ?: return

        when (frame.event) {
            "ring" -> {
                val expiresAt = payload["expires_at"]?.jsonPrimitive?.longOrNull
                if (expiresAt != null && expiresAt * 1000 < System.currentTimeMillis()) return
                val caller = (payload["caller"] as? JsonObject)?.toPeer() ?: return

                when (val current = state.value) {
                    is CallState.Idle, is CallState.Ended ->
                        ring(CallState.Incoming(callId, caller, caller.name, ringExpiry(expiresAt)))
                    // Already ringing from the push: attach the peer's key.
                    is CallState.Incoming -> if (current.callId == callId) {
                        setState(current.copy(peer = caller, callerLabel = caller.name))
                    }
                    // One call at a time: anyone else hears "busy".
                    else -> scope.launch {
                        socket?.push(
                            "decline",
                            buildJsonObject {
                                put("call_id", callId)
                                put("reason", "busy")
                            },
                        )
                    }
                }
            }

            "ring_cancelled" -> onPushRingCancelled(callId)

            "peer_joined" -> {
                val current = state.value as? CallState.Outgoing ?: return
                if (current.callId != callId) return
                val peer = (payload["peer"] as? JsonObject)?.toPeer() ?: current.peer
                val engine = prepareEngine()
                setState(CallState.Active(callId, peer, engine))
                CallService.start(context, "In a call with ${peer.name}")
                connectEngine(engine, peer)
            }

            "signal" -> {
                val active = state.value as? CallState.Active ?: return
                if (active.callId != callId || payload.string("from") != active.peer.id) return
                val sealed = SealedSignal(
                    ciphertext = payload.string("ciphertext") ?: return,
                    nonce = payload.string("nonce") ?: return,
                )
                // A signal that fails authentication was not sealed by the
                // pinned peer key and is dropped.
                val opened = sealer?.open(sealed)
                if (opened == null) android.util.Log.w("SimpleVeejRtc", "dropped a signal that did not open")
                opened?.let(active.engine::onSignal)
            }

            "ended" -> {
                val mine = when (val current = state.value) {
                    is CallState.Outgoing -> current.callId == callId
                    is CallState.Active -> current.callId == callId
                    else -> false
                }
                if (mine) end(endedMessage(payload.string("reason")))
            }
        }
    }

    private val mutableEngine = MutableStateFlow<CallEngine?>(null)

    /** The current call's media, from dialling until it ends. */
    val engine: StateFlow<CallEngine?> = mutableEngine.asStateFlow()

    // Camera and microphone start while the other side is still ringing, so
    // the first frame is ready the moment they answer.
    private fun prepareEngine(): CallEngine =
        mutableEngine.value ?: CallEngine(context, eglBase, factory).also {
            mutableEngine.value = it
            it.startLocalMedia()
            it.setMicrophoneEnabled(!muted.value)
        }

    private fun connectEngine(engine: CallEngine, peer: CallPeer) {
        val myId = store.myUserId ?: return end("This phone is not set up.")
        val secret = store.identitySecret() ?: return end("This phone is not set up.")
        val peerKey = runCatching { Base64.getDecoder().decode(peer.publicKey) }.getOrNull()
            ?: return end("That person has no encryption key.")

        val newSealer = SignalSealer(crypto, secret, peerKey)
        sealer?.destroy()
        sealer = newSealer

        val iceServers = socket?.joined?.value?.get("ice_servers") as? JsonArray ?: JsonArray(emptyList())
        val callId = (state.value as? CallState.Active)?.callId ?: return

        engine.connect(iceServers, polite = isPolite(myId, peer.id)) { signal ->
            val sealed = newSealer.seal(signal)
            scope.launch {
                socket?.push(
                    "signal",
                    buildJsonObject {
                        put("call_id", callId)
                        put("ciphertext", sealed.ciphertext)
                        put("nonce", sealed.nonce)
                        put("target", peer.id)
                    },
                )
            }
        }

        scope.launch {
            engine.connection.collect { connection ->
                if (connection == MediaConnection.Failed && state.value is CallState.Active) {
                    hangUp()
                }
            }
        }
    }

    private fun end(message: String) {
        RingNotifier.cancel(context)
        CallService.stop(context)
        mutableEngine.value?.close()
        mutableEngine.value = null
        sealer?.destroy()
        sealer = null
        mutableMuted.value = false
        setState(CallState.Ended(message))

        clearEndedJob?.cancel()
        clearEndedJob = scope.launch {
            delay(ENDED_BANNER_MS)
            if (state.value is CallState.Ended) setState(CallState.Idle)
        }
    }

    private fun setState(next: CallState) {
        if (next !is CallState.Ended) clearEndedJob?.cancel()
        // Timers belong to the state that set them.
        if (next::class != mutableState.value::class) timeoutJob?.cancel()
        mutableState.value = next

        // Whatever ended a ring or a call, its notifications go with it.
        if (next !is CallState.Incoming) RingNotifier.cancel(context)
        if (next is CallState.Idle || next is CallState.Ended) CallService.stop(context)
    }

    // The server marks an unanswered ring missed without telling anyone, so
    // the phone stops ringing on its own clock.
    private fun ring(incoming: CallState.Incoming) {
        setState(incoming)
        RingNotifier.show(context, incoming.callId, incoming.callerLabel)
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay((incoming.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0))
            val still = state.value
            if (still is CallState.Incoming && still.callId == incoming.callId) {
                RingNotifier.cancel(context)
                setState(CallState.Ended("Missed call from ${still.callerLabel}."))
                clearEndedJob = scope.launch {
                    delay(MISSED_BANNER_MS)
                    if (state.value is CallState.Ended) setState(CallState.Idle)
                }
            }
        }
    }

    private fun ringExpiry(expiresAtUnix: Long?): Long =
        expiresAtUnix?.times(1000) ?: (System.currentTimeMillis() + DEFAULT_RING_MS)

    private suspend fun pushWhenJoined(event: String, payload: JsonObject): PhoenixReply? {
        start()
        val phoenix = socket ?: return null
        withTimeoutOrNull(JOIN_WAIT_MS) { phoenix.joined.filterNotNull().first() } ?: return null
        return phoenix.push(event, payload)
    }

    private fun callIdPayload(callId: String) = buildJsonObject { put("call_id", callId) }

    private fun startErrorMessage(reason: String?) = when (reason) {
        "not_a_friend" -> "You are no longer friends on veejr."
        "callee_unreachable" -> "Their veejr server could not be reached."
        null -> "No connection. Check the internet and try again."
        else -> "Could not start the call."
    }

    private fun endedMessage(reason: String?) = when (reason) {
        "declined" -> "They declined."
        "busy" -> "Busy now — try later."
        "missed" -> "No answer."
        "connection_lost" -> "The connection was lost."
        else -> "Call ended."
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.toPeer(): CallPeer? {
        val id = string("id") ?: return null
        val key = string("public_key") ?: return null
        val name = string("display_name")?.takeIf { it.isNotBlank() } ?: string("handle") ?: "Your friend"
        return CallPeer(id, name, key)
    }

    private fun JsonObject.peers(): List<CallPeer> =
        (this["peers"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { (it as? JsonObject)?.toPeer() }

    private companion object {
        const val TOPIC = "calls:v1"
        const val JOIN_WAIT_MS = 10_000L
        const val ENDED_BANNER_MS = 3_000L
        const val MISSED_BANNER_MS = 60_000L
        const val DEFAULT_RING_MS = 60_000L

        // A little past the server's 60-second ring, so its answer wins a tie.
        const val OUTGOING_TIMEOUT_MS = 70_000L
    }
}
