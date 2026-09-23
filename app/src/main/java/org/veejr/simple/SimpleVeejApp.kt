package org.veejr.simple

import android.app.Application
import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.veejr.core.network.ApiEndpoint
import org.veejr.core.network.AuthSessionManager
import org.veejr.core.network.DeviceInfo
import org.veejr.core.network.VeejrApiClient

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

    fun refreshPushToken() {
        if (!pushAvailable) {
            android.util.Log.w("SimpleVeejPush", "Firebase is not configured in this build")
            return
        }
        FirebaseMessaging.getInstance().token.addOnSuccessListener(::registerPushToken)
    }

    fun registerPushToken(token: String) {
        if (!store.isSetUp) return
        scope.launch {
            runCatching { sessions()?.registerPushToken(token) }
                .onSuccess {
                    store.pushRegisteredAt = System.currentTimeMillis()
                    android.util.Log.d("SimpleVeejPush", "push token registered with the server")
                }
                .onFailure { android.util.Log.w("SimpleVeejPush", "push token registration failed", it) }
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
