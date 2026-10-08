package com.socksrelay.vertex.net

import com.socksrelay.vertex.log.AppLog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal SOCKS4 / SOCKS4a client (CONNECT command only). SOCKS4 has no
 * username/password auth in the protocol itself (just an optional "user
 * ID" string servers can ignore or check against a fixed value) — pass
 * [username] through as that user ID if a free-proxy entry specifies one.
 *
 * Uses the SOCKS4a extension (destination IP `0.0.0.1`, hostname appended
 * after the user ID) so this also works with domain-name destinations,
 * matching what [Socks5Client] offers for SOCKS5.
 */
object Socks4Client {
    private const val TAG = "Socks4Client"
    private const val VERSION = 0x04
    private const val CMD_CONNECT = 0x01

    private val ipv4Regex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    @Throws(IOException::class)
    fun connect(
        protectSocket: (Socket) -> Boolean,
        proxyHost: String,
        proxyPort: Int,
        destinationHost: String,
        destPort: Int,
        userId: String? = null
    ): Socket {
        val socket = Socket()
        AppLog.i(TAG, "Opening TCP connection to SOCKS4 proxy $proxyHost:$proxyPort")
        try {
            socket.bind(InetSocketAddress(0)) // see Socks5Client for why this matters before protect()
        } catch (e: IOException) {
            AppLog.e(TAG, "Could not bind local socket before connecting: ${e.message}")
            throw e
        }
        if (!protectSocket(socket)) throw IOException("protect() failed")

        try {
            socket.connect(InetSocketAddress(proxyHost, proxyPort), 10_000)
        } catch (e: IOException) {
            AppLog.e(TAG, "Could not reach SOCKS4 proxy $proxyHost:$proxyPort: ${e.message}")
            throw e
        }

        val out = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())
        val isIpv4 = ipv4Regex.matches(destinationHost)
        val userIdBytes = (userId ?: "").toByteArray(Charsets.US_ASCII)

        val request = if (isIpv4) {
            val octets = destinationHost.split(".").map { it.toInt().toByte() }.toByteArray()
            byteArrayOf(VERSION.toByte(), CMD_CONNECT.toByte(),
                ((destPort shr 8) and 0xFF).toByte(), (destPort and 0xFF).toByte()) +
                octets + userIdBytes + byteArrayOf(0x00)
        } else {
            // SOCKS4a: dest IP field is 0.0.0.x (x != 0) to signal "read the
            // hostname after the user ID", per the de-facto SOCKS4a spec.
            val hostBytes = destinationHost.toByteArray(Charsets.US_ASCII)
            byteArrayOf(VERSION.toByte(), CMD_CONNECT.toByte(),
                ((destPort shr 8) and 0xFF).toByte(), (destPort and 0xFF).toByte(),
                0x00, 0x00, 0x00, 0x01) +
                userIdBytes + byteArrayOf(0x00) + hostBytes + byteArrayOf(0x00)
        }

        AppLog.i(TAG, "Sending SOCKS4${if (isIpv4) "" else "a"} CONNECT request for $destinationHost:$destPort")
        out.write(request)
        out.flush()

        val reply = ByteArray(8)
        input.readFully(reply)
        val status = reply[1].toInt() and 0xFF
        if (status != 0x5A) { // 0x5A = request granted
            AppLog.e(TAG, "SOCKS4 proxy refused CONNECT (status=0x${status.toString(16)})",
                socks4StatusHint(status))
            runCatching { socket.close() }
            throw IOException("SOCKS4 CONNECT failed with status 0x${status.toString(16)}")
        }
        AppLog.success(TAG, "SOCKS4 tunnel established to $destinationHost:$destPort")
        return socket
    }

    private fun socks4StatusHint(status: Int): String = when (status) {
        0x5B -> "Request rejected or failed."
        0x5C -> "Request rejected: proxy couldn't reach your device's identd (this proxy expects one)."
        0x5D -> "Request rejected: user ID mismatch."
        else -> "Unknown SOCKS4 error status."
    }
}
