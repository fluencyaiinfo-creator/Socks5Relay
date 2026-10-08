package com.socksrelay.vertex.freeproxy

import com.socksrelay.vertex.log.AppLog
import com.socksrelay.vertex.net.ProxyProtocol
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Shared by the plain `ip:port`-per-line sources ([MonosansSource],
 * [TheSpeedXSource]). These lists carry no country information — that is
 * filled in later, and only for proxies that survive verification (see
 * [VerificationSession] / [GeoResolver]).
 */
object ProxyListParser {
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** Downloads every (protocol, url) file in parallel and returns everything parsed. */
    fun fetchPlainLists(tag: String, files: List<Pair<ProxyProtocol, String>>): List<FreeProxy> {
        val pool = Executors.newFixedThreadPool(files.size)
        try {
            val futures = files.map { (protocol, url) ->
                pool.submit(Callable {
                    try {
                        parse(HttpFetch.fetchText(url), protocol)
                    } catch (e: Exception) {
                        AppLog.w(tag, "$tag: one list failed to download (${e.message})")
                        emptyList<FreeProxy>()
                    }
                })
            }
            val all = futures.flatMap { it.get() }
            AppLog.i(tag, "$tag: parsed ${all.size} proxy entries")
            return all
        } finally {
            pool.shutdownNow()
        }
    }

    fun parse(text: String, protocol: ProxyProtocol): List<FreeProxy> {
        val out = ArrayList<FreeProxy>()
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            line = line.substringBefore(' ').substringBefore('\t')
            line = line.substringAfter("://") // tolerate "socks5://1.2.3.4:1080"
            if (line.contains('@')) continue   // credentialed proxies: not supported by free lists here
            val host = line.substringBefore(':')
            val port = line.substringAfter(':', "").toIntOrNull() ?: continue
            if (port !in 1..65535) continue
            if (!isPublicIpv4(host)) continue
            out.add(FreeProxy(host = host, port = port, protocol = protocol))
        }
        return out
    }

    private fun isPublicIpv4(host: String): Boolean {
        val m = ipv4.matchEntire(host) ?: return false
        val o = m.groupValues.drop(1).map { it.toInt() }
        if (o.any { it !in 0..255 }) return false
        val a = o[0]; val b = o[1]
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127))
    }
}
