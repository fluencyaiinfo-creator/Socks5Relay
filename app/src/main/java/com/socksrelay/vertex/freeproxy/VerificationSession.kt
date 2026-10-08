package com.socksrelay.vertex.freeproxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Verifies a stream of candidate proxies in parallel and hands back only
 * the ones that really work (see [ProxyVerifier]).
 *
 * Candidates can be added at any time with [offer] (so verification starts
 * on the first source's results while other sources are still downloading)
 * and are checked in rank order — lower rank first — so the most trustworthy
 * candidates (the ones already on screen, then the hourly-checked list) are
 * judged before the huge low-quality one. Each proxy is checked at most once
 * per session.
 *
 * A proxy that passes but has no country yet goes through [GeoResolver]
 * on a few separate threads, so slow lookups never hold up verification.
 * One that can't be placed in a country is left out this round.
 *
 * Results are collected with [drainVerified] / [drainFailed]; call
 * [closeInput] once nothing more will be offered, and wait for [isFinished].
 */
class VerificationSession(
    private val target: ProxyVerifier.Target,
    private val deadlineAtMs: Long,
    private val verifyThreads: Int = DEFAULT_VERIFY_THREADS
) {
    companion object {
        const val DEFAULT_VERIFY_THREADS = 300
        private const val GEO_THREADS = 8
        private const val GEO_GRACE_MS = 5_000L
    }

    private class Candidate(val proxy: FreeProxy, val rank: Int, val seq: Long) : Comparable<Candidate> {
        override fun compareTo(other: Candidate): Int =
            if (rank != other.rank) rank.compareTo(other.rank) else seq.compareTo(other.seq)
    }

    private val queue = PriorityBlockingQueue<Candidate>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val seq = AtomicLong()
    private val inputClosed = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private val verified = ConcurrentLinkedQueue<FreeProxy>()
    private val failed = ConcurrentLinkedQueue<String>()
    private val geoQueue = LinkedBlockingQueue<FreeProxy>()

    private val verifyLeft = AtomicInteger(verifyThreads)
    private val geoLeft = AtomicInteger(GEO_THREADS)

    val checked = AtomicInteger()
    val alive = AtomicInteger()
    val geoMissed = AtomicInteger()

    private val pool = Executors.newFixedThreadPool(verifyThreads + GEO_THREADS) { r ->
        Thread(r, "ProxyVerify").apply { isDaemon = true }
    }

    fun start() {
        repeat(verifyThreads) { pool.execute { verifyLoop() } }
        repeat(GEO_THREADS) { pool.execute { geoLoop() } }
    }

    /** Adds candidates (skipping any already seen this session). */
    fun offer(list: List<FreeProxy>, rank: Int) {
        for (p in list) {
            if (seen.add(p.dedupeKey)) queue.add(Candidate(p, rank, seq.incrementAndGet()))
        }
    }

    fun closeInput() { inputClosed.set(true) }

    fun isFinished(): Boolean = verifyLeft.get() == 0 && geoLeft.get() == 0

    fun drainVerified(): List<FreeProxy> = drain(verified)
    fun drainFailed(): List<String> = drain(failed)

    /** Stops everything immediately (used if the deadline passes with work still running). */
    fun abort() {
        stopped.set(true)
        pool.shutdownNow()
    }

    private fun <T> drain(q: ConcurrentLinkedQueue<T>): List<T> {
        val out = ArrayList<T>()
        while (true) out.add(q.poll() ?: break)
        return out
    }

    private fun pastDeadline() = System.currentTimeMillis() >= deadlineAtMs

    private fun verifyLoop() {
        try {
            while (!stopped.get() && !pastDeadline()) {
                val c = queue.poll(200, TimeUnit.MILLISECONDS)
                if (c == null) {
                    if (inputClosed.get() && queue.isEmpty()) break else continue
                }
                val p = c.proxy
                val verdict = ProxyVerifier.verify(p, target)
                checked.incrementAndGet()
                if (!verdict.ok) {
                    failed.add(p.dedupeKey)
                    continue
                }
                alive.incrementAndGet()
                val good = p.copy(latencyMs = verdict.latencyMs)
                if (good.countryCode != null) verified.add(good) else geoQueue.add(good)
            }
        } catch (e: InterruptedException) {
            // aborted
        } finally {
            verifyLeft.decrementAndGet()
        }
    }

    private fun geoLoop() {
        try {
            while (!stopped.get()) {
                val p = geoQueue.poll(200, TimeUnit.MILLISECONDS)
                if (p == null) {
                    if (verifyLeft.get() == 0 && geoQueue.isEmpty()) break else continue
                }
                val geo = GeoResolver.resolve(p.host)
                if (geo != null) {
                    verified.add(p.copy(countryCode = geo.first, countryName = geo.second))
                } else {
                    geoMissed.incrementAndGet()
                }
                if (System.currentTimeMillis() > deadlineAtMs + GEO_GRACE_MS) break
            }
        } catch (e: InterruptedException) {
            // aborted
        } finally {
            geoLeft.decrementAndGet()
        }
    }
}
