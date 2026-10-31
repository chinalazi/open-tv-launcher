package com.openlauncher.tv.ui.home

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简桌面。
 *
 * 一屏只做三件事：显示时间、把应用摆出来、给三个入口（网络 / 全部应用 / 设置）。
 * 没有背景图、没有网络监视器、没有桌面宠物，也没有分类货架 ——
 * 那些都收进「设置」和「全部应用」里了。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    private var categories: List<CategoryEntity> = emptyList()
    private var items: List<TileItem> = emptyList()

    private var lastSignature: String? = null
    private var focusedPosition = 0

    private val systemCache = HashMap<String, Boolean>()
    private val statusHandler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private val dateFormat = SimpleDateFormat("M月d日", Locale.CHINA)
    private val weekFormat = SimpleDateFormat("EEEE", Locale.CHINA)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTopBar()
        observeData()
    }

    override fun onResume() {
        super.onResume()
        // 时间一律以系统时钟为准，不再联网校正；只有用户在设置里手动设过偏移才带上
        AppTime.load(this)
        startTicker()
        updateClock()
        if (binding.appGrid.findFocus() == null) {
            binding.appGrid.post { focusGrid(focusedPosition) }
        }
    }

    override fun onPause() {
        super.onPause()
        stopTicker()
    }

    // ---- 顶栏 -------------------------------------------------------------------

    private fun setupTopBar() {
        binding.btnNetwork.setOnClickListener {
            openSystem(SystemSettingsLauncher.Target.WIFI)
        }
        binding.btnAllApps.setOnClickListener {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 顶栏按「下」回宫格：RecyclerView 的焦点搜索不会自己往上接
        listOf<View>(binding.btnNetwork, binding.btnAllApps, binding.btnSettings).forEach { view ->
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    focusGrid(focusedPosition)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun openSystem(target: SystemSettingsLauncher.Target) {
        if (!SystemSettingsLauncher.open(this, target)) {
            toast("这台电视没有提供该设置入口")
        }
    }

    // ---- 宫格 -------------------------------------------------------------------

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
        val signature = appList.joinToString("|") { "${it.id}:${it.label}" }
        if (signature == lastSignature) return
        val first = lastSignature == null
        lastSignature = signature
        categories = categoryList

        // 第三方排在前面，顺序沿用数据库里的整理结果；一屏最多 12 个
        items = appList
            .sortedBy { if (isThirdParty(it.packageName)) 0 else 1 }
            .take(HOME_SLOTS)
            .map { TileItem.App(it) }

        // 应用少的时候别硬摆 6 列留一片空：按数量算列数与图标尺寸，让每行都尽量满
        val spec = gridSpec(items.size)
        binding.appGrid.columns = spec.columns
        binding.appGrid.cellWidth = dp(spec.itemWidthDp)
        binding.appGrid.rowGap = dp(9)
        binding.appGrid.removeAllViews()
        items.forEachIndexed { index, item ->
            binding.appGrid.addView(makeTile(item, index, spec))
        }
        val target = if (first) 0 else focusedPosition
        binding.appGrid.post { focusGrid(target) }
    }

    private fun makeTile(item: TileItem, index: Int, spec: GridSpec): AppTileView {
        val tile = AppTileView(this, sizeDp = spec.tileDp)
        tile.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        when (item) {
            is TileItem.App -> tile.bindApp(item.entity)
            is TileItem.Action -> tile.bindAction(item.iconRes, item.title)
        }
        tile.setOnClickListener { onTileClicked(item) }
        tile.setOnLongClickListener {
            onTileLongClicked(item)
            true
        }
        tile.focusChanged = { focused -> if (focused) focusedPosition = index }
        // 第一行按「上」出宫格、最后一行按「下」吃掉 —— 焦点搜索本来就出不了这个容器
        tile.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val lastRowStart = items.size - spec.columns
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP && index < spec.columns -> {
                    upNeighbor(index, spec.columns).requestFocus()
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN && index >= lastRowStart -> true
                else -> false
            }
        }
        return tile
    }

    /** 从宫格往上走，落到视觉上最近的那个圆钮。 */
    private fun upNeighbor(index: Int, columns: Int): View = when {
        index <= 0 -> binding.btnNetwork
        index >= columns - 1 -> binding.btnSettings
        else -> binding.btnAllApps
    }

    /** 列数 / 图标边长 / 格子宽度，按应用数量挑一档，避免一屏空格太多。 */
    private fun gridSpec(count: Int): GridSpec {
        if (count <= 0) return GridSpec(COLUMNS, 81, 138)
        val rows = if (count <= 6) 1 else 2
        val columns = Math.ceil(count / rows.toDouble()).toInt().coerceIn(1, 6)
        return when (columns) {
            1 -> GridSpec(1, 118, 190)
            2 -> GridSpec(2, 118, 190)
            3 -> GridSpec(3, 118, 190)
            4 -> GridSpec(4, 100, 170)
            5 -> GridSpec(5, 90, 150)
            else -> GridSpec(6, 81, 138)
        }
    }

    private data class GridSpec(val columns: Int, val tileDp: Int, val itemWidthDp: Int)

    private fun focusGrid(position: Int) {
        if (items.isEmpty()) return
        val safe = position.coerceIn(0, items.size - 1)
        val view = binding.appGrid.getChildAt(safe)
        if (view != null) {
            view.requestFocus()
        } else {
            binding.appGrid.postDelayed({ focusGrid(safe) }, 60L)
        }
    }

    /** 系统预装的算「非第三方」，首页优先放用户自己装的。 */
    private fun isThirdParty(packageName: String): Boolean = systemCache.getOrPut(packageName) {
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }.getOrDefault(true)
    }

    // ---- 交互 -------------------------------------------------------------------

    private fun onTileClicked(item: TileItem) {
        if (item is TileItem.App) launchApp(item.entity)
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
            toast("还没有其他分类，可到「设置 → 桌面与分类」新建")
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
            toast("已隐藏「${entity.label}」")
        }
    }

    // ---- 时钟 -------------------------------------------------------------------

    private fun startTicker() {
        stopTicker()
        val runnable = object : Runnable {
            override fun run() {
                updateClock()
                statusHandler.postDelayed(this, 2000L)
            }
        }
        ticker = runnable
        statusHandler.postDelayed(runnable, 2000L)
    }

    private fun stopTicker() {
        ticker?.let { statusHandler.removeCallbacks(it) }
        ticker = null
    }

    /** 读系统时钟（带用户手动设的偏移，默认 0），见 [AppTime]。 */
    private fun updateClock() {
        val now = Date(AppTime.now())
        val time = timeFormat.format(now)
        if (binding.clockTime.text != time) binding.clockTime.text = time
        val date = "${dateFormat.format(now)} ${weekFormat.format(now)}"
        if (binding.clockDate.text != date) binding.clockDate.text = date
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("桌面根界面按返回键不退出")
    override fun onBackPressed() {
        // 作为默认桌面，Back 不退出
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val COLUMNS = 6
        private const val HOME_SLOTS = COLUMNS * 2
    }
}
