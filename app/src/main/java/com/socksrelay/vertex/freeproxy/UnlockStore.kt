package com.socksrelay.vertex.freeproxy

import android.content.Context
import com.socksrelay.vertex.log.AppLog

/**
 * Once a user watches the rewarded ad to unlock a country's full proxy
 * list, that unlock lasts [UNLOCK_DURATION_MS] (currently 3 hours) from
 * the moment they earned it, then reverts to the free preview automatically
 * — no explicit "re-lock" action needed, [isUnlocked] just compares against
 * the stored expiry timestamp on every check.
 *
 * Stored as an expiry timestamp (millis since epoch) rather than a plain
 * boolean specifically so this expiry works without any background job:
 * there's nothing to schedule or cancel, "is it unlocked right now" is a
 * pure function of the current time and what's on disk.
 */
object UnlockStore {
    private const val TAG = "UnlockStore"
    private const val PREFS_NAME = "unlocked_countries"
    const val UNLOCK_DURATION_MS = 3 * 60 * 60 * 1000L

    fun isUnlocked(context: Context, countryCode: String): Boolean {
        return remainingUnlockMillis(context, countryCode) != null
    }

    fun unlock(context: Context, countryCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(countryCode, System.currentTimeMillis() + UNLOCK_DURATION_MS)
            .apply()
    }

    /** Milliseconds left before this country re-locks, or null if it's not currently unlocked. */
    fun remainingUnlockMillis(context: Context, countryCode: String): Long? {
        val expiresAt = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(countryCode, 0L)
        } catch (e: ClassCastException) {
            // A pre-expiry version of this app stored a plain Boolean under
            // this same key/file. Reading it back as a Long throws rather
            // than returning a default — treat that as "not unlocked"
            // (falls back to the free preview) instead of crashing.
            AppLog.w(TAG, "Found an old-format unlock entry for $countryCode, treating it as expired")
            0L
        }
        val remaining = expiresAt - System.currentTimeMillis()
        return if (remaining > 0) remaining else null
    }
}
