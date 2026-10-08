package com.socksrelay.vertex

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.socksrelay.vertex.databinding.ActivityFavoritesBinding
import com.socksrelay.vertex.freeproxy.FavoritesStore
import com.socksrelay.vertex.freeproxy.FreeProxy
import com.socksrelay.vertex.freeproxy.ProxyHealth
import com.socksrelay.vertex.freeproxy.ProxyHealthStore
import com.socksrelay.vertex.freeproxy.ProxyListAdapter
import com.socksrelay.vertex.net.ProxyTester

/**
 * Your saved favorite proxies, across all countries — same row UI
 * (protocol, host:port, copy/test/star) as the per-country browser via
 * [ProxyListAdapter], but its own simpler layout (no refresh button or
 * protocol filter — those are about the live upstream list, not a
 * user-curated favorites list).
 */
class FavoritesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFavoritesBinding
    private lateinit var adapter: ProxyListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.favorites_title)
        binding.proxiesEmptyStateText.text = getString(R.string.favorites_empty)

        binding.proxyRecyclerView.layoutManager = LinearLayoutManager(this)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        // Re-check in case a favorite was removed via its star while this
        // screen was in the background (e.g. user backed out and back in).
        refreshList()
    }

    private fun refreshList() {
        val favorites = FavoritesStore.loadAll(this)
        if (favorites.isEmpty()) {
            binding.proxyRecyclerView.visibility = View.GONE
            binding.proxiesEmptyStateText.visibility = View.VISIBLE
            return
        }
        binding.proxyRecyclerView.visibility = View.VISIBLE
        binding.proxiesEmptyStateText.visibility = View.GONE

        adapter = ProxyListAdapter(
            proxies = favorites,
            isFavorite = { true }, // everything on this screen is, by definition, a favorite
            onCopy = { proxy -> copyToClipboard(proxy) },
            onTest = { proxy, resultView, badgeView -> testProxy(proxy, resultView, badgeView) },
            onToggleFavorite = { proxy, _ ->
                // Removing here means the item should disappear entirely,
                // not just flip its star — rebuild the whole list.
                FavoritesStore.remove(this, proxy)
                Toast.makeText(this, R.string.favorite_removed, Toast.LENGTH_SHORT).show()
                refreshList()
            }
        )
        binding.proxyRecyclerView.adapter = adapter
    }

    private fun copyToClipboard(proxy: FreeProxy) {
        val text = proxy.toProxyString()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Proxy", text))
        Toast.makeText(this, getString(R.string.proxy_copied, text), Toast.LENGTH_SHORT).show()
    }

    private fun testProxy(proxy: FreeProxy, resultView: TextView, badgeView: View) {
        resultView.visibility = View.VISIBLE
        resultView.text = getString(R.string.testing_proxy)

        Thread({
            val result = ProxyTester.test(proxy.protocol, proxy.host, proxy.port, proxy.username, proxy.password)
            ProxyHealthStore.recordTest(proxy, result.success)
            runOnUiThread {
                resultView.text = if (result.success) {
                    "\u2705 ${result.message} (${result.elapsedMs}ms)"
                } else {
                    "\u274C ${result.message}"
                }
                // Unlike the country browser, a failed test does NOT remove
                // a favorite — the user starred it deliberately, and a
                // failed test here (unlike there) doesn't necessarily mean
                // "gone from the upstream list forever", just "didn't work
                // just now". The red badge (via ProxyHealthStore above)
                // already communicates that without deleting anything.
                ProxyListAdapter.setHealthBadge(badgeView, if (result.success) ProxyHealth.GOOD else ProxyHealth.BAD)
            }
        }, "FavoriteTestThread").start()
    }
}
