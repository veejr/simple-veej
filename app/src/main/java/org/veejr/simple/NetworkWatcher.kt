package org.veejr.simple

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Whether the phone currently has a default network, so the calls socket can
 * sleep while offline and reconnect the moment a network returns.
 */
class NetworkWatcher(context: Context) {
    // Starts true: if the callback cannot be registered, behave as before
    // and simply keep retrying.
    private val online = MutableStateFlow(true)

    init {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            online.value = manager.activeNetwork != null
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        online.value = true
                    }

                    override fun onLost(network: Network) {
                        online.value = false
                    }
                },
            )
        }
    }

    /** Returns at once when online; otherwise suspends until a network appears. */
    suspend fun awaitOnline() {
        online.first { it }
    }
}
