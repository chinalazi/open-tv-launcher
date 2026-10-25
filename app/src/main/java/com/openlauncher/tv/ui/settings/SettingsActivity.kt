package com.openlauncher.tv.ui.settings

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.BuildConfig
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.databinding.ActivitySettingsBinding
import com.openlauncher.tv.databinding.ViewSettingsRowBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 极简设置：一列行，点进去是系统对应的细分页。
 *
 * 为什么不用系统那个设置主页：`com.xiaomi.mitv.settings/.entry.MainActivity`
 * 自己在主线程每 500ms 轮询 `getService()`，实测十几秒才绘制；
 * 这里每一行都直达细分页面（Wi-Fi 420ms、账号 467ms），入口感受完全不同。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var firstRow: View? = null

    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvSettingsSub.text = "${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        binding.tvVersion.text =
            "OpenTV Launcher ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · MIT"

        buildRows()

        // 遥控器过来就能直接按方向键
        firstRow?.post { firstRow?.requestFocus() }
    }

    private fun buildRows() {
        addRow(R.drawable.ic_wifi, "Wi-Fi 与网络", networkText()) {
            openSystem(SystemSettingsLauncher.Target.WIFI)
        }
        addRow(R.drawable.ic_bt, "蓝牙与遥控器", bluetoothText()) {
            openSystem(SystemSettingsLauncher.Target.BLUETOOTH)
        }
        addRow(R.drawable.ic_display, "画面与声音", "") {
            openSystem(SystemSettingsLauncher.Target.DISPLAY)
        }
        val appsValue = addRow(R.drawable.ic_apps, "应用管理", "…") {
            openSystem(SystemSettingsLauncher.Target.APPS)
        }
        addRow(R.drawable.ic_desktop, "桌面与分类", "") {
            startActivity(Intent(this, CategoryManagerActivity::class.java))
        }
        addRow(R.drawable.ic_shield, "账号与安全", "") {
            openSystem(SystemSettingsLauncher.Target.ACCOUNT)
        }
        addRow(R.drawable.ic_info, "关于本机", Build.MODEL) {
            openSystem(SystemSettingsLauncher.Target.ABOUT)
        }
        // 系统设置主页是这台机器上最慢的入口（十几秒才绘制），先把预期说清楚
        addRow(R.drawable.ic_gear, "系统设置", "") {
            Toast.makeText(this, "正在打开系统设置主页，可能要十几秒…", Toast.LENGTH_LONG).show()
            openSystem(SystemSettingsLauncher.Target.ROOT, watchdogMs = 14_000L)
        }

        lifecycleScope.launch {
            val count = runCatching {
                launcherApp.database.launcherDao().getAllVisibleAppsFlow().first().size
            }.getOrDefault(0)
            appsValue.text = "$count 个应用"
        }
    }

    private fun addRow(
        @DrawableRes iconRes: Int,
        title: String,
        value: String,
        onClick: () -> Unit
    ): TextView {
        val row = ViewSettingsRowBinding.inflate(layoutInflater, binding.settingsList, false)
        row.rowIcon.setImageResource(iconRes)
        row.rowTitle.text = title
        row.rowValue.text = value
        row.rowBody.setOnClickListener { onClick() }
        binding.settingsList.addView(row.root)
        if (firstRow == null) firstRow = row.rowBody
        return row.rowValue
    }

    private fun networkText(): String = runCatching {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = cm.activeNetworkInfo
        if (info != null && info.isConnected) {
            if (info.type == ConnectivityManager.TYPE_WIFI) "Wi-Fi 已连接" else "已连接"
        } else {
            "未连接"
        }
    }.getOrDefault("—")

    private fun bluetoothText(): String = runCatching {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        when {
            adapter == null -> "不支持"
            adapter.isEnabled -> "已开启"
            else -> "已关闭"
        }
    }.getOrDefault("—")

    /**
     * 打开系统页面 + 看门狗：小米电视有些设置页会「拉起来了但不绘制」，
     * 超时后如果本页还持有窗口焦点，就直接告诉用户，别让他干等。
     */
    private fun openSystem(target: SystemSettingsLauncher.Target, watchdogMs: Long = 1200L) {
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
        }, watchdogMs)
    }
}
