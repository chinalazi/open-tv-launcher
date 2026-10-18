package com.openlauncher.tv.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.openlauncher.tv.BuildConfig
import com.openlauncher.tv.databinding.ActivitySettingsBinding
import com.openlauncher.tv.ui.parental.ParentalSettingsActivity

class SettingsActivity : FragmentActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        binding.tvVersion.text = buildString {
            append("OpenTV Launcher ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            append("\nAndroid ${Build.VERSION.RELEASE} · 完全离线运行，无网络权限")
            append("\n开源协议：MIT / Apache-2.0（AndroidX）")
        }
    }

    private fun setupClickListeners() {
        binding.btnSystemSettings.setOnClickListener { launchSettingSafe(Settings.ACTION_SETTINGS) }
        binding.btnWifi.setOnClickListener { launchSettingSafe(Settings.ACTION_WIFI_SETTINGS) }
        binding.btnBluetooth.setOnClickListener { launchSettingSafe(Settings.ACTION_BLUETOOTH_SETTINGS) }
        binding.btnApps.setOnClickListener { launchSettingSafe(Settings.ACTION_APPLICATION_SETTINGS) }
        binding.btnDisplay.setOnClickListener { launchSettingSafe(Settings.ACTION_DISPLAY_SETTINGS) }
        binding.btnSound.setOnClickListener { launchSettingSafe(Settings.ACTION_SOUND_SETTINGS) }
        binding.btnHome.setOnClickListener { launchSettingSafe(Settings.ACTION_HOME_SETTINGS) }

        binding.btnParental.setOnClickListener {
            startActivity(Intent(this, ParentalSettingsActivity::class.java))
        }
        binding.btnCategories.setOnClickListener {
            startActivity(Intent(this, CategoryManagerActivity::class.java))
        }
    }

    private fun launchSettingSafe(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            Toast.makeText(this, "该电视系统暂不支持快速唤起该选项", Toast.LENGTH_SHORT).show()
        }
    }
}
