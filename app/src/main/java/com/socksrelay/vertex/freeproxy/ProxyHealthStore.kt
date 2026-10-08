package com.socksrelay.vertex.freeproxy

import java.util.concurrent.ConcurrentHashMap

enum class ProxyHealth { UNKNOWN, GOOD, BAD }

/**
 * Last known result per proxy (keyed by [FreeProxy.dedupeKey]), backing the
 * green/yellow/red badge shown next to each row — deliberately **in-memory
 * only**, not persisted to disk. A badge is a claim about "is this proxy
 * alive right now", and carrying that claim across an app restart (when it
 * could easily be hours later) would just be showing a stale, possibly
 * wrong answer as if it were current. Starting blank (yellow/UNKNOWN)
 * every launch is the honest default.
 *
 * Two things feed this:
 *  - the verification run on every refresh / re-check — a real
 *    handshake plus HTTP request through the proxy ([ProxyVerifier]). Proxies
 *    that fail are removed from the list, so a shown proxy is GOOD or not yet re-checked.
 *  - The per-row "Test" button — a real protocol handshake via
 *    [com.socksrelay.vertex.net.ProxyTester], the stronger signal.
 * If the Test button has ever run for a proxy this session, its result
 * wins and the liveness sweep is no longer allowed to overwrite it — see
 * [recordLiveness].
 */
object ProxyHealthStore {
    private val results = ConcurrentHashMap<String, ProxyHealth>()
    private val confirmedByRealTest = ConcurrentHashMap<String, Boolean>()

    fun healthOf(proxy: FreeProxy): ProxyHealth = results[proxy.dedupeKey] ?: ProxyHealth.UNKNOWN

    /** From the automatic verification runs — skipped for any proxy the Test button has already given a real result for. */
    fun recordLiveness(proxy: FreeProxy, reachable: Boolean) {
        if (confirmedByRealTest[proxy.dedupeKey] == true) return
        results[proxy.dedupeKey] = if (reachable) ProxyHealth.GOOD else ProxyHealth.BAD
    }

    /** From the per-row Test button — a full protocol handshake, so this always wins over a liveness-sweep guess. */
    fun recordTest(proxy: FreeProxy, success: Boolean) {
        results[proxy.dedupeKey] = if (success) ProxyHealth.GOOD else ProxyHealth.BAD
        confirmedByRealTest[proxy.dedupeKey] = true
    }

    /** Called after each refresh merges a new proxy list, so entries for proxies no longer in the list don't accumulate forever. */
    fun retainOnly(dedupeKeys: Set<String>) {
        results.keys.retainAll(dedupeKeys)
        confirmedByRealTest.keys.retainAll(dedupeKeys)
    }
}
