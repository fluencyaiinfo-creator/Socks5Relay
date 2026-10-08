package com.socksrelay.vertex.freeproxy

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared by every proxy source ([VakhovSource], [MonosansSource],
 * [TheSpeedXSource]) so the throttle-retry behavior (retry with backoff on
 * HTTP 429 / 5xx) lives in one place.
 */
object HttpFetch {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_RETRIES_ON_THROTTLE = 3
    private const val RETRY_BASE_DELAY_MS = 600L

    class NotFoundException : IOException("404")
    private class ThrottledException(status: Int) : IOException("HTTP $status")

    fun fetchText(urlString: String, retryOnThrottle: Boolean = true): String {
        var attempt = 0
        while (true) {
            try {
                return fetchOnce(urlString)
            } catch (e: ThrottledException) {
                attempt++
                if (!retryOnThrottle || attempt > MAX_RETRIES_ON_THROTTLE) throw e
                Thread.sleep(RETRY_BASE_DELAY_MS * (1L shl (attempt - 1)))
            }
        }
    }

    private fun fetchOnce(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "SocksRelay-Android-App")
        try {
            val code = connection.responseCode
            if (code == 404) throw NotFoundException()
            if (code == 429 || code in 500..599) throw ThrottledException(code)
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
