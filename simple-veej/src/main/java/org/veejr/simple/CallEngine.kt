package org.veejr.simple

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
 * touches the peer connection runs on one dedicated thread, which is what
 * makes the `makingOffer` / `ignoreOffer` flags safe.
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

    private var makingOffer = false
    private var ignoreOffer = false
    private var isSettingRemoteAnswerPending = false
    private val pendingIce = mutableListOf<IceCandidate>()
    private var restartAttempts = 0

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
        val pc = peerConnection ?: return@launch
        if (closed) return@launch

        runCatching {
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
        }
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

    // The perfect-negotiation core; mirrors CallPeer.applySignal.
    private suspend fun applyDescription(pc: PeerConnection, type: SessionDescription.Type, sdp: String) {
        val readyForOffer = !makingOffer &&
            (pc.signalingState() == PeerConnection.SignalingState.STABLE || isSettingRemoteAnswerPending)
        val offerCollision = type == SessionDescription.Type.OFFER && !readyForOffer

        ignoreOffer = !polite && offerCollision
        if (ignoreOffer) return

        if (offerCollision) {
            // Polite side yields: withdraw our own offer before taking theirs.
            pc.setLocal(SessionDescription(SessionDescription.Type.ROLLBACK, ""))
        }

        isSettingRemoteAnswerPending = type == SessionDescription.Type.ANSWER
        pc.setRemote(SessionDescription(type, sdp))
        isSettingRemoteAnswerPending = false

        if (type == SessionDescription.Type.OFFER) {
            pc.setLocalImplicit()
            pc.localDescription?.let { send(CallSignal.Answer(it.description)) }
        }

        val queued = pendingIce.toList()
        pendingIce.clear()
        queued.forEach { pc.addIceCandidate(it) }
    }

    private fun negotiate() = scope.launch {
        val pc = peerConnection ?: return@launch
        if (closed) return@launch
        try {
            makingOffer = true
            pc.setLocalImplicit()
            pc.localDescription
                ?.takeIf { it.type == SessionDescription.Type.OFFER }
                ?.let { send(CallSignal.Offer(it.description)) }
        } catch (_: Exception) {
            // Recoverable: an ICE restart or a later negotiation tries again.
        } finally {
            makingOffer = false
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onRenegotiationNeeded() {
            negotiate()
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch {
                send(CallSignal.Ice(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) mutableRemoteVideo.value = track
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            scope.launch {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        restartAttempts = 0
                        mutableConnection.value = MediaConnection.Connected
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

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
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
        private const val STREAM_ID = "simple-veej"
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
        private const val CAPTURE_FPS = 30
        private const val MAX_ICE_RESTARTS = 2

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

private suspend fun PeerConnection.setLocalImplicit() = suspendCancellableCoroutine { cont ->
    setLocalDescription(sdpObserver(cont))
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
