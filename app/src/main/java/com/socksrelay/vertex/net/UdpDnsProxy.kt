package com.socksrelay.vertex.net

import com.socksrelay.vertex.log.AppLog
import java.io.IOException
import java.io.InputStream
import java.net.Socket

/**
 * Handles UDP port 53 specifically, by resolving THROUGH the configured
 * proxy rather than going directly to an upstream resolver.
 *
 * Earlier versions of this scaffold sent DNS queries directly from the
 * device (via a protect()-ed UDP socket straight to an upstream resolver),
 * bypassing the proxy entirely for DNS while TCP traffic went through it.
 * That's a real privacy leak: anyone watching the device's real network
 * (ISP, Wi-Fi operator, etc.) could see every domain being resolved even
 * though the actual TCP connections were proxied.
 *
 * Fix: resolve via DNS-over-TCP (RFC 1035 §4.2.2 — a 2-byte big-endian
 * length prefix, then the raw DNS message) tunneled through the SAME proxy
 * connection mechanism as every other TCP flow ([ProxyClient]/[Socks5Client]
 * /[Socks4Client]/[HttpProxyClient]). This works with all three supported
 * proxy protocols, unlike SOCKS5 UDP ASSOCIATE (SOCKS4 and HTTP proxies
 * don't support UDP at all, and free proxies often don't implement UDP
 * ASSOCIATE reliably even when using SOCKS5) — a plain CONNECT to the
 * upstream resolver's port 53 is the most broadly compatible option.
 *
 * Two upstream resolvers are tried in order ([UPSTREAMS]) because free
 * proxies frequently block one particular resolver address. BOTH are
 * reached through the proxy: the backup is for reliability, never a way
 * around the proxy. There is deliberately NO fallback to direct/unproxied
 * DNS — failing the lookup is safer than silently leaking it. If proxied
 * DNS keeps failing, that usually means the proxy itself is down (see
 * [com.socksrelay.vertex.SocksVpnService]'s health monitor / kill switch).
 *
 * Answers are cached briefly ([DnsCache]) so repeat lookups are instant.
 */
object UdpDnsProxy {
    private const val TAG = "UdpDnsProxy"
    private val UPSTREAMS = listOf("1.1.1.1", "8.8.8.8")
    private const val TIMEOUT_MS = 5000

    fun resolveViaProxy(
        protocol: ProxyProtocol,
        protectSocket: (Socket) -> Boolean,
        proxyHost: String,
        proxyPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        query: ByteArray
    ): ByteArray? {
        DnsCache.get(query)?.let { return it }

        var lastError: String? = null
        var lastResponse: ByteArray? = null
        for (upstream in UPSTREAMS) {
            try {
                val response = queryUpstream(
                    upstream, protocol, protectSocket, proxyHost, proxyPort, proxyUsername, proxyPassword, query
                )
                // SERVFAIL (2) / REFUSED (5) from one resolver: give the
                // other one a chance before accepting the failure.
                val rcode = if (response.size >= 4) response[3].toInt() and 0x0F else 0
                if (rcode == 2 || rcode == 5) {
                    lastResponse = response
                    continue
                }
                DnsCache.put(query, response)
                return response
            } catch (e: Exception) {
                lastError = e.message
            }
        }

        lastResponse?.let { return it }

        AppLog.w(TAG, "Proxied DNS resolution failed: $lastError",
            "Couldn't resolve via the proxy (DNS-over-TCP to ${UPSTREAMS.joinToString(" and ")} through it). " +
                "This usually means the proxy itself is unreachable right now — check the connection status, not just DNS.")
        return null
    }

    @Throws(IOException::class)
    private fun queryUpstream(
        upstream: String,
        protocol: ProxyProtocol,
        protectSocket: (Socket) -> Boolean,
        proxyHost: String,
        proxyPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        query: ByteArray
    ): ByteArray {
        val socket = ProxyClient.connect(
            protocol = protocol,
            protectSocket = protectSocket,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            destinationHost = upstream,
            destPort = 53,
            username = proxyUsername,
            password = proxyPassword
        )
        socket.soTimeout = TIMEOUT_MS
        return socket.use {
            val output = it.getOutputStream()
            val length = query.size
            output.write(byteArrayOf(((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()))
            output.write(query)
            output.flush()

            val input = it.getInputStream()
            val lengthBuffer = ByteArray(2)
            readFully(input, lengthBuffer)
            val responseLength = ((lengthBuffer[0].toInt() and 0xFF) shl 8) or (lengthBuffer[1].toInt() and 0xFF)
            val responseBuffer = ByteArray(responseLength)
            readFully(input, responseBuffer)
            responseBuffer
        }
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw IOException("DNS-over-TCP connection closed before a full response arrived")
            offset += n
        }
    }
}
