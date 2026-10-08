package com.socksrelay.vertex.freeproxy

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.socksrelay.vertex.ConnectionState
import com.socksrelay.vertex.log.AppLog
import com.socksrelay.vertex.net.ProxyProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for the free-proxy list. **Only proxies that have
 * just passed a real end-to-end check are ever shown** (see
 * [ProxyVerifier]) — there is no "unverified" state in the UI any more.
 *
 * How a dead proxy is kept out / pushed out:
 *  - **Full refresh** (on launch, then every 15 min, or the Refresh button):
 *    the list currently on screen is re-checked first, then new candidates
 *    from [FreeProxyFetcher] are checked as they download. Whatever fails
 *    disappears; whatever passes appears — live, while it runs.
 *  - **Quick re-check** every 3 min: re-verifies only what's on screen and
 *    removes the ones that died since.
 *  - **Per-country re-check** whenever a country screen is opened, so the
 *    proxies you're about to pick were alive seconds ago.
 *  - **Test button** failures remove the proxy at once ([removeDead]).
 *
 * Nothing is verified while the VPN is connected: those checks would be
 * captured by the user's own tunnel and test the wrong thing. The list just
 * stays as it was until you disconnect.
 *
 * Caches to disk so something is on screen instantly at next launch — the
 * first full refresh then re-checks it. Not WorkManager-backed: if the
 * process is killed, refreshing stops until the app is reopened.
 *
 * IMPORTANT — free proxy lists are a real privacy/security tradeoff: these
 * are public, unauthenticated servers run by unknown third parties. Some
 * are fine; some log everything that passes through them, or worse. Don't
 * route anything sensitive (banking, logins you care about, etc.) through
 * one of these without understanding that risk.
 */
object FreeProxyRepository {
    private const val TAG = "FreeProxyRepository"
    private const val REFRESH_INTERVAL_MS = 15 * 60 * 1000L
    private const val RECHECK_INTERVAL_MS = 3 * 60 * 1000L
    private const val CACHE_FILE_NAME = "free_proxy_cache.json"

    private const val FULL_BUDGET_MS = 90_000L
    private const val RECHECK_BUDGET_MS = 40_000L
    private const val COUNTRY_BUDGET_MS = 20_000L
    private const val WAIT_GRACE_MS = 7_000L

    // Lower rank = checked first.
    private const val RANK_ON_SCREEN = 0
    private const val RANK_SOURCE_2 = 1
    private const val RANK_SOURCE_1 = 2
    private const val RANK_SOURCE_3 = 3

    data class CountryGroup(val countryCode: String, val countryName: String, val proxies: List<FreeProxy>)

    @Volatile var proxies: List<FreeProxy> = emptyList()
        private set
    @Volatile var lastUpdated: Long = 0L
        private set
    @Volatile var isRefreshing: Boolean = false
        private set

    private val listeners = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var initialized = false

    /** Only one verification run at a time (full refresh or quick re-check). */
    private val busy = AtomicBoolean(false)
    @Volatile private var pendingRefresh = false
    private val countryRechecks = HashSet<String>()

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val recheckRunnable = object : Runnable {
        override fun run() {
            recheck()
            mainHandler.postDelayed(this, RECHECK_INTERVAL_MS)
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        loadFromCache()
        GeoResolver.seed(proxies)
        mainHandler.post(refreshRunnable) // first run is immediate, then repeats every 15 min
        mainHandler.postDelayed(recheckRunnable, RECHECK_INTERVAL_MS)
    }

    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post {
            synchronized(this) { listeners.toList() }.forEach { it() }
        }
    }

    // ---- Public triggers -------------------------------------------------

    /** Safe to call from the UI thread — all work happens on background threads. */
    fun refresh() {
        if (isRefreshing) {
            AppLog.i(TAG, "Refresh already in progress — ignoring")
            return
        }
        isRefreshing = true
        notifyListeners()
        if (busy.compareAndSet(false, true)) launch(full = true) else pendingRefresh = true
    }

    /** Quick background re-check of what's on screen; silently skipped if anything else is running or the VPN is up. */
    private fun recheck() {
        if (isRefreshing || ConnectionState.isConnected || proxies.isEmpty()) return
        if (busy.compareAndSet(false, true)) launch(full = false)
    }

    /** Called when a country screen opens: re-verifies just that country's proxies right now. */
    fun recheckCountry(code: String) {
        if (ConnectionState.isConnected || isRefreshing) return
        synchronized(countryRechecks) { if (!countryRechecks.add(code)) return }
        Thread({
            try {
                val list = proxies.filter { it.countryCode == code }
                if (list.isEmpty()) return@Thread
                val target = ProxyVerifier.resolveTarget() ?: return@Thread
                val deadline = System.currentTimeMillis() + COUNTRY_BUDGET_MS
                val session = VerificationSession(target, deadline, verifyThreads = 150)
                session.start()
                session.offer(list, RANK_ON_SCREEN)
                session.closeInput()
                while (!session.isFinished() && System.currentTimeMillis() < deadline + WAIT_GRACE_MS) Thread.sleep(300)
                if (!session.isFinished()) session.abort()

                val updated = session.drainVerified().associateBy { it.dedupeKey }
                val dead = session.drainFailed().toSet()
                // Everything failing at once while our own connection is down means "offline", not "all dead".
                if (updated.isEmpty() && dead.isNotEmpty() && !ProxyVerifier.networkLooksUp(target)) return@Thread
                applyVerdicts(dead, updated)
            } catch (e: Exception) {
                AppLog.w(TAG, "Country re-check failed: ${e.message}")
            } finally {
                synchronized(countryRechecks) { countryRechecks.remove(code) }
            }
        }, "CountryRecheckThread").start()
    }

    // ---- Verification cycle ----------------------------------------------

    /** [busy] must already be held by the caller; it is released here when the run ends. */
    private fun launch(full: Boolean) {
        Thread({
            try {
                runCycle(full)
            } catch (e: Exception) {
                AppLog.w(TAG, "Proxy check run failed: ${e.message}")
            } finally {
                busy.set(false)
                if (full) {
                    isRefreshing = false
                    notifyListeners()
                }
                if (pendingRefresh) {
                    pendingRefresh = false
                    if (busy.compareAndSet(false, true)) launch(full = true)
                    else { isRefreshing = false; notifyListeners() }
                }
            }
        }, if (full) "FreeProxyRefreshThread" else "FreeProxyRecheckThread").start()
    }

    private fun rankFor(sourceName: String) = when (sourceName) {
        "Source 2" -> RANK_SOURCE_2
        "Source 1" -> RANK_SOURCE_1
        else -> RANK_SOURCE_3
    }

    private fun runCycle(full: Boolean) {
        if (ConnectionState.isConnected) {
            if (full) AppLog.i(TAG, "Refresh paused while the VPN is connected — disconnect to refresh the list")
            return
        }
        val target = ProxyVerifier.resolveTarget()
        if (target == null) {
            if (full) AppLog.w(TAG, "No internet connection — keeping the current list",
                "Check your device's internet connection. The list will refresh by itself once you're back online.")
            return
        }

        val startList = proxies
        GeoResolver.seed(startList)
        val deadline = System.currentTimeMillis() + if (full) FULL_BUDGET_MS else RECHECK_BUDGET_MS
        val session = VerificationSession(target, deadline)
        session.start()
        session.offer(startList, RANK_ON_SCREEN) // what's on screen is judged first

        if (full) AppLog.i(TAG, "Refreshing free proxy list — only working proxies will be shown...")

        val fetchThread: Thread? = if (full) {
            Thread({
                try {
                    FreeProxyFetcher.fetchAll { name, list ->
                        GeoResolver.seed(list) // Source 1 already knows each proxy's country
                        session.offer(if (name == "Source 3") list.shuffled() else list, rankFor(name))
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG, "Fetching sources failed: ${e.message}")
                } finally {
                    session.closeInput()
                }
            }, "FreeProxyFetchThread").also { it.start() }
        } else {
            session.closeInput()
            null
        }

        val good = LinkedHashMap<String, FreeProxy>()
        val judged = HashSet<String>()
        fun collect(): Boolean {
            var changed = false
            for (p in session.drainVerified()) {
                good[p.dedupeKey] = p
                judged.add(p.dedupeKey)
                ProxyHealthStore.recordLiveness(p, true)
                changed = true
            }
            for (key in session.drainFailed()) {
                judged.add(key)
                changed = true
            }
            return changed
        }

        val hardStop = deadline + WAIT_GRACE_MS
        while (!session.isFinished() && System.currentTimeMillis() < hardStop) {
            Thread.sleep(1_000)
            if (collect()) {
                // Interim view: verified so far + on-screen proxies not judged yet.
                val stale = startList.filter { it.dedupeKey !in judged && it.dedupeKey !in good }
                commit(good.values.toList() + stale)
            }
        }
        if (!session.isFinished()) session.abort()
        collect()
        fetchThread?.join(2_000)

        val result = good.values.toList()
        if (result.isEmpty() && (session.checked.get() == 0 || !ProxyVerifier.networkLooksUp(target))) {
            // Nothing could be judged, or the connection dropped mid-run: don't wipe the list over that.
            AppLog.w(TAG, "Couldn't verify any proxies this time — keeping the previous list")
            commit(startList)
            return
        }

        commit(result)
        ProxyHealthStore.retainOnly(result.map { it.dedupeKey }.toSet())
        saveToCache(result)
        val countries = result.mapNotNull { it.countryCode }.distinct().size
        AppLog.success(TAG, "===== ${result.size} working proxies across $countries countries " +
            "(checked ${session.checked.get()}, ${session.checked.get() - session.alive.get()} dead) =====")
    }

    @Synchronized
    private fun commit(list: List<FreeProxy>) {
        proxies = list
        lastUpdated = System.currentTimeMillis()
        notifyListeners()
    }

    /** Applies the outcome of a partial re-check (one country) without touching the rest of the list. */
    @Synchronized
    private fun applyVerdicts(dead: Set<String>, updated: Map<String, FreeProxy>) {
        if (dead.isEmpty() && updated.isEmpty()) return
        val before = proxies.size
        val next = proxies.filterNot { it.dedupeKey in dead }.map { updated[it.dedupeKey] ?: it }
        proxies = next
        if (next.size != before) AppLog.i(TAG, "Removed ${before - next.size} dead proxies")
        for (p in updated.values) ProxyHealthStore.recordLiveness(p, true)
        saveToCache(next)
        notifyListeners()
    }

    /**
     * Drops one proxy from the list immediately — used when the per-row
     * "Test" button (a full protocol test) confirms it is dead right now.
     */
    @Synchronized
    fun removeDead(proxy: FreeProxy) {
        if (proxies.none { it.dedupeKey == proxy.dedupeKey }) return
        proxies = proxies.filterNot { it.dedupeKey == proxy.dedupeKey }
        saveToCache(proxies)
        AppLog.i(TAG, "Removed dead proxy confirmed by Test: ${proxy.protocol.name} ${proxy.host}:${proxy.port}")
        notifyListeners()
    }

    /** United States and United Kingdom pinned first, then everyone else alphabetically by name. */
    private val PINNED_COUNTRY_CODES = listOf("US", "GB")

    fun groupedByCountry(): List<CountryGroup> {
        return proxies
            .filter { it.countryCode != null }
            .groupBy { it.countryCode!! }
            .map { (code, list) ->
                // Different sources spell a country slightly differently; show the most common spelling once.
                val name = list.mapNotNull { it.countryName }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: code
                CountryGroup(
                    countryCode = code,
                    countryName = name,
                    proxies = list.sortedWith(
                        compareBy({ it.latencyMs ?: Int.MAX_VALUE }, { it.protocol.name }, { it.host }, { it.port })
                    )
                )
            }
            .sortedWith(
                compareBy(
                    { group -> PINNED_COUNTRY_CODES.indexOf(group.countryCode).let { if (it == -1) Int.MAX_VALUE else it } },
                    { group -> group.countryName }
                )
            )
    }

    // ---- Local disk cache ------------------------------------------------

    private fun cacheFile(): File? = appContext?.let { File(it.filesDir, CACHE_FILE_NAME) }

    private fun loadFromCache() {
        val file = cacheFile() ?: return
        if (!file.exists()) return
        try {
            val array = JSONArray(file.readText())
            val loaded = mutableListOf<FreeProxy>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                loaded.add(
                    FreeProxy(
                        host = obj.getString("host"),
                        port = obj.getInt("port"),
                        protocol = ProxyProtocol.fromString(obj.optString("protocol")),
                        username = obj.optString("username").ifEmpty { null },
                        password = obj.optString("password").ifEmpty { null },
                        countryCode = obj.optString("countryCode").ifEmpty { null },
                        countryName = obj.optString("countryName").ifEmpty { null },
                        latencyMs = obj.optInt("latencyMs", -1).takeIf { it >= 0 }
                    )
                )
            }
            proxies = loaded
            lastUpdated = file.lastModified()
            AppLog.i(TAG, "Loaded ${loaded.size} proxies from local cache (last updated ${ageDescription()}) — re-checking them now")
            notifyListeners()
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not read free proxy cache: ${e.message}")
        }
    }

    private fun saveToCache(list: List<FreeProxy>) {
        val file = cacheFile() ?: return
        try {
            val array = JSONArray()
            for (p in list) {
                array.put(JSONObject().apply {
                    put("host", p.host)
                    put("port", p.port)
                    put("protocol", p.protocol.name)
                    put("username", p.username ?: "")
                    put("password", p.password ?: "")
                    put("countryCode", p.countryCode ?: "")
                    put("countryName", p.countryName ?: "")
                    put("latencyMs", p.latencyMs ?: -1)
                })
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not write free proxy cache: ${e.message}")
        }
    }

    fun ageDescription(): String {
        if (lastUpdated == 0L) return "never"
        val minutes = (System.currentTimeMillis() - lastUpdated) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes == 1L -> "1 minute ago"
            else -> "$minutes minutes ago"
        }
    }
}
