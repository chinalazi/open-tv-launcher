package com.openlauncher.tv.ui.allapps

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.local.AppItemEntity
import com.openlauncher.tv.databinding.ActivityAllAppsBinding
import com.openlauncher.tv.ui.home.TileAdapter
import com.openlauncher.tv.ui.home.TileCallbacks
import com.openlauncher.tv.ui.home.TileItem
import com.openlauncher.tv.ui.settings.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「更多」：桌面上没有直接摆放的应用（主要是系统自带应用）都在这里，
 * 网格布局，遥控器方向键直接走位。
 */
class AllAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAllAppsBinding
    private val launcherApp: LauncherApplication get() = application as LauncherApplication
    private val systemCache = HashMap<String, Boolean>()

    private val callbacks = TileCallbacks(
        onFocused = { },
        onClicked = { item -> if (item is TileItem.App) launchApp(item.entity) },
        onLongClicked = { item -> if (item is TileItem.App) hideApp(item.entity) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAllAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val columns = (resources.configuration.screenWidthDp / 132).coerceIn(4, 8)
        binding.gridAllApps.layoutManager = GridLayoutManager(this, columns)

        lifecycleScope.launch {
            val all = launcherApp.database.launcherDao().getAllVisibleAppsFlow().first()
            // 第三方在前，系统应用在后，同类按名称排序
            val sorted = all.sortedWith(
                compareBy({ isSystem(it.packageName) }, { it.label.lowercase() })
            )
            binding.tvAllAppsSub.text = "共 ${sorted.size} 个应用"
            binding.gridAllApps.adapter = TileAdapter(sorted.map { TileItem.App(it) }, callbacks)
            binding.gridAllApps.postDelayed({ focusFirst() }, 120L)
        }
    }

    private fun focusFirst() {
        val manager = binding.gridAllApps.layoutManager as? GridLayoutManager ?: return
        manager.findViewByPosition(0)?.requestFocus()
            ?: binding.gridAllApps.postDelayed({ focusFirst() }, 80L)
    }

    private companion object {
        /** 小米电视「设置」的包名：它的主页入口慢到不可用，见 [launchApp]。 */
        const val MI_SETTINGS = "com.xiaomi.mitv.settings"
    }

    private fun isSystem(packageName: String): Boolean = systemCache.getOrPut(packageName) {
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(false)
    }

    private fun launchApp(entity: AppItemEntity) {
        // 小米系统设置的 launcher 入口（.entry.MainActivity）在主线程轮询自家系统服务，
        // 实测十几秒才绘制，用户观感就是"按了没反应"。这里换成我们自己的分类设置页：
        // 秒开，每一类再直达对应的系统细分页（Wi-Fi 420ms、账号 467ms）。
        if (entity.packageName == MI_SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val intent = Intent().apply {
            component = ComponentName(entity.packageName, entity.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        if (runCatching { startActivity(intent) }.isFailure) {
            Toast.makeText(this, "无法启动「${entity.label}」", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideApp(entity: AppItemEntity) {
        lifecycleScope.launch {
            launcherApp.database.launcherDao().setHidden(entity.id, true)
            Toast.makeText(this@AllAppsActivity, "已隐藏「${entity.label}」", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
