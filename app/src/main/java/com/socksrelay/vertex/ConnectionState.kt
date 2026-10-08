package com.socksrelay.vertex

import android.os.Handler
import android.os.Looper

/**
 * The actual source of truth for "what's the VPN doing right now" —
 * [SocksVpnService] updates this whenever it starts/stops the relay or
 * notices the proxy has gone unreachable, and [MainActivity] reads it fresh
 * every time it becomes visible.
 *
 * [Status] is more than just connected/disconnected because a "kill switch"
 * only means something if there's a state in between: the tunnel can be up
 * (still capturing all device traffic, so nothing leaks to the real network)
 * while the underlying proxy itself is temporarily unreachable.
 *
 *  - [Status.CONNECTED]: tunnel up, proxy healthy, traffic flowing.
 *  - [Status.RECONNECTING]: tunnel still up (still blocking leaks), proxy
 *    currently unreachable, [SocksVpnService]'s health monitor is retrying.
 *  - [Status.BLOCKED]: same as RECONNECTING, but retries have gone on long
 *    enough that we've told the user plainly — Kill Switch is keeping them
 *    offline rather than risk leaking traffic outside the tunnel.
 *  - [Status.DISCONNECTED]: tunnel down, device is back on its normal network.
 *
 * Why this exists as an object (not just a Service field): Android is free
 * to destroy and recreate an Activity while a foreground Service — like this
 * app's VPN — keeps running in the same process. Reading state from here on
 * every `onStart()` keeps the UI in sync regardless of Activity lifecycle.
 */
object ConnectionState {

    enum class Status { DISCONNECTED, CONNECTED, RECONNECTING, BLOCKED }

    @Volatile
    var status: Status = Status.DISCONNECTED
        private set

    /** True for any non-disconnected state — the tunnel is up and capturing traffic either way. */
    val isConnected: Boolean
        get() = status != Status.DISCONNECTED

    private val listeners = mutableListOf<(Status) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setStatus(newStatus: Status) {
        status = newStatus
        mainHandler.post {
            synchronized(this) { listeners.toList() }.forEach { it(newStatus) }
        }
    }

    /** Convenience for the simple connected/disconnected transitions (VPN fully up or fully torn down). */
    fun setConnected(connected: Boolean) {
        setStatus(if (connected) Status.CONNECTED else Status.DISCONNECTED)
    }

    @Synchronized
    fun addListener(listener: (Status) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }
}
