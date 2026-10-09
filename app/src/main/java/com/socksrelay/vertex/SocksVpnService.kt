package com.socksrelay.vertex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.socksrelay.vertex.log.AppLog
import com.socksrelay.vertex.net.DnsCache
import com.socksrelay.vertex.net.NetworkMonitor
import com.socksrelay.vertex.net.PacketRouter
import com.socksrelay.vertex.net.ProxyHealthChecker
import com.socksrelay.vertex.net.ProxyProtocol
import com.socksrelay.vertex.net.VpnProtect
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Owns the tun interface. This is the Android-mandated boilerplate for any
 * app that wants to see all (or a chosen subset of) device traffic:
 *
 *  1. Build a virtual network adapter with Builder() and call establish().
 *  2. Run a foreground service with a persistent notification while active
 *     (required by the OS so a VPN can't silently run in the background).
 *  3. Read raw IP packets from the tun fd, decide what to do with them, and
 *     write responses back into the same fd.
 *
 * The actual per-packet handling (TCP/UDP session tracking + relaying each
 * flow through a real SOCKS5 connection) lives in [PacketRouter] and the
 * rest of the `net` package, kept separate so this class stays focused on
 * lifecycle/OS plumbing.
 */
class SocksVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.socksrelay.vertex.CONNECT"
        const val ACTION_DISCONNECT = "com.socksrelay.vertex.DISCONNECT"
        const val EXTRA_SOCKS_HOST = "extra_socks_host"
        const val EXTRA_SOCKS_PORT = "extra_socks_port"
        const val EXTRA_SOCKS_USERNAME = "extra_socks_username"
        const val EXTRA_SOCKS_PASSWORD = "extra_socks_password"
        const val EXTRA_PROXY_PROTOCOL = "extra_proxy_protocol"

        private const val TAG = "SocksVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ADDRESS_PREFIX_LEN = 32
        private const val VPN_DNS = "1.1.1.1"
        // IPv6 is not proxied by this app, so it is blocked instead: the
        // tunnel claims ALL IPv6 traffic (::/0) via this placeholder
        // address, and PacketRouter drops whatever arrives. Without this,
        // IPv6 traffic would bypass the tunnel and go out on the real
        // network — a leak that neither the proxy nor the Kill Switch sees.
        // (fd00::/8 is a private range, never routable on the internet.)
        private const val VPN_ADDRESS_V6 = "fd00:5a5a:5a5a::2"
        private const val VPN_ADDRESS_V6_PREFIX_LEN = 128
        private const val VPN_MTU = 1500

        // ---- Kill switch / auto-reconnect health monitor ----
        // Deliberately simple fixed-interval polling rather than exponential
        // backoff: this is a teaching-scale relay (see PacketRouter's doc
        // comment), and a fixed interval is much easier to reason about and
        // explain than a variable one, at the cost of a little extra battery
        // while the proxy is down.
        private const val HEALTH_CHECK_INTERVAL_MS = 8_000L
        // ~1 minute of consecutive failures (8s * 8) before we treat the
        // proxy as "really" down rather than a brief blip.
        private const val MAX_CONSECUTIVE_FAILURES_BEFORE_GIVING_UP = 8

        // ---- Auto reconnect ----
        // After the connection drops, wait 5s, 10s, 20s, 40s, then 60s between
        // attempts (each attempt is just a cheap "is the proxy answering?"
        // probe; the VPN tunnel itself is only rebuilt once it is).
        private const val RECONNECT_BASE_DELAY_MS = 5_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
        // ~15 minutes of trying before giving up and fully disconnecting.
        private const val MAX_RECONNECT_ATTEMPTS = 20
        // If the tunnel dies again within this window after being rebuilt,
        // that counts as a "quick drop"; this many in a row means something
        // is fundamentally wrong, so stop instead of looping forever.
        private const val QUICK_DROP_WINDOW_MS = 15_000L
        private const val MAX_QUICK_DROPS = 3
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var routerThread: Thread? = null
    private var healthThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var healthMonitorRunning = false

    // Network-change handling: the monitor reports changes of the phone's
    // real connection; the semaphores let it wake the health-check and
    // reconnect loops immediately instead of letting them sleep out their
    // full interval.
    private var networkMonitor: NetworkMonitor? = null
    @Volatile private var networkAvailable = true
    @Volatile private var activeRouter: PacketRouter? = null
    private val healthWake = Semaphore(0)
    private val reconnectWake = Semaphore(0)
    private var currentProxyHost: String = ""
    private var currentProxyPort: Int = 0

    // Everything needed to rebuild the connection after a drop.
    private var lastProtocol: ProxyProtocol = ProxyProtocol.SOCKS5
    private var lastUsername: String? = null
    private var lastPassword: String? = null
    private var reconnectThread: Thread? = null
    @Volatile private var reconnecting = false
    // True once the user explicitly disconnects (or the system revokes the
    // VPN): auto reconnect must never fight an intentional disconnect.
    @Volatile private var userStopped = false
    // Identifies the current tunnel so a stale router thread from an older
    // tunnel can never trigger a reconnect for a newer one.
    @Volatile private var sessionId = 0
    private var lastEstablishedAt = 0L
    private var quickDrops = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                AppLog.i(TAG, "Disconnect requested")
                userStop()
            }
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_SOCKS_HOST)
                val port = intent.getIntExtra(EXTRA_SOCKS_PORT, 1080)
                val username = intent.getStringExtra(EXTRA_SOCKS_USERNAME)
                val password = intent.getStringExtra(EXTRA_SOCKS_PASSWORD)
                val protocol = ProxyProtocol.fromString(intent.getStringExtra(EXTRA_PROXY_PROTOCOL))
                if (host.isNullOrBlank()) {
                    AppLog.e(TAG, "Connect requested with no host set", "Enter a proxy host before connecting.")
                    return START_NOT_STICKY
                }
                AppLog.i(TAG, "Connect requested: [$protocol] $host:$port" + if (!username.isNullOrEmpty()) " (with auth)" else "")
                // A fresh, explicit Connect replaces any reconnect in progress.
                cancelReconnect()
                userStopped = false
                quickDrops = 0
                startNetworkMonitor()
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.vpn_notification_title)))
                startRelay(protocol, host, port, username, password)
            }
        }
        return START_STICKY
    }

    /** Returns true once the tunnel is up and relaying, false if it could not be established. */
    private fun startRelay(protocol: ProxyProtocol, proxyHost: String, proxyPort: Int, username: String?, password: String?): Boolean {
        if (running) {
            AppLog.i(TAG, "startRelay() called while already running — ignoring")
            return false
        }

        lastProtocol = protocol
        lastUsername = username
        lastPassword = password
        currentProxyHost = proxyHost
        currentProxyPort = proxyPort
        val mySession = ++sessionId

        AppLog.i(TAG, "Building VPN interface (address=$VPN_ADDRESS, dns=$VPN_DNS, mtu=$VPN_MTU, route=0.0.0.0/0)")
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(VPN_MTU)
            .addAddress(VPN_ADDRESS, VPN_ADDRESS_PREFIX_LEN)
            .addDnsServer(VPN_DNS)
            // Routing 0.0.0.0/0 captures ALL device traffic for whichever
            // apps are actually let into the tunnel (see split-tunneling
            // block below) — this line itself doesn't change between
            // whole-device and split-tunnel modes, addAllowedApplication
            // is what narrows who's included.
            .addRoute("0.0.0.0", 0)
            // Without this, the fd's blocking mode isn't guaranteed on every
            // OEM/Android version — some will make input.read() return 0
            // immediately instead of waiting for a packet, which turns the
            // router loop into a CPU-burning busy-spin instead of an
            // efficient blocking read.
            .setBlocking(true)

        // Capture IPv6 too, so it can be blocked rather than leak (see
        // VPN_ADDRESS_V6). If a device refuses this, connecting still works
        // exactly as before, but IPv6 may leak, so say so clearly in the logs.
        try {
            builder.addAddress(VPN_ADDRESS_V6, VPN_ADDRESS_V6_PREFIX_LEN)
            builder.addRoute("::", 0)
            AppLog.i(TAG, "IPv6 traffic is blocked (apps will use IPv4 through the proxy)")
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not block IPv6 on this device: ${e.message}",
                "IPv6 traffic may bypass the proxy. If your network has IPv6, turn it off for a strict leak-free setup.")
        }

        // IMPORTANT: do NOT also call builder.addDisallowedApplication(packageName)
        // here. protect() (used per-socket in PacketRouter/Socks5Client) and
        // addDisallowedApplication (app-level exclusion) are two different,
        // overlapping mechanisms for the same goal — and combining them is
        // known to make protect() start failing on real devices, because a
        // socket belonging to an already app-excluded process has "nothing
        // to protect" from the tunnel's point of view. Pick one; this
        // project relies on protect() being called for every relay socket,
        // so app-level exclusion must be left off.

        applySplitTunneling(builder)

        val iface = builder.establish()
        if (iface == null) {
            AppLog.e(TAG, "Builder.establish() returned null",
                "Android refused to create the VPN interface. This usually means VPN permission wasn't " +
                    "actually granted, or another VPN app grabbed it first. Try disconnecting any other " +
                    "VPN app and reconnecting from this app's Connect button (not just relying on a " +
                    "previous grant).")
            if (tunInterface != null) {
                // Kill Switch is holding the old tunnel up (blocking, not
                // leaking) while we reconnect. Keep holding it and let the
                // reconnect loop try again — tearing everything down here
                // would open the very leak the Kill Switch exists to prevent.
                return false
            }
            reconnecting = false
            ConnectionState.setStatus(ConnectionState.Status.DISCONNECTED)
            stopSelf()
            return false
        }
        AppLog.success(TAG, "VPN interface established — tun file descriptor is live")
        val previousTunnel = tunInterface
        tunInterface = iface
        // If a Kill-Switch hold kept the old (reader-less, traffic-blocking)
        // tunnel up, the new one is in place now, so closing the old one
        // can't open a gap.
        previousTunnel?.close()
        running = true
        currentProxyHost = proxyHost
        currentProxyPort = proxyPort
        ConnectionStats.start(protocol, proxyHost, proxyPort)
        ConnectionState.setStatus(ConnectionState.Status.CONNECTED)
        lastEstablishedAt = System.currentTimeMillis()
        // A different proxy can mean a different exit location, so don't
        // serve DNS answers cached for the previous one.
        DnsCache.clear()
        startHealthMonitor(proxyHost, proxyPort)

        val router = PacketRouter(
            vpnInterface = iface,
            protectSocket = { socket -> VpnProtect.protect(this, socket) },
            protectDatagram = { datagram -> VpnProtect.protect(this, datagram) },
            proxyProtocol = protocol,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = username,
            proxyPassword = password
        )
        activeRouter = router

        routerThread = Thread({
            try {
                FileInputStream(iface.fileDescriptor).use { input ->
                    FileOutputStream(iface.fileDescriptor).use { output ->
                        router.run(input, output) { running }
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Packet router stopped unexpectedly: ${e.message}")
            }
            // If the tunnel died on its own (we did not tear it down and the
            // user did not disconnect), bring it back automatically.
            if (running && !userStopped && mySession == sessionId &&
                SettingsStore.isAutoReconnectEnabled(this)) {
                beginAutoReconnect("The VPN tunnel stopped unexpectedly")
            }
        }, "PacketRouterThread").also { it.start() }

        AppLog.success(
            TAG, "===== CONNECTED. READY TO USE =====",
            "All your traffic now goes through [$protocol] $proxyHost:$proxyPort."
        )
        return true
    }

    /**
     * Reacts to the phone's real network changing (Wi-Fi <-> mobile, a
     * network dropping out, airplane mode, ...):
     *  - Tunnel up: RST every open connection (they were bound to the old
     *    network and would otherwise hang until they time out) so apps
     *    reconnect over the new one at once, and run a health check NOW.
     *  - Auto-reconnecting: wake the reconnect loop right away, with its
     *    backoff restarted, instead of waiting out a long delay.
     * No network at all is not the proxy's fault, so it never counts toward
     * giving up (see the health monitor).
     */
    private fun startNetworkMonitor() {
        if (networkMonitor != null) return
        networkAvailable = true
        networkMonitor = NetworkMonitor(this, object : NetworkMonitor.Listener {
            override fun onNetworkLost() {
                networkAvailable = false
                if (userStopped) return
                AppLog.w(TAG, "No internet connection — the VPN will reconnect as soon as a network is back")
            }

            override fun onNetworkChanged() {
                networkAvailable = true
                if (userStopped) return
                AppLog.i(TAG, "Network changed — refreshing the connection")
                if (reconnecting) {
                    reconnectWake.release()
                } else if (running) {
                    val reset = activeRouter?.resetAllFlows() ?: 0
                    if (reset > 0) {
                        AppLog.i(TAG, "Reset $reset open connection(s) so apps reconnect over the new network")
                    }
                    healthWake.release()
                }
            }
        }).also { it.start() }
    }

    private fun stopNetworkMonitor() {
        networkMonitor?.stop()
        networkMonitor = null
    }

    /**
     * Auto reconnect: the tunnel is closed (restoring the normal network so
     * nothing is stuck) and a background thread keeps probing the proxy with
     * increasing delays; the moment it answers, the tunnel is rebuilt with
     * the same proxy settings. Status stays RECONNECTING in the meantime, so
     * the UI still shows the connection as active (and no ad requests are
     * made) until it either recovers or gives up.
     */
    @Synchronized
    private fun beginAutoReconnect(reason: String) {
        if (reconnecting || userStopped) return

        // Kill Switch ON means "never let traffic out unproxied", so while
        // reconnecting the tunnel is HELD UP with nothing reading it (every
        // packet just goes nowhere = blocked) instead of being released.
        // Kill Switch OFF releases it so the normal network works meanwhile.
        val holdTunnel = SettingsStore.isKillSwitchEnabled(this)

        val now = System.currentTimeMillis()
        quickDrops = if (now - lastEstablishedAt < QUICK_DROP_WINDOW_MS) quickDrops + 1 else 0
        if (quickDrops >= MAX_QUICK_DROPS) {
            if (holdTunnel) {
                releaseRouterKeepTunnel()
                ConnectionState.setStatus(ConnectionState.Status.BLOCKED)
                AppLog.e(TAG, "The connection keeps dropping right after connecting — traffic stays blocked",
                    "Kill Switch is ON, so nothing is allowed out unproxied. Tap Disconnect, check that the " +
                        "proxy is working (use Test proxy), then connect again.")
            } else {
                AppLog.e(TAG, "The connection keeps dropping right after connecting — giving up",
                    "Check that the proxy is working (use Test proxy), then connect again.")
                giveUpAndDisconnect()
            }
            return
        }

        val host = currentProxyHost
        val port = currentProxyPort
        val protocol = lastProtocol
        val username = lastUsername
        val password = lastPassword

        reconnecting = true
        reconnectWake.drainPermits()
        AppLog.w(TAG, "$reason — reconnecting automatically to $host:$port...",
            if (holdTunnel) "Kill Switch is ON, so traffic stays blocked (not leaked) until the proxy is back." else null)
        if (holdTunnel) releaseRouterKeepTunnel() else closeTunnel()
        ConnectionState.setStatus(ConnectionState.Status.RECONNECTING)

        reconnectThread = Thread({
            var attempt = 0
            var restored = false
            while (reconnecting && !userStopped) {
                // Kill Switch ON never gives up on its own (that would mean
                // releasing the tunnel); OFF stops after a while.
                if (!holdTunnel && attempt >= MAX_RECONNECT_ATTEMPTS) break
                attempt++
                val wait = minOf(
                    RECONNECT_BASE_DELAY_MS * (1L shl minOf(attempt - 1, 4)),
                    RECONNECT_MAX_DELAY_MS
                )
                try {
                    // Sleep out the delay, unless the network changes: then
                    // try straight away and restart the backoff.
                    if (reconnectWake.tryAcquire(wait, TimeUnit.MILLISECONDS)) {
                        reconnectWake.drainPermits()
                        attempt = 1
                    }
                } catch (e: InterruptedException) {
                    break
                }
                if (!reconnecting || userStopped) break

                // With the tunnel released, this probe uses the normal
                // network and needs no protect(). With it held, the probe
                // MUST be protect()-ed or it would run into the blocked
                // tunnel and always fail.
                val proxyUp = if (holdTunnel) {
                    ProxyHealthChecker.isReachable({ sock -> VpnProtect.protect(this, sock) }, host, port)
                } else {
                    ProxyHealthChecker.isReachable({ _ -> true }, host, port)
                }
                if (!proxyUp) {
                    val outOf = if (holdTunnel) "" else "/$MAX_RECONNECT_ATTEMPTS"
                    AppLog.i(TAG, "Reconnect attempt $attempt$outOf: $host:$port not reachable yet")
                    continue
                }

                AppLog.i(TAG, "$host:$port is reachable again — restoring the VPN tunnel")
                reconnecting = false
                if (startRelay(protocol, host, port, username, password)) {
                    restored = true
                    if (userStopped) closeTunnel() // user hit Disconnect mid-restore
                    break
                }
                // Couldn't rebuild the tunnel this time. If it's being held
                // (Kill Switch), keep trying; otherwise startRelay already
                // shut things down.
                if (tunInterface == null) break
                reconnecting = true
            }

            if (!restored && reconnecting && !userStopped) {
                AppLog.e(TAG, "Could not reconnect to $host:$port after $MAX_RECONNECT_ATTEMPTS attempts — giving up",
                    "The proxy may be permanently down. Try Test proxy, or connect with a different one.")
                giveUpAndDisconnect()
            }
        }, "AutoReconnect").also { it.start() }
    }

    /**
     * Stops relaying and health-checking but keeps the tun interface OPEN
     * with nothing reading it. Every packet apps send into it just goes
     * nowhere, so traffic is blocked rather than leaking out on the real
     * network. Used by the Kill Switch while reconnecting.
     */
    private fun releaseRouterKeepTunnel() {
        running = false
        activeRouter = null
        stopHealthMonitor()
        routerThread?.interrupt()
        routerThread = null
    }

    private fun giveUpAndDisconnect() {
        reconnecting = false
        closeTunnel()
        ConnectionStats.stop()
        ConnectionState.setStatus(ConnectionState.Status.DISCONNECTED)
        AppLog.w(TAG, "===== DISCONNECTED =====")
        stopSelf()
    }

    private fun cancelReconnect() {
        reconnecting = false
        reconnectThread?.interrupt()
        reconnectThread = null
    }

    /** Explicit Disconnect from the user: always final, never auto-reconnected. */
    private fun userStop() {
        userStopped = true
        cancelReconnect()
        val wasActive = running || tunInterface != null
        stopRelay()
        ConnectionStats.stop()
        // Also correct the state when we were mid-reconnect (no tunnel up),
        // where stopRelay() has nothing to tear down.
        ConnectionState.setStatus(ConnectionState.Status.DISCONNECTED)
        if (!wasActive) AppLog.w(TAG, "===== DISCONNECTED =====")
        stopSelf()
    }

    /**
     * Split tunneling uses `addAllowedApplication()` — the same *category*
     * of mechanism (app-level tunnel scoping) as `addDisallowedApplication`,
     * which we deliberately never use for this app's own package (see the
     * big comment above this call site — combining app-level exclusion
     * with per-socket protect() broke protect() entirely on real devices).
     *
     * The same risk applies here in reverse: if the user selects apps for
     * split tunneling and does NOT include this app itself, this app's own
     * relay sockets (opened deep inside PacketRouter to reach the actual
     * proxy) would be implicitly excluded from the tunnel too — same bug,
     * different door. Fix: always add our own package to the allow-list
     * alongside whatever the user picked, so our sockets stay "inside the
     * tunnel's jurisdiction" and protect() keeps having something real to
     * exempt them from.
     */
    private fun applySplitTunneling(builder: Builder) {
        if (!SplitTunnelStore.isEnabled(this)) return
        val selected = SplitTunnelStore.selectedPackages(this)
        if (selected.isEmpty()) {
            AppLog.w(TAG, "Split tunneling is enabled but no apps are selected — ignoring " +
                "(an empty allow-list would let nothing through the tunnel at all, including this app)")
            return
        }

        AppLog.i(TAG, "Split tunneling enabled for ${selected.size} app(s)")
        var added = 0
        for (pkg in selected + packageName) {
            try {
                builder.addAllowedApplication(pkg)
                added++
            } catch (e: PackageManager.NameNotFoundException) {
                AppLog.w(TAG, "Split tunneling: $pkg is no longer installed, skipping")
            }
        }
        AppLog.i(TAG, "Split tunneling: $added app(s) (including this one) will use the tunnel; everything else bypasses it")
    }

    /**
     * Periodically probes the proxy in the background while connected (see
     * [ProxyHealthChecker]) and updates [ConnectionState] accordingly:
     *
     *  - Healthy again after failures -> back to CONNECTED.
     *  - A handful of consecutive failures -> RECONNECTING. The tunnel is
     *    left exactly as-is here: it was already "fail closed" per-flow
     *    (see [com.socksrelay.vertex.net.PacketRouter] — a failed proxy
     *    connect sends the device a TCP RST, it never falls back to a
     *    direct connection), so simply continuing to hold the tun interface
     *    open already prevents any leak. Nothing to "reconnect" at the
     *    socket level; each new flow just keeps retrying the proxy on its
     *    own the next time the device opens a connection.
     *  - Enough consecutive failures that this looks like more than a blip
     *    -> Kill Switch decides what happens next: ON keeps the tunnel up
     *    and blocking indefinitely (BLOCKED); OFF gives up and disconnects,
     *    restoring the device's normal network.
     */
    private fun startHealthMonitor(host: String, port: Int) {
        healthMonitorRunning = true
        healthThread = Thread({
            var consecutiveFailures = 0
            var degraded = false
            healthWake.drainPermits()
            while (healthMonitorRunning && running) {
                try {
                    // One interval — or less, if a network change wakes us.
                    if (healthWake.tryAcquire(HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                        healthWake.drainPermits()
                    }
                } catch (e: InterruptedException) {
                    break
                }
                if (!healthMonitorRunning || !running) break

                val healthy = ProxyHealthChecker.isReachable({ s -> VpnProtect.protect(this, s) }, host, port)
                if (healthy) {
                    if (degraded) {
                        AppLog.success(TAG, "===== CONNECTED. READY TO USE =====",
                            if (consecutiveFailures > 0) "Reconnected to $host:$port after $consecutiveFailures failed check(s)."
                            else "Reconnected to $host:$port after the network came back.")
                        consecutiveFailures = 0
                        degraded = false
                        ConnectionState.setStatus(ConnectionState.Status.CONNECTED)
                        updateNotification(getString(R.string.vpn_notification_title))
                    }
                    continue
                }

                degraded = true
                if (!networkAvailable) {
                    // The phone has no internet at all — that says nothing
                    // about the proxy, so don't count it toward giving up.
                    // The network monitor wakes this loop the moment a
                    // network returns.
                    ConnectionState.setStatus(ConnectionState.Status.RECONNECTING)
                    continue
                }

                consecutiveFailures++
                val killSwitchOn = SettingsStore.isKillSwitchEnabled(this)
                // Log only the transition into "unhealthy", not every
                // periodic retry while it stays that way — logging on
                // every 8-second tick for as long as a flaky proxy stays
                // down could produce hundreds of entries in one session,
                // which is exactly what made View Logs crash trying to
                // render it all as one giant block of text.
                if (consecutiveFailures == 1) {
                    AppLog.w(TAG, "Proxy health check failed, retrying in the background: $host:$port not reachable",
                        "The proxy may have died, or you lost general network connectivity. " +
                            if (killSwitchOn) "Kill Switch is ON, so the tunnel stays up and blocked rather than leaking traffic."
                            else "Kill Switch is OFF, so this app will disconnect the VPN if this keeps failing.")
                }

                if (!killSwitchOn && consecutiveFailures >= MAX_CONSECUTIVE_FAILURES_BEFORE_GIVING_UP &&
                    SettingsStore.isAutoReconnectEnabled(this)) {
                    // Auto reconnect: release the tunnel (kill switch is off,
                    // so the normal network is restored meanwhile) and keep
                    // retrying in the background instead of giving up.
                    beginAutoReconnect("Proxy $host:$port stopped responding")
                    break
                } else if (!killSwitchOn && consecutiveFailures >= MAX_CONSECUTIVE_FAILURES_BEFORE_GIVING_UP) {
                    AppLog.e(TAG, "Giving up on the proxy after $consecutiveFailures failed checks — disconnecting VPN",
                        "Kill Switch is off, so normal internet has been restored instead of staying blocked. " +
                            "Turn Kill Switch on in Settings if you'd rather stay offline than risk unproxied traffic.")
                    running = false
                    ConnectionState.setStatus(ConnectionState.Status.DISCONNECTED)
                    stopRelay()
                    stopSelf()
                    break
                } else {
                    val status = if (killSwitchOn && consecutiveFailures >= MAX_CONSECUTIVE_FAILURES_BEFORE_GIVING_UP) {
                        ConnectionState.Status.BLOCKED
                    } else {
                        ConnectionState.Status.RECONNECTING
                    }
                    // Intentionally NOT reflected in the notification (or
                    // anywhere else user-visible — see MainActivity's
                    // setConnectedUi doc comment). ConnectionState.status
                    // still updates for internal logic (kill switch's
                    // give-up threshold above reads it indirectly via
                    // consecutiveFailures), and AppLog above still records
                    // the real detail for anyone who opens View Logs — this
                    // only holds back the ambient, ongoing notification.
                    ConnectionState.setStatus(status)
                }
            }
        }, "ProxyHealthMonitor").also { it.start() }
    }

    private fun stopHealthMonitor() {
        healthMonitorRunning = false
        healthThread?.interrupt()
        healthThread = null
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun stopRelay() {
        if (!running && tunInterface == null) return
        AppLog.i(TAG, "Tearing down VPN interface")
        ConnectionStats.stop()
        ConnectionState.setConnected(false)
        AppLog.w(TAG, "===== DISCONNECTED =====")
        closeTunnel()
    }

    /** Releases the tun interface and its threads, without touching connection state. */
    private fun closeTunnel() {
        running = false
        activeRouter = null
        stopHealthMonitor()
        routerThread?.interrupt()
        routerThread = null
        tunInterface?.close()
        tunInterface = null
    }

    override fun onDestroy() {
        userStopped = true
        stopNetworkMonitor()
        cancelReconnect()
        stopRelay()
        super.onDestroy()
    }

    override fun onRevoke() {
        // Called if the user revokes VPN permission (e.g. another VPN app
        // took over, or they turned it off from system settings).
        AppLog.w(TAG, "VPN permission revoked by the system", "Another app took over the VPN, or you disabled it in system settings.")
        // Permission is gone, so reconnecting automatically can't work (and
        // would fight whatever took over the VPN).
        userStopped = true
        stopNetworkMonitor()
        cancelReconnect()
        stopRelay()
        ConnectionState.setStatus(ConnectionState.Status.DISCONNECTED)
        super.onRevoke()
    }

    private fun buildNotification(contentText: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
