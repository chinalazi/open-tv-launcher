package com.openlauncher.tv.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.openlauncher.tv.BuildConfig
import com.openlauncher.tv.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        binding.tvVersion.text = buildString {
            append("OpenTV Launcher ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            append("\nAndroid ${Build.VERSION.RELEASE} · 仅联网获取每日壁纸，无其他网络请求")
            append("\n开源协议：MIT / Apache-2.0（AndroidX）")
        }

        // 遥控器进来就能直接按方向键，不用先"点亮"某个按钮
        binding.btnWifi.post { binding.btnWifi.requestFocus() }
    }

    private fun setupClickListeners() {
        binding.btnWifi.setOnClickListener { openSystem(SystemSettingsLauncher.Target.WIFI) }
        binding.btnBluetooth.setOnClickListener { openSystem(SystemSettingsLauncher.Target.BLUETOOTH) }
        binding.btnApps.setOnClickListener { openSystem(SystemSettingsLauncher.Target.APPS) }
        binding.btnDisplay.setOnClickListener { openSystem(SystemSettingsLauncher.Target.DISPLAY) }
        binding.btnSound.setOnClickListener { openSystem(SystemSettingsLauncher.Target.SOUND) }
        binding.btnHome.setOnClickListener { openSystem(SystemSettingsLauncher.Target.HOME) }

        binding.btnCategories.setOnClickListener {
            startActivity(Intent(this, CategoryManagerActivity::class.java))
        }
    }

    /**
     * 打开系统页面 + 1.2 秒看门狗。
     *
     * 小米电视有几个设置页会「拉起来了但不绘制」，以前用户就一直盯着黑屏等。
     * 现在如果 1.2 秒后本页还持有窗口焦点，说明目标页没出来，立刻给出提示而不是继续干等。
     */
    private fun openSystem(target: SystemSettingsLauncher.Target) {
        val started = SystemSettingsLauncher.open(this, target)
        if (!started) {
            Toast.makeText(this, "这台电视没有提供该设置入口", Toast.LENGTH_SHORT).show()
            return
        }
        binding.root.postDelayed({
            if (!isFinishing && hasWindowFocus()) {
                Toast.makeText(
                    this,
                    "系统页面没有响应（该机型固件限制），请换个入口试试",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, 1200L)
    }
}
