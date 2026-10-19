package com.openlauncher.tv.ui.home

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.local.AppItemEntity

/** 启动应用的统一入口（锁定状态下拦截，除非是白名单应用）。 */
object AppLauncher {

    fun launch(activity: Activity, item: AppItemEntity): Boolean {
        val app = activity.application as? LauncherApplication ?: return false
        val state = app.parental.state.value
        if (state.kidsMode && state.locked && !item.isWhitelistForKids) {
            Toast.makeText(activity, "观看时间已用完，请家长输入 PIN 码解锁", Toast.LENGTH_LONG).show()
            return false
        }
        return try {
            activity.startActivity(
                Intent().apply {
                    component = ComponentName(item.packageName, item.activityName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
            )
            true
        } catch (e: Exception) {
            Toast.makeText(activity, "无法启动「${item.label}」", Toast.LENGTH_LONG).show()
            false
        }
    }
}
