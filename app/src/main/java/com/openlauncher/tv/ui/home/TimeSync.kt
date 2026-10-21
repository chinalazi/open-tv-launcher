package com.openlauncher.tv.ui.home

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 网络校时。
 *
 * 小米电视 4A 上遇到过：系统设置里「自动确定时间」是开着的，时区也对（Asia/Shanghai），
 * 但系统时钟比真实时间慢了 37 分钟 —— 说明它的 NTP 对时其实没成功（大概率是连不上小米的对时服务器）。
 * 桌面时钟直接读系统时间，用户看到的就是错的。
 *
 * 这里不改系统时间（应用没权限），而是自己算一个偏移量：
 * 取一个能访问的站点响应头里的 `Date`（GMT），算出「真实时间 - 系统时间」，
 * 之后界面显示一律用 [now]。偏差几分钟以内完全够用，网络本身有几十毫秒误差，可忽略。
 */
object TimeSync {

    private const val TAG = "TimeSync"
    private const val PREFS = "time_sync"
    private const val KEY_OFFSET = "offset_ms"
    private const val KEY_AT = "synced_at"
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /** 顺序尝试：都是能返回 Date 头的站点，Bing 的家用网络基本都能通。 */
    private val ENDPOINTS = listOf(
        "https://www.bing.com",
        "https://cn.bing.com",
        "https://www.baidu.com"
    )

    private val httpDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    @Volatile
    private var offsetMs = 0L

    /** 校准后的「现在」。没校准成功时等于系统时间。 */
    fun now(): Long = System.currentTimeMillis() + offsetMs

    fun offset(): Long = offsetMs

    /** 先把上次算好的偏移读出来，界面立刻正确，再去后台刷新。 */
    fun loadCached(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        offsetMs = sp.getLong(KEY_OFFSET, 0L)
    }

    fun needsSync(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = sp.getLong(KEY_AT, 0L)
        return System.currentTimeMillis() - at > MAX_AGE_MS
    }

    /**
     * 同步一次。返回是否成功。
     * 用请求前后两个本地时间的中点去对，抵消一半往返延迟。
     */
    suspend fun sync(context: Context): Boolean = withContext(Dispatchers.IO) {
        for (endpoint in ENDPOINTS) {
            val serverMs = fetchServerTime(endpoint)
            if (serverMs == null) {
                Log.w(TAG, "sync failed: $endpoint")
                continue
            }
            val newOffset = serverMs - System.currentTimeMillis()
            offsetMs = newOffset
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_OFFSET, newOffset)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
            Log.i(TAG, "synced via $endpoint, offset=${newOffset / 1000}s")
            return@withContext true
        }
        false
    }

    /** 读响应头 Date，返回服务器时间（本地时区毫秒），失败返回 null。 */
    private fun fetchServerTime(endpoint: String): Long? {
        var conn: HttpURLConnection? = null
        return try {
            val before = System.currentTimeMillis()
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 6.0) OpenTVLauncher")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = conn.responseCode
            val header = conn.getHeaderField("Date") ?: return null
            val after = System.currentTimeMillis()
            if (code !in 200..399) return null
            val server = synchronized(httpDateFormat) { httpDateFormat.parse(header)?.time } ?: return null
            // Date 头只有秒级精度，取整到秒再加半个往返
            server + (after - before) / 2
        } catch (e: Exception) {
            Log.w(TAG, "fetch $endpoint: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 开机时长：电视这种长期不关机的设备，按「从开机算起」比按「使用时长」更直观。 */
    fun formatUptime(elapsedMs: Long): String {
        val totalMinutes = elapsedMs / 60_000L
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "${days}天${hours}小时"
            hours > 0 -> "${hours}小时${minutes.toString().padStart(2, '0')}分"
            else -> "${minutes}分钟"
        }
    }
}
