package org.veejr.simple.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

private const val MAX_ZOOM = 5f

/**
 * The other person's video, shown whole by default (letterboxed rather than
 * cropped) and zoomable: pinch to zoom, drag to look around while zoomed,
 * double-tap to switch between the whole picture and filling the screen.
 */
@Composable
fun ZoomableVideo(track: VideoTrack, eglContext: EglBase.Context, modifier: Modifier = Modifier) {
    var frameAspect by remember { mutableStateOf<Float?>(null) }
    var zoom by remember(track) { mutableFloatStateOf(1f) }
    var offset by remember(track) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val aspect = frameAspect ?: (boxW / boxH)

        // Size of the picture when shown whole, and the zoom that fills the screen.
        val fitW = if (boxW / boxH > aspect) boxH * aspect else boxW
        val fitH = fitW / aspect
        val fillZoom = max(boxW / fitW, boxH / fitH)

        fun clamp(candidate: Offset, atZoom: Float): Offset {
            val maxX = ((fitW * atZoom - boxW) / 2).coerceAtLeast(0f)
            val maxY = ((fitH * atZoom - boxH) / 2).coerceAtLeast(0f)
            return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(fillZoom) {
                    detectTapGestures(
                        onDoubleTap = {
                            zoom = if (zoom < fillZoom - 0.01f) fillZoom else 1f
                            offset = clamp(Offset.Zero, zoom)
                        },
                    )
                }
                .pointerInput(fitW, fitH) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                        offset = clamp(offset + pan, zoom)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            TextureVideo(
                track = track,
                eglContext = eglContext,
                onAspect = { frameAspect = it },
                modifier = Modifier
                    .aspectRatio(aspect)
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

@Composable
private fun TextureVideo(
    track: VideoTrack,
    eglContext: EglBase.Context,
    onAspect: (Float) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val view = remember { TextureVideoView(context, eglContext) }
    view.onAspect = onAspect

    DisposableEffect(track) {
        track.addSink(view)
        onDispose { runCatching { track.removeSink(view) } }
    }
    DisposableEffect(Unit) {
        onDispose { view.release() }
    }

    AndroidView(factory = { view }, modifier = modifier)
}

/**
 * WebRTC's SurfaceViewRenderer draws on a separate surface that ignores view
 * transforms, so it cannot be zoomed. This draws the same frames with the
 * same EglRenderer into a TextureView, which scales and pans like any view.
 */
private class TextureVideoView(context: Context, eglContext: EglBase.Context) :
    TextureView(context), TextureView.SurfaceTextureListener, VideoSink {

    private val renderer = EglRenderer("remote-video").apply {
        init(eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
    }
    private var frameWidth = 0
    private var frameHeight = 0

    @Volatile var onAspect: (Float) -> Unit = {}

    init {
        surfaceTextureListener = this
    }

    override fun onFrame(frame: VideoFrame) {
        val width = frame.rotatedWidth
        val height = frame.rotatedHeight
        if (width != frameWidth || height != frameHeight) {
            frameWidth = width
            frameHeight = height
            Log.d("SimpleVeejRtc", "remote frame ${width}x$height")
            if (width > 0 && height > 0) post { onAspect(width.toFloat() / height) }
        }
        renderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderer.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // The renderer must stop drawing before the texture goes away.
        val released = CountDownLatch(1)
        renderer.releaseEglSurface { released.countDown() }
        released.await(1, TimeUnit.SECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun release() {
        renderer.release()
    }
}
