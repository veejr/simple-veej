package org.veejr.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.veejr.core.network.ApiEndpoint
import org.veejr.core.network.AuthSessionManager
import org.veejr.core.network.VeejrApiClient

class AndroidPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        AndroidPushRegistration.register(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        showNotification(message.data["sender"], message.data["kind"])
    }

    private fun showNotification(sender: String?, kind: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New messages", NotificationManager.IMPORTANCE_HIGH),
        )
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val itemKind = kind ?: "message"
        val body = sender?.let { "$it sent you an encrypted $itemKind." } ?: "Something encrypted awaits you."
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("veejr")
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "new_messages"
        const val NOTIFICATION_ID = 1001
    }
}

object AndroidPushRegistration {
    fun register(context: Context, token: String, onResult: ((Boolean) -> Unit)? = null) {
        val storage = SessionVault(context)
        val endpoint = storage.endpoint
        if (endpoint == null) {
            onResult?.invoke(false)
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val registered = runCatching {
                val api = VeejrApiClient(ApiEndpoint.parse(endpoint))
                AuthSessionManager(api, storage).registerPushToken(token)
            }.isSuccess
            onResult?.let { callback ->
                withContext(Dispatchers.Main) { callback(registered) }
            }
        }
    }
}
