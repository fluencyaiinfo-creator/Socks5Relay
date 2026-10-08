package com.socksrelay.vertex

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.socksrelay.vertex.net.ProxyProtocol

data class SocksSettings(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val protocol: ProxyProtocol = ProxyProtocol.SOCKS5
)

/**
 * Remembers your proxy host/port/credentials/protocol and theme preference
 * across app restarts, so you only have to type/pick them once.
 *
 * NOTE: this uses plain (unencrypted) SharedPreferences. That's fine for a
 * personal test proxy on your own device, but if these are credentials you
 * care about protecting (e.g. against another app or a rooted device
 * reading them), swap this for `androidx.security.crypto.EncryptedSharedPreferences`
 * instead — same API shape, just an encrypted backing store.
 */
object SettingsStore {
    private const val PREFS_NAME = "socks_relay_settings"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_KILL_SWITCH_ENABLED = "kill_switch_enabled"

    fun save(context: Context, settings: SocksSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, settings.host)
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_USERNAME, settings.username)
            .putString(KEY_PASSWORD, settings.password)
            .putString(KEY_PROTOCOL, settings.protocol.name)
            .apply()
    }

    /** Returns null if nothing has ever been saved (first run). */
    fun load(context: Context): SocksSettings? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null) ?: return null
        return SocksSettings(
            host = host,
            port = prefs.getInt(KEY_PORT, 1080),
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            protocol = ProxyProtocol.fromString(prefs.getString(KEY_PROTOCOL, null))
        )
    }

    /** One of [AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM] / `MODE_NIGHT_NO` / `MODE_NIGHT_YES`. */
    fun saveThemeMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_THEME_MODE, mode)
            .apply()
    }

    fun loadThemeMode(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    /**
     * When true (the default), [SocksVpnService]'s health monitor keeps the
     * tunnel up and blocking ALL traffic if the proxy becomes unreachable,
     * rather than ever falling back to the device's normal network. When
     * false, after enough consecutive failed health checks the service
     * gives up and disconnects the VPN entirely, restoring normal internet.
     */
    fun isKillSwitchEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KILL_SWITCH_ENABLED, true)
    }

    fun setKillSwitchEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_KILL_SWITCH_ENABLED, enabled)
            .apply()
    }
}

