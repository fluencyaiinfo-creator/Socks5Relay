package com.socksrelay.vertex.freeproxy

import com.socksrelay.vertex.log.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the three proxy sources in parallel:
 *  - **Source 1** — a CSV that already includes country + protocol
 *  - **Source 2** — hourly-checked plain lists (no country)
 *  - **Source 3** — large daily plain lists (no country)
 *
 * Each source hands its raw candidates to [onSource] the moment it finishes,
 * so verification (see [VerificationSession]) can start on the first batch
 * while the others are still downloading. Nothing returned here is trusted:
 * the repository only ever shows proxies that passed verification.
 *
 * A source that fails or is still slow after [DEADLINE_SECONDS] simply
 * contributes nothing this round; the others are unaffected.
 */
object FreeProxyFetcher {
    private const val TAG = "FreeProxyFetcher"
    private const val DEADLINE_SECONDS = 30L

    fun fetchAll(onSource: (name: String, proxies: List<FreeProxy>) -> Unit): Int {
        val sources = listOf<Pair<String, () -> List<FreeProxy>>>(
            "Source 2" to { MonosansSource.fetch() },
            "Source 1" to { VakhovSource.fetch() },
            "Source 3" to { TheSpeedXSource.fetch() }
        )
        val pool = Executors.newFixedThreadPool(sources.size)
        val latch = CountDownLatch(sources.size)
        val closed = AtomicBoolean(false)
        val total = java.util.concurrent.atomic.AtomicInteger(0)

        for ((name, fetch) in sources) {
            pool.execute {
                try {
                    val list = fetch()
                    if (!closed.get()) {
                        total.addAndGet(list.size)
                        AppLog.i(TAG, "$name: ${list.size} candidates")
                        onSource(name, list)
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG, "$name failed: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
        }

        val allDone = latch.await(DEADLINE_SECONDS, TimeUnit.SECONDS)
        closed.set(true)
        pool.shutdownNow()
        if (!allDone) AppLog.w(TAG, "Some sources were still slow after ${DEADLINE_SECONDS}s — continuing without them")
        return total.get()
    }
}
