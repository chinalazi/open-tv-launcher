package com.openlauncher.tv.ui.home

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/**
 * 内存 / 存储读取与「一键清理」。
 *
 * 清理只调 ActivityManager.killBackgroundProcesses（系统允许的正常手段，不需要 root）：
 * 后台进程会被回收，前台应用和系统关键进程不受影响。
 */
object SystemStatus {

    /** 已用内存占比 0f..1f */
    fun memoryRatio(context: Context): Float = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem <= 0) 0f
        else ((info.totalMem - info.availMem).toFloat() / info.totalMem).coerceIn(0f, 1f)
    }.getOrDefault(0f)

    fun memoryFreeBytes(context: Context): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.availMem
    }.getOrDefault(0L)

    fun storageFreeBytes(): Long = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        stat.availableBytes
    }.getOrDefault(0L)

    fun storageTotalBytes(): Long = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        stat.totalBytes
    }.getOrDefault(0L)

    /**
     * 一键清理：遍历已安装应用，逐个回收其后台进程。
     * 返回估算释放的字节数（清理前后的可用内存差）。
     */
    fun cleanBackground(context: Context): Long {
        val before = memoryFreeBytes(context)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val self = context.packageName
        runCatching {
            context.packageManager.getInstalledApplications(0).forEach { app ->
                if (app.packageName == self) return@forEach
                runCatching { am.killBackgroundProcesses(app.packageName) }
            }
        }
        // 给系统一点回收时间，再读一次
        runCatching { Thread.sleep(400) }
        val after = memoryFreeBytes(context)
        return (after - before).coerceAtLeast(0L)
    }

    /** 48.2GB / 860MB 这种短格式 */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0GB"
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.0fGB", gb)
        } else {
            String.format(Locale.US, "%.0fMB", bytes / 1024.0 / 1024.0)
        }
    }
}
