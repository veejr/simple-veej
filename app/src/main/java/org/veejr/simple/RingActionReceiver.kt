package org.veejr.simple

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Decline from the ring notification, without opening the app. */
class RingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE) return
        val callId = intent.getStringExtra(MainActivity.EXTRA_CALL_ID) ?: return
        SimpleVeejApp.from(context).calls.decline(callId)
    }

    companion object {
        const val ACTION_DECLINE = "org.veejr.simple.DECLINE"
    }
}
