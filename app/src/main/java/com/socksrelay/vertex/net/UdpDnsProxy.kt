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
 * ASSOCIATE reliably even when using SOCKS5) — a plain CONNECT to
 * [UPSTREAM_DNS]:53 is the most broadly compatible option.
 *
 * There is deliberately NO fallback to direct/unproxied DNS if this fails —
 * failing the lookup is safer than silently leaking it. If proxied DNS
 * keeps failing, that usually means the proxy itself is down (see
 * [com.socksrelay.vertex.SocksVpnService]'s health monitor / kill switch),
 * not that DNS specifically needs a workaround.
 */
object UdpDnsProxy {
    private const val TAG = "UdpDnsProxy"
    private const val UPSTREAM_DNS = "1.1.1.1"
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
        return try {
            val socket = ProxyClient.connect(
                protocol = protocol,
                protectSocket = protectSocket,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                destinationHost = UPSTREAM_DNS,
                destPort = 53,
                username = proxyUsername,
                password = proxyPassword
            )
            socket.soTimeout = TIMEOUT_MS
            socket.use {
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
        } catch (e: Exception) {
            AppLog.w(TAG, "Proxied DNS resolution failed: ${e.message}",
                "Couldn't resolve via the proxy (DNS-over-TCP to $UPSTREAM_DNS:53 through it). This usually " +
                    "means the proxy itself is unreachable right now — check the connection status, not just DNS.")
            null
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
