package com.socksrelay.vertex

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.socksrelay.vertex.databinding.ActivityAppListBinding

/**
 * Lets the user pick which apps should use the VPN tunnel when split
 * tunneling is on (see [SplitTunnelStore] / `SocksVpnService.applySplitTunneling`).
 * This app itself is deliberately not shown here — it's always
 * automatically included (see the doc comment on `applySplitTunneling`
 * for why that's required, not optional).
 */
class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.split_tunnel_choose_apps)
        binding.appRecyclerView.layoutManager = LinearLayoutManager(this)

        Thread({
            val apps = loadLaunchableApps()
            runOnUiThread {
                binding.loadingSpinner.visibility = View.GONE
                bindList(apps)
            }
        }, "AppListLoadThread").start()
    }

    private fun bindList(apps: List<InstalledAppInfo>) {
        val selected = SplitTunnelStore.selectedPackages(this).toMutableSet()
        val adapter = AppPickerAdapter(
            apps = apps,
            isSelected = { pkg -> selected.contains(pkg) },
            onToggle = { app, nowChecked ->
                if (nowChecked) selected.add(app.packageName) else selected.remove(app.packageName)
                SplitTunnelStore.setSelectedPackages(this, selected)
            }
        )
        binding.appRecyclerView.adapter = adapter
    }

    private fun loadLaunchableApps(): List<InstalledAppInfo> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        return resolved
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName } // don't show ourselves — always auto-included, see class doc
            .mapNotNull { pkg ->
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    InstalledAppInfo(
                        packageName = pkg,
                        label = pm.getApplicationLabel(appInfo).toString(),
                        icon = pm.getApplicationIcon(appInfo)
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }
            .sortedBy { it.label.lowercase() }
    }
}
