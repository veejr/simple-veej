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
import androidx.core.content.ContextCompat
import org.veejr.simple.ui.CallingScreen
import org.veejr.simple.ui.ChoosePersonScreen
import org.veejr.simple.ui.HomeScreen
import org.veejr.simple.ui.InCallScreen
import org.veejr.simple.ui.IncomingScreen
import org.veejr.simple.ui.SettingsScreen
import org.veejr.simple.ui.SignInScreen
import org.veejr.simple.ui.UnlockScreen

class MainActivity : ComponentActivity() {
    private val app by lazy { SimpleVeejApp.from(this) }
    private val setup: SetupModel by viewModels()

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
            MaterialTheme {
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
            is SetupStep.ChoosePerson -> ChoosePersonScreen(current.friends) { friend, name ->
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
                    onBack = { showSettings = false },
                    onStartOver = {
                        showSettings = false
                        setup.startOver()
                    },
                )
            } else {
                HomeScreen(
                    personName = personName,
                    banner = (current as? CallState.Ended)?.message,
                    fullScreenAllowed = fullScreenAllowed,
                    onAllowFullScreen = ::openFullScreenSettings,
                    onCall = { withPermissions { calls.callMyPerson() } },
                    onLongPressSettings = { showSettings = true },
                )
            }
        }
    }

    private fun handleRingIntent(intent: Intent?) {
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
        const val ACTION_SHOW_RING = "org.veejr.simple.SHOW_RING"
        const val EXTRA_CALL_ID = "call_id"

        private val MEDIA_PERMISSIONS = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        private val NO_VIDEO = kotlinx.coroutines.flow.MutableStateFlow<org.webrtc.VideoTrack?>(null)
    }
}
