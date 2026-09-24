package org.veejr.simple

import android.app.Application
import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.veejr.core.network.ApiEndpoint
import org.veejr.core.network.AuthSessionManager
import org.veejr.core.network.DeviceInfo
import org.veejr.core.network.VeejrApiClient

sealed interface PushStatus {
    data object Unavailable : PushStatus
    data object NotRegistered : PushStatus
    data object Registering : PushStatus
    data class Ready(val since: Long) : PushStatus
    data class Failed(val reason: String) : PushStatus
}

class SimpleVeejApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store by lazy { SimpleStore(this) }
    val calls by lazy { CallController(this, scope, store, ::sessions) }

    private var cachedSessions: Pair<String, AuthSessionManager>? = null

    override fun onCreate() {
        super.onCreate()
        if (store.isSetUp) {
            calls.start()
            refreshPushToken()
        }
    }

    /** The authenticated API for the configured instance, if any. */
    fun sessions(): AuthSessionManager? {
        val endpoint = store.endpoint ?: return null
        cachedSessions?.let { (url, manager) -> if (url == endpoint) return manager }
        val api = VeejrApiClient(ApiEndpoint.parse(endpoint, allowHttp = BuildConfig.ALLOW_HTTP))
        return AuthSessionManager(api, store).also { cachedSessions = endpoint to it }
    }

    val device: DeviceInfo
        get() = DeviceInfo(
            name = "simple-veej on ${Build.MANUFACTURER} ${Build.MODEL}".take(80),
            appVersion = BuildConfig.VERSION_NAME,
        )

    /** Firebase is only configured when a google-services.json was built in. */
    val pushAvailable: Boolean
        get() = FirebaseApp.getApps(this).isNotEmpty()

    // Lazy: it reads storage, which needs the context attached after construction.
    private val mutablePushStatus by lazy { MutableStateFlow(initialPushStatus()) }

    /** Whether a closed app can be woken for a ring, kept current for Settings. */
    val pushStatus: StateFlow<PushStatus> get() = mutablePushStatus.asStateFlow()

    private fun initialPushStatus(): PushStatus = when {
        !pushAvailable -> PushStatus.Unavailable
        else -> store.pushRegisteredAt?.let(PushStatus::Ready) ?: PushStatus.NotRegistered
    }

    fun refreshPushToken() {
        if (!pushAvailable) {
            android.util.Log.w("SimpleVeejPush", "Firebase is not configured in this build")
            mutablePushStatus.value = PushStatus.Unavailable
            return
        }
        if (mutablePushStatus.value !is PushStatus.Ready) mutablePushStatus.value = PushStatus.Registering
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener(::registerPushToken)
            .addOnFailureListener { error ->
                android.util.Log.w("SimpleVeejPush", "could not get a push token", error)
                mutablePushStatus.value = PushStatus.Failed(
                    "This phone could not get a push token from Google (${error.message ?: "no details"}).",
                )
            }
    }

    fun registerPushToken(token: String) {
        if (!store.isSetUp) return
        scope.launch {
            runCatching { checkNotNull(sessions()) { "not signed in" }.registerPushToken(token) }
                .onSuccess {
                    val now = System.currentTimeMillis()
                    store.pushRegisteredAt = now
                    mutablePushStatus.value = PushStatus.Ready(now)
                    android.util.Log.d("SimpleVeejPush", "push token registered with the server")
                }
                .onFailure { error ->
                    android.util.Log.w("SimpleVeejPush", "push token registration failed", error)
                    mutablePushStatus.value = PushStatus.Failed(
                        "The veejr server did not accept this phone (${error.message ?: "no details"}).",
                    )
                }
        }
    }

    /** Signs this phone out and forgets its setup. */
    suspend fun forget() {
        calls.stop()
        runCatching { sessions()?.logout() }
        store.reset()
        cachedSessions = null
    }

    companion object {
        fun from(context: Context): SimpleVeejApp = context.applicationContext as SimpleVeejApp
    }
}
