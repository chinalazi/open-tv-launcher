package com.openlauncher.tv.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.BuildConfig
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.ui.common.AmbientBackdropView
import com.openlauncher.tv.ui.parental.ParentalSettingsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/** 设置：左侧导航 + 右侧信息卡列表，信息密度比一排按钮高得多。 */
class SettingsActivity : FragmentActivity() {

    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    private lateinit var nav: LinearLayout
    private lateinit var panel: LinearLayout

    private var kidsValueView: TextView? = null
    private var kidsDescView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<AmbientBackdropView>(R.id.ambient_backdrop)
            .setGlowColor(0xFF2A4B8C.toInt(), false)

        nav = findViewById(R.id.nav_container)
        panel = findViewById(R.id.panel_container)

        buildNav()
        buildPanel()
        observeParentalState()

        nav.getChildAt(0)?.requestFocus()
    }

    // ---- 左侧导航 ---------------------------------------------------------------

    private fun buildNav() {
        addNav(R.drawable.ic_settings, "系统设置", active = true) {
            openSystem(SystemSettingsLauncher.Target.ROOT)
        }
        addNav(R.drawable.ic_grid, "桌面与分类") {
            startActivity(Intent(this, CategoryManagerActivity::class.java))
        }
        addNav(R.drawable.ic_shield, "家长控制") {
            startActivity(Intent(this, ParentalSettingsActivity::class.java))
        }
    }

    private fun addNav(iconRes: Int, title: String, active: Boolean = false, onClick: () -> Unit) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_nav_selector)
            isFocusable = true
            isFocusableInTouchMode = true
            isSelected = active
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { onClick() }
        }

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            alpha = 0.85f
        }
        item.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(12) })

        val label = TextView(this).apply {
            text = title
            textSize = 13.5f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
        }
        item.addView(label, LinearLayout.LayoutParams(WC, WC))

        nav.addView(item, LinearLayout.LayoutParams(MP, WC).apply {
            bottomMargin = dp(4)
        })
    }

    // ---- 右侧信息卡 -------------------------------------------------------------

    private fun buildPanel() {
        addGroup("系统入口")
        addRow(
            R.drawable.ic_wifi, "Wi-Fi 网络",
            "无线网络连接", wifiLabel()
        ) { openSystem(SystemSettingsLauncher.Target.WIFI) }
        addRow(
            R.drawable.ic_bluetooth, "外设与蓝牙",
            "遥控器、手柄、音箱配对", null
        ) { openSystem(SystemSettingsLauncher.Target.BLUETOOTH) }
        addRow(
            R.drawable.ic_display, "图像与声音",
            "画面模式、音量、音效", null
        ) { openSystem(SystemSettingsLauncher.Target.DISPLAY) }
        addRow(
            R.drawable.ic_apps, "应用管理",
            "查看、卸载已安装应用", null
        ) { openSystem(SystemSettingsLauncher.Target.APPS) }
        addRow(
            R.drawable.ic_home, "默认桌面",
            "把 OpenTV 设为电视的默认桌面", null
        ) { openSystem(SystemSettingsLauncher.Target.HOME) }

        addGroup("家长控制")
        val kidsRow = addRow(
            R.drawable.ic_shield, "儿童模式与防沉迷",
            "单次与每日时长、白名单、PIN 码", null
        ) { startActivity(Intent(this, ParentalSettingsActivity::class.java)) }
        kidsValueView = kidsRow.findViewById(R.id.row_value)
        kidsDescView = kidsRow.findViewById(R.id.row_desc)

        addRow(
            R.drawable.ic_grid, "分类与应用管理",
            "整理分类、恢复隐藏的应用", null
        ) { startActivity(Intent(this, CategoryManagerActivity::class.java)) }

        addGroup("关于")
        addRow(
            R.drawable.ic_info, "OpenTV Launcher",
            "Android ${Build.VERSION.RELEASE} · 完全离线，无网络权限",
            BuildConfig.VERSION_NAME,
            clickable = false
        )
    }

    private fun addGroup(title: String) {
        val view = TextView(this).apply {
            text = title
            textSize = 11f
            letterSpacing = 0.26f
            setTextColor(0x66FFFFFF)
            setPadding(dp(4), dp(10), 0, dp(10))
        }
        panel.addView(view, LinearLayout.LayoutParams(MP, WC))
    }

    private fun addRow(
        iconRes: Int,
        title: String,
        desc: String,
        value: String?,
        clickable: Boolean = true,
        onClick: (() -> Unit)? = null
    ): View {
        val row = layoutInflater.inflate(R.layout.item_settings_row, panel, false)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.row_title).text = title
        row.findViewById<TextView>(R.id.row_desc).text = desc

        val valueView = row.findViewById<TextView>(R.id.row_value)
        if (value.isNullOrEmpty()) {
            valueView.visibility = View.GONE
        } else {
            valueView.text = value
            valueView.visibility = View.VISIBLE
        }

        if (!clickable || onClick == null) {
            row.isClickable = false
            row.isFocusable = false
            row.background = null
            row.findViewById<TextView>(R.id.row_chev).visibility = View.GONE
        } else {
            row.setOnClickListener { onClick() }
            row.setOnFocusChangeListener { view, focused ->
                view.animate()
                    .translationX(if (focused) dp(2).toFloat() else 0f)
                    .setDuration(140L)
                    .start()
            }
        }
        panel.addView(row)
        return row
    }

    private fun observeParentalState() {
        lifecycleScope.launch {
            launcherApp.parental.state.collectLatest { state ->
                kidsValueView?.text = if (state.kidsMode) "已开启" else "已关闭"
                kidsDescView?.text = buildString {
                    append("单次 ${state.singleLimitMin} 分钟 · 每日 ${state.dailyLimitMin} 分钟")
                    if (state.kidsMode) {
                        append(" · 剩余 ")
                        append(String.format(Locale.US, "%d:%02d", state.remainingSec / 60, state.remainingSec % 60))
                    }
                }
            }
        }
    }

    // ---- 系统设置入口 -----------------------------------------------------------

    private fun wifiLabel(): String = try {
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        val ssid = wm?.connectionInfo?.ssid?.trim('"').orEmpty()
        if (ssid.isEmpty() || ssid == "<unknown>") "" else ssid
    } catch (e: Exception) {
        ""
    }

    private fun openSystem(target: SystemSettingsLauncher.Target) {
        if (SystemSettingsLauncher.open(this, target)) return
        val hint = if (target == SystemSettingsLauncher.Target.ROOT) {
            "这台电视不允许第三方应用打开设置主页，请用上面的分类入口"
        } else {
            "这台电视没有提供该设置入口"
        }
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

}
