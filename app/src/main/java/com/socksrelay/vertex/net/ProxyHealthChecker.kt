package com.socksrelay.vertex.net

import java.net.InetSocketAddress
import java.net.Socket

/**
 * A cheap, protocol-agnostic "is anything answering at this address at all?"
 * check — a plain TCP connect attempt to the proxy's host:port, not a full
 * SOCKS/HTTP handshake. This is deliberately lighter than [ProxyClient]:
 * it's called repeatedly in the background by [com.socksrelay.vertex.SocksVpnService]'s
 * health monitor while connected, so it needs to fail fast and cheaply
 * rather than run the full handshake every few seconds.
 *
 * Always goes through [protectSocket] like every other socket this app
 * opens to reach the proxy itself, for the same reason: without it, this
 * probe's own traffic would get captured by our own tunnel and loop back
 * on itself instead of actually reaching the proxy.
 */
object ProxyHealthChecker {

    fun isReachable(
        protectSocket: (Socket) -> Boolean,
        host: String,
        port: Int,
        timeoutMs: Int = 5000
    ): Boolean {
        return try {
            Socket().use { socket ->
                if (!protectSocket(socket)) return false
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
