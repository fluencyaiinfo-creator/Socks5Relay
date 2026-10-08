package com.socksrelay.vertex.freeproxy

import android.content.Context
import com.socksrelay.vertex.log.AppLog
import com.socksrelay.vertex.net.ProxyProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Proxies the user has starred as favorites (after testing/copying them,
 * typically), persisted to a small JSON file in app-private storage so
 * they survive restarts — same on-disk pattern as [FreeProxyRepository]'s
 * cache, just a separate file and a much smaller, user-curated list.
 */
object FavoritesStore {
    private const val TAG = "FavoritesStore"
    private const val FILE_NAME = "favorite_proxies.json"

    @Volatile private var cached: MutableList<FreeProxy>? = null

    fun isFavorite(context: Context, proxy: FreeProxy): Boolean {
        return loadAll(context).any { it.dedupeKey == proxy.dedupeKey }
    }

    fun toggle(context: Context, proxy: FreeProxy): Boolean {
        return if (isFavorite(context, proxy)) {
            remove(context, proxy)
            false
        } else {
            add(context, proxy)
            true
        }
    }

    fun add(context: Context, proxy: FreeProxy) {
        val list = loadAll(context).toMutableList()
        if (list.none { it.dedupeKey == proxy.dedupeKey }) {
            list.add(proxy)
            saveAll(context, list)
        }
    }

    fun remove(context: Context, proxy: FreeProxy) {
        val list = loadAll(context).toMutableList()
        if (list.removeAll { it.dedupeKey == proxy.dedupeKey }) {
            saveAll(context, list)
        }
    }

    fun loadAll(context: Context): List<FreeProxy> {
        cached?.let { return it }
        val file = File(context.filesDir, FILE_NAME)
        val list = mutableListOf<FreeProxy>()
        if (file.exists()) {
            try {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        FreeProxy(
                            host = obj.getString("host"),
                            port = obj.getInt("port"),
                            protocol = ProxyProtocol.fromString(obj.optString("protocol")),
                            username = obj.optString("username").ifEmpty { null },
                            password = obj.optString("password").ifEmpty { null },
                            countryCode = obj.optString("countryCode").ifEmpty { null },
                            countryName = obj.optString("countryName").ifEmpty { null }
                        )
                    )
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Could not read favorites: ${e.message}")
            }
        }
        cached = list
        return list
    }

    private fun saveAll(context: Context, list: List<FreeProxy>) {
        cached = list.toMutableList()
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
                })
            }
            File(context.filesDir, FILE_NAME).writeText(array.toString())
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not save favorites: ${e.message}")
        }
    }
}
