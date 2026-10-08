package com.socksrelay.vertex.net

import java.io.IOException
import java.net.Socket

/**
 * Single entry point PacketRouter/ProxyTester call instead of picking a
 * protocol-specific client themselves — dispatches to [Socks5Client],
 * [Socks4Client], or [HttpProxyClient] based on [protocol].
 */
object ProxyClient {

    @Throws(IOException::class)
    fun connect(
        protocol: ProxyProtocol,
        protectSocket: (Socket) -> Boolean,
        proxyHost: String,
        proxyPort: Int,
        destinationHost: String,
        destPort: Int,
        username: String? = null,
        password: String? = null
    ): Socket = when (protocol) {
        ProxyProtocol.SOCKS5 -> Socks5Client.connect(
            protectSocket, proxyHost, proxyPort, destinationHost, destPort, username, password
        )
        ProxyProtocol.SOCKS4 -> Socks4Client.connect(
            protectSocket, proxyHost, proxyPort, destinationHost, destPort, username
        )
        ProxyProtocol.HTTP -> HttpProxyClient.connect(
            protectSocket, proxyHost, proxyPort, destinationHost, destPort, username, password
        )
    }
}
