package com.socksrelay.vertex.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/**
 * Notices when the phone's REAL (non-VPN) internet connection changes —
 * Wi-Fi <-> mobile data, Wi-Fi dropping out, airplane mode on/off — so the
 * VPN can react immediately instead of waiting for its next periodic health
 * check.
 *
 * The VPN's own network is ignored on purpose (it would otherwise look like
 * a "change" every time the tunnel is rebuilt). The "current" network is
 * simply the best usable underlying one: validated beats unvalidated, then
 * Ethernet > Wi-Fi > mobile. Events are debounced so a burst of callbacks
 * during a handover becomes one notification.
 *
 * Callbacks to [Listener] always arrive on the main thread.
 */
class NetworkMonitor(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        /** No usable internet connection is left. */
        fun onNetworkLost()

        /** A different usable connection is now in place (or one came back after a loss). */
        fun onNetworkChanged()
    }

    private companion object {
        const val NONE = -1L
        const val DEBOUNCE_MS = 1500L
    }

    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var currentHandle = NONE
    private var evaluationPending = false
    private var registered = false

    private val evaluate = Runnable {
        evaluationPending = false
        val best = bestNetworkHandle()
        if (best == currentHandle) return@Runnable
        currentHandle = best
        if (best == NONE) listener.onNetworkLost() else listener.onNetworkChanged()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleEvaluation()
        override fun onLost(network: Network) = scheduleEvaluation()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleEvaluation()
    }

    private fun scheduleEvaluation() {
        handler.post {
            if (evaluationPending) return@post
            evaluationPending = true
            handler.postDelayed(evaluate, DEBOUNCE_MS)
        }
    }

    fun start() {
        val cm = connectivityManager ?: return
        if (registered) return
        currentHandle = bestNetworkHandle()
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: Exception) {
            // Network-change detection is a bonus; the periodic health
            // check still works without it.
        }
    }

    fun stop() {
        handler.removeCallbacks(evaluate)
        evaluationPending = false
        if (!registered) return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // already unregistered
        }
        registered = false
    }

    @Suppress("DEPRECATION")
    private fun bestNetworkHandle(): Long {
        val cm = connectivityManager ?: return NONE
        var bestScore = -1
        var bestHandle = NONE
        try {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                var score = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 100
                score += when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 30
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 20
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 10
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    bestHandle = network.networkHandle
                }
            }
        } catch (e: Exception) {
            return currentHandle // can't tell; assume nothing changed
        }
        return bestHandle
    }
}
