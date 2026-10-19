package com.openlauncher.tv.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.service.TimeTrackingService

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
