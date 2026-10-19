package com.openlauncher.tv.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 各家电视对标准 Settings Action 的支持差异很大：
 * 小米电视（com.xiaomi.mitv.settings）根本没有实现 android.settings.SETTINGS，
 * 所以这里为每个入口准备一组有序候选（标准 Action → 小米组件 → AOSP 组件），逐个尝试。
 */
object SystemSettingsLauncher {

    enum class Target { ROOT, WIFI, BLUETOOTH, DISPLAY, SOUND, APPS, HOME }

    private const val TAG = "SystemSettings"
    private const val MI = "com.xiaomi.mitv.settings"

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
        Target.ROOT -> listOf(
            // 小米电视没实现 android.settings.SETTINGS，但有自定义 action，优先用它
            action("com.xiaomi.mitv.settings.SETTINGS"),
            action(Settings.ACTION_SETTINGS),
            component(MI, "$MI.entry.MainActivity"),
            component("com.android.settings", "com.android.settings.Settings"),
            component("com.android.tv.settings", "com.android.tv.settings.MainSettings")
        )

        Target.WIFI -> listOf(
            action("com.xiaomi.mitv.settings.WIFI_SETTINGS"),
            action(Settings.ACTION_WIFI_SETTINGS),
            component(MI, "$MI.wifi.MitvWifiActivity")
        )

        Target.BLUETOOTH -> listOf(
            action("com.xiaomi.mibox.settings.BLUETOOTH_ENTRY"),
            action(Settings.ACTION_BLUETOOTH_SETTINGS),
            component(MI, "$MI.bluetooth.MitvBluetoothActivity")
        )

        Target.DISPLAY -> listOf(
            action("com.xiaomi.mitv.settings.DISPLAYSOUND_SETTINGS"),
            action(Settings.ACTION_DISPLAY_SETTINGS),
            component(MI, "$MI.entry.DisplaySoundActivity")
        )

        Target.SOUND -> listOf(
            action("com.xiaomi.mitv.settings.DISPLAYSOUND_SETTINGS"),
            action(Settings.ACTION_SOUND_SETTINGS),
            component(MI, "$MI.entry.DisplaySoundActivity")
        )

        Target.APPS -> listOf(
            action(Settings.ACTION_APPLICATION_SETTINGS),
            component(MI, "$MI.entry.MainActivity"),
            component("com.android.settings", "com.android.settings.Settings")
        )

        Target.HOME -> listOf(
            action(Settings.ACTION_HOME_SETTINGS),
            component(MI, "$MI.entry.MainActivity")
        )
    }
}
