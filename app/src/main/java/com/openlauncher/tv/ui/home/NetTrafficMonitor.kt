package com.openlauncher.tv.ui.home

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.net.TrafficStats
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * 联网进程采样器。
 *
 * 数据来自两个内核文件，都不需要任何权限（Android 8 之前普通应用可读，本机是 6.0.1）：
 * - `/proc/net/tcp`、`/proc/net/tcp6`：socket 表，每行带 uid，可以知道"谁在连谁"；
 *   我们把 uid 用 [PackageManager.getPackagesForUid] 翻成包名。
 * - `/proc/net/xt_qtaguid/stats`：按 uid 累计收发的字节数，隔一秒取差分就是实时速率。
 *
 * 关键约束：qtaguid 是累计值，**第一轮只能建基线**，从第二轮开始才有速率。
 * 采样放在独立线程上，1 秒一轮；读失败（内核没编 qtaguid、SELinux 拒读）时降级成
 * 只有连接、没有速率，界面上不要把 0 显示成"没流量"。
 */
class NetTrafficMonitor private constructor(context: Context) {

    /** 一条活跃连接（state = ESTABLISHED）。 */
    data class Conn(
        val uid: Int,
        val label: String,
        val pkg: String,
        val remote: String,
        val rate: Long,
        val isUp: Boolean,
        val inode: Long
    )

    /** 按进程聚合的速率，排行榜用。 */
    data class ProcRate(
        val uid: Int,
        val label: String,
        val pkg: String,
        val down: Long,
        val up: Long
    ) {
        val total: Long get() = down + up
    }

    data class Snapshot(
        val conns: List<Conn> = emptyList(),
        val procs: List<ProcRate> = emptyList(),
        val freshConns: List<Conn> = emptyList(),
        val totalDown: Long = 0,
        val totalUp: Long = 0,
        val sampling: Boolean = false,
        val hasRate: Boolean = false
    )

    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val handlerThread = HandlerThread("net-monitor").apply { start() }
    private val worker = Handler(handlerThread.looper)
    private val main = Handler(Looper.getMainLooper())

    private val labelCache = HashMap<Int, Pair<String, String>>()   // uid -> (label, pkg)
    private var prevBytes: Map<Int, LongArray>? = null               // uid -> [rx, tx]
    private var prevTotal: LongArray? = null                         // 整机 [rx, tx]
    private var smoothDown = 0.0                                     // 底栏用的平滑值
    private var smoothUp = 0.0
    private var prevInodes: Set<Long> = emptySet()
    private var running = false

    @Volatile
    private var listener: ((Snapshot) -> Unit)? = null

    fun start(onSnapshot: (Snapshot) -> Unit) {
        listener = onSnapshot
        if (running) return
        running = true
        worker.post(sampleLoop)
    }

    fun stop() {
        running = false
        listener = null
        worker.removeCallbacks(sampleLoop)
    }

    fun release() {
        stop()
        handlerThread.quit()
    }

    private val sampleLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val snapshot = runCatching { sample() }.getOrElse {
                Log.w(TAG, "采样失败: ${it.javaClass.simpleName} ${it.message}")
                Snapshot()
            }
            main.post { if (running) listener?.invoke(snapshot) }
            worker.postDelayed(this, INTERVAL_MS)
        }
    }

    // ---- 采样 -------------------------------------------------------------------

    private fun sample(): Snapshot {
        val rawConns = readConnections()
        val byUid = rawConns.groupBy { it.uid }

        val stats = readUidStats(byUid.keys)
        val hasRate = stats != null && prevBytes != null

        // 速率 = 本轮累计 - 上轮累计。计数回绕或明显异常时视为 0，不要把负数画到界面上
        val delta = HashMap<Int, LongArray>()
        if (stats != null) {
            val prev = prevBytes
            if (prev != null) {
                for ((uid, cur) in stats) {
                    val old = prev[uid] ?: continue
                    val dRx = cur[0] - old[0]
                    val dTx = cur[1] - old[1]
                    if (dRx >= 0 && dTx >= 0) delta[uid] = longArrayOf(dRx, dTx)
                }
            }
            prevBytes = stats
        }

        // 全局速率：getTotalRxBytes 不受 uid 限制，per-uid 拿不到时用它兜底底栏
        val nowRx = TrafficStats.getTotalRxBytes()
        val nowTx = TrafficStats.getTotalTxBytes()
        val prevRx = prevTotal?.get(0)
        val prevTx = prevTotal?.get(1)
        val globalDown = if (prevRx != null && nowRx > prevRx) nowRx - prevRx else 0L
        val globalUp = if (prevTx != null && nowTx > prevTx) nowTx - prevTx else 0L
        prevTotal = longArrayOf(nowRx, nowTx)

        val conns = ArrayList<Conn>(rawConns.size)
        val procs = ArrayList<ProcRate>()
        var totalDown = 0L
        var totalUp = 0L

        for ((uid, list) in byUid) {
            val (label, pkg) = nameOf(uid)
            val d = delta[uid]
            val down = d?.get(0) ?: 0L
            val up = d?.get(1) ?: 0L
            totalDown += down
            totalUp += up
            procs.add(ProcRate(uid, label, pkg, down, up))

            // 一个进程可能开着好几条连接（视频分片就是多个 CDN 长连接），
            // 速率均分到每条上，日志里每条看起来才像"这条连接的速度"
            val share = maxOf(1, list.size)
            val upHeavy = up > down
            for (c in list) {
                conns.add(
                    Conn(
                        uid = uid,
                        label = label,
                        pkg = pkg,
                        remote = c.remote,
                        rate = (down + up) / share,
                        isUp = upHeavy,
                        inode = c.inode
                    )
                )
            }
        }

        // 没有 socket 但仍有流量的（多播、后台 UDP），也要出现在排行榜里
        for ((uid, d) in delta) {
            if (byUid.containsKey(uid)) continue
            val (label, pkg) = nameOf(uid)
            totalDown += d[0]
            totalUp += d[1]
            procs.add(ProcRate(uid, label, pkg, d[0], d[1]))
        }

        // per-uid 完全拿不到时，底栏至少还能显示整机速率
        if (!hasRate) {
            totalDown = globalDown
            totalUp = globalUp
        }

        val inodes = conns.map { it.inode }.toSet()
        val fresh = conns.filter { it.inode !in prevInodes }
        prevInodes = inodes

        procs.sortByDescending { it.total }
        conns.sortByDescending { it.rate }

        // 底栏和曲线做一点平滑：原始值每秒跳得太厉害（视频缓冲、连接重建都会瞬间归零），
        // 平滑后才是用户对"网速"的直觉。日志行仍用瞬时值。
        smoothDown = if (smoothDown <= 0.0) totalDown.toDouble() else smoothDown * 0.55 + totalDown * 0.45
        smoothUp = if (smoothUp <= 0.0) totalUp.toDouble() else smoothUp * 0.55 + totalUp * 0.45

        return Snapshot(
            conns = conns,
            procs = procs,
            freshConns = fresh,
            totalDown = smoothDown.toLong(),
            totalUp = smoothUp.toLong(),
            sampling = true,
            hasRate = hasRate
        )
    }

    /**
     * 按 uid 读累计收发字节。
     *
     * 首选 `/proc/uid_stat/<uid>/tcp_rcv|tcp_snd`：这是老的内核按-uid TCP 计数，
     * **任何 uid 都能读**，正好是我们要的按进程流量（Android 7 之后这个目录被移除了）。
     *
     * 另外两条路都试过，都有坑：
     * - `TrafficStats.getUidRxBytes(uid)`：对别的进程恒返回 0，拿不到数；
     * - `/proc/net/xt_qtaguid/stats`：应用侧打开只能看到**自己**那两行，别的进程看不到。
     * 所以它们只作为兜底。
     */
    private fun readUidStats(uids: Set<Int>): Map<Int, LongArray>? {
        val map = HashMap<Int, LongArray>(uids.size)
        for (uid in uids) {
            val rcv = readProcLong("/proc/uid_stat/$uid/tcp_rcv")
            val snd = readProcLong("/proc/uid_stat/$uid/tcp_snd")
            if (rcv == null || snd == null) continue
            map[uid] = longArrayOf(rcv, snd)
        }
        if (map.isNotEmpty()) {
            uidStatWorks = true
            return map
        }
        if (!warnedNoUidStat) {
            warnedNoUidStat = true
            Log.w(TAG, "没有 /proc/uid_stat，改用 TrafficStats / qtaguid 兜底")
        }

        for (uid in uids) {
            val rx = runCatching { TrafficStats.getUidRxBytes(uid) }.getOrDefault(-1L)
            val tx = runCatching { TrafficStats.getUidTxBytes(uid) }.getOrDefault(-1L)
            if (rx <= 0 && tx <= 0) continue
            map[uid] = longArrayOf(rx, tx)
        }
        return map.ifEmpty { readQtaguid() }
    }

    private fun readProcLong(path: String): Long? =
        runCatching { File(path).readText().trim().toLong() }.getOrNull()

    // ---- /proc/net/tcp ----------------------------------------------------------

    private data class RawConn(val uid: Int, val remote: String, val inode: Long)

    private fun readConnections(): List<RawConn> {
        val out = ArrayList<RawConn>(64)
        parseTcp(File("/proc/net/tcp"), v6 = false, out = out)
        parseTcp(File("/proc/net/tcp6"), v6 = true, out = out)
        return out
    }

    private fun parseTcp(file: File, v6: Boolean, out: MutableList<RawConn>) {
        val reader = runCatching { RandomAccessFile(file, "r") }.getOrNull() ?: return
        reader.use { raf ->
            runCatching { raf.readLine() }   // 跳过表头
            while (true) {
                val line = raf.readLine() ?: break
                val f = line.trim().split(WHITESPACE)
                if (f.size < 10) continue
                // st：01 = ESTABLISHED，只有这种才代表真的在收发
                if (f[3] != "01") continue
                val uid = f[7].toIntOrNull() ?: continue
                val inode = f[9].toLongOrNull() ?: 0L
                val remote = formatRemote(f[2], v6)
                if (remote.isEmpty()) continue
                out.add(RawConn(uid, remote, inode))
            }
        }
    }

    /** `8E1FA8C0:1F90` -> `192.168.31.142:8080`；地址是 little-endian 的十六进制。 */
    private fun formatRemote(field: String, v6: Boolean): String {
        val parts = field.split(':')
        if (parts.size != 2) return ""
        val hexIp = parts[0]
        val port = parts[1].toIntOrNull(16) ?: return ""
        if (port == 0) return ""
        return if (!v6) {
            if (hexIp.length != 8) return ""
            val ip = hexToIpv4(hexIp) ?: return ""
            if (ip == "0.0.0.0" || ip.startsWith("127.")) return ""
            "$ip:$port"
        } else {
            if (hexIp.length != 32) return ""
            val ip = hexToIpv6(hexIp)
            if (ip == "::" || ip == "::1") return ""
            "$ip:$port"
        }
    }

    private fun hexToIpv4(hex: String): String? {
        if (hex.length != 8) return null
        val b = IntArray(4) { i -> hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null }
        // 内核按 host 字节序打印，反着读才是点分十进制
        return "${b[3]}.${b[2]}.${b[1]}.${b[0]}"
    }

    private fun hexToIpv6(hex: String): String {
        // 4 组 32 位，每组内部 little-endian
        val bytes = IntArray(16)
        for (g in 0 until 4) {
            val word = hex.substring(g * 8, g * 8 + 8)
            for (i in 0 until 4) {
                bytes[g * 4 + i] = word.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: 0
            }
            // 整组反转
            val t = bytes[g * 4]
            bytes[g * 4] = bytes[g * 4 + 3]
            bytes[g * 4 + 3] = t
            val t2 = bytes[g * 4 + 1]
            bytes[g * 4 + 1] = bytes[g * 4 + 2]
            bytes[g * 4 + 2] = t2
        }
        val groups = IntArray(8) { (bytes[it * 2] shl 8) or bytes[it * 2 + 1] }
        if (groups.all { it == 0 }) return "::"
        // IPv4-mapped（::ffff:a.b.c.d）还原成点分十进制：电视上一堆冒号根本认不出是哪台服务器
        if (groups[0] == 0 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0 &&
            groups[4] == 0 && groups[5] == 0xffff
        ) {
            return "${groups[6] shr 8}.${groups[6] and 0xff}.${groups[7] shr 8}.${groups[7] and 0xff}"
        }
        // 中间连续 0 段压缩成 ::（只压缩最长的一段，够用）
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLen && j - i > 1) { bestStart = i; bestLen = j - i }
                i = j
            } else i++
        }
        val sb = StringBuilder()
        for (g in 0 until 8) {
            if (g == bestStart) { sb.append("::"); continue }
            if (g > bestStart && bestStart >= 0 && g < bestStart + bestLen) continue
            if (sb.isNotEmpty() && !sb.endsWith(":")) sb.append(':')
            sb.append(Integer.toHexString(groups[g]))
        }
        return if (sb.isEmpty()) "::" else sb.toString()
    }

    // ---- /proc/net/xt_qtaguid/stats ---------------------------------------------

    /** 返回 null 表示这个内核没有 qtaguid（或者读不到），此时只能显示连接、没有速率。 */
    private fun readQtaguid(): Map<Int, LongArray>? {
        val file = File("/proc/net/xt_qtaguid/stats")
        val reader = runCatching { RandomAccessFile(file, "r") }.getOrNull() ?: run {
            if (!warnedNoStats) {
                warnedNoStats = true
                Log.w(TAG, "读不到 ${file.path}，速率将不可用")
            }
            return null
        }
        val map = HashMap<Int, LongArray>(32)
        var lines = 0
        reader.use { raf ->
            runCatching { raf.readLine() }
            while (true) {
                val line = raf.readLine() ?: break
                val f = line.trim().split(WHITESPACE)
                if (f.size < 8) continue
                // 不按 acct_tag 过滤：带 tag 的 socket 和不带 tag 的流量记在不同行，
                // 彼此不重叠，按 uid 把它们全加起来才是这个进程的真实总量。
                // （一开始只取 0x0 行，结果每个 uid 都是 0，界面上一片 0 B/s）
                val uid = f[3].toIntOrNull() ?: continue
                val rx = f[5].toLongOrNull() ?: continue
                val tx = f[7].toLongOrNull() ?: continue
                val acc = map.getOrPut(uid) { longArrayOf(0, 0) }
                acc[0] += rx
                acc[1] += tx
                lines++
            }
        }
        return map
    }

    private var warnedNoStats = false
    private var warnedNoUidStat = false
    private var uidStatWorks = false

    // ---- uid -> 包名 -------------------------------------------------------------

    private fun nameOf(uid: Int): Pair<String, String> =
        labelCache.getOrPut(uid) {
            if (uid < 10000) {
                // 1000 = system，0 = root，内核线程没有包名
                val label = when (uid) {
                    0 -> "kernel"
                    1000 -> "系统服务"
                    1001 -> "电话服务"
                    1013 -> "媒体服务"
                    2000 -> "adb"
                    else -> "uid $uid"
                }
                label to ""
            } else {
                val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
                val pkg = pkgs?.firstOrNull()
                if (pkg == null) {
                    "未知应用" to ""
                } else {
                    val label = runCatching {
                        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                    }.getOrDefault(pkg.substringAfterLast('.'))
                    label to pkg
                }
            }
        }

    companion object {
        private const val TAG = "NetTrafficMonitor"
        private const val INTERVAL_MS = 1000L
        private val WHITESPACE = Regex("\\s+")

        @Volatile
        private var instance: NetTrafficMonitor? = null

        fun get(context: Context): NetTrafficMonitor =
            instance ?: synchronized(this) {
                instance ?: NetTrafficMonitor(context).also { instance = it }
            }
    }
}
