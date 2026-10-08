package com.socksrelay.vertex.freeproxy

import com.socksrelay.vertex.net.ProxyProtocol

/**
 * https://github.com/monosans/proxy-list — plain `ip:port` lists that the
 * maintainer re-checks hourly, one file per protocol. Shown in logs/UI only
 * as "Source 2" (keep provider names out of user-facing text).
 */
object MonosansSource {
    private const val TAG = "Source 2"
    private const val BASE = "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies"

    fun fetch(): List<FreeProxy> = ProxyListParser.fetchPlainLists(
        TAG,
        listOf(
            ProxyProtocol.SOCKS5 to "$BASE/socks5.txt",
            ProxyProtocol.SOCKS4 to "$BASE/socks4.txt",
            ProxyProtocol.HTTP to "$BASE/http.txt"
        )
    )
}
