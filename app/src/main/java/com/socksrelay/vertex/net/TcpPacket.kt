package com.socksrelay.vertex.net

import java.net.InetAddress

/**
 * Minimal TCP segment reader/writer. Enough to track a flow's sequence/ack
 * numbers and move payload bytes — no options parsing/generation beyond
 * what's needed for a bare handshake, no window scaling, no SACK, no
 * retransmission timers. Each session in this scaffold trusts the OS TCP
 * stack on the device side to retransmit if needed, and just does its best
 * to keep seq/ack consistent for a single in-flight segment at a time.
 */
data class TcpPacket(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val ackNumber: Long,
    val flags: Int,
    val dataOffset: Int, // header length in 32-bit words
    val payload: ByteArray
) {
    val isSyn get() = flags and FLAG_SYN != 0
    val isAck get() = flags and FLAG_ACK != 0
    val isFin get() = flags and FLAG_FIN != 0
    val isRst get() = flags and FLAG_RST != 0

    companion object {
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10

        fun parse(buffer: ByteArray, offset: Int, length: Int): TcpPacket? {
            if (length < 20) return null
            val srcPort = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
            val dstPort = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
            val seq = readUInt32(buffer, offset + 4)
            val ack = readUInt32(buffer, offset + 8)
            val dataOffsetByte = (buffer[offset + 12].toInt() and 0xFF) shr 4
            val headerLen = dataOffsetByte * 4
            val flags = buffer[offset + 13].toInt() and 0x3F
            if (length < headerLen) return null
            val payload = buffer.copyOfRange(offset + headerLen, offset + length)

            return TcpPacket(srcPort, dstPort, seq, ack, flags, dataOffsetByte, payload)
        }

        private fun readUInt32(buffer: ByteArray, offset: Int): Long {
            return ((buffer[offset].toLong() and 0xFF) shl 24) or
                ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
                ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
                (buffer[offset + 3].toLong() and 0xFF)
        }

        /**
         * Builds a full IPv4+TCP packet (header + [payload]) representing a
         * segment sent from ([sourceAddress]:[sourcePort]) to
         * ([destAddress]:[destPort]) — i.e. this is used to send data *back
         * into the tun interface*, spoofed as if it came from the remote
         * host the device app thinks it's talking to.
         */
        fun buildSegment(
            sourceAddress: InetAddress,
            sourcePort: Int,
            destAddress: InetAddress,
            destPort: Int,
            seq: Long,
            ack: Long,
            flags: Int,
            payload: ByteArray
        ): ByteArray {
            val tcpHeader = ByteArray(20)
            tcpHeader[0] = ((sourcePort shr 8) and 0xFF).toByte()
            tcpHeader[1] = (sourcePort and 0xFF).toByte()
            tcpHeader[2] = ((destPort shr 8) and 0xFF).toByte()
            tcpHeader[3] = (destPort and 0xFF).toByte()
            writeUInt32(tcpHeader, 4, seq)
            writeUInt32(tcpHeader, 8, ack)
            tcpHeader[12] = (5 shl 4).toByte() // data offset = 5 (no options)
            tcpHeader[13] = flags.toByte()
            val window = 65535
            tcpHeader[14] = ((window shr 8) and 0xFF).toByte()
            tcpHeader[15] = (window and 0xFF).toByte()
            tcpHeader[16] = 0; tcpHeader[17] = 0 // checksum placeholder
            tcpHeader[18] = 0; tcpHeader[19] = 0 // urgent pointer

            val checksum = tcpChecksum(sourceAddress, destAddress, tcpHeader, payload)
            tcpHeader[16] = ((checksum shr 8) and 0xFF).toByte()
            tcpHeader[17] = (checksum and 0xFF).toByte()

            val ipHeader = IpV4Packet.buildHeader(
                sourceAddress, destAddress, IpV4Packet.PROTOCOL_TCP, tcpHeader.size + payload.size
            )
            return ipHeader + tcpHeader + payload
        }

        private fun writeUInt32(buffer: ByteArray, offset: Int, value: Long) {
            buffer[offset] = ((value shr 24) and 0xFF).toByte()
            buffer[offset + 1] = ((value shr 16) and 0xFF).toByte()
            buffer[offset + 2] = ((value shr 8) and 0xFF).toByte()
            buffer[offset + 3] = (value and 0xFF).toByte()
        }

        /** TCP checksum over a pseudo-header + the TCP header + payload. */
        private fun tcpChecksum(
            src: InetAddress,
            dst: InetAddress,
            tcpHeader: ByteArray,
            payload: ByteArray
        ): Int {
            val pseudoAndSegment = ByteArray(12 + tcpHeader.size + payload.size)
            System.arraycopy(src.address, 0, pseudoAndSegment, 0, 4)
            System.arraycopy(dst.address, 0, pseudoAndSegment, 4, 4)
            pseudoAndSegment[8] = 0
            pseudoAndSegment[9] = IpV4Packet.PROTOCOL_TCP.toByte()
            val tcpLength = tcpHeader.size + payload.size
            pseudoAndSegment[10] = ((tcpLength shr 8) and 0xFF).toByte()
            pseudoAndSegment[11] = (tcpLength and 0xFF).toByte()
            System.arraycopy(tcpHeader, 0, pseudoAndSegment, 12, tcpHeader.size)
            System.arraycopy(payload, 0, pseudoAndSegment, 12 + tcpHeader.size, payload.size)
            return IpV4Packet.checksum(pseudoAndSegment, 0, pseudoAndSegment.size)
        }
    }
}
