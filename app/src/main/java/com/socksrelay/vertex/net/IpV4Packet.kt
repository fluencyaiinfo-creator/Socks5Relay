package com.socksrelay.vertex.net

import java.net.InetAddress

/**
 * Minimal IPv4 header reader/writer — just enough fields to route TCP/UDP
 * flows and rebuild response packets. No IP options support, no
 * fragmentation handling (MTU is set to 1500 in SocksVpnService so in
 * practice we don't see fragments for normal traffic).
 */
data class IpV4Packet(
    val version: Int,
    val ihl: Int,               // header length in 32-bit words
    val totalLength: Int,
    val protocol: Int,          // 6 = TCP, 17 = UDP
    val sourceAddress: InetAddress,
    val destinationAddress: InetAddress,
    val headerLength: Int,      // header length in bytes = ihl * 4
    val payloadOffset: Int,
    val payloadLength: Int
) {
    companion object {
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17

        fun parse(buffer: ByteArray, length: Int): IpV4Packet? {
            if (length < 20) return null
            val versionIhl = buffer[0].toInt() and 0xFF
            val version = versionIhl shr 4
            if (version != 4) return null // IPv6 not handled by this scaffold
            val ihl = versionIhl and 0x0F
            val headerLength = ihl * 4
            if (length < headerLength) return null

            val totalLength = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
            val protocol = buffer[9].toInt() and 0xFF
            val src = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
            val dst = InetAddress.getByAddress(buffer.copyOfRange(16, 20))

            return IpV4Packet(
                version = version,
                ihl = ihl,
                totalLength = totalLength,
                protocol = protocol,
                sourceAddress = src,
                destinationAddress = dst,
                headerLength = headerLength,
                payloadOffset = headerLength,
                payloadLength = (totalLength - headerLength).coerceAtLeast(0)
            )
        }

        /** Builds a 20-byte IPv4 header (no options) with a correct checksum. */
        fun buildHeader(
            source: InetAddress,
            destination: InetAddress,
            protocol: Int,
            payloadLength: Int
        ): ByteArray {
            val header = ByteArray(20)
            val totalLength = 20 + payloadLength
            header[0] = 0x45 // version 4, IHL 5 (20 bytes)
            header[1] = 0
            header[2] = ((totalLength shr 8) and 0xFF).toByte()
            header[3] = (totalLength and 0xFF).toByte()
            header[4] = 0; header[5] = 0 // identification
            header[6] = 0x40.toByte() // flags: don't fragment
            header[7] = 0
            header[8] = 64 // TTL
            header[9] = protocol.toByte()
            header[10] = 0; header[11] = 0 // checksum placeholder

            System.arraycopy(source.address, 0, header, 12, 4)
            System.arraycopy(destination.address, 0, header, 16, 4)

            val checksum = checksum(header, 0, 20)
            header[10] = ((checksum shr 8) and 0xFF).toByte()
            header[11] = (checksum and 0xFF).toByte()
            return header
        }

        /** Standard Internet checksum (RFC 791 / 1071). */
        fun checksum(data: ByteArray, offset: Int, length: Int): Int {
            var sum = 0L
            var i = offset
            val end = offset + length
            while (i < end - 1) {
                sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (i < end) {
                sum += (data[i].toInt() and 0xFF) shl 8
            }
            while (sum shr 16 != 0L) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return sum.inv().toInt() and 0xFFFF
        }
    }
}
