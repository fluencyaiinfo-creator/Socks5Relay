package com.socksrelay.vertex.net

import android.util.Base64
import com.socksrelay.vertex.log.AppLog
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A minimal HTTP proxy client using the `CONNECT` method (RFC 7231 §4.3.6),
 * the standard way an HTTP proxy tunnels arbitrary TCP (most commonly used
 * for HTTPS, but works for any protocol once the tunnel is up — which is
 * all this app needs, since it just relays raw bytes either way).
 */
object HttpProxyClient {
    private const val TAG = "HttpProxyClient"

    @Throws(IOException::class)
    fun connect(
        protectSocket: (Socket) -> Boolean,
        proxyHost: String,
        proxyPort: Int,
        destinationHost: String,
        destPort: Int,
        username: String? = null,
        password: String? = null
    ): Socket {
        val socket = Socket()
        AppLog.i(TAG, "Opening TCP connection to HTTP proxy $proxyHost:$proxyPort")
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
            AppLog.e(TAG, "Could not reach HTTP proxy $proxyHost:$proxyPort: ${e.message}")
            throw e
        }

        val authHeader = if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            val encoded = Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "Proxy-Authorization: Basic $encoded\r\n"
        } else ""

        val target = "$destinationHost:$destPort"
        AppLog.i(TAG, "Sending HTTP CONNECT request for $target")
        val request = "CONNECT $target HTTP/1.1\r\nHost: $target\r\n$authHeader" +
            "Proxy-Connection: Keep-Alive\r\n\r\n"
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()

        val statusLine = readStatusLine(socket)
        val statusCode = Regex("""HTTP/\d\.\d\s+(\d+)""").find(statusLine)?.groupValues?.get(1)?.toIntOrNull()

        if (statusCode == null) {
            AppLog.e(TAG, "Unexpected response to CONNECT: \"$statusLine\"",
                "The thing listening on that host/port didn't reply like an HTTP proxy.")
            runCatching { socket.close() }
            throw IOException("Unexpected HTTP proxy response")
        }
        if (statusCode !in 200..299) {
            AppLog.e(TAG, "HTTP proxy refused CONNECT to $target: \"$statusLine\"", httpStatusHint(statusCode))
            runCatching { socket.close() }
            throw IOException("HTTP CONNECT failed with status $statusCode")
        }

        AppLog.success(TAG, "HTTP CONNECT tunnel established to $target")
        return socket
    }

    /**
     * Reads the status line and remaining CONNECT response headers up to
     * the blank line, one byte at a time straight off the raw InputStream
     * (deliberately NOT wrapped in a BufferedReader, which would risk
     * over-reading into the tunnel's actual data that follows the headers).
     */
    private fun readStatusLine(socket: Socket): String {
        val input = socket.getInputStream()
        val statusLine = readLineRaw(input)
        while (true) {
            val line = readLineRaw(input)
            if (line.isEmpty()) break
        }
        return statusLine
    }

    private fun readLineRaw(input: java.io.InputStream): String {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
        }
        return out.toString()
    }

    private fun httpStatusHint(code: Int): String = when (code) {
        407 -> "Proxy requires authentication — check the username/password."
        403 -> "Proxy refused this destination (forbidden)."
        502, 504 -> "Proxy couldn't reach the destination."
        else -> "Proxy returned HTTP status $code for the CONNECT request."
    }
}
