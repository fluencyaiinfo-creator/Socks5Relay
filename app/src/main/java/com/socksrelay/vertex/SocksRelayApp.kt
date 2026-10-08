package com.socksrelay.vertex

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.socksrelay.vertex.ads.AdManager
import com.socksrelay.vertex.freeproxy.FreeProxyRepository

class SocksRelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the saved light/dark/system preference before any Activity
        // inflates — doing this later (e.g. in MainActivity.onCreate) can
        // cause a visible flash of the wrong theme first.
        AppCompatDelegate.setDefaultNightMode(SettingsStore.loadThemeMode(this))

        // Kick off the free-proxy list fetch immediately on app startup,
        // then keep it refreshing every 15 minutes for as long as the
        // process is alive (see FreeProxyRepository for the schedule/cache
        // details). Loads from the on-disk cache instantly if present, so
        // there's something to show right away even before the network
        // fetch completes.
        FreeProxyRepository.init(this)

        // Bottom banner on every screen: any Activity whose layout includes
        // layout/footer.xml gets it automatically — no per-screen code.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = AdManager.attachBanner(activity)
            override fun onActivityResumed(activity: Activity) = AdManager.resumeBanner(activity)
            override fun onActivityPaused(activity: Activity) = AdManager.pauseBanner(activity)
            override fun onActivityDestroyed(activity: Activity) = AdManager.detachBanner(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }
}
