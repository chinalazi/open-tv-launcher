package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 壁纸仓库：多图源 + 双缓冲。
 *
 * 双缓冲策略 —— 电视开机时 Wi-Fi 往往还没握手完，桌面已经起来了：
 * 1. 启动时先把本地缓存图（current）**同步解码**出来，毫秒级上屏，永远不出现转圈或空白；
 * 2. 后台静默下载新图，写进 pending，**不**立刻切换；
 * 3. 下一次启动（或下次从应用返回桌面）时把 pending 提升为 current 再切换。
 *
 * 于是用户看到的永远是"已经下载好的图"，切换发生在看不见的时刻。
 *
 * 图源见 [Source]，选择结果存在 SharedPreferences("wallpaper") 里，开机即用。
 */
class WallpaperRepository private constructor(private val context: Context) {

    data class Meta(val title: String, val date: String, val url: String)

    data class Loaded(val frame: Bitmap?, val backdrop: Bitmap?, val meta: Meta?)

    /** 一张候选图：原图地址 + 缩略图地址（弹窗预览用）。 */
    data class Candidate(val url: String, val thumbUrl: String, val title: String)

    /**
     * 可选图源。
     *
     * [kind] 是弹窗里「图片种类」那一行的归属，用来让"种类"和"来源"联动：
     * 点种类会跳到对应来源，点来源会把种类高亮切过去，两边永远说的是同一件事。
     *
     * 注意：两个枚举互相引用必须写成 getter，不能在构造参数里直接引用对方——
     * 那样会在类初始化过程中拿到 null（JVM 会把还在初始化中的枚举字段读成 null），
     * 真机上表现为点一下就 NPE 崩掉。
     */
    enum class Source(val label: String) {
        BING_CN("Bing 每日壁纸·中文"),
        BING_JP("Bing 日本"),
        BING_US("Bing 美国"),
        BING_GB("Bing 欧洲"),
        SAFEBOORU("safebooru 动漫"),
        CUSTOM("自定义地址"),
        SOLID("纯色渐变");

        val kind: Kind
            get() = when (this) {
                BING_CN -> Kind.DAILY
                BING_JP -> Kind.WORLD
                BING_US, BING_GB -> Kind.PHOTO
                SAFEBOORU -> Kind.ANIME
                CUSTOM -> Kind.DAILY
                SOLID -> Kind.SOLID
            }

        companion object {
            fun of(name: String?): Source =
                entries.firstOrNull { it.name == name } ?: BING_CN
        }
    }

    enum class Kind(val label: String) {
        DAILY("每日风景"),
        WORLD("世界风光"),
        PHOTO("摄影"),
        ANIME("动漫"),
        SOLID("纯色");

        val defaultSource: Source
            get() = when (this) {
                DAILY -> Source.BING_CN
                WORLD -> Source.BING_JP
                PHOTO -> Source.BING_US
                ANIME -> Source.SAFEBOORU
                SOLID -> Source.SOLID
            }

        companion object {
            fun of(source: Source): Kind = source.kind
        }
    }

    private val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
    private val currentFile = File(dir, "current.jpg")
    private val pendingFile = File(dir, "pending.jpg")
    private val prefs = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
    private val fetchLock = Mutex()

    @Volatile
    private var lastKnownMeta: Meta? = null

    /** 本次进程是否已经做过"开机加载"，用于区分「启动」与「返回桌面」。 */
    var launched = false
        private set

    // ---- 配置 -------------------------------------------------------------------

    fun source(): Source = Source.of(prefs.getString(KEY_SOURCE, null))

    fun setSource(value: Source) {
        prefs.edit().putString(KEY_SOURCE, value.name).apply()
    }

    fun customUrl(): String = prefs.getString(KEY_CUSTOM_URL, "") ?: ""

    fun setCustomUrl(value: String) {
        prefs.edit().putString(KEY_CUSTOM_URL, value.trim()).apply()
    }

    /** 当前图源对应的接口地址，展示给用户在弹窗里看（自定义源就是用户填的那个）。 */
    fun endpoint(): String = when (source()) {
        Source.BING_CN -> bingEndpoint("zh-CN")
        Source.BING_JP -> bingEndpoint("ja-JP")
        Source.BING_US -> bingEndpoint("en-US")
        Source.BING_GB -> bingEndpoint("en-GB")
        Source.SAFEBOORU -> SAFEBOORU_API + "&limit=60&tags=scenery"
        Source.CUSTOM -> customUrl().ifBlank { "（还没填地址）" }
        Source.SOLID -> "本地生成，不走网络"
    }

    // ---- 生命周期 ---------------------------------------------------------------

    /**
     * 启动时调用：先把上次下载好的 pending 提升为 current，再解码本地缓存。
     * 纯本地操作，不碰网络。
     */
    suspend fun prepareOnLaunch(): Loaded = withContext(Dispatchers.IO) {
        if (pendingFile.exists() && pendingFile.length() > 0) {
            promotePending()
        }
        launched = true
        decodeCurrent()
    }

    /** 从别的应用回到桌面：如果后台已经下好新图，此刻再切换（用户看不到加载过程）。 */
    suspend fun onReturnToHome(): Loaded? = withContext(Dispatchers.IO) {
        if (!launched) return@withContext null
        if (pendingFile.exists() && pendingFile.length() > 0) {
            promotePending()
            decodeCurrent()
        } else {
            null
        }
    }

    /** 后台静默拉取一张新图，写入 pending。失败就保持旧图，不打扰用户。 */
    suspend fun fetchIntoPending(): Boolean = withContext(Dispatchers.IO) {
        fetchLock.withLock {
            runCatching {
                if (source() == Source.SOLID) return@withLock false
                if (!hasNetwork()) return@withLock false
                val candidate = candidates(1).firstOrNull() ?: return@withLock false
                val currentUrl = readMeta(CURRENT)?.url
                if (candidate.url == currentUrl) return@withLock false
                downloadToPending(candidate)
            }.getOrElse {
                Log.w(TAG, "fetch failed: ${it.javaClass.simpleName} ${it.message}")
                false
            }
        }
    }

    /**
     * 首次安装（本地一张缓存都没有）时的兜底：既然没得看，就把刚下好的图直接上屏，
     * 不用等下次启动。之后仍走"先看旧图、下次再换"的双缓冲节奏。
     */
    suspend fun adoptPendingIfNoCurrent(): Loaded? = withContext(Dispatchers.IO) {
        if (currentFile.exists() && currentFile.length() > 0) return@withContext null
        if (!pendingFile.exists() || pendingFile.length() == 0L) return@withContext null
        promotePending()
        decodeCurrent()
    }

    /** 手动换一张：下载后立刻切换（用户主动点了，就该看到变化）。 */
    suspend fun refreshNow(): Loaded? = withContext(Dispatchers.IO) {
        if (source() == Source.SOLID) return@withContext applySolid()
        val ok = fetchIntoPending()
        if (!ok) return@withContext null
        promotePending()
        decodeCurrent()
    }

    /** 用户在某张预览图上按了确认：立刻换成这一张。 */
    suspend fun applyCandidate(candidate: Candidate): Loaded? = withContext(Dispatchers.IO) {
        fetchLock.withLock {
            runCatching {
                val bytes = httpGetBytes(candidate.url)
                if (bytes.size < MIN_IMAGE_BYTES) return@withLock null
                writePendingBytes(bytes, Meta(candidate.title, today(), candidate.url))
                promotePending()
                decodeCurrent()
            }.getOrElse {
                Log.w(TAG, "apply failed: ${it.message}")
                null
            }
        }
    }

    /** 纯色渐变：不联网，本地画一张 1920x1080 的绿色渐变。 */
    suspend fun applySolid(): Loaded? = withContext(Dispatchers.IO) {
        runCatching {
            val w = 1920
            val h = 1080
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0xFF0E6B52.toInt(), 0xFF12B57F.toInt(), 0xFF8FE3C6.toInt()),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            val out = File(dir, "pending.tmp")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            if (pendingFile.exists()) pendingFile.delete()
            out.renameTo(pendingFile)
            writeMeta(PENDING, Meta("纯色渐变", today(), "solid://green"))
            promotePending()
            decodeCurrent()
        }.getOrElse {
            Log.w(TAG, "solid failed: ${it.message}")
            null
        }
    }

    fun meta(): Meta? = lastKnownMeta ?: readMeta(CURRENT)

    /** 弹窗预览用的本地当前图。 */
    fun currentFile(): File? = currentFile.takeIf { it.exists() && it.length() > 0 }

    // ---- 候选图 ----------------------------------------------------------------

    /**
     * 拉一批候选图（弹窗预览 + 静默换图都用它）。
     * 任何一步失败都返回空列表，调用方按"这张没拿到"处理，不弹错。
     */
    suspend fun candidates(limit: Int): List<Candidate> = withContext(Dispatchers.IO) {
        runCatching {
            when (source()) {
                Source.BING_CN -> bingCandidates("zh-CN", limit)
                Source.BING_JP -> bingCandidates("ja-JP", limit)
                Source.BING_US -> bingCandidates("en-US", limit)
                Source.BING_GB -> bingCandidates("en-GB", limit)
                Source.SAFEBOORU -> safebooruCandidates(limit)
                Source.CUSTOM -> customCandidates(limit)
                Source.SOLID -> emptyList()
            }
        }.getOrElse {
            Log.w(TAG, "candidates failed: ${it.message}")
            emptyList()
        }
    }

    /** 把候选列表里第 [index] 张的缩略图下下来（弹窗预览用，小图，失败就返回 null）。 */
    suspend fun loadThumb(candidate: Candidate): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = httpGetBytes(candidate.thumbUrl)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                Log.w(TAG, "thumb decode failed: ${bytes.size}B ${candidate.thumbUrl.take(80)}")
            }
            bitmap
        }.getOrElse {
            Log.w(TAG, "thumb failed: ${it.javaClass.simpleName} ${it.message} ${candidate.thumbUrl.take(80)}")
            null
        }
    }

    private fun bingCandidates(market: String, limit: Int): List<Candidate> {
        val json = httpGet(bingEndpoint(market, limit.coerceAtLeast(1)))
        val images: JSONArray = JSONObject(json).getJSONArray("images")
        val list = ArrayList<Candidate>(images.length())
        for (i in 0 until images.length()) {
            val image = images.getJSONObject(i)
            val base = image.getString("url")
            val full = "https://www.bing.com" + base
            list.add(
                Candidate(
                    url = full,
                    thumbUrl = full.replace("_1920x1080.jpg", "_400x240.jpg"),
                    title = image.optString("copyright").substringBefore(" (").trim()
                        .ifBlank { image.optString("title") }
                )
            )
        }
        return list
    }

    private fun safebooruCandidates(limit: Int): List<Candidate> {
        // 这个库横图少，多抓一些再按比例筛；电视上竖图没法看
        val json = httpGet("$SAFEBOORU_API&limit=60&tags=scenery")
        val array = JSONArray(json)
        val list = ArrayList<Candidate>(limit)
        for (i in 0 until array.length()) {
            if (list.size >= limit) break
            val item = array.getJSONObject(i)
            val w = item.optInt("width")
            val h = item.optInt("height")
            if (w <= 0 || h <= 0 || w.toFloat() / h < 1.6f) continue
            val full = item.optString("file_url")
            if (full.isBlank()) continue
            list.add(
                Candidate(
                    url = full,
                    thumbUrl = item.optString("preview_url").ifBlank { full },
                    title = "动漫 · safebooru #${item.optString("id")}"
                )
            )
        }
        return list
    }

    private fun customCandidates(limit: Int): List<Candidate> {
        val url = customUrl()
        if (url.isBlank()) return emptyList()
        // 自定义地址当成图片直链处理：原图和缩略图都用它
        return listOf(Candidate(url, url, "自定义图源"))
    }

    // ---- 内部 -------------------------------------------------------------------

    private fun downloadToPending(candidate: Candidate): Boolean {
        val bytes = httpGetBytes(candidate.url)
        if (bytes.size < MIN_IMAGE_BYTES) return false
        writePendingBytes(bytes, Meta(candidate.title, today(), candidate.url))
        Log.d(TAG, "downloaded ${bytes.size / 1024}KB ${candidate.title}")
        return true
    }

    private fun writePendingBytes(bytes: ByteArray, meta: Meta) {
        val tmp = File(dir, "pending.tmp")
        tmp.writeBytes(bytes)
        if (pendingFile.exists()) pendingFile.delete()
        tmp.renameTo(pendingFile)
        writeMeta(PENDING, meta)
    }

    private fun promotePending() {
        runCatching {
            val backup = File(dir, "current.bak")
            if (currentFile.exists()) {
                backup.delete()
                currentFile.renameTo(backup)
            }
            if (pendingFile.renameTo(currentFile)) {
                backup.delete()
                val meta = readMeta(PENDING)
                if (meta != null) writeMeta(CURRENT, meta)
                prefs.edit().remove(KEY_PENDING).apply()
            } else if (backup.exists()) {
                // 提升失败就回滚，绝不把旧图弄丢
                backup.renameTo(currentFile)
            }
        }.onFailure { Log.w(TAG, "promote failed: ${it.message}") }
    }

    private fun decodeCurrent(): Loaded {
        val meta = readMeta(CURRENT)
        lastKnownMeta = meta
        if (!currentFile.exists() || currentFile.length() == 0L) {
            return Loaded(null, null, meta)
        }
        val frame = decode(currentFile, 1280)
        // 背景用极小的缩略图，放大后天然就是"糊"的，省内存也省 CPU（低配电视跑不动高斯模糊）
        val backdrop = decode(currentFile, 48)
        return Loaded(frame, backdrop, meta)
    }

    private fun decode(file: File, targetWidth: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.path, opts)
    }.getOrNull()

    private fun hasNetwork(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetworkInfo?.isConnected == true
    }.getOrDefault(false)

    private fun httpGet(url: String): String = httpGetBytes(url).toString(Charsets.UTF_8)

    private fun httpGetBytes(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun bingEndpoint(market: String, count: Int = 1): String =
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=$count&mkt=$market"

    private fun today(): String =
        SimpleDateFormat("yyyy/MM/dd", Locale.CHINA).format(Date())

    private fun readMeta(slot: String): Meta? = runCatching {
        val raw = prefs.getString(if (slot == CURRENT) KEY_CURRENT else KEY_PENDING, null)
            ?: return@runCatching null
        val obj = JSONObject(raw)
        Meta(obj.getString("title"), obj.getString("date"), obj.getString("url"))
    }.getOrNull()

    private fun writeMeta(slot: String, meta: Meta) {
        val json = JSONObject()
            .put("title", meta.title)
            .put("date", meta.date)
            .put("url", meta.url)
            .toString()
        prefs.edit()
            .putString(if (slot == CURRENT) KEY_CURRENT else KEY_PENDING, json)
            .apply()
    }

    companion object {
        private const val TAG = "Wallpaper"
        private const val SAFEBOORU_API =
            "https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 6.0.1; MiTV4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0 Mobile Safari/537.36"
        private const val MIN_IMAGE_BYTES = 20_000
        private const val KEY_CURRENT = "meta_current"
        private const val KEY_PENDING = "meta_pending"
        private const val KEY_SOURCE = "source"
        private const val KEY_CUSTOM_URL = "custom_url"
        private const val CURRENT = "current"
        private const val PENDING = "pending"

        @Volatile
        private var instance: WallpaperRepository? = null

        fun get(context: Context): WallpaperRepository =
            instance ?: synchronized(this) {
                instance ?: WallpaperRepository(context.applicationContext).also { instance = it }
            }
    }
}
