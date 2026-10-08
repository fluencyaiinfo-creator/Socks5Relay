package com.socksrelay.vertex.ads

import android.content.Context

/**
 * Simple per-placement "don't show again within N milliseconds" guard,
 * persisted to SharedPreferences so the cooldown survives app restarts
 * (an in-memory-only cooldown would reset every time the process dies,
 * defeating the point). Industry rule of thumb for interstitials is
 * roughly one every 3–5 minutes of active use — [MIN_INTERVAL_MS] defaults
 * used below are in that range; tune per placement as you see real data.
 */
object AdFrequencyGuard {
    private const val PREFS_NAME = "ad_frequency_guard"

    fun shouldShow(context: Context, placementKey: String, minIntervalMs: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(placementKey, 0L)
        return System.currentTimeMillis() - last >= minIntervalMs
    }

    fun recordShown(context: Context, placementKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(placementKey, System.currentTimeMillis())
            .apply()
    }
}
