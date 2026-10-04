package org.veejr.simple

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Wakes the phone for a ring, and alerts for a new message from the chosen friend. The push names the call and the caller only;
 * answering happens over the authenticated calls socket.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        SimpleVeejApp.from(this).registerPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        android.util.Log.d("SimpleVeejPush", "push ${data["type"]} priority=${message.priority}/${message.originalPriority}")
        if (data["type"] == "new_message") {
            // Content-free: names the sender's handle and kind only.
            val person = SimpleVeejApp.from(this).store.myPerson
            if (person != null && data["kind"] == "message" && data["sender"] == person.handle) {
                MessageNotifier.show(this, person.name)
            }
            return
        }
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
