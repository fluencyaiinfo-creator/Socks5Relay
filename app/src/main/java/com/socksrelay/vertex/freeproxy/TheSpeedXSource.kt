package com.socksrelay.vertex.freeproxy

import com.socksrelay.vertex.net.ProxyProtocol

/**
 * https://github.com/TheSpeedX/PROXY-List — very large plain `ip:port`
 * lists, updated daily. Lots of entries are dead, which is exactly why
 * every proxy is verified before it is shown (see [VerificationSession]).
 * Shown in logs/UI only as "Source 3".
 */
object TheSpeedXSource {
    private const val TAG = "Source 3"
    private const val BASE = "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master"

    fun fetch(): List<FreeProxy> = ProxyListParser.fetchPlainLists(
        TAG,
        listOf(
            ProxyProtocol.SOCKS5 to "$BASE/socks5.txt",
            ProxyProtocol.SOCKS4 to "$BASE/socks4.txt",
            ProxyProtocol.HTTP to "$BASE/http.txt"
        )
    )
}
