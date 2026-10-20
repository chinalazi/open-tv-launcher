package com.openlauncher.tv.ui.home

import androidx.appcompat.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.local.AppItemEntity
import com.openlauncher.tv.data.local.CategoryEntity
import com.openlauncher.tv.databinding.ActivityMainBinding
import com.openlauncher.tv.ui.settings.SettingsActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 桌面：Apple TV 风格。
 *
 * 结构上是「一行一个分类」的货架：焦点落在磁贴上时，顶部用大字显示应用名，
 * 背景泛出该应用图标的主色；磁贴本身是圆角品牌色方块，聚焦放大并描白边。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    private var categories: List<CategoryEntity> = emptyList()
    private var apps: List<AppItemEntity> = emptyList()

    private var lastSignature: String? = null
    private var focusedKey: String? = null

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
        observeData()
    }

    override fun onResume() {
        super.onResume()
        // 从应用回到桌面时，焦点可能已经被系统清掉，放回原来的位置
        if (currentFocus == null) {
            binding.rowsContainer.post { restoreFocus() }
        }
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
        var settingsPlaced = false
        for (category in categoryList) {
            val rowApps = grouped[category.id].orEmpty()
            if (rowApps.isEmpty()) continue
            val tiles = ArrayList<TileItem>(rowApps.size + 1)
            rowApps.forEach { tiles.add(TileItem.App(it)) }
            if (!settingsPlaced) {
                tiles.add(settingsTile())
                settingsPlaced = true
            }
            binding.rowsContainer.addView(buildRow(category.name, tiles), rowParams())
        }
        if (!settingsPlaced) {
            binding.rowsContainer.addView(buildRow("系统", listOf(settingsTile())), rowParams())
        }

        binding.rowsContainer.post {
            if (firstRender) {
                if (appList.isEmpty()) showGreeting()
                focusRow(0)
            } else {
                restoreFocus()
            }
        }
    }

    private fun buildRow(title: String, tiles: List<TileItem>): ShelfRowView =
        ShelfRowView(this, callbacks).apply { bind(title, tiles) }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(18) }

    private fun settingsTile() = TileItem.Action(
        id = ACTION_SETTINGS,
        title = "设置",
        iconRes = R.drawable.ic_gear,
        color = ACTION_COLOR
    )

    // ---- 焦点与顶部大字 ---------------------------------------------------------

    private fun onTileFocused(item: TileItem) {
        focusedKey = keyOf(item)
        when (item) {
            is TileItem.App -> {
                val count = apps.count { it.categoryId == item.entity.categoryId }
                showHero(
                    item.entity.label,
                    "$count 个应用",
                    AppPalette.colorOf(this, item.entity.packageName)
                )
            }

            is TileItem.Action -> showHero(item.title, "电视与启动器设置", item.color)
        }
    }

    private fun showGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..11 -> "早上好"
            in 12..17 -> "下午好"
            else -> "晚上好"
        }
        showHero(greeting, "用方向键挑选应用，长按 OK 管理应用", GLOW_DEFAULT)
    }

    private fun showHero(title: String, subtitle: String, color: Int) {
        binding.tvHeroSubtitle.text = subtitle
        binding.glow.setBrandColor(color)
        if (binding.tvHeroTitle.text.toString() == title) return

        binding.tvHeroTitle.animate().cancel()
        binding.tvHeroTitle.alpha = 0f
        binding.tvHeroTitle.translationY = dp(12).toFloat()
        binding.tvHeroTitle.text = title
        binding.tvHeroTitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200L)
            .start()
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
        when (item) {
            is TileItem.App -> launchApp(item.entity)
            is TileItem.Action -> if (item.id == ACTION_SETTINGS) openSettings()
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
        private val ACTION_COLOR = 0xFF3A3A3C.toInt()
        private val GLOW_DEFAULT = 0xFF0A84FF.toInt()
    }
}
