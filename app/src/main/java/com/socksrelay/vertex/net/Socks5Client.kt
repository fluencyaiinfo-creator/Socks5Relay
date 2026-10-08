package com.socksrelay.vertex.net

import com.socksrelay.vertex.log.AppLog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A minimal SOCKS5 client implementing the CONNECT command (RFC 1928),
 * with no-auth and username/password auth (RFC 1929).
 *
 * Every step logs to [AppLog] with a plain-English hint on failure, since
 * this is the single most common point of failure for the whole app — if
 * this doesn't succeed, nothing else will work.
 */
object Socks5Client {
    private const val TAG = "Socks5Client"

    private const val SOCKS_VERSION = 0x05
    private const val CMD_CONNECT = 0x01
    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val AUTH_NONE = 0x00
    private const val AUTH_USER_PASS = 0x02

    private val ipv4Regex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * Opens a socket to [socksHost]:[socksPort], protects it via
     * [protectSocket] (mandatory when called from inside the VPN service —
     * pass `{ true }` if calling from a plain activity/test where no tun
     * interface exists yet), and asks the proxy to CONNECT through to
     * [destinationHost]:[destPort]. [destinationHost] can be a dotted IPv4
     * address or a domain name — domain names are resolved by the proxy
     * itself (ATYP 0x03), not locally, which is both more correct (some
     * proxies/DNS setups only work this way) and lets [destinationHost] be
     * used for the "Test Proxy" feature without needing local DNS first.
     *
     * Returns the connected [Socket] on success, already past the SOCKS
     * handshake — write/read on it now talks directly to the destination.
     */
    @Throws(IOException::class)
    fun connect(
        protectSocket: (Socket) -> Boolean,
        socksHost: String,
        socksPort: Int,
        destinationHost: String,
        destPort: Int,
        username: String? = null,
        password: String? = null
    ): Socket {
        val socket = Socket()
        AppLog.i(TAG, "Opening TCP connection to proxy $socksHost:$socksPort")

        // java.net.Socket() does NOT create the underlying OS socket file
        // descriptor immediately — on most Android versions it's created
        // lazily, only once the socket is bound or connected. protect()
        // needs a real fd to mark, so calling it on a brand-new, unbound
        // Socket() reliably fails (returns false) even though nothing else
        // is wrong. Binding to an ephemeral local port first forces the fd
        // into existence so protect() has something to actually protect.
        try {
            socket.bind(InetSocketAddress(0))
        } catch (e: IOException) {
            AppLog.e(TAG, "Could not bind local socket before connecting: ${e.message}")
            throw e
        }

        if (!protectSocket(socket)) {
            // Detailed diagnosis (including the "Block connections without
            // VPN" device-setting hint) is logged by VpnProtect itself when
            // called from inside SocksVpnService — see the log lines just
            // above this one.
            throw IOException("protect() failed")
        }

        val connectStart = System.currentTimeMillis()
        try {
            socket.connect(InetSocketAddress(socksHost, socksPort), 10_000)
        } catch (e: IOException) {
            AppLog.e(TAG, "Could not reach proxy $socksHost:$socksPort: ${e.message}",
                "Check that: (1) the host/port are correct, (2) the SOCKS5 server is actually running, " +
                    "(3) if testing on the same machine, '127.0.0.1' means the PHONE itself, not your " +
                    "computer — use your computer's LAN IP address instead, and (4) your phone and the " +
                    "proxy are on a network that can reach each other (same Wi-Fi, or a public IP/port).")
            throw e
        }
        AppLog.i(TAG, "TCP connected to proxy in ${System.currentTimeMillis() - connectStart}ms")

        val out = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())

        negotiateAuth(out, input, username, password)
        sendConnectRequest(out, input, destinationHost, destPort)

        AppLog.success(TAG, "SOCKS5 tunnel established to $destinationHost:$destPort")
        return socket
    }

    private fun negotiateAuth(
        out: DataOutputStream,
        input: DataInputStream,
        username: String?,
        password: String?
    ) {
        val supportsUserPass = !username.isNullOrEmpty() && !password.isNullOrEmpty()
        val methods = if (supportsUserPass) byteArrayOf(AUTH_NONE.toByte(), AUTH_USER_PASS.toByte())
        else byteArrayOf(AUTH_NONE.toByte())

        AppLog.i(TAG, "Sending SOCKS5 greeting (offering: ${if (supportsUserPass) "no-auth, username/password" else "no-auth"})")
        out.write(byteArrayOf(SOCKS_VERSION.toByte(), methods.size.toByte()) + methods)
        out.flush()

        val version: Int
        val chosenMethod: Int
        try {
            version = input.readUnsignedByte()
            chosenMethod = input.readUnsignedByte()
        } catch (e: IOException) {
            AppLog.e(TAG, "No response to SOCKS5 greeting: ${e.message}",
                "The thing listening on that host/port didn't speak SOCKS5 at all (e.g. it might be a " +
                    "plain HTTP proxy, or nothing recognizable). Double-check it's really a SOCKS5 server.")
            throw e
        }
        if (version != SOCKS_VERSION) {
            AppLog.e(TAG, "Server replied with protocol version $version instead of 5",
                "That's not a SOCKS5 server (or it's replying with garbage). Check the host/port again.")
            throw IOException("Unexpected SOCKS version in reply: $version")
        }

        when (chosenMethod) {
            AUTH_NONE -> AppLog.i(TAG, "Proxy accepted no-auth")
            AUTH_USER_PASS -> {
                if (!supportsUserPass) {
                    AppLog.e(TAG, "Proxy requires username/password authentication",
                        "Enter a username and password in the app and try again.")
                    throw IOException("Proxy requires auth but none was configured")
                }
                AppLog.i(TAG, "Proxy requires username/password, authenticating as '$username'")
                val userBytes = username!!.toByteArray(Charsets.US_ASCII)
                val passBytes = password!!.toByteArray(Charsets.US_ASCII)
                out.write(byteArrayOf(0x01, userBytes.size.toByte()) + userBytes +
                    byteArrayOf(passBytes.size.toByte()) + passBytes)
                out.flush()
                input.readUnsignedByte() // auth sub-negotiation version
                val status = input.readUnsignedByte()
                if (status != 0x00) {
                    AppLog.e(TAG, "SOCKS5 authentication failed (status=$status)",
                        "The username or password was rejected by the proxy. Double-check both.")
                    throw IOException("SOCKS5 auth failed (status=$status)")
                }
                AppLog.success(TAG, "Authenticated successfully")
            }
            0xFF -> {
                AppLog.e(TAG, "Proxy rejected all offered authentication methods",
                    "The proxy doesn't accept no-auth and either doesn't support username/password " +
                        "either, or you need to configure it differently on the server side.")
                throw IOException("SOCKS5 server rejected all offered auth methods")
            }
            else -> {
                AppLog.e(TAG, "Proxy chose unsupported auth method: $chosenMethod",
                    "This client only supports no-auth and username/password auth.")
                throw IOException("SOCKS5 server chose unsupported auth method: $chosenMethod")
            }
        }
    }

    private fun sendConnectRequest(
        out: DataOutputStream,
        input: DataInputStream,
        destinationHost: String,
        destPort: Int
    ) {
        val isIpv4 = ipv4Regex.matches(destinationHost)
        val request = if (isIpv4) {
            val octets = destinationHost.split(".").map { it.toInt().toByte() }
            byteArrayOf(SOCKS_VERSION.toByte(), CMD_CONNECT.toByte(), 0x00, ATYP_IPV4.toByte()) +
                octets.toByteArray() +
                byteArrayOf(((destPort shr 8) and 0xFF).toByte(), (destPort and 0xFF).toByte())
        } else {
            val hostBytes = destinationHost.toByteArray(Charsets.US_ASCII)
            byteArrayOf(SOCKS_VERSION.toByte(), CMD_CONNECT.toByte(), 0x00, ATYP_DOMAIN.toByte(), hostBytes.size.toByte()) +
                hostBytes +
                byteArrayOf(((destPort shr 8) and 0xFF).toByte(), (destPort and 0xFF).toByte())
        }

        AppLog.i(TAG, "Sending CONNECT request for $destinationHost:$destPort")
        out.write(request)
        out.flush()

        val version = input.readUnsignedByte()
        val replyCode = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        val addressType = input.readUnsignedByte()

        // Consume the bound-address field regardless of type so the stream
        // is left positioned correctly for the actual relayed data.
        when (addressType) {
            0x01 -> input.skipBytes(4)
            0x03 -> input.skipBytes(input.readUnsignedByte())
            0x04 -> input.skipBytes(16)
        }
        input.skipBytes(2) // bound port

        if (version != SOCKS_VERSION) {
            throw IOException("Unexpected SOCKS version in CONNECT reply: $version")
        }
        if (replyCode != 0x00) {
            AppLog.e(TAG, "Proxy refused CONNECT to $destinationHost:$destPort (code $replyCode)",
                replyCodeHint(replyCode))
            throw IOException("SOCKS5 CONNECT failed with reply code $replyCode")
        }
        AppLog.i(TAG, "Proxy accepted CONNECT to $destinationHost:$destPort")
    }

    private fun replyCodeHint(code: Int): String = when (code) {
        0x01 -> "General SOCKS server failure — something went wrong on the proxy's side."
        0x02 -> "Connection not allowed by the proxy's own ruleset (it may be blocking this destination)."
        0x03 -> "Network unreachable from the proxy's side."
        0x04 -> "Host unreachable — the proxy couldn't reach that destination address."
        0x05 -> "Connection refused by the destination — nothing is listening there, or it's blocking the proxy's IP."
        0x06 -> "TTL expired reaching the destination."
        0x07 -> "Command not supported by this proxy (it may not support CONNECT)."
        0x08 -> "Address type not supported by this proxy."
        else -> "Unknown SOCKS5 error code."
    }
}
