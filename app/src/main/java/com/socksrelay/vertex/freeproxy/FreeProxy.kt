package com.socksrelay.vertex.freeproxy

import com.socksrelay.vertex.net.ProxyProtocol

data class FreeProxy(
    val host: String,
    val port: Int,
    val protocol: ProxyProtocol,
    val username: String? = null,
    val password: String? = null,
    val countryCode: String? = null,   // ISO 3166-1 alpha-2, e.g. "US" — filled in by FreeProxyFetcher
    val countryName: String? = null,
    /** Round-trip time of the last successful verification (connect + handshake + one HTTP request), used to list faster proxies first. */
    val latencyMs: Int? = null
) {
    /** A stable identity for dedup purposes — same host+port+protocol counts as the same proxy. */
    val dedupeKey: String get() = "${protocol.name}|$host|$port"

    /** The `ip:port` or `ip:port@user:pass` format used elsewhere in the app (paste field, clipboard). */
    fun toProxyString(): String {
        return if (!username.isNullOrEmpty()) "$host:$port@$username:${password.orEmpty()}" else "$host:$port"
    }
}
