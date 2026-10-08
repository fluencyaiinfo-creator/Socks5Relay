package com.socksrelay.vertex

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.socksrelay.vertex.ads.AdIds
import com.socksrelay.vertex.ads.AdManager
import com.socksrelay.vertex.ads.AdPlacements
import com.socksrelay.vertex.databinding.ActivityCountryListBinding
import com.socksrelay.vertex.freeproxy.CountryListAdapter
import com.socksrelay.vertex.freeproxy.FreeProxyRepository

/**
 * Screen 1 of the free-proxy browser: a list of countries (flag + name +
 * how many proxies are available there), sourced from
 * [FreeProxyRepository]. Tapping a country opens [CountryProxiesActivity].
 */
class CountryListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCountryListBinding
    private lateinit var adapter: CountryListAdapter
    private val listener = { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCountryListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.free_proxy_title)

        // Preloaded here (rather than only at app startup) since this is
        // requested more often as the user browses between countries.
        AdManager.preloadInterstitial(this, AdIds.interstitialCountryView)

        adapter = CountryListAdapter(emptyList()) { group ->
            AdManager.showInterstitial(
                this, AdIds.interstitialCountryView,
                AdPlacements.KEY_COUNTRY_VIEW, AdPlacements.COUNTRY_VIEW_MIN_INTERVAL_MS
            ) {
                val intent = Intent(this, CountryProxiesActivity::class.java).apply {
                    putExtra(CountryProxiesActivity.EXTRA_COUNTRY_CODE, group.countryCode)
                    putExtra(CountryProxiesActivity.EXTRA_COUNTRY_NAME, group.countryName)
                }
                startActivity(intent)
            }
        }
        binding.countryRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.countryRecyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { FreeProxyRepository.refresh() }
        binding.refreshButton.setOnClickListener { FreeProxyRepository.refresh() }

        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        FreeProxyRepository.addListener(listener)
        refreshUi()
    }

    override fun onStop() {
        FreeProxyRepository.removeListener(listener)
        super.onStop()
    }

    private fun refreshUi() {
        val groups = FreeProxyRepository.groupedByCountry()
        adapter.submitList(groups)
        binding.swipeRefresh.isRefreshing = FreeProxyRepository.isRefreshing

        val total = FreeProxyRepository.proxies.size
        binding.lastUpdatedText.text = if (total > 0) {
            getString(R.string.free_proxy_last_updated, total, FreeProxyRepository.ageDescription())
        } else {
            ""
        }

        binding.emptyStateText.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyStateText.text = if (FreeProxyRepository.isRefreshing) {
            getString(R.string.free_proxy_loading)
        } else {
            getString(R.string.free_proxy_empty)
        }
    }
}
