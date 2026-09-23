package org.veejr.simple

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person

/**
 * The incoming-call alert: a full-screen, ringing notification that opens
 * straight into the big Answer / Decline screen, even on the lock screen.
 */
object RingNotifier {
    private const val CHANNEL_ID = "incoming_calls"
    private const val NOTIFICATION_ID = 2001

    fun show(context: Context, callId: String, callerLabel: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
            },
        )

        val openRinging = activityIntent(context, callId, MainActivity.ACTION_SHOW_RING, 0)
        val answer = activityIntent(context, callId, MainActivity.ACTION_ANSWER, 1)
        val decline = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, RingActionReceiver::class.java)
                .setAction(RingActionReceiver.ACTION_DECLINE)
                .putExtra(MainActivity.EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val caller = Person.Builder().setName(callerLabel).setImportant(true).build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(callerLabel)
            .setContentText("is calling you")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(openRinging, true)
            .setContentIntent(openRinging)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer).setIsVideo(true))
            .build()
            .apply { flags = flags or android.app.Notification.FLAG_INSISTENT }

        // Without notification permission the ring still shows in-app when
        // the app is open; it just cannot wake a locked phone.
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        try {
            notifications.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun activityIntent(context: Context, callId: String, action: String, requestCode: Int) =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .setAction(action)
                .putExtra(MainActivity.EXTRA_CALL_ID, callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // Matches the server's 60-second stale-ring threshold.
    private const val RING_TIMEOUT_MS = 60_000L
}
