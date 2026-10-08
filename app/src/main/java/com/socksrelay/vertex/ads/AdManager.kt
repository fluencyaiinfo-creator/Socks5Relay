package com.socksrelay.vertex.ads

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.socksrelay.vertex.ConnectionState
import com.socksrelay.vertex.R
import com.socksrelay.vertex.log.AppLog
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Central place for loading/showing ads. The guiding rule throughout:
 * **an ad being unavailable, slow, or failing must never block the actual
 * feature it's attached to** — e.g. tapping Connect always eventually
 * connects, whether or not the pre-connect interstitial had anything to
 * show. Every "show" function here takes a callback that fires exactly
 * once, whether the ad played, failed, or was skipped by the frequency
 * cap — callers just proceed in that callback and never need to know
 * which case happened.
 *
 * A second, equally important rule: **no ad network call of any kind
 * (init, load, or show) is ever allowed to happen while the VPN tunnel is
 * up.** This app's whole point is routing traffic through a proxy the
 * user supplied — often a free/public one. If AdMob's own SDK requests
 * went out through that same proxy, they'd be indistinguishable from the
 * exact "invalid traffic" pattern (ad requests from datacenter/rotating/
 * proxy IPs) that gets AdMob accounts suspended. [isSafeToRequestAds]
 * (backed by [ConnectionState], the single source of truth for VPN state)
 * is checked at the top of every public entry point below, so this holds
 * for every placement automatically — including any added later — rather
 * than relying on each call site remembering to check it individually.
 */
object AdManager {
    // Deliberately generic: nothing the ad code logs may reveal the ad network,
    // SDK, or any unit/publisher ID — see also AppLog's scrubber.
    private const val TAG = "Ads"

    @Volatile private var initialized = false
    private val interstitials = mutableMapOf<String, InterstitialAd?>()

    /** True only when the VPN tunnel is fully down — the only time it's safe to touch the network for ads. */
    private fun isSafeToRequestAds(): Boolean = !ConnectionState.isConnected

    // Re-runs whatever ad warm-up would normally happen at this point, the
    // moment the VPN goes back down — so being connected doesn't leave ads
    // stuck un-preloaded indefinitely once the user disconnects.
    private var pendingInitContext: Context? = null
    private val reconnectListener: (ConnectionState.Status) -> Unit = { status ->
        // Hide banners while the tunnel is up, bring them back when it drops.
        updateBanners()
        if (status == ConnectionState.Status.DISCONNECTED) {
            pendingInitContext?.let { ctx ->
                pendingInitContext = null
                initializeIfNeeded(ctx)
            }
        }
    }

    init {
        ConnectionState.addListener(reconnectListener)
    }

    fun initializeIfNeeded(context: Context) {
        if (initialized) return
        if (!isSafeToRequestAds()) {
            AppLog.i(TAG, "Ads paused while VPN is connected")
            pendingInitContext = context.applicationContext
            return
        }
        initialized = true
        MobileAds.initialize(context.applicationContext) {
            updateBanners()
            // Warm the cache for the two interstitials used at fixed trigger
            // points; the country-view one is preloaded from CountryListActivity
            // instead, since it's requested more often as the user browses.
            preloadInterstitial(context, AdIds.interstitialPreConnect)
            preloadInterstitial(context, AdIds.interstitialEngagement)
        }
    }

    fun preloadInterstitial(context: Context, adUnitId: String) {
        if (!isSafeToRequestAds()) {
            AppLog.i(TAG, "Ads paused while VPN is connected")
            return
        }
        val request = AdRequest.Builder().build()
        InterstitialAd.load(context.applicationContext, adUnitId, request, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitials[adUnitId] = ad
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitials[adUnitId] = null
                AppLog.i(TAG, "Ad failed to load")
            }
        })
    }

    /**
     * Shows the interstitial for [adUnitId] if: the VPN is disconnected
     * (see the class doc comment), the frequency cap for [placementKey]
     * allows it, AND a preloaded ad is actually ready.
     * [onDone] always fires exactly once — proceed with whatever the ad
     * was blocking in there, regardless of which branch was taken.
     */
    fun showInterstitial(
        activity: Activity,
        adUnitId: String,
        placementKey: String,
        minIntervalMs: Long,
        onDone: () -> Unit
    ) {
        if (!isSafeToRequestAds()) {
            AppLog.i(TAG, "Ads paused while VPN is connected")
            onDone()
            return
        }
        if (!initialized || !AdFrequencyGuard.shouldShow(activity, placementKey, minIntervalMs)) {
            onDone()
            return
        }
        val ad = interstitials[adUnitId]
        if (ad == null) {
            preloadInterstitial(activity, adUnitId)
            onDone()
            return
        }

        var finished = false
        fun finishOnce() {
            if (finished) return
            finished = true
            interstitials[adUnitId] = null
            preloadInterstitial(activity, adUnitId)
            onDone()
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = finishOnce()
            override fun onAdFailedToShowFullScreenContent(adError: AdError) = finishOnce()
        }
        AdFrequencyGuard.recordShown(activity, placementKey)
        ad.show(activity)
    }

    /**
     * Loads a fresh rewarded ad on demand (rewarded ads are shown far less
     * often than interstitials, so preloading eagerly isn't worth it — load
     * when the user actually taps "watch ad"). [onLoaded] receives null if
     * the load failed OR was skipped because the VPN is connected (see the
     * class doc comment) — callers should disable/hide the unlock button in
     * either case rather than leave it silently non-functional. Use
     * [isSafeToRequestAds] beforehand if you want to show the user WHY
     * (e.g. "disconnect the VPN to unlock via ad") rather than a generic disabled state.
     */
    fun loadRewarded(context: Context, adUnitId: String, onLoaded: (RewardedAd?) -> Unit) {
        if (!isSafeToRequestAds()) {
            AppLog.i(TAG, "Ads paused while VPN is connected")
            onLoaded(null)
            return
        }
        val request = AdRequest.Builder().build()
        RewardedAd.load(context.applicationContext, adUnitId, request, object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) = onLoaded(ad)
            override fun onAdFailedToLoad(error: LoadAdError) {
                AppLog.i(TAG, "Ad failed to load")
                onLoaded(null)
            }
        })
    }

    /** Exposed so a screen can show a specific "disconnect the VPN first" message instead of a generic disabled button. */
    fun isBlockedByActiveVpn(): Boolean = ConnectionState.isConnected

    /** [onEarned] fires only if the user actually watched to completion; [onClosed] always fires after. */
    fun showRewarded(activity: Activity, ad: RewardedAd, onEarned: () -> Unit, onClosed: () -> Unit) {
        var finished = false
        fun finishOnce() {
            if (finished) return
            finished = true
            onClosed()
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = finishOnce()
            override fun onAdFailedToShowFullScreenContent(adError: AdError) = finishOnce()
        }
        ad.show(activity) { onEarned() }
    }

    // ---- Bottom banner ---------------------------------------------------
    //
    // Every screen includes layout/footer.xml, which contains an (initially
    // hidden) FrameLayout with id bannerAdContainer. SocksRelayApp's
    // lifecycle callbacks call attachBanner/detachBanner for every Activity
    // automatically, so a new screen only needs to <include> the footer to
    // get the banner. The same VPN rule as every other placement applies:
    // no banner request while the tunnel is up (the banner is removed when
    // it connects and reloaded after it disconnects).

    private class BannerSlot(val activity: Activity, val container: FrameLayout) {
        var adView: AdView? = null
    }

    private val banners = mutableListOf<BannerSlot>()

    fun attachBanner(activity: Activity) {
        val container = activity.findViewById<FrameLayout>(R.id.bannerAdContainer) ?: return
        if (container.getTag(R.id.bannerAdContainer) != null) {
            updateBanners()
            return
        }
        val slot = BannerSlot(activity, container)
        container.setTag(R.id.bannerAdContainer, slot)
        banners.add(slot)
        updateBanner(slot)
    }

    fun detachBanner(activity: Activity) {
        banners.filter { it.activity === activity }.forEach {
            destroyBanner(it)
            banners.remove(it)
        }
    }

    fun pauseBanner(activity: Activity) {
        banners.filter { it.activity === activity }.forEach { it.adView?.pause() }
    }

    fun resumeBanner(activity: Activity) {
        banners.filter { it.activity === activity }.forEach { it.adView?.resume() }
    }

    private fun updateBanners() {
        banners.toList().forEach { updateBanner(it) }
    }

    private fun updateBanner(slot: BannerSlot) {
        if (!AdIds.bannerConfigured || !initialized || !isSafeToRequestAds()) {
            destroyBanner(slot)
            return
        }
        if (slot.adView != null) return

        val metrics = slot.activity.resources.displayMetrics
        val widthDp = (metrics.widthPixels / metrics.density).toInt()
        val adView = AdView(slot.activity)
        adView.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(slot.activity, widthDp))
        adView.adUnitId = AdIds.banner
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                slot.container.visibility = View.VISIBLE
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                AppLog.i(TAG, "Ad failed to load")
                destroyBanner(slot) // frees the space; retried next time the screen starts or VPN state changes
            }
        }
        slot.container.addView(adView)
        slot.adView = adView
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun destroyBanner(slot: BannerSlot) {
        slot.adView?.destroy()
        slot.adView = null
        slot.container.removeAllViews()
        slot.container.visibility = View.GONE
    }
}
