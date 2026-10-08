package com.socksrelay.vertex

import android.content.Context

/**
 * Split tunneling: when enabled with a non-empty app selection, only the
 * selected apps' traffic is routed through the VPN tunnel — everything
 * else on the device bypasses it and uses the normal network directly.
 * When disabled (the default), behavior is unchanged from before: every
 * app's traffic goes through the tunnel.
 *
 * This maps directly onto `VpnService.Builder.addAllowedApplication()` —
 * see `SocksVpnService.kt` for where these selections actually get applied
 * when the tunnel is established.
 */
object SplitTunnelStore {
    private const val PREFS_NAME = "split_tunnel_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PACKAGES = "selected_packages"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun selectedPackages(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()
    }

    fun setSelectedPackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_PACKAGES, packages)
            .apply()
    }
}
