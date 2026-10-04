package org.veejr.simple.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.Message
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import org.veejr.simple.ChatMessage
import org.veejr.simple.CallEngine
import org.veejr.simple.MediaConnection
import org.webrtc.EglBase
import org.webrtc.VideoTrack

val CallGreen = Color(0xFF1DB954)
val HangUpRed = Color(0xFFE5383B)
private val Ink = Color(0xFF0B0B0F)

/**
 * The home screen *is* the button. Tap anywhere on the circle to call.
 * Settings hide behind a long press so they are never hit by accident.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    personName: String,
    banner: String?,
    fullScreenAllowed: Boolean,
    onAllowFullScreen: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onLongPressSettings: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(Modifier.fillMaxSize().background(Ink).systemBarsPadding(), contentAlignment = Alignment.Center) {
        if (!fullScreenAllowed) {
            // Without this, a locked phone only shows a small notification
            // for a call instead of the big Answer screen.
            Text(
                "Tap here so calls can ring on the lock screen",
                color = Ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFFD166))
                    .combinedClickable(onClick = onAllowFullScreen)
                    .padding(18.dp),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .aspectRatio(1f)
                    .scale(if (pressed) 0.96f else 1f)
                    .clip(CircleShape)
                    .background(CallGreen)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onCall,
                        onLongClick = onLongPressSettings,
                    )
                    .semantics { contentDescription = "Call $personName" },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Videocam, null, tint = Color.White, modifier = Modifier.size(96.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Call", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium)
                    Text(
                        personName,
                        color = Color.White,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f))
                        .clickable(onClick = onMessage)
                        .semantics { contentDescription = "Message $personName" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Message, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
            Text(
                banner ?: " ",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
fun IncomingScreen(callerName: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "ring")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "ring-scale",
    )

    Column(
        Modifier.fillMaxSize().background(Ink).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 48.dp)) {
            Text(callerName, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("is calling you", color = Color.White.copy(alpha = 0.8f), fontSize = 26.sp)
        }
        BigRoundButton(
            label = "Answer",
            icon = Icons.Filled.Videocam,
            color = CallGreen,
            diameter = 220,
            modifier = Modifier.scale(scale),
            onClick = onAnswer,
        )
        BigRoundButton(label = "Decline", icon = Icons.Filled.CallEnd, color = HangUpRed, diameter = 120, onClick = onDecline)
    }
}

@Composable
fun CallingScreen(
    personName: String,
    localVideo: VideoTrack?,
    eglContext: EglBase.Context,
    onHangUp: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        localVideo?.let { VideoView(it, eglContext, Modifier.fillMaxSize(), mirror = true) }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Calling $personName…",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 48.dp).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(16.dp),
            )
            BigRoundButton(label = "Hang up", icon = Icons.Filled.CallEnd, color = HangUpRed, diameter = 160, onClick = onHangUp)
        }
    }
}

@Composable
fun InCallScreen(
    personName: String,
    engine: CallEngine,
    eglContext: EglBase.Context,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onHangUp: () -> Unit,
) {
    val remote by engine.remoteVideo.collectAsState()
    val local by engine.localVideo.collectAsState()
    val connection by engine.connection.collectAsState()
    val peerMedia by engine.peerMedia.collectAsState()

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (remote != null && peerMedia.video) {
            ZoomableVideo(remote!!, eglContext, Modifier.fillMaxSize())
        } else {
            Text(
                personName,
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        local?.let {
            VideoView(
                it,
                eglContext,
                Modifier
                    .systemBarsPadding()
                    .padding(16.dp)
                    .width(120.dp)
                    .height(170.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .align(Alignment.TopEnd),
                mirror = true,
                onTop = true,
            )
        }

        val status = when (connection) {
            MediaConnection.New, MediaConnection.Connecting -> "Connecting…"
            MediaConnection.Reconnecting -> "Reconnecting…"
            else -> null
        }
        if (status != null) {
            Text(
                status,
                color = Color.White,
                fontSize = 24.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .systemBarsPadding()
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(bottom = 36.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BigRoundButton(
                label = if (muted) "Unmute" else "Mute",
                icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                color = if (muted) Color.White else Color.White.copy(alpha = 0.25f),
                contentColor = if (muted) Ink else Color.White,
                diameter = 110,
                onClick = onToggleMute,
            )
            BigRoundButton(label = "Hang up", icon = Icons.Filled.CallEnd, color = HangUpRed, diameter = 140, onClick = onHangUp)
        }
    }
}

@Composable
fun BigRoundButton(
    label: String,
    icon: ImageVector,
    color: Color,
    diameter: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            Modifier
                .size(diameter.dp)
                .scale(if (pressed) 0.94f else 1f)
                .clip(CircleShape)
                .background(color)
                .combinedClickableNoIndication(interaction, onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size((diameter * 0.45f).dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoIndication(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
) = combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)

@Composable
fun MessageDialog(
    personName: String,
    recent: List<ChatMessage>,
    sending: Boolean,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Message $personName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.takeLast(3).forEach { Bubble(it, Modifier.fillMaxWidth()) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !sending,
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(text) }, enabled = !sending && text.isNotBlank()) {
                Text(if (sending) "Sending…" else "Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") } },
    )
}

/** Full-screen conversation: bubbles on top, a text box and send button below. */
@Composable
fun ChatScreen(
    personName: String,
    messages: List<ChatMessage>,
    sending: Boolean,
    error: String?,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(Ink).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Text(personName, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { Bubble(it, Modifier.fillMaxWidth()) }
        }
        if (error != null) {
            Text(error, color = Color(0xFFB00020), fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !sending,
                maxLines = 4,
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onSend(text); text = "" },
                enabled = !sending && text.isNotBlank(),
                modifier = Modifier.size(56.dp).clip(CircleShape)
                    .background(if (!sending && text.isNotBlank()) CallGreen else Color.LightGray),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
            }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            message.text,
            color = if (message.mine) Color.White else Color.Black,
            fontSize = 20.sp,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentWidth(if (message.mine) Alignment.End else Alignment.Start)
                .clip(RoundedCornerShape(16.dp))
                .background(if (message.mine) CallGreen else Color(0xFFE6E6E6))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
