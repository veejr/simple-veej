package org.veejr.simple

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Wakes the phone for a ring. The push names the call and the caller only;
 * answering happens over the authenticated calls socket.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        SimpleVeejApp.from(this).registerPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        android.util.Log.d("SimpleVeejPush", "push ${data["type"]} priority=${message.priority}/${message.originalPriority}")
        val callId = data["call_id"] ?: return
        val calls = SimpleVeejApp.from(this).calls

        // Pushes arrive on a worker thread; the controller lives on main.
        android.os.Handler(mainLooper).post {
            when (data["type"]) {
            "call_ring" -> calls.onPushRing(
                callId = callId,
                callerLabel = data["caller"] ?: "Someone",
                expiresAtUnix = data["expires_at"]?.toLongOrNull(),
            )
            "call_ring_cancelled" -> calls.onPushRingCancelled(callId)
            }
        }
    }
}
