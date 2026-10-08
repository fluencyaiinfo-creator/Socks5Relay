package com.socksrelay.vertex.freeproxy

import com.socksrelay.vertex.log.AppLog
import com.socksrelay.vertex.net.ProxyProtocol

/**
 * https://github.com/vakhov/fresh-proxy-list — a single semicolon-delimited
 * CSV, hosted on GitHub *Pages* (`vakhov.github.io`, not
 * `raw.githubusercontent.com` or `api.github.com`), so it is independent of
 * the GitHub-hosted plain lists. Every row already has protocol
 * flags *and* country in one place — no cross-referencing needed, which
 * makes this source both simpler and more reliable than the plain-list sources.
 *
 * Header: host;ip;port;lastseen;delay;cid;country_code;country_name;city;
 *         checks_up;checks_down;anon;http;ssl;socks4;socks5
 * The last four columns are "1"/"0" flags — a single row can (rarely) mark
 * more than one protocol true; we emit one [FreeProxy] per flag that's set.
 */
object VakhovSource {
    // Shown in logs/UI as "Source 1" rather than the underlying provider
    // name — keep provider-identifying details out of user-facing text.
    private const val TAG = "Source 1"
    private const val CSV_URL = "https://vakhov.github.io/fresh-proxy-list/proxylist.csv"

    private const val COL_IP = 1
    private const val COL_PORT = 2
    private const val COL_COUNTRY_CODE = 6
    private const val COL_COUNTRY_NAME = 7
    private const val COL_HTTP = 12
    private const val COL_SOCKS4 = 14
    private const val COL_SOCKS5 = 15
    private const val MIN_COLUMNS = 16

    private val ipv4Regex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun fetch(): List<FreeProxy> {
        return try {
            val text = HttpFetch.fetchText(CSV_URL)
            val results = mutableListOf<FreeProxy>()
            var lineNumber = 0
            for (rawLine in text.lineSequence()) {
                lineNumber++
                if (lineNumber == 1) continue // header row
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                parseRow(line)?.let { results.addAll(it) }
            }
            AppLog.i(TAG, "Source 1: parsed ${results.size} proxy entries")
            results
        } catch (e: Exception) {
            AppLog.w(TAG, "Source 1 failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseRow(line: String): List<FreeProxy>? {
        val cols = line.split(";")
        if (cols.size < MIN_COLUMNS) return null

        val ip = cols[COL_IP].trim()
        val port = cols[COL_PORT].trim().toIntOrNull() ?: return null
        val countryCode = cols[COL_COUNTRY_CODE].trim().uppercase()
        val countryName = cols[COL_COUNTRY_NAME].trim()

        if (!ipv4Regex.matches(ip)) return null
        if (!ip.split(".").all { (it.toIntOrNull() ?: -1) in 0..255 }) return null
        if (port !in 1..65535) return null
        if (countryCode.length != 2 || countryName.isEmpty()) return null

        val protocols = mutableListOf<ProxyProtocol>()
        if (cols[COL_SOCKS5].trim() == "1") protocols.add(ProxyProtocol.SOCKS5)
        if (cols[COL_SOCKS4].trim() == "1") protocols.add(ProxyProtocol.SOCKS4)
        if (cols[COL_HTTP].trim() == "1") protocols.add(ProxyProtocol.HTTP)
        if (protocols.isEmpty()) return null

        return protocols.map { protocol ->
            FreeProxy(host = ip, port = port, protocol = protocol, countryCode = countryCode, countryName = countryName)
        }
    }
}
