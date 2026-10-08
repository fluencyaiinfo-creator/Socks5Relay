package com.socksrelay.vertex

import com.socksrelay.vertex.net.ProxyProtocol
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight session statistics for the connection dashboard.
 * Counts application payload bytes relayed through the VPN tunnel.
 */
object ConnectionStats {
    data class Snapshot(
        val active: Boolean,
        val startedAtMs: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val proxyHost: String,
        val proxyPort: Int,
        val protocol: ProxyProtocol
    )

    @Volatile private var active = false
    @Volatile private var startedAtMs = 0L
    private val uploadedBytes = AtomicLong(0L)
    private val downloadedBytes = AtomicLong(0L)
    @Volatile private var proxyHost = ""
    @Volatile private var proxyPort = 0
    @Volatile private var protocol = ProxyProtocol.SOCKS5

    @Synchronized
    fun start(protocol: ProxyProtocol, host: String, port: Int) {
        this.protocol = protocol
        proxyHost = host
        proxyPort = port
        uploadedBytes.set(0L)
        downloadedBytes.set(0L)
        startedAtMs = System.currentTimeMillis()
        active = true
    }

    @Synchronized
    fun stop() {
        active = false
    }

    fun addUploaded(bytes: Long) {
        if (bytes > 0) uploadedBytes.addAndGet(bytes)
    }

    fun addDownloaded(bytes: Long) {
        if (bytes > 0) downloadedBytes.addAndGet(bytes)
    }

    fun snapshot(): Snapshot = Snapshot(
        active = active,
        startedAtMs = startedAtMs,
        uploadedBytes = uploadedBytes.get(),
        downloadedBytes = downloadedBytes.get(),
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        protocol = protocol
    )
}
