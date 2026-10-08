package com.socksrelay.vertex

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.socksrelay.vertex.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.settings_title)

        binding.themeRow.setOnClickListener { showThemePicker() }
        binding.languageRow.setOnClickListener { showLanguagePicker() }

        binding.splitTunnelSwitch.isChecked = SplitTunnelStore.isEnabled(this)
        binding.splitTunnelSwitch.setOnCheckedChangeListener { _, checked ->
            SplitTunnelStore.setEnabled(this, checked)
            refreshSplitTunnelUi()
        }
        binding.chooseAppsRow.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        binding.killSwitchSwitch.isChecked = SettingsStore.isKillSwitchEnabled(this)
        binding.killSwitchSwitch.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setKillSwitchEnabled(this, checked)
        }
        binding.autoReconnectSwitch.isChecked = SettingsStore.isAutoReconnectEnabled(this)
        binding.autoReconnectSwitch.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setAutoReconnectEnabled(this, checked)
        }
        binding.systemVpnSettingsRow.setOnClickListener { openSystemVpnSettings() }

        binding.appVersionValueText.text = BuildConfig.VERSION_NAME

        binding.contactUsRow.setOnClickListener {
            openDocument(getString(R.string.settings_contact_us), "contact_us.md")
        }
        binding.privacyPolicyRow.setOnClickListener {
            openDocument(getString(R.string.settings_privacy_policy), "privacy_policy.md")
        }
        binding.termsRow.setOnClickListener {
            openDocument(getString(R.string.settings_terms_of_service), "terms_of_service.md")
        }
        binding.aboutUsRow.setOnClickListener {
            openDocument(getString(R.string.settings_about_us), "about_us.md")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshThemeValueText()
        refreshLanguageValueText()
        refreshSplitTunnelUi()
    }

    private fun openDocument(title: String, assetFileName: String) {
        val intent = Intent(this, DocumentViewerActivity::class.java).apply {
            putExtra(DocumentViewerActivity.EXTRA_TITLE, title)
            putExtra(DocumentViewerActivity.EXTRA_ASSET_FILE_NAME, assetFileName)
        }
        startActivity(intent)
    }

    // ---- Theme ----

    private fun refreshThemeValueText() {
        val mode = SettingsStore.loadThemeMode(this)
        binding.themeValueText.text = getString(
            when (mode) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.string.theme_light
                AppCompatDelegate.MODE_NIGHT_YES -> R.string.theme_dark
                else -> R.string.theme_system
            }
        )
    }

    private fun showThemePicker() {
        val options = arrayOf(
            getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)
        )
        val modes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES
        )
        val current = SettingsStore.loadThemeMode(this)
        val checkedIndex = modes.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.action_theme)
            .setSingleChoiceItems(options, checkedIndex) { dialog, which ->
                SettingsStore.saveThemeMode(this, modes[which])
                AppCompatDelegate.setDefaultNightMode(modes[which])
                refreshThemeValueText()
                dialog.dismiss()
            }
            .show()
    }

    // ---- Language ----

    private fun refreshLanguageValueText() {
        val code = LanguageManager.currentLanguageCode()
        binding.languageValueText.text = if (code == "system") {
            getString(R.string.theme_system)
        } else {
            LanguageManager.SUPPORTED_LANGUAGES.firstOrNull { it.code == code }?.displayName ?: code
        }
    }

    private fun showLanguagePicker() {
        val labels = listOf(getString(R.string.theme_system)) + LanguageManager.SUPPORTED_LANGUAGES.map { it.displayName }
        val codes = listOf("system") + LanguageManager.SUPPORTED_LANGUAGES.map { it.code }
        val current = LanguageManager.currentLanguageCode()
        val checkedIndex = codes.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dialog, which ->
                LanguageManager.setLanguage(codes[which]) // triggers an Activity recreate automatically
                dialog.dismiss()
            }
            .show()
    }

    // ---- System-level kill switch ----

    /**
     * This app's own Kill Switch (see [SettingsStore.isKillSwitchEnabled])
     * only works while the app/service is alive. Android has a stronger,
     * OS-enforced version of the same idea — "Always-on VPN" + "Block
     * connections without VPN" — but it lives in system Settings and can
     * only be turned on by the user themselves, not by any app
     * programmatically. This just deep-links to that screen.
     */
    private fun openSystemVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Open Settings > Network & internet > VPN, tap the gear next to ${getString(R.string.app_name)}, " +
                    "and enable \"Block connections without VPN\".",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---- Split tunneling ----

    private fun refreshSplitTunnelUi() {
        val enabled = SplitTunnelStore.isEnabled(this)
        binding.splitTunnelSwitch.isChecked = enabled
        binding.chooseAppsRow.isEnabled = enabled
        binding.chooseAppsRow.alpha = if (enabled) 1.0f else 0.4f
        val count = SplitTunnelStore.selectedPackages(this).size
        binding.chooseAppsValueText.text = if (enabled) {
            resources.getQuantityString(R.plurals.split_tunnel_app_count, count, count)
        } else {
            ""
        }
    }
}
