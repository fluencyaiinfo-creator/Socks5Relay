package com.socksrelay.vertex.ads

import com.socksrelay.vertex.BuildConfig

/**
 * Real ad unit IDs are only ever used in release builds. Debug builds
 * always use Google's official sample test IDs instead — this is the
 * "single toggle" and it's automatic, not something you can accidentally
 * leave in the wrong state: it's tied to [BuildConfig.DEBUG], which
 * Android sets based on build type, not a flag anyone has to remember to
 * flip. Never request real ad unit IDs from a debug build or your own
 * device during development — repeated impressions/clicks from a known
 * dev device on real ad units is exactly the "invalid traffic" pattern
 * that gets AdMob accounts suspended.
 */
object AdIds {
    // ---- Real (release only) ----
    private const val REAL_REWARDED_PROXY_UNLOCK = "ca-app-pub-9452296761063041/3194410018"
    private const val REAL_INTERSTITIAL_COUNTRY_VIEW = "ca-app-pub-9452296761063041/1171665724"
    private const val REAL_INTERSTITIAL_PRE_CONNECT = "ca-app-pub-9452296761063041/4287999838"
    private const val REAL_INTERSTITIAL_ENGAGEMENT = "ca-app-pub-9452296761063041/1661836496"
    private const val REAL_INTERSTITIAL_PROXY_TEST = "ca-app-pub-9452296761063041/8141302718"

    private const val REAL_BANNER = "ca-app-pub-9452296761063041/5706857827"

    // ---- Google's official sample test IDs (safe to request unlimited times, always fill) ----
    private const val TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917"
    private const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"

    val rewardedProxyUnlock: String get() = if (BuildConfig.DEBUG) TEST_REWARDED else REAL_REWARDED_PROXY_UNLOCK
    val interstitialCountryView: String get() = if (BuildConfig.DEBUG) TEST_INTERSTITIAL else REAL_INTERSTITIAL_COUNTRY_VIEW
    val interstitialPreConnect: String get() = if (BuildConfig.DEBUG) TEST_INTERSTITIAL else REAL_INTERSTITIAL_PRE_CONNECT
    val interstitialEngagement: String get() = if (BuildConfig.DEBUG) TEST_INTERSTITIAL else REAL_INTERSTITIAL_ENGAGEMENT
    val interstitialProxyTest: String get() = if (BuildConfig.DEBUG) TEST_INTERSTITIAL else REAL_INTERSTITIAL_PROXY_TEST
    val banner: String get() = if (BuildConfig.DEBUG) TEST_BANNER else REAL_BANNER

    /** False until a real banner unit ID has been pasted into [REAL_BANNER] — the banner is skipped entirely until then. */
    val bannerConfigured: Boolean get() = banner.startsWith("ca-app-pub-")
}
