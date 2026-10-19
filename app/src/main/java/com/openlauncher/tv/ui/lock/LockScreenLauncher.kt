package com.openlauncher.tv.ui.lock

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * 锁屏拉起入口。由计时服务与桌面 Activity 共同调用。
 * 带 800ms 节流，避免 onResume 与 service 同时触发时重复 startActivity。
 */
object LockScreenLauncher {

    private const val TAG = "LockScreenLauncher"
    private const val MIN_INTERVAL_MS = 800L

    private var lastLaunchAt = 0L

    fun show(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAt < MIN_INTERVAL_MS) return
        lastLaunchAt = now

        val intent = Intent(context, LockScreenActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "拉起锁屏失败", it) }
    }
}
