package com.socksrelay.vertex.ads

/** Placement keys/intervals for [AdFrequencyGuard] — tune these once you have real usage data. */
object AdPlacements {
    const val KEY_COUNTRY_VIEW = "interstitial_country_view"
    const val KEY_PRE_CONNECT = "interstitial_pre_connect"
    const val KEY_ENGAGEMENT = "interstitial_engagement"
    const val KEY_PROXY_TEST = "interstitial_proxy_test"

    /** Don't show the "you tapped into a country" interstitial more than once every 3 minutes of browsing. */
    const val COUNTRY_VIEW_MIN_INTERVAL_MS = 3 * 60 * 1000L

    /** Pre-connect interstitial: once every 10 minutes — this one's tied to a meaningful action, not casual browsing. */
    const val PRE_CONNECT_MIN_INTERVAL_MS = 10 * 60 * 1000L

    /** General "it's been a while" engagement interstitial, checked when MainActivity comes to the foreground. */
    const val ENGAGEMENT_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * Extra safety net alongside [ProxyTestAdCounter]'s 1st/every-3rd
     * count-based cadence — without this, someone rapidly tapping "test"
     * on several proxies in a row could still hit two ads within seconds
     * of each other right at a "count of 3" boundary.
     */
    const val PROXY_TEST_MIN_INTERVAL_MS = 2 * 60 * 1000L
}
