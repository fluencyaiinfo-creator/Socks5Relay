package com.socksrelay.vertex

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.socksrelay.vertex.databinding.ActivityLogsBinding
import com.socksrelay.vertex.log.AppLog
import com.socksrelay.vertex.log.LogEntry
import com.socksrelay.vertex.log.LogLevel

/**
 * Shows everything logged via [AppLog] in a plain scrollable, selectable
 * text view — no adb required. Updates live while this screen is open
 * (e.g. keep it open in split-screen while you browse in another app to
 * watch flows connect/fail in real time). Entries are color-coded by
 * level (green = success, red = error, amber = warning) so a "CONNECTED"
 * or "DISCONNECTED" line jumps out at a glance.
 */
class LogsActivity : AppCompatActivity() {

    companion object {
        // Rendering everything ever logged into one giant SpannableStringBuilder
        // (with a color + bold span per entry) is what crashed this screen —
        // a long connected session could accumulate hundreds of entries.
        // Capping what's actually rendered bounds the worst case regardless
        // of how log volume changes elsewhere in the app in the future.
        // "Copy logs" still copies everything, not just what's shown here.
        private const val MAX_RENDERED_ENTRIES = 200

        // While the VPN is connected, every flow the device opens writes
        // several log lines, often dozens per second. Rebuilding the whole
        // text on every single line floods the main thread (and thrashes the
        // selectable TextView), which is what crashed "View logs" while
        // connected. Instead, any number of new lines within this window
        // collapse into ONE redraw.
        private const val REFRESH_THROTTLE_MS = 400L
    }

    private lateinit var binding: ActivityLogsBinding
    private val uiHandler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false
    private val refreshRunnable = Runnable {
        refreshScheduled = false
        refresh()
    }
    private val listener: () -> Unit = { scheduleRefresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.action_view_logs)

        binding.copyLogsButton.setOnClickListener { copyLogsToClipboard() }
        binding.clearLogsButton.setOnClickListener {
            AppLog.clear()
        }

        refresh()
    }

    override fun onStart() {
        super.onStart()
        AppLog.addListener(listener)
        refresh()
    }

    override fun onStop() {
        AppLog.removeListener(listener)
        uiHandler.removeCallbacks(refreshRunnable)
        refreshScheduled = false
        super.onStop()
    }

    private fun scheduleRefresh() {
        if (refreshScheduled || isFinishing || isDestroyed) return
        refreshScheduled = true
        uiHandler.postDelayed(refreshRunnable, REFRESH_THROTTLE_MS)
    }

    private fun refresh() {
        if (isFinishing || isDestroyed) return
        // Showing logs must never be able to take the app down, whatever
        // ends up in them — worst case the screen just doesn't update.
        try {
            val entries = AppLog.snapshot()
            binding.logsText.text = if (entries.isEmpty()) {
                getString(R.string.logs_empty)
            } else {
                val shown = entries.takeLast(MAX_RENDERED_ENTRIES)
                val builder = buildColoredLogText(shown)
                if (entries.size > shown.size) {
                    SpannableStringBuilder(getString(R.string.logs_truncated_notice, entries.size, shown.size))
                        .append("\n\n").append(builder)
                } else {
                    builder
                }
            }
            // Auto-scroll to the newest entry. scrollTo (not fullScroll) so
            // the selectable TextView never has focus yanked around.
            binding.logsScrollView.post {
                binding.logsScrollView.scrollTo(0, binding.logsText.bottom)
            }
        } catch (t: Throwable) {
            android.util.Log.e("LogsActivity", "Failed to render logs", t)
        }
    }

    private fun buildColoredLogText(entries: List<LogEntry>): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        entries.forEachIndexed { index, entry ->
            val text = AppLog.format(entry)
            val start = builder.length
            builder.append(text)
            val end = builder.length

            val colorRes = when (entry.level) {
                LogLevel.SUCCESS -> R.color.log_success
                LogLevel.ERROR -> R.color.log_error
                LogLevel.WARN -> R.color.log_warn
                LogLevel.INFO -> R.color.log_info
            }
            builder.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, colorRes)),
                start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            // Make SUCCESS/ERROR lines bold too — that's what should catch
            // your eye when scanning, e.g. a "CONNECTED" or "DISCONNECTED"
            // banner line.
            if (entry.level == LogLevel.SUCCESS || entry.level == LogLevel.ERROR) {
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (index != entries.lastIndex) builder.append("\n\n")
        }
        return builder
    }

    private fun copyLogsToClipboard() {
        val text = AppLog.snapshot().joinToString("\n\n") { AppLog.format(it) }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Socks5 Relay – VPN & Proxy logs", text))
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }
}
