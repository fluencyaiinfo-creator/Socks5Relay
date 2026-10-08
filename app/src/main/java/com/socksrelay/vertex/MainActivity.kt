package com.socksrelay.vertex

import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.socksrelay.vertex.ads.AdIds
import com.socksrelay.vertex.ads.AdManager
import com.socksrelay.vertex.ads.AdPlacements
import com.socksrelay.vertex.ads.ConsentManager
import com.socksrelay.vertex.ads.ProxyTestAdCounter
import com.socksrelay.vertex.databinding.ActivityMainBinding
import com.socksrelay.vertex.log.AppLog
import com.socksrelay.vertex.net.ProxyProtocol
import com.socksrelay.vertex.net.ProxyTester

/**
 * Entry point UI. This does:
 *  1. Collect SOCKS5 host/port + optional username/password (pre-filled
 *     from [SettingsStore] if you've used the app before).
 *  2. Let the user run a real, standalone connectivity + geolocation test
 *     against the proxy (see [ProxyTester]) before ever touching the VPN.
 *  3. Ask the OS for permission to create a VPN interface (VpnService.prepare).
 *  4. Start/stop SocksVpnService, which owns the actual tun interface + relay.
 *  5. Open [LogsActivity] to see exactly what happened, in plain English.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            updateConnectionStats()
            if (ConnectionState.isConnected) statsHandler.postDelayed(this, 1000L)
        }
    }

    // Live-updates the button/status if the VPN connects, disconnects, or
    // the health monitor reports the proxy is down, while this screen
    // happens to be visible.
    private val connectionListener: (ConnectionState.Status) -> Unit = { status ->
        runOnUiThread { setConnectedUi(status) }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpn()
            } else {
                AppLog.w("MainActivity", "User declined the VPN permission dialog")
                setConnectedUi(ConnectionState.status)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreSavedSettings()

        // Consent (required before loading ads in the EEA/UK, harmless
        // no-op elsewhere) must resolve before the Mobile Ads SDK
        // initializes anything — see ConsentManager's doc comment.
        ConsentManager.requestConsent(this) {
            if (ConsentManager.canRequestAds(this)) {
                AdManager.initializeIfNeeded(this)
            }
        }

        binding.connectButton.setOnClickListener {
            if (ConnectionState.isConnected) {
                stopVpn()
            } else {
                persistCurrentSettings()
                // Pre-connect interstitial fires here, deliberately BEFORE
                // requestVpnPermissionThenStart() — once the VPN interface
                // is up, this app's own traffic (including any ad request)
                // gets routed through the tunnel too, which is unreliable
                // when the tunnel is a random free proxy. Showing (or
                // skipping, if capped/unavailable) the ad first means it
                // always runs over the real network.
                AdManager.showInterstitial(
                    this, AdIds.interstitialPreConnect,
                    AdPlacements.KEY_PRE_CONNECT, AdPlacements.PRE_CONNECT_MIN_INTERVAL_MS
                ) {
                    requestVpnPermissionThenStart()
                }
            }
        }

        binding.testProxyButton.setOnClickListener {
            persistCurrentSettings()
            // Cadence: ad on the 1st test ever, then every 3rd after that
            // (see ProxyTestAdCounter). Combined with AdManager's VPN-state
            // check and AdFrequencyGuard's time cooldown inside
            // showInterstitial — this is just the "is it this test's turn"
            // part of the decision.
            if (ProxyTestAdCounter.recordTestAndCheckCadence(this)) {
                AdManager.showInterstitial(
                    this, AdIds.interstitialProxyTest,
                    AdPlacements.KEY_PROXY_TEST, AdPlacements.PROXY_TEST_MIN_INTERVAL_MS
                ) {
                    runProxyTest()
                }
            } else {
                runProxyTest()
            }
        }
        binding.logsButton.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        binding.pasteApplyButton.setOnClickListener { applyPastedProxyString() }
        binding.freeProxiesButton.setOnClickListener {
            startActivity(Intent(this, CountryListActivity::class.java))
        }
        binding.favoritesButton.setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        // This is the fix for "app said Connect but the VPN was still up":
        // don't trust whatever this Activity instance last remembered —
        // Android can recreate it while the VPN's own Service keeps running
        // in the background, which used to leave the button out of sync.
        // Read the real, current state every time this screen appears.
        setConnectedUi(ConnectionState.status)
        updateConnectionStats()
        statsHandler.removeCallbacks(statsRunnable)
        if (ConnectionState.isConnected) statsHandler.post(statsRunnable)
        ConnectionState.addListener(connectionListener)

        // General "it's been a while" engagement interstitial — gated
        // entirely by AdFrequencyGuard's cooldown (see AdPlacements), so
        // this check is cheap and safe to run on every return to this
        // screen; it only actually shows anything once the interval's up.
        AdManager.showInterstitial(
            this, AdIds.interstitialEngagement,
            AdPlacements.KEY_ENGAGEMENT, AdPlacements.ENGAGEMENT_MIN_INTERVAL_MS
        ) { }
    }

    override fun onStop() {
        statsHandler.removeCallbacks(statsRunnable)
        ConnectionState.removeListener(connectionListener)
        super.onStop()
    }

    /**
     * Parses whatever's in the paste field (ip:port or ip:port@user:pass)
     * and fills the individual host/port/username/password fields from it,
     * so people don't have to hunt for four separate inputs when they've
     * been handed one string by wherever they got the proxy from.
     */
    private fun applyPastedProxyString() {
        val raw = binding.pasteInput.text?.toString().orEmpty()
        val parsed = ProxyStringParser.parse(raw)
        if (parsed == null) {
            Toast.makeText(this, R.string.paste_parse_failed, Toast.LENGTH_LONG).show()
            AppLog.w("MainActivity", "Could not parse pasted proxy string: \"$raw\"",
                "Expected format: ip:port or ip:port@username:password")
            return
        }
        binding.hostInput.setText(parsed.host)
        binding.portInput.setText(parsed.port.toString())
        binding.usernameInput.setText(parsed.username.orEmpty())
        binding.passwordInput.setText(parsed.password.orEmpty())
        binding.pasteInput.text?.clear()
        persistCurrentSettings()
        Toast.makeText(this, R.string.paste_parse_success, Toast.LENGTH_SHORT).show()
        AppLog.i("MainActivity", "Filled fields from pasted proxy string (host=${parsed.host}, port=${parsed.port})")
    }

    private fun restoreSavedSettings() {
        val saved = SettingsStore.load(this) ?: return
        binding.hostInput.setText(saved.host)
        binding.portInput.setText(saved.port.toString())
        binding.usernameInput.setText(saved.username)
        binding.passwordInput.setText(saved.password)
        setProtocolSelection(saved.protocol)
    }

    private fun persistCurrentSettings() {
        SettingsStore.save(
            this,
            SocksSettings(
                host = currentHost(),
                port = currentPort(),
                username = currentUsername(),
                password = currentPassword(),
                protocol = currentProtocol()
            )
        )
    }

    private fun currentProtocol(): ProxyProtocol = when (binding.protocolRadioGroup.checkedRadioButtonId) {
        R.id.protocolSocks4 -> ProxyProtocol.SOCKS4
        R.id.protocolHttp -> ProxyProtocol.HTTP
        else -> ProxyProtocol.SOCKS5
    }

    private fun setProtocolSelection(protocol: ProxyProtocol) {
        val id = when (protocol) {
            ProxyProtocol.SOCKS4 -> R.id.protocolSocks4
            ProxyProtocol.HTTP -> R.id.protocolHttp
            ProxyProtocol.SOCKS5 -> R.id.protocolSocks5
        }
        binding.protocolRadioGroup.check(id)
    }

    override fun onPause() {
        super.onPause()
        // Belt-and-suspenders: also persist on pause/backgrounding, not just
        // on button taps, so edits aren't lost if the app is closed some
        // other way (home button, task switcher, etc.).
        persistCurrentSettings()
    }

    private fun currentHost() = binding.hostInput.text?.toString()?.trim().orEmpty()
    private fun currentPort() = binding.portInput.text?.toString()?.trim()?.toIntOrNull() ?: 1080
    private fun currentUsername() = binding.usernameInput.text?.toString()?.trim().orEmpty()
    private fun currentPassword() = binding.passwordInput.text?.toString()?.trim().orEmpty()

    /**
     * Runs a full SOCKS5 handshake + real HTTP request + IP geolocation
     * lookup against the proxy, completely independent of the VPN — so you
     * can find out "is my proxy config right, and where does it actually
     * exit from?" before ever touching tun/routing. Runs on a plain
     * background thread (this app doesn't pull in coroutines) and reports
     * back to the UI thread when done.
     */
    private fun runProxyTest() {
        val host = currentHost()
        val port = currentPort()
        val username = currentUsername()
        val password = currentPassword()

        binding.testProxyButton.isEnabled = false
        binding.testResultText.visibility = android.view.View.VISIBLE
        binding.testResultText.text = getString(R.string.testing_proxy)

        Thread({
            val result = ProxyTester.test(currentProtocol(), host, port, username, password)
            runOnUiThread {
                binding.testProxyButton.isEnabled = true
                binding.testResultText.text = formatTestResult(result)
            }
        }, "ProxyTestThread").start()
    }

    private fun formatTestResult(result: ProxyTester.Result): String {
        if (!result.success) {
            return "\u274C Proxy test failed (${result.elapsedMs}ms) — ${result.message}\nTap \"View logs\" for details."
        }
        val geo = result.geo
        val header = "\u2705 Proxy OK (${result.elapsedMs}ms)"
        if (geo == null) {
            return "$header\nConnected, but couldn't fetch exit-IP details this time."
        }
        val lines = mutableListOf(header)
        geo.ip?.let { lines.add("IP: $it") }
        val place = listOfNotNull(geo.city, geo.region, geo.zip, geo.country)
        if (place.isNotEmpty()) lines.add("Location: ${place.joinToString(", ")}")
        geo.timezone?.let { lines.add("Timezone: $it") }
        geo.isp?.let { lines.add("ISP: $it") }
        return lines.joinToString("\n")
    }

    private fun requestVpnPermissionThenStart() {
        // VpnService.prepare() returns null if the user already granted this
        // app permission previously (or if some other VPN app currently owns
        // the tun interface and needs to be usurped, which also triggers null
        // after consent). If it returns an Intent, we must launch it and wait
        // for the result before calling establish() in the service.
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            vpnPermissionLauncher.launch(consentIntent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, SocksVpnService::class.java).apply {
            action = SocksVpnService.ACTION_CONNECT
            putExtra(SocksVpnService.EXTRA_SOCKS_HOST, currentHost())
            putExtra(SocksVpnService.EXTRA_SOCKS_PORT, currentPort())
            putExtra(SocksVpnService.EXTRA_SOCKS_USERNAME, currentUsername())
            putExtra(SocksVpnService.EXTRA_SOCKS_PASSWORD, currentPassword())
            putExtra(SocksVpnService.EXTRA_PROXY_PROTOCOL, currentProtocol().name)
        }
        startService(intent)
        // Optimistic immediate feedback — SocksVpnService will confirm (or
        // correct) this via ConnectionState once establish() actually
        // completes, a moment later.
        setConnectedUi(ConnectionState.Status.CONNECTED)
    }

    private fun stopVpn() {
        val intent = Intent(this, SocksVpnService::class.java).apply {
            action = SocksVpnService.ACTION_DISCONNECT
        }
        startService(intent)
        setConnectedUi(ConnectionState.Status.DISCONNECTED)
    }

    /**
     * Deliberately collapses [ConnectionState.Status] down to just two
     * user-facing states: "Connected" or "Disconnected". RECONNECTING and
     * BLOCKED are real internal states (the health monitor in
     * SocksVpnService still runs, kill switch still protects you, retries
     * still happen) — they're just never surfaced here. A brief health
     * check failure doesn't always mean the proxy is actually down, and
     * flashing that uncertainty at the user does more harm than good; the
     * tunnel is either up (safe, blocking leaks either way) or it's down.
     */
    private fun setConnectedUi(status: ConnectionState.Status) {
        val connected = status != ConnectionState.Status.DISCONNECTED
        binding.statusText.text = getString(
            if (connected) R.string.status_connected else R.string.status_disconnected
        )
        val statusColor = ContextCompat.getColor(
            this,
            if (connected) R.color.status_connected_green else R.color.status_disconnected_gray
        )
        binding.statusText.setTextColor(statusColor)
        binding.statusIndicator.setTextColor(statusColor)

        binding.connectButton.text = getString(
            if (connected) R.string.action_disconnect else R.string.action_connect
        )
        binding.connectButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (connected) R.color.button_disconnect_red else R.color.button_connect_default
            )
        )
        updateConnectionStats()
        if (connected) {
            statsHandler.removeCallbacks(statsRunnable)
            statsHandler.post(statsRunnable)
        } else {
            statsHandler.removeCallbacks(statsRunnable)
        }
    }

    private fun updateConnectionStats() {
        val stats = ConnectionStats.snapshot()
        binding.uploadText.text = formatBytes(stats.uploadedBytes)
        binding.downloadText.text = formatBytes(stats.downloadedBytes)

        if (stats.startedAtMs > 0L) {
            val elapsed = (System.currentTimeMillis() - stats.startedAtMs).coerceAtLeast(0L)
            binding.sessionTimeText.text = formatDuration(elapsed)
        } else {
            binding.sessionTimeText.text = "00:00:00"
        }

        if (stats.active && stats.proxyHost.isNotBlank()) {
            binding.activeProxyText.text = "${stats.proxyHost}:${stats.proxyPort}"
            binding.protocolText.text = stats.protocol.name.replace('_', ' ')
        } else {
            binding.activeProxyText.text = getString(R.string.stat_no_active_connection)
            binding.protocolText.text = getString(R.string.stat_protocol_not_connected)
        }
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.US, "%.2f GB", gb)
    }
}
