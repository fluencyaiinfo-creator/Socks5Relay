package com.socksrelay.vertex.net

import java.net.InetAddress
import java.net.Socket

/**
 * Tracks one device-initiated TCP flow while it's being relayed through the
 * SOCKS5 proxy. Keyed by source port in [PacketRouter] (sufficient here
 * since every flow in this scaffold originates from the single VPN client
 * address).
 *
 * Sequence-number bookkeeping is intentionally simple: we act as the
 * "server" side of the TCP connection as far as the device is concerned, so
 * [nextSeqToClient] is our own byte counter (incremented as we relay bytes
 * from the real destination back to the device), and [nextAckToClient] is
 * our record of how many bytes we've received from the device so far
 * (echoed back as the ack number).
 */
class TcpSession(
    val localPort: Int,
    val remoteAddress: InetAddress,
    val remotePort: Int,
    val clientInitialSeq: Long
) {
    var socket: Socket? = null
    var established = false
    var closing = false

    /** The device-side address of this flow (needed to craft packets outside the normal receive path). */
    var clientAddress: InetAddress? = null

    /**
     * Set when the router deliberately killed this flow (e.g. the phone
     * switched networks): the device was already sent a RST, so the normal
     * "socket closed" cleanup must not also send a FIN or log noise.
     */
    @Volatile var reset = false

    // Our "server-side" ISN for this spoofed connection.
    var ourInitialSeq: Long = (Math.random() * 0xFFFFFFFL).toLong()

    var nextSeqToClient: Long = ourInitialSeq
    var nextAckToClient: Long = clientInitialSeq + 1 // +1 for the SYN itself

    /** Guards writes to the shared tun output stream for this session's packets. */
    val writeLock = Any()
}
