package org.veejr.simple.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Renders one WebRTC video track; mirrored for the front-camera preview. */
@Composable
fun VideoView(
    track: VideoTrack,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    fill: Boolean = true,
    onTop: Boolean = false,
) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(
                eglContext,
                object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {
                        Log.d("SimpleVeejRtc", "first frame rendered track=${track.id()} mirror=$mirror")
                    }

                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                        Log.d("SimpleVeejRtc", "frame ${width}x$height rot=$rotation track=${track.id()}")
                    }
                },
            )
            setMirror(mirror)
            setEnableHardwareScaler(true)
            setScalingType(
                if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL
                else RendererCommon.ScalingType.SCALE_ASPECT_FIT,
            )
            // The small self-view sits over the full-screen remote video.
            setZOrderMediaOverlay(onTop)
        }
    }

    DisposableEffect(track) {
        track.addSink(renderer)
        onDispose { runCatching { track.removeSink(renderer) } }
    }
    DisposableEffect(Unit) {
        onDispose { renderer.release() }
    }

    AndroidView(factory = { renderer }, modifier = modifier)
}
