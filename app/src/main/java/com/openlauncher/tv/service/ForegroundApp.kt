package com.openlauncher.tv.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * 判断"当前前台是不是白名单应用"。
 * 需要用户在系统设置里授予"使用情况访问权限"；没有授权时返回 null，调用方退化为只按亮屏计时。
 */
object ForegroundApp {

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }.getOrNull() ?: return false
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun currentForegroundPackage(context: Context): String? {
        if (!hasUsageAccess(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 90_000L, now) }.getOrNull() ?: return null
        val resumedType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == resumedType) {
                last = event.packageName
            }
        }
        return last
    }
}
