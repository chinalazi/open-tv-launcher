package com.openlauncher.tv.ui.home

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import kotlin.math.abs

/**
 * 时间源。
 *
 * 1.7.0 之前这里做的是自动网络校时：这台小米电视的 NTP 不生效、系统钟慢 37 分钟，
 * 所以启动时抓一次 HTTP 响应的 `Date` 头算出偏移，界面显示全走 [now]。
 * 系统时间修好之后那套逻辑就是纯负担 —— 每次开机都会把本来正确的时间又校歪。
 *
 * 现在：
 *
 * - **默认直接读系统时间**，不联网、不做任何自动校正；
 * - 只有用户在「设置 → 日期与时间」里点过「同步阿里时间」、而系统时钟又改不动时，
 *   才留一个人工偏移（[offsetMs]），让桌面时钟自己先对上。
 */
object AppTime {

    private const val TAG = "AppTime"

    /** 新开一个 prefs 文件：旧的 `time_sync` 里存着当年自动校时算出的 37 分钟，绝不能再读。 */
    private const val PREFS = "app_time"
    private const val KEY_OFFSET = "manual_offset_ms"
    private const val KEY_SYNC_AT = "last_sync_at"
    private const val KEY_SYNC_SOURCE = "last_sync_source"
    private const val KEY_SYNC_DIFF = "last_sync_diff_ms"

    @Volatile
    private var offsetMs = 0L

    @Volatile
    private var syncAt = 0L

    @Volatile
    private var syncSource = ""

    @Volatile
    private var syncDiffMs = 0L

    /** 当前该显示的时间。没设过偏移时就是系统时间。 */
    fun now(): Long = System.currentTimeMillis() + offsetMs

    fun offset(): Long = offsetMs

    /** 上次校时成功的时刻（系统钟），0 表示从没校过。 */
    fun lastSyncAt(): Long = syncAt

    /** 上次校时用的时间源，如 `ntp.aliyun.com`。 */
    fun lastSyncSource(): String = syncSource

    /** 上次校时时「阿里时间 − 系统时间」，正数表示系统慢了。 */
    fun lastSyncDiff(): Long = syncDiffMs

    fun load(context: Context) {
        val prefs = prefs(context)
        offsetMs = prefs.getLong(KEY_OFFSET, 0L)
        syncAt = prefs.getLong(KEY_SYNC_AT, 0L)
        syncSource = prefs.getString(KEY_SYNC_SOURCE, "").orEmpty()
        syncDiffMs = prefs.getLong(KEY_SYNC_DIFF, 0L)
    }

    fun setOffset(context: Context, ms: Long) {
        offsetMs = ms
        prefs(context).edit().putLong(KEY_OFFSET, ms).apply()
    }

    fun clearOffset(context: Context) = setOffset(context, 0L)

    /** 记一笔校时结果，只用于界面展示，不参与计算。 */
    fun recordSync(context: Context, at: Long, source: String, diffMs: Long) {
        syncAt = at
        syncSource = source
        syncDiffMs = diffMs
        prefs(context).edit()
            .putLong(KEY_SYNC_AT, at)
            .putString(KEY_SYNC_SOURCE, source)
            .putLong(KEY_SYNC_DIFF, diffMs)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 尝试把系统时钟改成 [millis]。
     *
     * @return null 表示成功；否则返回给人看的原因。
     *
     * 普通应用要改系统时间得有 `android.permission.SET_TIME`（signature|privileged），
     * 这台电视的固件没放开，所以这里大概率会失败 —— 失败就如实报错，
     * 绝不偷偷改成「只在本应用生效」假装成功。
     */
    fun setSystemTime(context: Context, millis: Long): String? {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return "系统服务不可用"

        return try {
            @Suppress("DEPRECATION")
            alarm.setTime(millis)
            // 有的 ROM 不抛异常也不真改，回读一次确认
            if (abs(System.currentTimeMillis() - millis) <= 3_000L) {
                null
            } else {
                "系统接受了请求但没有生效（固件限制）"
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "setTime denied: ${e.message}")
            "系统拒绝：普通应用没有修改系统时间的权限"
        } catch (e: Throwable) {
            Log.w(TAG, "setTime failed: ${e.message}")
            "修改失败：${e.javaClass.simpleName}"
        }
    }
}
