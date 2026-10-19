package com.openlauncher.tv.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
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
        binding.btnSystemSettings.setOnClickListener { openSystem(SystemSettingsLauncher.Target.ROOT) }
        binding.btnWifi.setOnClickListener { openSystem(SystemSettingsLauncher.Target.WIFI) }
        binding.btnBluetooth.setOnClickListener { openSystem(SystemSettingsLauncher.Target.BLUETOOTH) }
        binding.btnApps.setOnClickListener { openSystem(SystemSettingsLauncher.Target.APPS) }
        binding.btnDisplay.setOnClickListener { openSystem(SystemSettingsLauncher.Target.DISPLAY) }
        binding.btnSound.setOnClickListener { openSystem(SystemSettingsLauncher.Target.SOUND) }
        binding.btnHome.setOnClickListener { openSystem(SystemSettingsLauncher.Target.HOME) }

        binding.btnParental.setOnClickListener {
            startActivity(Intent(this, ParentalSettingsActivity::class.java))
        }
        binding.btnCategories.setOnClickListener {
            startActivity(Intent(this, CategoryManagerActivity::class.java))
        }
    }

    private fun openSystem(target: SystemSettingsLauncher.Target) {
        if (!SystemSettingsLauncher.open(this, target)) {
            Toast.makeText(this, "这台电视没有提供该设置入口", Toast.LENGTH_SHORT).show()
        }
    }
}
