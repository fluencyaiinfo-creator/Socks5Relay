package com.socksrelay.vertex.freeproxy

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Works out which country a proxy IP is in, for the sources whose lists
 * contain only `ip:port`. Only ever called for proxies that have **already
 * passed verification**, so the number of lookups per refresh stays small.
 *
 * Uses free, key-less HTTPS lookup services in order, skipping any that has
 * failed repeatedly for a minute (so one rate-limited service doesn't slow
 * everything down). Results are cached for the life of the process.
 */
object GeoResolver {
    private const val TIMEOUT_MS = 3_500
    private const val FAILS_BEFORE_PAUSE = 8
    private const val PAUSE_MS = 60_000L

    private val cache = ConcurrentHashMap<String, Pair<String, String>>()

    private class Provider(
        val url: (String) -> String,
        val parse: (String) -> Pair<String, String?>?
    ) {
        @Volatile var fails = 0
        @Volatile var pausedUntil = 0L
    }

    private val providers = listOf(
        Provider({ ip -> "https://api.country.is/$ip" }) { body ->
            val code = JSONObject(body).optString("country")
            if (code.length == 2) code to null else null
        },
        Provider({ ip -> "https://ipwho.is/$ip?fields=success,country_code,country" }) { body ->
            val j = JSONObject(body)
            val code = j.optString("country_code")
            if (j.optBoolean("success", false) && code.length == 2) code to j.optString("country").ifEmpty { null } else null
        },
        Provider({ ip -> "https://get.geojs.io/v1/ip/country/$ip.json" }) { body ->
            val t = body.trim()
            val j = if (t.startsWith("[")) JSONArray(t).getJSONObject(0) else JSONObject(t)
            val code = j.optString("country")
            if (code.length == 2) code to j.optString("name").ifEmpty { null } else null
        }
    )

    /** Reuse countries we already know (cache file, Source 1's own data) so they are never looked up again. */
    fun seed(list: List<FreeProxy>) {
        for (p in list) {
            val code = p.countryCode ?: continue
            val name = p.countryName ?: continue
            cache.putIfAbsent(p.host, code to name)
        }
    }

    /** Blocking. Returns (ISO code, English country name) or null if every service failed. */
    fun resolve(ip: String): Pair<String, String>? {
        cache[ip]?.let { return it }
        val now = System.currentTimeMillis()
        for (p in providers) {
            if (p.pausedUntil > now) continue
            val parsed = try {
                fetch(p.url(ip))?.let(p.parse)
            } catch (e: Exception) {
                null
            }
            if (parsed == null) {
                p.fails++
                if (p.fails >= FAILS_BEFORE_PAUSE) {
                    p.pausedUntil = System.currentTimeMillis() + PAUSE_MS
                    p.fails = 0
                }
                continue
            }
            p.fails = 0
            val code = parsed.first.uppercase()
            if (code.length != 2 || !code.all { it in 'A'..'Z' } || code == "ZZ" || code == "XX") continue
            val entry = code to nameFor(code, parsed.second)
            cache[ip] = entry
            return entry
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun nameFor(code: String, providerName: String?): String {
        val local = Locale("", code).getDisplayCountry(Locale.ENGLISH)
        return when {
            local.isNotBlank() && local != code -> local
            !providerName.isNullOrBlank() -> providerName
            else -> code
        }
    }

    private fun fetch(urlString: String): String? {
        val c = URL(urlString).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("User-Agent", "SocksRelay-Android-App")
        return try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
