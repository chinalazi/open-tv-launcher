package com.openlauncher.tv.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.openlauncher.tv.data.local.AppItemEntity
import com.openlauncher.tv.data.local.LauncherDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(
    private val context: Context,
    private val launcherDao: LauncherDao
) {

    /**
     * 扫描系统已安装的 TV 与普通 Launchable 应用并同步到数据库
     */
    suspend fun scanAndSyncInstalledApps() = withContext(Dispatchers.IO) {
        val pm = context.packageManager

        // 1. 优先扫描 Android TV Leanback 应用
        val tvIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        val tvApps = pm.queryIntentActivities(tvIntent, 0)

        // 2. 补充扫描普通应用（兼容某些未声明 Leanback 的标准 Android 应用）
        val standardIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val standardApps = pm.queryIntentActivities(standardIntent, 0)

        val combinedApps = mutableMapOf<String, ResolveInfo>()
        for (info in tvApps) {
            val key = "${info.activityInfo.packageName}/${info.activityInfo.name}"
            combinedApps[key] = info
        }
        for (info in standardApps) {
            val key = "${info.activityInfo.packageName}/${info.activityInfo.name}"
            if (!combinedApps.containsKey(key)) {
                combinedApps[key] = info
            }
        }

        // 过滤自身
        combinedApps.remove("${context.packageName}/com.openlauncher.tv.ui.home.MainActivity")

        // 获取默认分类 ID（常用应用，通常为 ID 1）
        val categories = launcherDao.getAllCategories()
        val defaultCategoryId = categories.firstOrNull()?.id ?: 1L

        val entitiesToInsert = mutableListOf<AppItemEntity>()
        for ((_, resolveInfo) in combinedApps) {
            val pkg = resolveInfo.activityInfo.packageName
            val cls = resolveInfo.activityInfo.name
            val label = resolveInfo.loadLabel(pm).toString()

            val existing = launcherDao.findApp(pkg, cls)
            if (existing == null) {
                entitiesToInsert.add(
                    AppItemEntity(
                        packageName = pkg,
                        activityName = cls,
                        label = label,
                        categoryId = defaultCategoryId,
                        isHidden = false
                    )
                )
            }
        }

        if (entitiesToInsert.isNotEmpty()) {
            launcherDao.insertAppsIfNotExist(entitiesToInsert)
        }
    }
}
