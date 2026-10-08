package com.socksrelay.vertex.log

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale

enum class LogLevel { INFO, WARN, ERROR, SUCCESS }

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    /** Optional plain-English explanation of what this likely means / how to fix it. */
    val hint: String? = null
)

/**
 * A small in-memory, thread-safe log buffer that both:
 *  1. Forwards everything to standard Logcat (so `adb logcat` still works), and
 *  2. Keeps the last [MAX_ENTRIES] entries in memory so they can be shown in
 *     [com.socksrelay.vertex.LogsActivity] without needing adb at all.
 *
 * This is the main way a beginner (no adb) can see *why* a connection or a
 * proxy test failed — every failure point in this app logs a `hint`
 * explaining what the error usually means and what to check.
 */
object AppLog {
    private const val MAX_ENTRIES = 1000
    private val entries = ArrayDeque<LogEntry>()
    private val listeners = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        notifyListeners()
    }

    /**
     * Last-line-of-defence scrubber: whatever a caller passes in, ad-network
     * identifiers (publisher/app/unit IDs) never reach the Logs screen or
     * Logcat. Ad code should already log only generic text (see AdManager),
     * this just guarantees a future slip can't leak one.
     */
    /**
     * Housekeeping from the free-proxy list (which sources were fetched, how
     * many proxies were found, cache/refresh chatter) and from the ad SDK is
     * background noise: on the Logs screen people only want to see what is
     * happening with the one proxy they are connecting to or testing —
     * whether it's their own or a free one. Anything logged under these tags
     * never reaches the Logs screen. (It still goes to Logcat for debugging,
     * and the ad-ID scrubber below still applies.)
     */
    private val hiddenTags = setOf(
        "Ads",
        "FreeProxyFetcher", "FreeProxyRepository", "UnlockStore", "FavoritesStore"
    )
    private fun isHidden(tag: String): Boolean =
        tag in hiddenTags || tag.startsWith("Source ")

    private val adIdRegex = Regex("""ca-app-pub-\d+\s*[~/]\s*\d+""", RegexOption.IGNORE_CASE)
    private fun clean(text: String): String = adIdRegex.replace(text, "[ad]")

    fun i(tag: String, message: String, hint: String? = null) {
        val m = clean(message)
        Log.i(tag, m)
        push(LogEntry(System.currentTimeMillis(), LogLevel.INFO, tag, m, hint?.let { clean(it) }))
    }

    fun success(tag: String, message: String, hint: String? = null) {
        val m = clean(message)
        Log.i(tag, m)
        push(LogEntry(System.currentTimeMillis(), LogLevel.SUCCESS, tag, m, hint?.let { clean(it) }))
    }

    fun w(tag: String, message: String, hint: String? = null) {
        val m = clean(message)
        Log.w(tag, m)
        push(LogEntry(System.currentTimeMillis(), LogLevel.WARN, tag, m, hint?.let { clean(it) }))
    }

    fun e(tag: String, message: String, hint: String? = null) {
        val m = clean(message)
        Log.e(tag, m)
        push(LogEntry(System.currentTimeMillis(), LogLevel.ERROR, tag, m, hint?.let { clean(it) }))
    }

    @Synchronized
    private fun push(entry: LogEntry) {
        if (isHidden(entry.tag)) return
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        notifyListeners()
    }

    private fun notifyListeners() {
        mainHandler.post {
            synchronized(this) { listeners.toList() }.forEach { it() }
        }
    }

    fun format(entry: LogEntry): String {
        val time = timeFormat.format(entry.timestamp)
        val levelTag = when (entry.level) {
            LogLevel.INFO -> "INFO "
            LogLevel.WARN -> "WARN "
            LogLevel.ERROR -> "ERROR"
            LogLevel.SUCCESS -> "OK   "
        }
        val base = "$time [$levelTag] ${entry.tag}: ${entry.message}"
        return if (entry.hint != null) "$base\n         \u2192 ${entry.hint}" else base
    }
}
