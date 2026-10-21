package com.openlauncher.tv.ui.home

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.local.AppItemEntity
import com.openlauncher.tv.data.local.CategoryEntity
import com.openlauncher.tv.databinding.ActivityMainBinding
import com.openlauncher.tv.ui.allapps.AllAppsActivity
import com.openlauncher.tv.ui.settings.SettingsActivity
import com.openlauncher.tv.ui.settings.SystemSettingsLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面：Apple TV 风格。
 *
 * 左列是每日壁纸画框 + 「常用应用」货架，右列是快捷入口（网络/信号源/账号）与内存、存储状态。
 * 常用应用只放第三方应用与固定入口，系统自带应用收进「更多」。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    private var categories: List<CategoryEntity> = emptyList()
    private var apps: List<AppItemEntity> = emptyList()

    private var lastSignature: String? = null
    private var focusedKey: String? = null
    private var wallpaperApplied = false

    private val systemCache = HashMap<String, Boolean>()
    private val statusHandler = Handler(Looper.getMainLooper())
    private var statusTicker: Runnable? = null

    private lateinit var wallpaper: WallpaperRepository

    private val dateFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
    private val weekFormat = SimpleDateFormat("EEEE", Locale.CHINA)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
    private var tickCount = 0

    private val callbacks = TileCallbacks(
        onFocused = { item -> onTileFocused(item) },
        onClicked = { item -> onTileClicked(item) },
        onLongClicked = { item -> onTileLongClicked(item) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rowsScroll.isVerticalScrollBarEnabled = false
        // clipToOutline 在 XML 里的属性要 API 31 才认，这里用代码设置，低版本也能裁圆角
        binding.wallpaperFrame.clipToOutline = true

        wallpaper = WallpaperRepository.get(this)

        setupQuickButtons()
        setupHomeActions()
        setupWallpaper()
        observeData()
    }

    override fun onResume() {
        super.onResume()
        if (currentFocus == null) {
            binding.rowsContainer.post { restoreFocus() }
        }
        TimeSync.loadCached(this)
        startStatusTicker()
        updateClock()
        // 电视的 NTP 对时不一定成功（这台机器就慢了 37 分钟），启动时自己校一次
        lifecycleScope.launch {
            if (TimeSync.needsSync(this@MainActivity)) {
                val ok = TimeSync.sync(this@MainActivity)
                if (!ok) Log.w("MainActivity", "网络校时失败，继续用系统时间")
                updateClock()
            }
        }
        // 从应用回到桌面：如果后台已经下好新壁纸，这时候再切换，用户看不到加载过程
        if (wallpaperApplied) {
            lifecycleScope.launch {
                val updated = wallpaper.onReturnToHome() ?: return@launch
                applyWallpaper(updated, animate = true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopStatusTicker()
    }

    // ---- 壁纸 -------------------------------------------------------------------

    private fun setupWallpaper() {
        binding.frameCaption.text = "正在准备今日壁纸…"
        lifecycleScope.launch {
            val loaded = wallpaper.prepareOnLaunch()
            applyWallpaper(loaded, animate = false)
            // 本地缓存已上屏，再在后台静默下载今天的新图（存 pending，下次回来才换）
            launch(Dispatchers.IO) {
                val fetched = wallpaper.fetchIntoPending()
                if (!fetched) return@launch
                // 首次安装没有任何缓存时，没得看就直接把新图放上来
                val adopted = wallpaper.adoptPendingIfNoCurrent() ?: return@launch
                withContext(Dispatchers.Main) { applyWallpaper(adopted, animate = false) }
            }
        }
        binding.btnWallpaperSettings.setOnClickListener { showWallpaperSettings() }
        binding.btnWallpaperSettings.setOnFocusChangeListener { view, focused ->
            showTip(view as View, "壁纸设置", placeLeftOf = true, focused = focused)
        }
        // 齿轮在画框里，跟货架隔着 ScrollView，默认焦点搜索找不到彼此，这里显式接通
        binding.btnWallpaperSettings.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    restoreFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    binding.btnNetwork.requestFocus()
                    true
                }
                else -> false
            }
        }
    }

    /** 画框右上角齿轮：弹出壁纸设置面板（种类 / 来源 / 接口地址 / 预览）。 */
    private fun showWallpaperSettings() {
        hideTip()
        WallpaperSettingsDialog(this, wallpaper) { loaded ->
            applyWallpaper(loaded, animate = true)
        }.show()
    }

    private fun applyWallpaper(loaded: WallpaperRepository.Loaded, animate: Boolean) {
        wallpaperApplied = true
        val frame = loaded.frame
        if (frame != null) {
            if (animate && binding.frameImage.drawable != null) {
                binding.frameImage.animate().alpha(0.35f).setDuration(160L).withEndAction {
                    binding.frameImage.setImageBitmap(frame)
                    binding.frameImage.animate().alpha(1f).setDuration(320L).start()
                }.start()
            } else {
                binding.frameImage.setImageBitmap(frame)
            }
        }
        binding.backdrop.setBitmap(loaded.backdrop)

        val meta = loaded.meta
        binding.frameCaption.text = when {
            meta == null -> "正在准备今日壁纸…"
            meta.date.isBlank() -> meta.title
            else -> "${meta.title} · ${meta.date}"
        }
    }

    // ---- 右侧快捷入口 -----------------------------------------------------------

    private fun setupQuickButtons() {
        binding.btnNetwork.setOnClickListener {
            openSystem(SystemSettingsLauncher.Target.WIFI, "没有找到网络设置入口")
        }
        binding.btnSource.setOnClickListener {
            openSystem(SystemSettingsLauncher.Target.SOURCE, "这台电视没有提供信号源切换入口")
        }
        binding.btnAccount.setOnClickListener {
            openSystem(SystemSettingsLauncher.Target.ACCOUNT, "这台电视没有提供账号设置入口")
        }
        binding.btnClean.setOnClickListener { cleanMemory() }
        binding.btnClean.nextFocusDownId = R.id.btn_robot_cfg

        // 图标只剩圆形轮廓，聚焦时浮出名称，不然用户分不清哪个是哪个
        listOf(
            binding.btnNetwork to "网络连接",
            binding.btnSource to "信号源",
            binding.btnAccount to "账号与安全"
        ).forEach { (view, label) ->
            view.setOnFocusChangeListener { v, focused ->
                showTip(v as View, label, placeLeftOf = false, focused = focused)
            }
        }

        // 右侧最左的两个按钮，按「左」回跳到左边的应用货架，避免焦点卡死在右列
        listOf<View>(binding.btnNetwork, binding.btnClean).forEach { view ->
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    restoreFocus()
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * 图标聚焦时在下方（齿轮在左侧）浮出名称气泡。
     * 气泡铺在根布局上而不是塞进按钮里，免得被 56dp 的按钮裁掉。
     */
    private fun showTip(anchor: View, text: String, placeLeftOf: Boolean, focused: Boolean) {
        val tip = binding.tvFocusTip
        if (!focused) {
            tip.visibility = View.GONE
            return
        }
        tip.text = text
        tip.visibility = View.VISIBLE
        tip.post {
            // 用「相对根布局」的坐标算，气泡自己的位置不参与运算，
            // 否则第二次显示时会把自己上一次的位置又减一遍，跑到左上角去
            val anchorLocation = IntArray(2)
            val rootLocation = IntArray(2)
            anchor.getLocationInWindow(anchorLocation)
            binding.root.getLocationInWindow(rootLocation)
            val anchorX = anchorLocation[0] - rootLocation[0]
            val anchorY = anchorLocation[1] - rootLocation[1]
            tip.x = if (placeLeftOf) {
                (anchorX - tip.width - dp(8)).toFloat()
            } else {
                anchorX + (anchor.width - tip.width) / 2f
            }
            tip.y = if (placeLeftOf) {
                anchorY + (anchor.height - tip.height) / 2f
            } else {
                (anchorY + anchor.height + dp(6)).toFloat()
            }
        }
    }

    private fun hideTip() {
        binding.tvFocusTip.visibility = View.GONE
    }

    /** 右下角只剩「更多」，设置入口收进「更多」里（桌面不再留设置磁贴）。 */
    private fun setupHomeActions() {
        val more = AppTileView(this, sizeDp = 80).apply {
            id = R.id.btn_home_more
            bindAction(R.drawable.ic_more, "更多", ACTION_COLOR, iconTint = 0xFF12B57F.toInt())
            setOnClickListener { startActivity(Intent(this@MainActivity, AllAppsActivity::class.java)) }
            nextFocusUpId = R.id.btn_robot_cfg
            focusChanged = { focused -> if (focused) binding.desktopPet.onRemoteActivity() }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    restoreFocus()
                    true
                } else {
                    false
                }
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        binding.homeActions.addView(more, lp)

        setupRobotConfig()
    }

    /** 右下角「更多」上面的机器人设置钮：点开环状动作菜单。 */
    private fun setupRobotConfig() {
        binding.btnRobotCfg.apply {
            nextFocusUpId = R.id.btn_clean
            nextFocusDownId = R.id.btn_home_more
            setOnClickListener { showRobotWheel() }
            setOnFocusChangeListener { v, focused ->
                // 位置贴着屏幕右下角，气泡放到左边，免得掉出屏幕
                showTip(v as View, "机器人设置", placeLeftOf = true, focused = focused)
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    restoreFocus()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun showRobotWheel() {
        hideTip()
        val dialog = RobotWheelDialog(this, binding.desktopPet.currentActionId())
        dialog.onPick = { id ->
            binding.desktopPet.setAction(id)
            val action = DesktopPetView.PetAction.fromId(id)
            toast("机器人动作：${action.label}")
        }
        // 弹窗期间把机器人停掉并隐藏：既省电，也免得两只从遮罩后面透出来挡住菜单文字
        binding.desktopPet.pauseAnimation()
        binding.desktopPet.alpha = 0f
        dialog.setOnDismissListener {
            binding.desktopPet.alpha = 1f
            binding.desktopPet.resumeAnimation()
        }
        dialog.show()
    }

    /**
     * 打开系统页面并做超时兜底：
     * 小米电视的某些设置页会「起了但不绘制」，用户只能干等。1.2 秒后如果焦点还在本页，就明确告诉他。
     */
    private fun openSystem(target: SystemSettingsLauncher.Target, failHint: String) {
        val started = SystemSettingsLauncher.open(this, target)
        if (!started) {
            toast(failHint)
            return
        }
        binding.root.postDelayed({
            if (!isFinishing && hasWindowFocus()) {
                toast("系统页面没有响应，可到「设置」里用分类入口")
            }
        }, 1200L)
    }

    private fun cleanMemory() {
        toast("正在清理后台…")
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { SystemStatus.cleanBackground(this@MainActivity) }
            refreshMemory()
            toast(if (freed > 8L * 1024 * 1024) "已释放 ${SystemStatus.formatSize(freed)}" else "已经是最清爽的状态了")
        }
    }

    private fun startStatusTicker() {
        refreshMemory()
        updateClock()
        stopStatusTicker()
        val ticker = object : Runnable {
            override fun run() {
                // 时钟按秒走（分钟跳变才真正 setText），内存每 5 秒查一次就够
                if (tickCount++ % 5 == 0) refreshMemory()
                updateClock()
                statusHandler.postDelayed(this, 1000L)
            }
        }
        statusTicker = ticker
        statusHandler.postDelayed(ticker, 1000L)
    }

    private fun stopStatusTicker() {
        statusTicker?.let { statusHandler.removeCallbacks(it) }
        statusTicker = null
    }

    private fun refreshMemory() {
        val ratio = SystemStatus.memoryRatio(this)
        binding.ringMemory.progress = ratio
        binding.tvMemoryFree.text = "可用 ${SystemStatus.formatSize(SystemStatus.memoryFreeBytes(this))}"
    }

    /**
     * 时钟、日期、开机时长。
     *
     * 时间不再用 TextClock 直接跟系统走：这台电视的系统时钟比真实时间慢 37 分钟（NTP 没生效），
     * 所以统一用 [TimeSync.now]（系统时间 + 网络校出来的偏移）。校时失败时偏移为 0，退化成系统时间。
     */
    private fun updateClock() {
        val nowMs = TimeSync.now()
        val now = Date(nowMs)

        val time = timeFormat.format(now)
        if (binding.clockTime.text != time) binding.clockTime.text = time

        val date = "${dateFormat.format(now)} · ${weekFormat.format(now)}"
        if (binding.clockDate.text != date) binding.clockDate.text = date

        // 开机时长：从开机算起（含待机），电视常年不断电，这个比"本次使用时长"更直观
        val uptime = "已开机 " + TimeSync.formatUptime(SystemClock.elapsedRealtime())
        if (binding.clockUptime.text != uptime) binding.clockUptime.text = uptime
    }

    // ---- 数据 -------------------------------------------------------------------

    private fun observeData() {
        val dao = launcherApp.database.launcherDao()
        lifecycleScope.launch {
            combine(
                dao.getAllCategoriesFlow(),
                dao.getAllVisibleAppsFlow()
            ) { categoryList, appList -> categoryList to appList }
                .collect { (categoryList, appList) -> render(categoryList, appList) }
        }
    }

    private fun render(categoryList: List<CategoryEntity>, appList: List<AppItemEntity>) {
        val signature = buildString {
            categoryList.forEach { append(it.id).append(':').append(it.name).append(',') }
            append('|')
            appList.forEach {
                append(it.id).append(':').append(it.categoryId).append(':')
                    .append(it.label).append(',')
            }
        }
        // 内容没变就不重建，避免无谓地打断焦点
        if (signature == lastSignature) return
        val firstRender = lastSignature == null
        lastSignature = signature
        categories = categoryList
        apps = appList

        binding.rowsContainer.removeAllViews()

        val grouped = appList.groupBy { it.categoryId }
        var isFirstRow = true
        for (category in categoryList) {
            val rowApps = grouped[category.id].orEmpty()
            val tiles = ArrayList<TileItem>(rowApps.size + 3)
            if (isFirstRow) {
                // 常用应用：只留第三方应用；设置/更多固定在右下角那两块磁贴上
                rowApps.filter { isThirdParty(it.packageName) }
                    .forEach { tiles.add(TileItem.App(it)) }
                if (tiles.isNotEmpty()) {
                    binding.rowsContainer.addView(
                        buildRow(
                            category.name,
                            tiles,
                            rightNeighborId = R.id.btn_home_more,
                            upNeighborId = R.id.btn_wallpaper_settings
                        ),
                        rowParams()
                    )
                }
                isFirstRow = false
            } else {
                if (rowApps.isEmpty()) continue
                rowApps.forEach { tiles.add(TileItem.App(it)) }
                binding.rowsContainer.addView(buildRow(category.name, tiles), rowParams())
            }
        }

        binding.rowsContainer.post {
            if (firstRender) {
                focusRow(0)
            } else {
                restoreFocus()
            }
        }
    }

    private fun buildRow(
        title: String,
        tiles: List<TileItem>,
        rightNeighborId: Int = View.NO_ID,
        upNeighborId: Int = View.NO_ID
    ): ShelfRowView =
        ShelfRowView(this, callbacks).apply {
            bind(title, tiles, rightNeighborId, upNeighborId)
        }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }

    /** 系统应用（预装）判为"非第三方"，收进「更多」。 */    private fun isThirdParty(packageName: String): Boolean = systemCache.getOrPut(packageName) {
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }.getOrDefault(true)
    }

    // ---- 焦点 -------------------------------------------------------------------

    private fun onTileFocused(item: TileItem) {
        binding.desktopPet.onRemoteActivity()
        focusedKey = keyOf(item)
    }

    private fun restoreFocus() {
        val key = focusedKey
        if (key != null) {
            for (index in 0 until binding.rowsContainer.childCount) {
                val row = binding.rowsContainer.getChildAt(index) as? ShelfRowView ?: continue
                val position = row.items.indexOfFirst { keyOf(it) == key }
                if (position >= 0) {
                    row.focusAt(position)
                    return
                }
            }
        }
        focusRow(0)
    }

    private fun focusRow(index: Int) {
        if (binding.rowsContainer.childCount == 0) return
        val safe = index.coerceIn(0, binding.rowsContainer.childCount - 1)
        (binding.rowsContainer.getChildAt(safe) as? ShelfRowView)?.focusFirst()
    }

    private fun keyOf(item: TileItem): String = when (item) {
        is TileItem.App -> "app:${item.entity.id}"
        is TileItem.Action -> "action:${item.id}"
    }

    // ---- 交互 -------------------------------------------------------------------

    private fun onTileClicked(item: TileItem) {
        binding.desktopPet.onUserAction()
        when (item) {
            is TileItem.App -> launchApp(item.entity)
            is TileItem.Action -> when (item.id) {
                ACTION_SETTINGS -> openSettings()
                ACTION_MORE -> startActivity(Intent(this, AllAppsActivity::class.java))
            }
        }
    }

    private fun launchApp(entity: AppItemEntity) {
        val intent = Intent().apply {
            component = ComponentName(entity.packageName, entity.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        if (runCatching { startActivity(intent) }.isFailure) {
            toast("无法启动「${entity.label}」")
        }
    }

    private fun onTileLongClicked(item: TileItem) {
        if (item !is TileItem.App) return
        val entity = item.entity
        AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle(entity.label)
            .setItems(arrayOf("移动到其他分类", "在桌面隐藏")) { _, which ->
                when (which) {
                    0 -> showMoveDialog(entity)
                    1 -> hideApp(entity)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMoveDialog(entity: AppItemEntity) {
        val targets = categories.filter { it.id != entity.categoryId }
        if (targets.isEmpty()) {
            toast("还没有其他分类，可到「设置 → 分类与应用管理」新建")
            return
        }
        AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("移动到")
            .setItems(targets.map { it.name }.toTypedArray()) { _, which ->
                val target = targets[which]
                lifecycleScope.launch {
                    launcherApp.database.launcherDao().setCategory(entity.id, target.id)
                    toast("已移动到「${target.name}」")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun hideApp(entity: AppItemEntity) {
        lifecycleScope.launch {
            launcherApp.database.launcherDao().setHidden(entity.id, true)
            toast("已隐藏「${entity.label}」，可在设置里恢复")
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            binding.desktopPet.onRemoteActivity()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("桌面根界面按返回键不退出")
    override fun onBackPressed() {
        // 作为默认桌面，Back 不退出
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val ACTION_SETTINGS = "settings"
        private const val ACTION_MORE = "more"
        private val ACTION_COLOR = 0xFFF2FAF6.toInt()
    }
}
