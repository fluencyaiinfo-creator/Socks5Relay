package com.socksrelay.vertex.net

import android.os.ParcelFileDescriptor
import com.socksrelay.vertex.ConnectionStats
import com.socksrelay.vertex.log.AppLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The core relay loop. Reads raw IP packets from the tun interface, and:
 *
 *  - New outbound TCP flow (SYN)   -> open a SOCKS5 CONNECT to the real
 *                                      destination, spoof a SYN-ACK back,
 *                                      then pump bytes in both directions.
 *  - TCP data/FIN/RST on a known
 *    flow                          -> forward payload to the SOCKS5 socket
 *                                      / tear the session down.
 *  - UDP to port 53                -> resolve via [UdpDnsProxy] and write
 *                                      the response back as a UDP packet.
 *  - Anything else (other UDP,
 *    IPv6, ICMP, ...)              -> dropped. See UdpDnsProxy's doc comment
 *                                      for what a fuller implementation
 *                                      would add here.
 *
 * This is a teaching-scale implementation: single segment in flight per
 * direction, no retransmission/reordering/congestion control, no IPv6. For
 * production use, prefer a real user-space network stack (e.g. gVisor's
 * netstack, or an existing tun2socks) instead of extending this by hand.
 *
 * Every notable event logs through [AppLog] (visible on the in-app Logs
 * screen), since tracking down exactly which flow failed and why is most of
 * the work in debugging a VpnService-based relay.
 */
class PacketRouter(
    private val vpnInterface: ParcelFileDescriptor,
    private val protectSocket: (Socket) -> Boolean,
    // Kept for a future general (non-DNS) UDP-over-SOCKS5-UDP-ASSOCIATE relay
    // — DNS itself now goes through [protectSocket]/TCP via UdpDnsProxy instead.
    private val protectDatagram: (DatagramSocket) -> Boolean,
    private val proxyProtocol: ProxyProtocol,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val proxyUsername: String? = null,
    private val proxyPassword: String? = null
) {
    companion object {
        private const val TAG = "PacketRouter"
    }

    private val sessions = ConcurrentHashMap<Int, TcpSession>()
    private val outputLock = Any()
    private val flowsOpened = AtomicInteger(0)
    private val flowsFailed = AtomicInteger(0)

    fun run(input: FileInputStream, output: FileOutputStream, isRunning: () -> Boolean) {
        AppLog.i(TAG, "Packet router loop starting")
        val buffer = ByteArray(32767)
        var packetsRead = 0
        var ipv6Dropped = 0L
        try {
            while (isRunning()) {
                val length = input.read(buffer)
                if (length <= 0) continue
                packetsRead++
                if (packetsRead == 1) {
                    AppLog.success(TAG, "First packet read from tun interface — device traffic is reaching the app")
                }

                // IPv6 is intentionally not proxied. The tunnel captures it
                // (see SocksVpnService) and it is dropped here, so it can
                // never leak onto the real network; apps fall back to IPv4.
                if (length > 0 && (buffer[0].toInt() and 0xF0) == 0x60) {
                    ipv6Dropped++
                    continue
                }

                val ip = IpV4Packet.parse(buffer, length)
                if (ip == null) {
                    // Very common and harmless: IPv6 packets get skipped by
                    // this scaffold's parser (see IpV4Packet.parse). If your
                    // phone prefers IPv6 for everything, that alone could
                    // explain "no internet" even with a perfectly working
                    // proxy — see the README for how to add IPv6 handling,
                    // or disable IPv6 on the test network in the meantime.
                    continue
                }
                when (ip.protocol) {
                    IpV4Packet.PROTOCOL_TCP -> handleTcp(ip, buffer, output)
                    IpV4Packet.PROTOCOL_UDP -> handleUdp(ip, buffer, output)
                    else -> { /* ICMP / etc. not handled by this scaffold */ }
                }
            }
        } catch (e: IOException) {
            AppLog.i(TAG, "Router loop ending: ${e.message}")
        } finally {
            AppLog.i(TAG, "Router loop stopped. Packets read: $packetsRead, flows opened: ${flowsOpened.get()}, flows failed: ${flowsFailed.get()}" +
                if (ipv6Dropped > 0) ", IPv6 packets blocked: $ipv6Dropped" else "")
            sessions.values.forEach { runCatching { it.socket?.close() } }
            sessions.clear()
        }
    }

    // ---- TCP ----------------------------------------------------------

    private fun handleTcp(ip: IpV4Packet, buffer: ByteArray, output: FileOutputStream) {
        val tcp = TcpPacket.parse(buffer, ip.payloadOffset, ip.payloadLength) ?: return

        when {
            tcp.isRst -> {
                sessions.remove(tcp.sourcePort)?.socket?.let { runCatching { it.close() } }
            }
            tcp.isSyn && !tcp.isAck -> {
                openNewSession(ip, tcp, output)
            }
            else -> {
                val session = sessions[tcp.sourcePort] ?: return
                forwardClientSegment(session, ip, tcp, output)
            }
        }
    }

    private fun openNewSession(ip: IpV4Packet, tcp: TcpPacket, output: FileOutputStream) {
        // The device's TCP stack retransmits the SYN (usually after ~1s) if
        // it doesn't hear back in time. A SOCKS5 CONNECT round-trip (proxy
        // handshake + real destination connect) can easily take longer than
        // that, especially on a slow/remote proxy. Without this guard, every
        // retransmit would spawn ANOTHER Socks5Client.connect() for the same
        // port and stomp the session map entry, orphaning the first attempt
        // and usually resulting in the flow never completing at all.
        if (sessions.containsKey(tcp.sourcePort)) {
            AppLog.i(TAG, "Ignoring retransmitted SYN for port ${tcp.sourcePort} (already connecting/connected)")
            return
        }

        val destHost = ip.destinationAddress.hostAddress ?: ip.destinationAddress.toString()
        AppLog.i(TAG, "New connection: device app wants $destHost:${tcp.destinationPort}")

        val session = TcpSession(
            localPort = tcp.sourcePort,
            remoteAddress = ip.destinationAddress,
            remotePort = tcp.destinationPort,
            clientInitialSeq = tcp.sequenceNumber
        )
        sessions[tcp.sourcePort] = session

        // Connecting via the proxy is blocking I/O, so do it off the router
        // thread — otherwise one slow/unreachable destination would stall
        // every other flow's packets from being read off the tun fd.
        Thread({
            try {
                val socket = ProxyClient.connect(
                    protocol = proxyProtocol,
                    protectSocket = protectSocket,
                    proxyHost = proxyHost,
                    proxyPort = proxyPort,
                    destinationHost = destHost,
                    destPort = tcp.destinationPort,
                    username = proxyUsername,
                    password = proxyPassword
                )
                session.socket = socket
                session.established = true
                flowsOpened.incrementAndGet()
                AppLog.success(TAG, "Flow to $destHost:${tcp.destinationPort} established (port ${tcp.sourcePort})")

                // Spoof the SYN-ACK the device's TCP stack is waiting for.
                synchronized(session.writeLock) {
                    writeToTun(output, buildTcpSegment(session, ip, TcpPacket.FLAG_SYN or TcpPacket.FLAG_ACK, ByteArray(0)))
                    session.nextSeqToClient++ // SYN consumes one sequence number
                }

                pumpSocketToClient(session, ip, output)
            } catch (e: IOException) {
                flowsFailed.incrementAndGet()
                AppLog.w(TAG, "Failed to open flow to $destHost:${tcp.destinationPort}: ${e.message}")
                writeToTun(output, buildTcpSegment(session, ip, TcpPacket.FLAG_RST, ByteArray(0)))
                sessions.remove(session.localPort)
            }
        }, "SocksConnect-${tcp.destinationPort}").start()
    }

    /** Reads bytes arriving from the real destination (via the SOCKS5 socket) and relays them to the device. */
    private fun pumpSocketToClient(session: TcpSession, ip: IpV4Packet, output: FileOutputStream) {
        val socket = session.socket ?: return
        val readBuffer = ByteArray(16384)
        var bytesRelayed = 0L
        try {
            val input = socket.getInputStream()
            while (!session.closing) {
                val n = input.read(readBuffer)
                if (n < 0) break
                bytesRelayed += n
                ConnectionStats.addDownloaded(n.toLong())
                val payload = readBuffer.copyOf(n)
                synchronized(session.writeLock) {
                    writeToTun(output, buildTcpSegment(session, ip, TcpPacket.FLAG_PSH or TcpPacket.FLAG_ACK, payload))
                    session.nextSeqToClient += n
                }
            }
        } catch (e: IOException) {
            AppLog.i(TAG, "Socket closed for port ${session.localPort}: ${e.message}")
        } finally {
            AppLog.i(TAG, "Flow on port ${session.localPort} closed after relaying $bytesRelayed bytes to device")
            synchronized(session.writeLock) {
                writeToTun(output, buildTcpSegment(session, ip, TcpPacket.FLAG_FIN or TcpPacket.FLAG_ACK, ByteArray(0)))
                session.nextSeqToClient++
            }
            sessions.remove(session.localPort)
            runCatching { socket.close() }
        }
    }

    /** Forwards a data/FIN segment coming from the device's TCP stack out to the SOCKS5 socket. */
    private fun forwardClientSegment(session: TcpSession, ip: IpV4Packet, tcp: TcpPacket, output: FileOutputStream) {
        val socket = session.socket ?: return

        if (tcp.payload.isNotEmpty()) {
            try {
                socket.getOutputStream().write(tcp.payload)
                socket.getOutputStream().flush()
                ConnectionStats.addUploaded(tcp.payload.size.toLong())
            } catch (e: IOException) {
                AppLog.w(TAG, "Write to socket failed for port ${session.localPort}: ${e.message}",
                    "The destination closed the connection unexpectedly, or the proxy dropped it mid-flow.")
            }
            synchronized(session.writeLock) {
                session.nextAckToClient = tcp.sequenceNumber + tcp.payload.size
                writeToTun(output, buildTcpSegment(session, ip, TcpPacket.FLAG_ACK, ByteArray(0)))
            }
        }

        if (tcp.isFin) {
            synchronized(session.writeLock) {
                session.nextAckToClient = tcp.sequenceNumber + tcp.payload.size + 1
                session.closing = true
                writeToTun(output, buildTcpSegment(session, ip, TcpPacket.FLAG_ACK, ByteArray(0)))
            }
            runCatching { socket.shutdownOutput() }
        }
    }

    private fun buildTcpSegment(session: TcpSession, ip: IpV4Packet, flags: Int, payload: ByteArray): ByteArray {
        // Source/destination are flipped relative to the original packet:
        // we're spoofing a reply from the destination back to the device.
        return TcpPacket.buildSegment(
            sourceAddress = ip.destinationAddress,
            sourcePort = session.remotePort,
            destAddress = ip.sourceAddress,
            destPort = session.localPort,
            seq = session.nextSeqToClient,
            ack = session.nextAckToClient,
            flags = flags,
            payload = payload
        )
    }

    // ---- UDP (DNS only in this scaffold) -------------------------------

    private fun handleUdp(ip: IpV4Packet, buffer: ByteArray, output: FileOutputStream) {
        if (ip.payloadLength < 8) return
        val offset = ip.payloadOffset
        val srcPort = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        val dstPort = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
        if (dstPort != 53) return // TODO: general UDP relay via SOCKS5 UDP ASSOCIATE

        val query = buffer.copyOfRange(offset + 8, offset + ip.payloadLength)
        ConnectionStats.addUploaded(query.size.toLong())

        Thread({
            val response = UdpDnsProxy.resolveViaProxy(
                protocol = proxyProtocol,
                protectSocket = protectSocket,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword,
                query = query
            )
            if (response == null) {
                AppLog.w(TAG, "DNS query from port $srcPort got no response",
                    "DNS is resolved through the proxy (not directly) — see UdpDnsProxy. If this keeps " +
                        "failing, the proxy itself is most likely unreachable right now.")
                return@Thread
            }
            ConnectionStats.addDownloaded(response.size.toLong())
            val udpHeader = ByteArray(8)
            udpHeader[0] = ((53 shr 8) and 0xFF).toByte()
            udpHeader[1] = (53 and 0xFF).toByte()
            udpHeader[2] = ((srcPort shr 8) and 0xFF).toByte()
            udpHeader[3] = (srcPort and 0xFF).toByte()
            val udpLength = 8 + response.size
            udpHeader[4] = ((udpLength shr 8) and 0xFF).toByte()
            udpHeader[5] = (udpLength and 0xFF).toByte()
            udpHeader[6] = 0; udpHeader[7] = 0 // checksum optional (0 = not computed) for UDP/IPv4

            val ipHeader = IpV4Packet.buildHeader(
                ip.destinationAddress, ip.sourceAddress, IpV4Packet.PROTOCOL_UDP, udpHeader.size + response.size
            )
            synchronized(outputLock) {
                output.write(ipHeader + udpHeader + response)
            }
        }, "DnsProxy-$srcPort").start()
    }

    /** Caller is responsible for holding `session.writeLock` when the write depends on session seq/ack state. */
    private fun writeToTun(output: FileOutputStream, packet: ByteArray) {
        synchronized(outputLock) {
            try {
                output.write(packet)
            } catch (e: IOException) {
                AppLog.w(TAG, "Write to tun failed: ${e.message}")
            }
        }
    }
}
