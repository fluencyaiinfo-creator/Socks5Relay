package com.socksrelay.vertex.freeproxy

import android.os.SystemClock
import com.socksrelay.vertex.net.ProxyProtocol
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The "is this proxy really alive" check. Much stricter than a TCP connect:
 *
 *  1. TCP connect to the proxy (short timeout),
 *  2. the protocol handshake (SOCKS5 no-auth / SOCKS4 / HTTP CONNECT) asking
 *     the proxy to open a tunnel to a well-known server,
 *  3. a real HTTP request through that tunnel, and the answer must be
 *     exactly `204 No Content` — this rejects proxies that connect but
 *     stall, hijack or rewrite traffic (captive/ad pages return 200), and
 *     ones that accept the handshake but can't relay data.
 *
 * Deliberately silent (no per-proxy AppLog lines): thousands of checks run
 * per refresh and would flood the Logs screen. Summary lines are logged by
 * the repository instead.
 */
object ProxyVerifier {
    private const val TEST_HOST = "connectivitycheck.gstatic.com"
    private const val TEST_PORT = 80
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val READ_TIMEOUT_MS = 2_500

    private const val REQUEST = "GET /generate_204 HTTP/1.1\r\nHost: $TEST_HOST\r\n" +
        "User-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n"

    /** The test server's IPv4 address, resolved once per run on the device itself (not through any proxy). */
    class Target(val ip: ByteArray, val ipText: String)

    data class Verdict(val ok: Boolean, val latencyMs: Int)

    /** Null means the device can't resolve the test host — i.e. it's effectively offline. */
    fun resolveTarget(): Target? {
        return try {
            val addr = InetAddress.getAllByName(TEST_HOST).filterIsInstance<Inet4Address>().firstOrNull()
            if (addr == null) null else Target(addr.address, addr.hostAddress ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    /** Direct (no proxy) reachability of the test server — used to tell "every proxy is dead" apart from "my internet dropped". */
    fun networkLooksUp(target: Target): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(target.ipText, TEST_PORT), 3_000) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun verify(proxy: FreeProxy, target: Target): Verdict {
        val start = SystemClock.elapsedRealtime()
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(proxy.host, proxy.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            val tunnelUp = when (proxy.protocol) {
                ProxyProtocol.SOCKS5 -> socks5(out, input, target)
                ProxyProtocol.SOCKS4 -> socks4(out, input, target)
                ProxyProtocol.HTTP -> httpConnect(out, input, target)
            }
            if (!tunnelUp) return Verdict(false, 0)

            out.write(REQUEST.toByteArray(Charsets.US_ASCII))
            out.flush()
            val status = readLine(input) ?: return Verdict(false, 0)
            val ok = status.startsWith("HTTP/1.1 204") || status.startsWith("HTTP/1.0 204")
            Verdict(ok, (SystemClock.elapsedRealtime() - start).toInt())
        } catch (e: Exception) {
            Verdict(false, 0)
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    // ---- handshakes ------------------------------------------------------

    private fun portBytes(): ByteArray = byteArrayOf((TEST_PORT shr 8).toByte(), (TEST_PORT and 0xFF).toByte())

    private fun socks5(out: OutputStream, input: InputStream, target: Target): Boolean {
        out.write(byteArrayOf(0x05, 0x01, 0x00)) // version 5, one method: no authentication
        out.flush()
        val method = readExactly(input, 2) ?: return false
        if (method[0] != 0x05.toByte() || method[1] != 0x00.toByte()) return false // wants auth or refuses

        out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01) + target.ip + portBytes()) // CONNECT, IPv4
        out.flush()
        val head = readExactly(input, 4) ?: return false
        if (head[0] != 0x05.toByte() || head[1] != 0x00.toByte()) return false
        val remaining = when (head[3].toInt()) {
            0x01 -> 4 + 2
            0x04 -> 16 + 2
            0x03 -> {
                val len = readExactly(input, 1) ?: return false
                (len[0].toInt() and 0xFF) + 2
            }
            else -> return false
        }
        return readExactly(input, remaining) != null
    }

    private fun socks4(out: OutputStream, input: InputStream, target: Target): Boolean {
        out.write(byteArrayOf(0x04, 0x01) + portBytes() + target.ip + byteArrayOf(0x00))
        out.flush()
        val reply = readExactly(input, 8) ?: return false
        return reply[1] == 0x5A.toByte() // request granted
    }

    private fun httpConnect(out: OutputStream, input: InputStream, target: Target): Boolean {
        val dest = "${target.ipText}:$TEST_PORT"
        out.write("CONNECT $dest HTTP/1.1\r\nHost: $dest\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        val block = readHeaderBlock(input) ?: return false
        val code = Regex("""^HTTP/\d\.\d\s+(\d{3})""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return code in 200..299
    }

    // ---- tiny I/O helpers ------------------------------------------------

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    /** Reads up to the end of the first line (CRLF), max 200 bytes. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 200) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** Reads response headers up to the blank line (max 4 KB), byte by byte so no tunnel data is over-read. */
    private fun readHeaderBlock(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 4096) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return null
    }
}
