package com.socksrelay.vertex.ads

import android.content.Context

/**
 * Counts "test proxy" taps (persisted, never resets) to decide the
 * requested cadence: show on the 1st test ever, then again after every 3
 * more (so test #1, #4, #7, #10…). This is intentionally separate from
 * [AdFrequencyGuard]'s time-based cooldown — [AdManager.showInterstitial]
 * combines both, so a test only actually shows an ad when it lands on one
 * of these counts AND enough real time has passed since the last one.
 */
object ProxyTestAdCounter {
    private const val PREFS_NAME = "proxy_test_ad_counter"
    private const val KEY_COUNT = "count"
    private const val EVERY_N = 3

    /** Increments the persisted count and returns whether THIS test lands on the ad cadence. */
    fun recordTestAndCheckCadence(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newCount = prefs.getLong(KEY_COUNT, 0L) + 1
        prefs.edit().putLong(KEY_COUNT, newCount).apply()
        return newCount == 1L || (newCount - 1L) % EVERY_N == 0L
    }
}
