package com.openlauncher.tv.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.ui.settings.SystemSettingsLauncher
import kotlinx.coroutines.launch

/**
 * 仅存在于 debug 构建的联调入口：
 *   adb shell am broadcast -a com.openlauncher.tv.DEBUG --es cmd refresh
 *   adb shell am broadcast -a com.openlauncher.tv.DEBUG --es cmd sysset --es target WIFI
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? LauncherApplication ?: return
        val cmd = intent.getStringExtra("cmd").orEmpty()
        Log.d(TAG, "debug cmd=$cmd")

        when (cmd) {
            // 重新扫描已安装应用（等价于跑一次开机同步）
            "refresh" -> {
                app.applicationScope.launch {
                    val result = runCatching { app.appRepository.scanAndSyncInstalledApps() }
                    Log.d(TAG, "refresh -> ${result.isSuccess}")
                }
            }

            // 联调系统设置各入口：--es cmd sysset --es target WIFI
            "sysset" -> {
                val name = intent.getStringExtra("target").orEmpty().ifEmpty { "ROOT" }
                val target = runCatching { SystemSettingsLauncher.Target.valueOf(name) }
                    .getOrDefault(SystemSettingsLauncher.Target.ROOT)
                val ok = SystemSettingsLauncher.open(context, target)
                Log.d(TAG, "sysset $target -> $ok")
            }
        }
    }

    companion object {
        private const val TAG = "DebugCommand"
    }
}
