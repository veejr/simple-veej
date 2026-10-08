package org.veejr.simple

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.em
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.veejr.simple.ui.CallingScreen
import org.veejr.simple.ui.ChoosePersonScreen
import org.veejr.simple.ui.HomeScreen
import org.veejr.simple.ui.InCallScreen
import org.veejr.simple.ui.ChatScreen
import org.veejr.simple.ui.IncomingScreen
import org.veejr.simple.ui.MessageDialog
import org.veejr.simple.ui.SettingsScreen
import org.veejr.simple.ui.SignInScreen
import org.veejr.simple.ui.UnlockScreen

class MainActivity : ComponentActivity() {
    private val app by lazy { SimpleVeejApp.from(this) }
    private val setup: SetupModel by viewModels()
    private val messages by lazy { MessageSender(app.store, app::sessions) }

    // What to do once camera and microphone are granted.
    private var afterPermissions: (() -> Unit)? = null

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val action = afterPermissions
            afterPermissions = null
            val mediaGranted = MEDIA_PERMISSIONS.all { results[it] ?: hasPermission(it) }
            if (mediaGranted) action?.invoke()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            // Text here runs from 16 to 44 sp; a line height fixed at the
            // theme's 24 sp made wrapped titles overlap. Scale it instead.
            MaterialTheme(typography = scaledLineHeights()) {
                Root()
            }
        }

        handleRingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        app.calls.start()
    }

    // Permissions can change in Settings while we are away; re-read on return.
    private val openChatRequests = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val resumeCount = kotlinx.coroutines.flow.MutableStateFlow(0)

    override fun onResume() {
        super.onResume()
        resumeCount.value += 1
        if (app.store.isSetUp) app.refreshPushToken()
    }

    private fun openFullScreenSettings() {
        runCatching { startActivity(RingReadiness.fullScreenSettings(this)) }
    }

    @Composable
    private fun Root() {
        val step by setup.step.collectAsState()
        val busy by setup.busy.collectAsState()
        val error by setup.error.collectAsState()

        when (val current = step) {
            SetupStep.SignIn -> SignInScreen(setup.defaultServer, busy, error) { server, email, password ->
                setup.signIn(server, email, password)
            }
            is SetupStep.Unlock -> UnlockScreen(
                busy = busy,
                error = error,
                onUnlock = { setup.unlock(current.account, it) },
                onStartOver = setup::startOver,
            )
            is SetupStep.ChoosePerson -> ChoosePersonScreen(
                friends = current.friends,
                onCancel = if (current.changing) setup::cancelChangePerson else null,
            ) { friend, name ->
                setup.choose(friend, name)
                withPermissions {}
            }
            SetupStep.Done -> CallRoot()
        }
    }

    @Composable
    private fun CallRoot() {
        val calls = app.calls
        val signedOut by calls.signedOut.collectAsState()
        LaunchedEffect(signedOut) { if (signedOut) setup.sessionExpired() }

        val state by calls.state.collectAsState()
        val muted by calls.muted.collectAsState()
        val engine by calls.engine.collectAsState()
        var showSettings by remember { mutableStateOf(false) }
        val setupBusy by setup.busy.collectAsState()
        val setupError by setup.error.collectAsState()
        val resumes by resumeCount.collectAsState()
        val fullScreenAllowed = remember(resumes) { RingReadiness.fullScreenAllowed(this) }
        val push by app.pushStatus.collectAsState()
        val pushStatus = when (val status = push) {
            PushStatus.Unavailable -> "Not available in this build"
            PushStatus.NotRegistered -> "Not registered yet"
            PushStatus.Registering -> "Registering…"
            is PushStatus.Ready -> "Ready"
            is PushStatus.Failed -> "Not working: ${status.reason}"
        }
        val personName = app.store.myPerson?.name ?: "my person"
        var showMessage by remember { mutableStateOf(false) }
        var showChat by remember { mutableStateOf(false) }
        var recent by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
        var chatError by remember { mutableStateOf<String?>(null) }
        var pending by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
        var sentNote by remember { mutableStateOf<String?>(null) }

        // Shows the message at once, sends in the background, marks it failed on error.
        fun sendFromChat(local: ChatMessage) {
            pending = pending + local
            chatError = null
            lifecycleScope.launch {
                runCatching { messages.send(local.text) }
                    .onSuccess {
                        // Swap the local bubble for the server copy in one step.
                        recent = runCatching { messages.recent() }.getOrDefault(recent)
                        pending = pending.filterNot { it.id == local.id }
                    }
                    .onFailure { error ->
                        chatError = error.message ?: "Message not sent."
                        pending = pending.map { if (it.id == local.id) it.copy(failed = true) else it }
                    }
            }
        }

        // Unread = an incoming message newer than the last one the user looked at.
        var lastReadAt by remember { mutableStateOf(app.store.lastReadAt) }
        val newestIncoming = recent.filter { !it.mine }.maxOfOrNull { it.at.toEpochMilli() }
        val hasUnread = newestIncoming != null && newestIncoming > (lastReadAt ?: Long.MAX_VALUE)
        fun markRead() {
            if (newestIncoming != null && newestIncoming != lastReadAt) {
                lastReadAt = newestIncoming
                app.store.lastReadAt = newestIncoming
            }
        }

        // Refreshes the cached conversation without blocking the UI.
        val refreshRecent: () -> Unit = {
            lifecycleScope.launch {
                runCatching { messages.recent() }.onSuccess { loaded ->
                    // First ever load: what is already there counts as read, not as news.
                    if (app.store.lastReadAt == null) {
                        val seed = loaded.filter { !it.mine }.maxOfOrNull { it.at.toEpochMilli() } ?: 1L
                        app.store.lastReadAt = seed
                        lastReadAt = seed
                    }
                    recent = loaded
                }
            }
        }
        // Keep the conversation and recipient keys warm so tapping Message needs no network wait.
        LaunchedEffect(resumes) {
            runCatching { messages.prewarm() }
            refreshRecent()
        }

        val openMessages: () -> Unit = {
            sentNote = null
            MessageNotifier.cancel(this)
            markRead()
            // Decide from what is already loaded, open at once, refresh behind it.
            val cutoff = java.time.Instant.now().minus(CHAT_WINDOW)
            if (recent.any { !it.mine && it.at.isAfter(cutoff) }) {
                chatError = null
                showChat = true
            } else {
                showMessage = true
            }
            refreshRecent()
        }
        // A tapped new-message notification opens the chat once the call UI is idle.
        val chatRequests by openChatRequests.collectAsState()
        LaunchedEffect(chatRequests, state) {
            if (chatRequests > 0 && (state is CallState.Idle || state is CallState.Ended)) {
                openChatRequests.value = 0
                // The tap came from a new-message notification: go straight to the chat.
                sentNote = null
                MessageNotifier.cancel(this@MainActivity)
                chatError = null
                showChat = true
                refreshRecent()
            }
        }

        when (val current = state) {
            is CallState.Incoming -> IncomingScreen(
                callerName = current.callerLabel,
                onAnswer = { withPermissions { calls.answer(current.callId) } },
                onDecline = { calls.decline(current.callId) },
            )
            is CallState.Outgoing -> {
                val local by (engine?.localVideo ?: NO_VIDEO).collectAsState()
                CallingScreen(current.peer.name, local, calls.rtcEglContext, calls::hangUp)
            }
            is CallState.Active -> InCallScreen(
                personName = current.peer.name,
                engine = current.engine,
                eglContext = calls.rtcEglContext,
                muted = muted,
                onToggleMute = { calls.setMuted(!muted) },
                onHangUp = calls::hangUp,
            )
            CallState.Idle, is CallState.Ended -> if (showSettings) {
                SettingsScreen(
                    personName = personName,
                    fullScreenAllowed = fullScreenAllowed,
                    pushStatus = pushStatus,
                    pushReady = push is PushStatus.Ready,
                    onRegisterPush = app::refreshPushToken,
                    onAllowFullScreen = ::openFullScreenSettings,
                    changingPerson = setupBusy,
                    error = setupError,
                    onChangePerson = setup::changePerson,
                    onBack = { showSettings = false },
                    onStartOver = {
                        showSettings = false
                        setup.startOver()
                    },
                )
            } else {
                HomeScreen(
                    personName = personName,
                    banner = sentNote ?: (current as? CallState.Ended)?.message,
                    onMessage = openMessages,
                    hasUnread = hasUnread,
                    fullScreenAllowed = fullScreenAllowed,
                    onAllowFullScreen = ::openFullScreenSettings,
                    onCall = { withPermissions { calls.callMyPerson() } },
                    onLongPressSettings = { showSettings = true },
                )
                if (showMessage) MessageDialog(
                    personName = personName,
                    recent = recent,
                    onDismiss = { showMessage = false },
                    onSend = { text ->
                        showMessage = false
                        sentNote = "Sending…"
                        lifecycleScope.launch {
                            val result = runCatching { messages.send(text) }
                            sentNote = result.fold(
                                onSuccess = { "Message sent." },
                                onFailure = { it.message ?: "Message not sent." },
                            )
                        }
                    },
                )
                // Seeing the conversation, in the chat or the quick box, clears the glow.
                LaunchedEffect(showChat, showMessage, recent) { if (showChat || showMessage) markRead() }
                // While the app is on screen, notice new messages even with no chat open.
                LaunchedEffect(showChat) {
                    if (!showChat) {
                        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                            while (true) {
                                kotlinx.coroutines.delay(UNREAD_POLL_MILLIS)
                                runCatching { messages.recent() }.onSuccess { recent = it }
                            }
                        }
                    }
                }
                // Keeps an open chat current; the effect is cancelled when it closes.
                LaunchedEffect(showChat) {
                    while (showChat) {
                        kotlinx.coroutines.delay(CHAT_POLL_MILLIS)
                        runCatching { messages.recent() }.onSuccess { recent = it }
                    }
                }
                if (showChat) ChatScreen(
                    personName = personName,
                    messages = recent + pending,
                    error = chatError,
                    onBack = { showChat = false },
                    onSend = { text ->
                        sendFromChat(ChatMessage("local-${System.nanoTime()}", text.trim(), true, java.time.Instant.now()))
                    },
                    onRetry = { failed ->
                        pending = pending.filterNot { it.id == failed.id }
                        sendFromChat(failed.copy(id = "local-${System.nanoTime()}", failed = false))
                    },
                )
            }
        }
    }

    private fun handleRingIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_CHAT) {
            openChatRequests.value += 1
            intent.action = null
            return
        }
        val callId = intent?.getStringExtra(EXTRA_CALL_ID) ?: return
        when (intent.action) {
            ACTION_ANSWER -> withPermissions { app.calls.answer(callId) }
            // ACTION_SHOW_RING: the controller already holds the ring; showing
            // this activity over the lock screen is the whole job.
        }
        // Handle each notification tap once, not again on recreation.
        intent.removeExtra(EXTRA_CALL_ID)
    }

    private fun withPermissions(action: () -> Unit) {
        val missing = (MEDIA_PERMISSIONS + optionalPermissions()).filterNot(::hasPermission)
        if (missing.isEmpty()) {
            action()
        } else {
            afterPermissions = action
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    private fun optionalPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_ANSWER = "org.veejr.simple.ANSWER"
        const val ACTION_OPEN_CHAT = "org.veejr.simple.OPEN_CHAT"
        const val ACTION_SHOW_RING = "org.veejr.simple.SHOW_RING"
        const val EXTRA_CALL_ID = "call_id"

        /** An incoming message this recent opens the full chat instead of the quick box. */
        private const val CHAT_POLL_MILLIS = 5_000L
        private const val UNREAD_POLL_MILLIS = 10_000L
        private val CHAT_WINDOW = java.time.Duration.ofMinutes(30)

        private val MEDIA_PERMISSIONS = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        private val NO_VIDEO = kotlinx.coroutines.flow.MutableStateFlow<org.webrtc.VideoTrack?>(null)
    }
}

private fun scaledLineHeights(): androidx.compose.material3.Typography {
    val base = androidx.compose.material3.Typography()
    fun androidx.compose.ui.text.TextStyle.scaled() = copy(lineHeight = 1.25.em)
    return base.copy(
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
    )
}
