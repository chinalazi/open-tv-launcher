package com.openlauncher.tv.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.service.TimeTrackingService
import com.openlauncher.tv.ui.settings.SystemSettingsLauncher
import kotlinx.coroutines.launch

/**
 * 仅存在于 debug 构建的联调入口：
 *   adb shell am broadcast -a com.openlauncher.tv.DEBUG --es cmd lock
 *   adb shell am broadcast -a com.openlauncher.tv.DEBUG --es cmd reset
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? LauncherApplication ?: return
        val cmd = intent.getStringExtra("cmd").orEmpty()
        Log.d(TAG, "debug cmd=$cmd")

        when (cmd) {
            // 把单次额度设成 0 并开启儿童模式，下一秒计时服务就会触发锁定
            "lock" -> {
                app.parental.setSingleLimitMinutes(0)
                app.parental.setKidsMode(true)
                TimeTrackingService.startService(context)
            }

            // 直接把家长 PIN 设为 1234，省去在电视上点九宫格
            "pin" -> {
                app.applicationScope.launch { app.parental.setPin("1234") }
            }

            // 联调系统设置各入口：--es cmd sysset --es target WIFI
            "sysset" -> {
                val name = intent.getStringExtra("target").orEmpty().ifEmpty { "ROOT" }
                val target = runCatching { SystemSettingsLauncher.Target.valueOf(name) }
                    .getOrDefault(SystemSettingsLauncher.Target.ROOT)
                val ok = SystemSettingsLauncher.open(context, target)
                Log.d(TAG, "sysset $target -> $ok")
            }

            "reset" -> {
                app.parental.setKidsMode(false)
                app.parental.setSingleLimitMinutes(45)
                app.parental.resetToday()
                TimeTrackingService.stopService(context)
            }
        }
    }

    companion object {
        private const val TAG = "DebugCommand"
    }
}
