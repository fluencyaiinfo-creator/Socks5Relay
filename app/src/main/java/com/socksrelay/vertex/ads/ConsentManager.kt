package com.socksrelay.vertex.ads

import android.app.Activity
import com.socksrelay.vertex.log.AppLog
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Wraps Google's User Messaging Platform (UMP) SDK — the consent form
 * required before loading ads for users in the EEA/UK (and increasingly
 * elsewhere). AdMob will not serve ads at all in those regions without
 * this, and Google can suspend accounts that skip it, so this runs before
 * [AdManager] initializes anything else.
 *
 * For users outside regions requiring consent, this resolves immediately
 * with nothing shown — same code path either way, no manual region checks
 * needed here.
 */
object ConsentManager {
    private const val TAG = "Ads"

    /** [onReady] is called once consent is resolved (obtained, not required, or failed) — ad loading should wait for this. */
    fun requestConsent(activity: Activity, onReady: () -> Unit) {
        val params = ConsentRequestParameters.Builder().build()
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                if (consentInformation.isConsentFormAvailable) {
                    loadAndShowForm(activity, onReady)
                } else {
                    onReady()
                }
            },
            { error ->
                AppLog.i(TAG, "Ad failed to load")
                onReady()
            }
        )
    }

    private fun loadAndShowForm(activity: Activity, onReady: () -> Unit) {
        UserMessagingPlatform.loadConsentForm(
            activity,
            { form ->
                val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
                if (consentInformation.consentStatus == ConsentInformation.ConsentStatus.REQUIRED) {
                    form.show(activity) { onReady() }
                } else {
                    onReady()
                }
            },
            { error ->
                AppLog.i(TAG, "Ad failed to load")
                onReady()
            }
        )
    }

    fun canRequestAds(activity: Activity): Boolean {
        return UserMessagingPlatform.getConsentInformation(activity).canRequestAds()
    }
}
