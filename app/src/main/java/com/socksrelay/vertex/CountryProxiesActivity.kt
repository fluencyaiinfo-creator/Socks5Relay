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
import com.socksrelay.vertex.ads.AdIds
import com.socksrelay.vertex.ads.AdManager
import com.socksrelay.vertex.databinding.ActivityCountryProxiesBinding
import com.socksrelay.vertex.freeproxy.CountryFlags
import com.socksrelay.vertex.freeproxy.FavoritesStore
import com.socksrelay.vertex.freeproxy.FreeProxy
import com.socksrelay.vertex.freeproxy.FreeProxyRepository
import com.socksrelay.vertex.freeproxy.ProxyHealth
import com.socksrelay.vertex.freeproxy.ProxyHealthStore
import com.socksrelay.vertex.freeproxy.ProxyListAdapter
import com.socksrelay.vertex.freeproxy.UnlockStore
import com.socksrelay.vertex.net.ProxyProtocol
import com.socksrelay.vertex.net.ProxyTester

/**
 * Every proxy available for one country, with:
 *  - a Refresh button (re-runs the same global [FreeProxyRepository.refresh],
 *    then re-filters this screen to the same country once it completes),
 *  - a protocol filter (SOCKS5 / SOCKS4 / HTTP, multi-select),
 *  - per-row Copy / Test / favorite-star,
 *  - and a monetization gate: only the first [FREE_PROXY_COUNT] proxies
 *    show by default; watching a rewarded ad unlocks the rest for this
 *    country (persisted — see [UnlockStore]).
 */
class CountryProxiesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COUNTRY_CODE = "extra_country_code"
        const val EXTRA_COUNTRY_NAME = "extra_country_name"
        private const val FREE_PROXY_COUNT = 3
    }

    private lateinit var binding: ActivityCountryProxiesBinding
    private lateinit var adapter: ProxyListAdapter
    private var countryCode: String? = null
    private val repositoryListener = { onRepositoryChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCountryProxiesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        countryCode = intent.getStringExtra(EXTRA_COUNTRY_CODE)
        val countryName = intent.getStringExtra(EXTRA_COUNTRY_NAME) ?: "Unknown"
        title = "${CountryFlags.flagEmoji(countryCode)} $countryName"

        adapter = ProxyListAdapter(
            proxies = emptyList(),
            isFavorite = { proxy -> FavoritesStore.isFavorite(this, proxy) },
            onCopy = { proxy -> copyToClipboard(proxy) },
            onTest = { proxy, resultView, badgeView -> testProxy(proxy, resultView, badgeView) },
            onToggleFavorite = { proxy, button -> toggleFavorite(proxy, button) }
        )
        binding.proxyRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.proxyRecyclerView.adapter = adapter

        binding.countryRefreshButton.setOnClickListener { FreeProxyRepository.refresh() }
        binding.protocolFilterChipGroup.setOnCheckedStateChangeListener { _, _ -> applyFilterAndDisplay() }
        binding.unlockButton.setOnClickListener { watchAdToUnlock() }

        applyFilterAndDisplay()
    }

    override fun onStart() {
        super.onStart()
        FreeProxyRepository.addListener(repositoryListener)
        onRepositoryChanged()
        // Re-verify this country's proxies right now so what's shown was alive seconds ago.
        countryCode?.let { FreeProxyRepository.recheckCountry(it) }
    }

    override fun onStop() {
        FreeProxyRepository.removeListener(repositoryListener)
        super.onStop()
    }

    private fun onRepositoryChanged() {
        binding.countryRefreshButton.isEnabled = !FreeProxyRepository.isRefreshing
        binding.countryRefreshButton.text = getString(
            if (FreeProxyRepository.isRefreshing) R.string.refreshing else R.string.action_refresh
        )
        binding.countryLastUpdatedText.text = getString(
            R.string.free_proxy_last_updated, FreeProxyRepository.proxies.size, FreeProxyRepository.ageDescription()
        )
        applyFilterAndDisplay()
    }

    private fun selectedProtocols(): Set<ProxyProtocol> {
        val selected = mutableSetOf<ProxyProtocol>()
        if (binding.filterSocks5.isChecked) selected.add(ProxyProtocol.SOCKS5)
        if (binding.filterSocks4.isChecked) selected.add(ProxyProtocol.SOCKS4)
        if (binding.filterHttp.isChecked) selected.add(ProxyProtocol.HTTP)
        return selected
    }

    private fun applyFilterAndDisplay() {
        val selectedProtocols = selectedProtocols()
        val allMatching = FreeProxyRepository.proxies
            .filter { it.countryCode == countryCode && it.protocol in selectedProtocols }
            .sortedWith(compareBy({ it.latencyMs ?: Int.MAX_VALUE }, { it.protocol.name }, { it.host }, { it.port }))

        val unlocked = countryCode == null || UnlockStore.isUnlocked(this, countryCode!!)
        val visible = if (unlocked || allMatching.size <= FREE_PROXY_COUNT) allMatching else allMatching.take(FREE_PROXY_COUNT)

        adapter.submitList(visible)

        val showEmpty = visible.isEmpty()
        binding.proxyRecyclerView.visibility = if (showEmpty) View.GONE else View.VISIBLE
        binding.proxiesEmptyStateText.visibility = if (showEmpty) View.VISIBLE else View.GONE
        binding.proxiesEmptyStateText.text = if (selectedProtocols.isEmpty()) {
            getString(R.string.free_proxy_no_protocol_selected)
        } else {
            getString(R.string.free_proxy_empty)
        }

        val locked = !unlocked && allMatching.size > FREE_PROXY_COUNT
        binding.unlockBar.visibility = if (locked) View.VISIBLE else View.GONE
        if (locked) {
            binding.unlockInfoText.text = getString(R.string.unlock_bar_info, visible.size, allMatching.size)
        }
    }

    private fun watchAdToUnlock() {
        val code = countryCode ?: return
        if (AdManager.isBlockedByActiveVpn()) {
            Toast.makeText(this, R.string.unlock_ad_disconnect_vpn_first, Toast.LENGTH_LONG).show()
            return
        }
        binding.unlockButton.isEnabled = false
        AdManager.loadRewarded(this, AdIds.rewardedProxyUnlock) { ad ->
            runOnUiThread {
                binding.unlockButton.isEnabled = true
                if (ad == null) {
                    Toast.makeText(this, R.string.unlock_ad_not_ready, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AdManager.showRewarded(
                    this, ad,
                    onEarned = {
                        UnlockStore.unlock(this, code)
                        runOnUiThread {
                            Toast.makeText(this, R.string.unlock_success, Toast.LENGTH_SHORT).show()
                            applyFilterAndDisplay()
                        }
                    },
                    onClosed = { }
                )
            }
        }
    }

    private fun copyToClipboard(proxy: FreeProxy) {
        val text = proxy.toProxyString()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Proxy", text))
        Toast.makeText(this, getString(R.string.proxy_copied, text), Toast.LENGTH_SHORT).show()
    }

    private fun toggleFavorite(proxy: FreeProxy, button: ImageButton) {
        val nowFavorite = FavoritesStore.toggle(this, proxy)
        ProxyListAdapter.setFavoriteIcon(button, nowFavorite)
        Toast.makeText(
            this,
            if (nowFavorite) R.string.favorite_saved else R.string.favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
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
                if (result.success) {
                    // Update this row's badge immediately — no full list
                    // rebind needed (and one would clear other rows'
                    // currently-visible test results, since onBindViewHolder
                    // always resets testResult on rebind).
                    ProxyListAdapter.setHealthBadge(badgeView, ProxyHealth.GOOD)
                } else {
                    // A real protocol test failing is stronger evidence than
                    // the periodic TCP liveness sweep — no need to wait for
                    // the next scheduled refresh to stop showing this one.
                    // This DOES trigger a full rebind (via the repository
                    // listener), which is correct here: the row disappears.
                    FreeProxyRepository.removeDead(proxy)
                    Toast.makeText(this, R.string.proxy_removed_dead, Toast.LENGTH_SHORT).show()
                }
            }
        }, "FreeProxyTestThread").start()
    }
}
