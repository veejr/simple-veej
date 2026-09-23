package org.veejr.simple

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

enum class MediaConnection { New, Connecting, Connected, Reconnecting, Failed }

/**
 * One WebRTC video call leg to one peer.
 *
 * Negotiation follows the same *perfect negotiation* pattern as the browser's
 * `CallPeer` (veejr `assets/js/veejr/call_peer.js`), so either end may offer
 * and a collision resolves by the polite side yielding. Everything that
 * touches the peer connection runs on one dedicated thread, and each
 * negotiation step completes before the next one starts.
 */
class CallEngine(
    private val context: Context,
    val eglBase: EglBase,
    private val factory: PeerConnectionFactory,
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "call-engine") }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    private val mutableLocalVideo = MutableStateFlow<VideoTrack?>(null)
    private val mutableRemoteVideo = MutableStateFlow<VideoTrack?>(null)
    private val mutableConnection = MutableStateFlow(MediaConnection.New)
    private val mutablePeerMedia = MutableStateFlow(CallSignal.MediaState(audio = true, video = true))

    val localVideo: StateFlow<VideoTrack?> = mutableLocalVideo.asStateFlow()
    val remoteVideo: StateFlow<VideoTrack?> = mutableRemoteVideo.asStateFlow()
    val connection: StateFlow<MediaConnection> = mutableConnection.asStateFlow()
    val peerMedia: StateFlow<CallSignal.MediaState> = mutablePeerMedia.asStateFlow()

    private var peerConnection: PeerConnection? = null
    private var polite = false
    private var send: (CallSignal) -> Unit = {}

    // Offers, answers, and candidates are applied strictly one at a time, in
    // arrival order. Interleaving two of them across a suspension point let
    // one negotiation read the other's local description, so the yielding
    // side sent its own offer back labelled as an answer.
    private val negotiation = kotlinx.coroutines.sync.Mutex()
    private var ignoreOffer = false
    private val pendingIce = mutableListOf<IceCandidate>()
    private var restartAttempts = 0
    private var statsJob: kotlinx.coroutines.Job? = null

    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var closed = false

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var previousAudioMode = AudioManager.MODE_NORMAL

    /** Starts the front camera and microphone; safe before the peer is known. */
    fun startLocalMedia() = scope.launch {
        if (closed || audioTrack != null) return@launch

        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("audio0", audioSource)

        val enumerator = Camera2Enumerator(context)
        val camera = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing)
            ?: enumerator.deviceNames.firstOrNull()

        if (camera != null) {
            // A missing camera still leaves a working voice call.
            val source = factory.createVideoSource(false)
            val helper = SurfaceTextureHelper.create("camera", eglBase.eglBaseContext)
            val cameraCapturer = enumerator.createCapturer(camera, null)
            cameraCapturer.initialize(helper, context, source.capturerObserver)
            cameraCapturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)

            videoSource = source
            textureHelper = helper
            capturer = cameraCapturer
            mutableLocalVideo.value = factory.createVideoTrack("video0", source)
        }

        routeAudioForVideoCall()
    }

    /**
     * Connects to the peer. [send] seals and relays a signal; it is invoked
     * on the engine thread.
     */
    fun connect(iceServers: JsonArray, polite: Boolean, send: (CallSignal) -> Unit) = scope.launch {
        if (closed || peerConnection != null) return@launch
        this@CallEngine.polite = polite
        this@CallEngine.send = send

        val config = PeerConnection.RTCConfiguration(parseIceServers(iceServers)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = checkNotNull(factory.createPeerConnection(config, observer)) {
            "Could not create a peer connection"
        }
        peerConnection = pc
        mutableConnection.value = MediaConnection.Connecting

        audioTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }
        mutableLocalVideo.value?.let { pc.addTrack(it, listOf(STREAM_ID)) }
    }

    /** Applies one opened signal from the peer. */
    fun onSignal(signal: CallSignal) = scope.launch {
        Log.d(TAG, "recv ${signal.describe()} signaling=${peerConnection?.signalingState()}")
        val pc = peerConnection ?: return@launch
        if (closed) return@launch

        runCatching {
            negotiation.lock()
            try {
            when (signal) {
                is CallSignal.Offer -> applyDescription(pc, SessionDescription.Type.OFFER, signal.sdp)
                is CallSignal.Answer -> applyDescription(pc, SessionDescription.Type.ANSWER, signal.sdp)
                is CallSignal.Ice -> {
                    val candidate = IceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.candidate)
                    // Candidates can arrive before the description they belong
                    // to; one that no longer applies is not worth failing over.
                    if (pc.remoteDescription != null) pc.addIceCandidate(candidate) else pendingIce += candidate
                }
                is CallSignal.MediaState -> mutablePeerMedia.value = signal
                CallSignal.Other -> Unit
            }
            } finally {
                negotiation.unlock()
            }
        }.onFailure { Log.w(TAG, "applying ${signal.describe()} failed", it) }
    }

    fun setMicrophoneEnabled(enabled: Boolean) = scope.launch {
        audioTrack?.setEnabled(enabled)
        if (peerConnection != null) {
            send(CallSignal.MediaState(audio = enabled, video = mutableLocalVideo.value != null))
        }
    }

    fun close() {
        scope.launch {
            if (closed) return@launch
            closed = true
            runCatching { capturer?.stopCapture() }
            capturer?.dispose()
            peerConnection?.dispose()
            peerConnection = null
            mutableLocalVideo.value?.dispose()
            mutableLocalVideo.value = null
            mutableRemoteVideo.value = null
            videoSource?.dispose()
            audioTrack?.dispose()
            audioSource?.dispose()
            textureHelper?.dispose()
            restoreAudio()
            mutableConnection.value = MediaConnection.Failed
        }.invokeOnCompletion {
            scope.cancel()
            executor.shutdown()
        }
    }

    // Diagnostics: whether video frames actually flow each way, and over
    // which candidate pair. Logged only; nothing leaves the device.
    private fun logStatsWhileConnected() {
        if (statsJob?.isActive == true) return
        statsJob = scope.launch {
            repeat(STATS_SAMPLES) {
                kotlinx.coroutines.delay(STATS_INTERVAL_MS)
                val pc = peerConnection ?: return@launch
                pc.getStats { report ->
                    val stats = report.statsMap.values
                    fun num(m: Map<String, Any>, key: String) = m[key]?.toString() ?: "-"
                    val inbound = stats.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }?.members
                    val outbound = stats.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" }?.members
                    val pair = stats.firstOrNull {
                        it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded"
                    }?.members
                    val localType = pair?.let { report.statsMap[it["localCandidateId"]]?.members?.get("candidateType") }
                    val remoteType = pair?.let { report.statsMap[it["remoteCandidateId"]]?.members?.get("candidateType") }
                    Log.d(
                        TAG,
                        "stats in[recv=${inbound?.let { num(it, "framesReceived") }} " +
                            "dec=${inbound?.let { num(it, "framesDecoded") }} " +
                            "${inbound?.let { num(it, "frameWidth") }}x${inbound?.let { num(it, "frameHeight") }} " +
                            "bytes=${inbound?.let { num(it, "bytesReceived") }}] " +
                            "out[enc=${outbound?.let { num(it, "framesEncoded") }} " +
                            "sent=${outbound?.let { num(it, "framesSent") }} " +
                            "limit=${outbound?.let { num(it, "qualityLimitationReason") }}] " +
                            "pair=$localType->$remoteType peerVideo=${mutablePeerMedia.value.video} " +
                            "remoteTrack=${mutableRemoteVideo.value?.id()}",
                    )
                }
            }
        }
    }

    // The perfect-negotiation core; mirrors CallPeer.applySignal. Runs under
    // [negotiation], so an offer of ours is either fully set (and visible as
    // HAVE_LOCAL_OFFER) or not started — never half-made.
    private suspend fun applyDescription(pc: PeerConnection, type: SessionDescription.Type, sdp: String) {
        val offerCollision = type == SessionDescription.Type.OFFER &&
            pc.signalingState() != PeerConnection.SignalingState.STABLE

        ignoreOffer = !polite && offerCollision
        Log.d(TAG, "description $type polite=$polite collision=$offerCollision ignore=$ignoreOffer")
        if (ignoreOffer) return

        if (offerCollision) {
            // Polite side yields: withdraw our own offer before taking theirs.
            pc.setLocal(SessionDescription(SessionDescription.Type.ROLLBACK, ""))
        }

        pc.setRemote(SessionDescription(type, sdp))

        if (type == SessionDescription.Type.OFFER) {
            // Send exactly the answer we created, never whatever the
            // connection's local description happens to be afterwards.
            val answer = pc.create { observer -> createAnswer(observer, MediaConstraints()) }
            pc.setLocal(answer)
            send(CallSignal.Answer(answer.description))
        }

        val queued = pendingIce.toList()
        pendingIce.clear()
        queued.forEach { pc.addIceCandidate(it) }
    }

    private fun negotiate() = scope.launch {
        negotiation.lock()
        try {
            val pc = peerConnection ?: return@launch
            // A remote offer applied meanwhile already renegotiated; a
            // half-finished exchange will raise negotiation-needed again.
            if (closed || pc.signalingState() != PeerConnection.SignalingState.STABLE) return@launch
            val offer = pc.create { observer -> createOffer(observer, MediaConstraints()) }
            pc.setLocal(offer)
            send(CallSignal.Offer(offer.description))
        } catch (error: Exception) {
            // Recoverable: an ICE restart or a later negotiation tries again.
            Log.w(TAG, "offer failed", error)
        } finally {
            negotiation.unlock()
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onRenegotiationNeeded() {
            Log.d(TAG, "negotiation needed")
            negotiate()
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "local candidate ${candidate.sdp.substringAfter(" typ ").substringBefore(" ")} ${candidate.sdp.take(60)}")
            scope.launch {
                send(CallSignal.Ice(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            Log.d(TAG, "remote track ${track?.kind()} mid=${transceiver.mid}")
            if (track is VideoTrack) mutableRemoteVideo.value = track
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.d(TAG, "connection $newState")
            scope.launch {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        restartAttempts = 0
                        mutableConnection.value = MediaConnection.Connected
                        logStatsWhileConnected()
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED ->
                        mutableConnection.value = MediaConnection.Reconnecting
                    PeerConnection.PeerConnectionState.FAILED -> {
                        // Same budget as the browser: two ICE restarts, then give up.
                        if (restartAttempts < MAX_ICE_RESTARTS) {
                            restartAttempts += 1
                            mutableConnection.value = MediaConnection.Reconnecting
                            peerConnection?.restartIce()
                        } else {
                            mutableConnection.value = MediaConnection.Failed
                        }
                    }
                    else -> Unit
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Log.d(TAG, "signaling $state")
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ice $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit

        // The browser may open a call-chat data channel; this app has no chat.
        override fun onDataChannel(channel: DataChannel) = Unit
    }

    private fun routeAudioForVideoCall() {
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // A video call is held at arm's length, so it plays on the speaker.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let(audioManager::setCommunicationDevice)
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun restoreAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = previousAudioMode
    }

    companion object {
        private const val TAG = "SimpleVeejRtc"
        private const val STREAM_ID = "simple-veej"
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
        private const val CAPTURE_FPS = 30
        private const val MAX_ICE_RESTARTS = 2
        private const val STATS_SAMPLES = 10
        private const val STATS_INTERVAL_MS = 2_000L

        fun createFactory(context: Context, eglBase: EglBase): PeerConnectionFactory {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            return PeerConnectionFactory.builder()
                .setAudioDeviceModule(
                    JavaAudioDeviceModule.builder(context.applicationContext)
                        .setUseHardwareAcousticEchoCanceler(true)
                        .setUseHardwareNoiseSuppressor(true)
                        .createAudioDeviceModule(),
                )
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
        }

        /** Maps the server's `ice_servers` (browser `RTCIceServer` shape). */
        fun parseIceServers(servers: JsonArray): List<PeerConnection.IceServer> =
            servers.mapNotNull { element ->
                val server = element as? JsonObject ?: return@mapNotNull null
                val urls = when (val value = server["urls"]) {
                    is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
                    is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                    else -> emptyList()
                }
                if (urls.isEmpty()) return@mapNotNull null

                PeerConnection.IceServer.builder(urls).apply {
                    server["username"]?.jsonPrimitive?.contentOrNull?.let(::setUsername)
                    server["credential"]?.jsonPrimitive?.contentOrNull?.let(::setPassword)
                }.createIceServer()
            }
    }
}

private suspend fun PeerConnection.create(
    start: PeerConnection.(SdpObserver) -> Unit,
): SessionDescription = suspendCancellableCoroutine { cont ->
    start(
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                if (cont.isActive) cont.resume(description)
            }

            override fun onCreateFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "SDP create failed"))
            }

            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        },
    )
}

private suspend fun PeerConnection.setLocal(description: SessionDescription) =
    suspendCancellableCoroutine { cont -> setLocalDescription(sdpObserver(cont), description) }

private suspend fun PeerConnection.setRemote(description: SessionDescription) =
    suspendCancellableCoroutine { cont -> setRemoteDescription(sdpObserver(cont), description) }

private fun sdpObserver(cont: kotlinx.coroutines.CancellableContinuation<Unit>) = object : SdpObserver {
    override fun onSetSuccess() {
        if (cont.isActive) cont.resume(Unit)
    }

    override fun onSetFailure(error: String?) {
        if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "SDP failure"))
    }

    override fun onCreateSuccess(description: SessionDescription?) = Unit
    override fun onCreateFailure(error: String?) = Unit
}

private fun CallSignal.describe(): String = when (this) {
    is CallSignal.Offer -> "offer(${sdp.lineSequence().count { it.startsWith("m=") }} m-lines)"
    is CallSignal.Answer -> "answer(${sdp.lineSequence().count { it.startsWith("m=") }} m-lines)"
    is CallSignal.Ice -> "ice(${candidate.substringAfter(" typ ").substringBefore(" ")})"
    is CallSignal.MediaState -> "media_state(audio=$audio video=$video)"
    CallSignal.Other -> "other"
}
