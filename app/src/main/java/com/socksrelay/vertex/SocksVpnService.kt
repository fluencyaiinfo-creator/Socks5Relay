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
import com.socksrelay.vertex.net.PacketRouter
import com.socksrelay.vertex.net.ProxyHealthChecker
import com.socksrelay.vertex.net.ProxyProtocol
import com.socksrelay.vertex.net.VpnProtect
import java.io.FileInputStream
import java.io.FileOutputStream

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
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var routerThread: Thread? = null
    private var healthThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var healthMonitorRunning = false
    private var currentProxyHost: String = ""
    private var currentProxyPort: Int = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                AppLog.i(TAG, "Disconnect requested")
                stopRelay()
                stopSelf()
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
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.vpn_notification_title)))
                startRelay(protocol, host, port, username, password)
            }
        }
        return START_STICKY
    }

    private fun startRelay(protocol: ProxyProtocol, proxyHost: String, proxyPort: Int, username: String?, password: String?) {
        if (running) {
            AppLog.i(TAG, "startRelay() called while already running — ignoring")
            return
        }

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
            stopSelf()
            return
        }
        AppLog.success(TAG, "VPN interface established — tun file descriptor is live")
        tunInterface = iface
        running = true
        currentProxyHost = proxyHost
        currentProxyPort = proxyPort
        ConnectionStats.start(protocol, proxyHost, proxyPort)
        ConnectionState.setStatus(ConnectionState.Status.CONNECTED)
        AppLog.success(TAG, "===== CONNECTED — VPN is READY =====")
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

        routerThread = Thread({
            FileInputStream(iface.fileDescriptor).use { input ->
                FileOutputStream(iface.fileDescriptor).use { output ->
                    router.run(input, output) { running }
                }
            }
        }, "PacketRouterThread").also { it.start() }
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
            while (healthMonitorRunning && running) {
                try {
                    Thread.sleep(HEALTH_CHECK_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
                if (!healthMonitorRunning || !running) break

                val healthy = ProxyHealthChecker.isReachable({ s -> VpnProtect.protect(this, s) }, host, port)
                if (healthy) {
                    if (consecutiveFailures > 0) {
                        AppLog.success(TAG, "Proxy connectivity restored after $consecutiveFailures failed check(s)")
                        consecutiveFailures = 0
                        ConnectionState.setStatus(ConnectionState.Status.CONNECTED)
                        updateNotification(getString(R.string.vpn_notification_title))
                    }
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

                if (!killSwitchOn && consecutiveFailures >= MAX_CONSECUTIVE_FAILURES_BEFORE_GIVING_UP) {
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
        running = false
        stopHealthMonitor()
        ConnectionStats.stop()
        ConnectionState.setConnected(false)
        AppLog.w(TAG, "===== DISCONNECTED =====")
        routerThread?.interrupt()
        routerThread = null
        tunInterface?.close()
        tunInterface = null
    }

    override fun onDestroy() {
        stopRelay()
        super.onDestroy()
    }

    override fun onRevoke() {
        // Called if the user revokes VPN permission (e.g. another VPN app
        // took over, or they turned it off from system settings).
        AppLog.w(TAG, "VPN permission revoked by the system", "Another app took over the VPN, or you disabled it in system settings.")
        stopRelay()
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
