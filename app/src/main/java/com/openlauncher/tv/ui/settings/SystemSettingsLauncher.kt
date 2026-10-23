package com.openlauncher.tv.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 各家电视对标准 Settings Action 的支持差异很大，小米电视（PatchWall）尤其离谱：
 * `com.xiaomi.mitv.settings.SETTINGS` 和 `.entry.MainActivity` 会被 startActivity 成功拉起，
 * 但**永远不绘制**（实测 10 秒以上仍然黑屏），用户只能干等 —— 这就是"进系统设置要等很久"的根因。
 *
 * 所以这里的候选表只保留实测能在本机正常出画面的入口：
 * - Wi-Fi：com.xiaomi.mitv.settings/.wifi.MitvWifiActivity（action WIFI_SETTINGS 也可）
 * - 信号源：com.xiaomi.mitv.tvplayer/.ExternalSourceActivity（123ms）
 * - 账号：com.xiaomi.account/...LoginActivity（约 1.2s）
 */
object SystemSettingsLauncher {

    enum class Target { ROOT, WIFI, BLUETOOTH, DISPLAY, SOUND, APPS, HOME, SOURCE, ACCOUNT, ABOUT }

    private const val TAG = "SystemSettings"
    private const val MI = "com.xiaomi.mitv.settings"
    private const val TV_PLAYER = "com.xiaomi.mitv.tvplayer"
    private const val MI_ACCOUNT = "com.xiaomi.account"

    fun open(context: Context, target: Target): Boolean {
        for (intent in candidates(target)) {
            if (tryStart(context, intent)) return true
        }
        return false
    }

    /**
     * 直接尝试启动并捕获异常。不用 resolveActivity 预判：
     * 部分定制 ROM（小米电视）对显式组件会返回 null，但实际可以启动。
     */
    private fun tryStart(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val label = intent.component?.flattenToShortString() ?: intent.action
        return try {
            context.startActivity(intent)
            Log.d(TAG, "started $label")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "failed $label: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    private fun action(name: String) = Intent(name)

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    private fun candidates(target: Target): List<Intent> = when (target) {
        // 设置主页。注意它是全机最慢的一个入口：MainActivity.onCreate 会在主线程
        // 轮询 ConfigurationManager 的系统服务（每次 sleep 500ms），实测十几秒才出画面。
        // 所以桌面上不再直接开它，走本应用的 SettingsActivity 分类入口；这里只作兜底。
        Target.ROOT -> listOf(
            component(MI, "$MI.entry.MainActivity"),
            component("com.android.tv.settings", "com.android.tv.settings.MainSettings")
        )

        Target.WIFI -> listOf(
            component(MI, "$MI.wifi.MitvWifiActivity"),
            action("com.xiaomi.mitv.settings.WIFI_SETTINGS"),
            action(Settings.ACTION_WIFI_SETTINGS)
        )

        Target.BLUETOOTH -> listOf(
            component(MI, "$MI.bluetooth.MitvBluetoothActivity"),
            action("com.xiaomi.mibox.settings.BLUETOOTH_ENTRY"),
            action(Settings.ACTION_BLUETOOTH_SETTINGS)
        )

        Target.DISPLAY -> listOf(
            component(MI, "$MI.displaysound.DisplaySoundActivity"),
            component(MI, "$MI.entry.DisplaySoundActivity"),
            action("com.xiaomi.mitv.settings.DISPLAYSOUND_SETTINGS")
        )

        Target.SOUND -> listOf(
            component(MI, "$MI.displaysound.DisplaySoundActivity"),
            component(MI, "$MI.entry.DisplaySoundActivity"),
            action("com.xiaomi.mitv.settings.DISPLAYSOUND_SETTINGS")
        )

        Target.APPS -> listOf(
            action(Settings.ACTION_APPLICATION_SETTINGS),
            component("com.android.settings", "com.android.settings.Settings")
        )

        Target.HOME -> listOf(
            action(Settings.ACTION_HOME_SETTINGS)
        )

        // 信号源（HDMI / 外接设备）
        Target.SOURCE -> listOf(
            component(TV_PLAYER, "$TV_PLAYER.ExternalSourceActivity"),
            action("com.xiaomi.mitv.tvplayer.SOURCE")
        )

        // 账号与安全：优先系统设置里的「账号与安全」页（实测 561ms 正常绘制）。
        // 注意 MiAccount 的 Activity 类名是 com.xiaomi.mitv.account.*，包名才是 com.xiaomi.account，
        // 不能写成 "$MI_ACCOUNT.mitv.account.*"——那样拼出来的类名不存在，startActivity 会直接抛异常。
        Target.ACCOUNT -> listOf(
            component(MI, "$MI.entry.SecurityActivity"),
            component(MI_ACCOUNT, "com.xiaomi.mitv.account.ui.system.LoginActivity"),
            component(MI_ACCOUNT, "com.xiaomi.mitv.account.ui.system.CloudEntryActivity"),
            action(Settings.ACTION_SECURITY_SETTINGS)
        )

        // 关于：设备信息 / 存储 / 系统更新都在这一页（实测可正常绘制）
        Target.ABOUT -> listOf(
            component(MI, "$MI.entry.AboutActivity"),
            action(Settings.ACTION_DEVICE_INFO_SETTINGS)
        )
    }
}
