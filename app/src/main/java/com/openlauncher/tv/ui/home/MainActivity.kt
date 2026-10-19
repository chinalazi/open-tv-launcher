package com.openlauncher.tv.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.local.AppItemEntity
import com.openlauncher.tv.data.local.CategoryEntity
import com.openlauncher.tv.data.parental.ParentalState
import com.openlauncher.tv.service.TimeTrackingService
import com.openlauncher.tv.ui.common.AmbientBackdropView
import com.openlauncher.tv.ui.lock.LockScreenLauncher
import com.openlauncher.tv.ui.parental.ParentalSettingsActivity
import com.openlauncher.tv.ui.settings.SettingsActivity
import com.openlauncher.tv.ui.settings.SystemSettingsLauncher
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 桌面：顶栏（问候 / 时钟 / 状态胶囊）+ 居中的「常用应用」一栏。
 * 一栏最多 6 个位置，应用更多时第 6 个位置是「更多」，进入全部应用页。
 */
class MainActivity : FragmentActivity() {

    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    private lateinit var ambient: AmbientBackdropView
    private lateinit var greetingView: TextView
    private lateinit var clockView: TextView
    private lateinit var ampmView: TextView
    private lateinit var dateView: TextView
    private lateinit var heroView: TextView
    private lateinit var rowApps: LinearLayout
    private lateinit var pillKids: TextView
    private lateinit var pillWifi: TextView

    private var trackingServiceRunning = false

    private val ticker = Handler(Looper.getMainLooper())
    private val clockTask = object : Runnable {
        override fun run() {
            updateClock()
            ticker.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ambient = findViewById(R.id.ambient_backdrop)
        greetingView = findViewById(R.id.tv_greeting)
        clockView = findViewById(R.id.tv_clock)
        ampmView = findViewById(R.id.tv_ampm)
        dateView = findViewById(R.id.tv_date)
        heroView = findViewById(R.id.tv_hero)
        rowApps = findViewById(R.id.row_apps)
        pillKids = findViewById(R.id.pill_kids)
        pillWifi = findViewById(R.id.pill_wifi)

        pillKids.setOnClickListener {
            startActivity(Intent(this, ParentalSettingsActivity::class.java))
        }
        pillWifi.setOnClickListener {
            if (!SystemSettingsLauncher.open(this, SystemSettingsLauncher.Target.WIFI)) {
                toast("这台电视没有提供 Wi-Fi 设置入口")
            }
        }
        findViewById<TextView>(R.id.pill_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        pillWifi.text = wifiLabel()
        observeApps()
        observeParentalState()
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // 回到桌面时若仍处于锁定状态，立刻把锁屏盖回来（防止按 HOME 绕过）
        val state = launcherApp.parental.state.value
        if (state.kidsMode && state.locked) {
            LockScreenLauncher.show(this)
        }
        pillWifi.text = wifiLabel()
        updateClock()
        ticker.removeCallbacks(clockTask)
        ticker.post(clockTask)

        rowApps.post {
            val focused = currentFocus
            if (focused == null || focused.parent !== rowApps) {
                rowApps.getChildAt(0)?.requestFocus()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(clockTask)
    }

    // ---- 桌面渲染 ---------------------------------------------------------------

    private fun observeApps() {
        val dao = launcherApp.database.launcherDao()
        lifecycleScope.launch {
            combine(
                dao.getAllCategoriesFlow(),
                dao.getAllVisibleAppsFlow()
            ) { categories, apps -> categories to apps }
                .collectLatest { (categories, apps) -> renderHome(categories, apps) }
        }
    }

    private fun renderHome(categories: List<CategoryEntity>, apps: List<AppItemEntity>) {
        val focusedTag = (currentFocus?.takeIf { it.parent === rowApps }?.tag) as? String
        val homeApps = pickHomeApps(categories, apps)
        val showMore = homeApps.size > MAX_TILES
        val visible = if (showMore) homeApps.take(MAX_TILES - 1) else homeApps

        rowApps.removeAllViews()
        visible.forEach { rowApps.addView(createTile(it)) }
        if (showMore) rowApps.addView(createMoreTile())

        val restored = focusedTag != null && restoreFocus(focusedTag)
        if (!restored) {
            val first = visible.firstOrNull()
            heroView.text = first?.label ?: "暂无应用"
            if (first != null) {
                ambient.setGlowColor(AppPalette.colorOf(this, first.packageName), false)
            }
        }
    }

    /** 首页只放一个分类：优先「常用应用」，该分类为空时退回全部应用。 */
    private fun pickHomeApps(
        categories: List<CategoryEntity>,
        apps: List<AppItemEntity>
    ): List<AppItemEntity> {
        if (apps.isEmpty()) return emptyList()
        val grouped = apps.groupBy { it.categoryId }
        for (category in categories) {
            val items = grouped[category.id]
            if (!items.isNullOrEmpty()) return items
        }
        return apps
    }

    private fun createTile(item: AppItemEntity): View {
        val tile = AppTileView(this)
        val lp = LinearLayout.LayoutParams(dp(176), dp(114))
        lp.marginEnd = dp(12)
        tile.layoutParams = lp
        tile.tag = item.packageName
        tile.bindApp(item)
        tile.onTileFocus = { view, focused ->
            if (focused) {
                heroView.text = item.label
                ambient.setGlowColor(view.brandColor)
            }
        }
        tile.setOnClickListener { AppLauncher.launch(this, item) }
        tile.setOnLongClickListener {
            AppMenu.show(this, item) { AppLauncher.launch(this, item) }
            true
        }
        return tile
    }

    private fun createMoreTile(): View {
        val tile = AppTileView(this)
        val lp = LinearLayout.LayoutParams(dp(176), dp(114))
        tile.layoutParams = lp
        tile.tag = TAG_MORE
        tile.bindMore()
        tile.onTileFocus = { view, focused ->
            if (focused) {
                heroView.text = "全部应用"
                ambient.setGlowColor(view.brandColor)
            }
        }
        tile.setOnClickListener {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }
        return tile
    }

    private fun restoreFocus(tag: String): Boolean {
        for (i in 0 until rowApps.childCount) {
            val child = rowApps.getChildAt(i)
            if (child.tag == tag) return child.requestFocus()
        }
        return false
    }

    // ---- 顶栏 -------------------------------------------------------------------

    private fun updateClock() {
        val calendar = Calendar.getInstance()
        val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
        val hour12 = calendar.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        clockView.text = String.format(
            Locale.US, "%d:%02d", hour12, calendar.get(Calendar.MINUTE)
        )
        ampmView.text = if (hour24 < 12) "AM" else "PM"
        greetingView.text = when {
            hour24 < 5 -> "GOOD NIGHT"
            hour24 < 12 -> "GOOD MORNING"
            hour24 < 14 -> "GOOD NOON"
            hour24 < 18 -> "GOOD AFTERNOON"
            else -> "GOOD EVENING"
        }
        dateView.text = SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH)
            .format(Date())
            .uppercase(Locale.ENGLISH)
    }

    private fun wifiLabel(): String = try {
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val ssid = wm?.connectionInfo?.ssid?.trim('"').orEmpty()
        if (ssid.isEmpty() || ssid == "<unknown>") "Wi-Fi" else ssid
    } catch (e: Exception) {
        "Wi-Fi"
    }

    // ---- 防沉迷状态 -------------------------------------------------------------

    private fun observeParentalState() {
        lifecycleScope.launch {
            launcherApp.parental.state
                .map { Triple(it.kidsMode, it.locked, it.remainingSec) }
                .distinctUntilChanged()
                .collectLatest { applyState(launcherApp.parental.state.value) }
        }
    }

    private fun applyState(state: ParentalState) {
        updateKidsPill(state)
        updateTrackingService(state.kidsMode)
    }

    private fun updateKidsPill(state: ParentalState) {
        if (!state.kidsMode) {
            pillKids.visibility = View.GONE
            return
        }
        pillKids.visibility = View.VISIBLE
        val warning = state.remainingSec in 1..180
        pillKids.setTextColor(
            ContextCompat.getColor(
                this,
                if (warning) R.color.status_warn else R.color.text_primary
            )
        )
        pillKids.text = if (state.unlimitedToday) {
            "今日不限时"
        } else {
            "剩余 ${formatDuration(state.remainingSec)}"
        }
    }

    private fun formatDuration(seconds: Int): String =
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    private fun updateTrackingService(kidsMode: Boolean) {
        if (kidsMode == trackingServiceRunning) return
        trackingServiceRunning = kidsMode
        if (kidsMode) {
            TimeTrackingService.startService(this)
        } else {
            TimeTrackingService.stopService(this)
        }
    }

    // ---- 其他 -------------------------------------------------------------------

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001) }
        }
    }

    @Deprecated("桌面根界面按返回键不退出")
    override fun onBackPressed() {
        // 作为默认桌面，Back 不退出
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val MAX_TILES = 6
        const val TAG_MORE = "__more__"
    }
}
