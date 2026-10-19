package com.openlauncher.tv.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import com.openlauncher.tv.data.local.AppItemEntity
import com.openlauncher.tv.data.local.CategoryEntity
import com.openlauncher.tv.data.local.LauncherDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(
    private val context: Context,
    private val launcherDao: LauncherDao
) {

    /** 保证至少存在一个分类（首次启动 / 全删光后的兜底）。返回最小 sortOrder 的分类 id。 */
    suspend fun ensureDefaultCategoryId(): Long = withContext(Dispatchers.IO) {
        val categories = launcherDao.getAllCategories()
        categories.minByOrNull { it.sortOrder }?.id
            ?: launcherDao.insertCategory(
                CategoryEntity(name = "常用应用", sortOrder = 0)
            )
    }

    /**
     * 扫描已安装的可启动应用并同步进数据库。
     * - 保留用户已有配置（分类 / 隐藏 / 白名单 / 排序），只补新增、更新改名、清理失效项。
     * - 同时扫描 LEANBACK 与普通 LAUNCHER 两类入口（国行电视很多应用只有后者）。
     */
    suspend fun scanAndSyncInstalledApps() = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val found = LinkedHashMap<String, ResolveInfo>()

        val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        for (intent in listOf(leanbackIntent, launcherIntent)) {
            val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull().orEmpty()
            for (info in resolved) {
                val activityInfo = info.activityInfo ?: continue
                val key = "${activityInfo.packageName}/${activityInfo.name}"
                if (!found.containsKey(key)) found[key] = info
            }
        }

        val defaultCategoryId = ensureDefaultCategoryId()
        val existing = launcherDao.getAllApps().associateBy {
            "${it.packageName}/${it.activityName}"
        }

        val aliveKeys = HashSet<String>(found.size)
        val inserts = ArrayList<AppItemEntity>()

        for ((key, info) in found) {
            val activityInfo = info.activityInfo ?: continue
            val pkg = activityInfo.packageName
            val cls = activityInfo.name
            // 过滤自身
            if (pkg == context.packageName) continue
            aliveKeys += key

            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                ?: activityInfo.packageName

            val old = existing[key]
            if (old == null) {
                inserts += AppItemEntity(
                    packageName = pkg,
                    activityName = cls,
                    label = label,
                    categoryId = defaultCategoryId
                )
            } else if (old.label != label) {
                launcherDao.updateLabel(old.id, label)
            }
        }

        if (inserts.isNotEmpty()) {
            launcherDao.insertAppsIfNotExist(inserts)
        }

        // 清理已卸载 / 组件已失效的残留项（不影响只是暂时被系统隐藏的应用配置）
        for ((key, old) in existing) {
            if (aliveKeys.contains(key)) continue
            val component = ComponentName(old.packageName, old.activityName)
            val stillAlive = runCatching { pm.getActivityInfo(component, 0) }.isSuccess
            if (!stillAlive) {
                launcherDao.deleteAppComponent(old.packageName, old.activityName)
            }
        }
        Unit
    }
}
