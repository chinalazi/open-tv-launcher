package com.openlauncher.tv.ui.home

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * 阿里校时。
 *
 * 只用阿里自家的两个时间源，不接第三方：
 *
 * - **NTP 优先**（`ntp.aliyun.com` / `ntp1` / `ntp2`，UDP 123）—— 精度最高，走的是系统对时的同一套协议；
 * - **HTTP 兜底**（`api.m.taobao.com` 的 `mtop.common.getTimestamp`）—— 部分路由器封掉 UDP 123 时还能出时间。
 *
 * [fetch] 是阻塞的，必须放在 IO 线程里调。
 */
object AliTimeSync {

    private const val TAG = "AliTimeSync"

    private const val NTP_PORT = 123

    /** NTP 计时元年在 1900 年，Unix 元年在 1970 年，差 2208988800 秒。 */
    private const val NTP_UNIX_EPOCH_DIFF = 2_208_988_800L

    private val NTP_HOSTS = arrayOf("ntp.aliyun.com", "ntp1.aliyun.com", "ntp2.aliyun.com")

    private const val HTTP_TAOBAO =
        "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"

    private val HTTP_TIME_PATTERN = Regex("\"t\"\\s*:\\s*\"?(\\d{10,})")

    /** 校时结果：阿里认为此刻是 [epochMillis] 毫秒，取自 [source]。 */
    data class Result(val epochMillis: Long, val source: String)

    /**
     * 依次试 NTP 三台、再试 HTTP。
     *
     * @return 全部失败返回 null —— 调用方据此显示"连不上"，不要编一个时间出来。
     */
    fun fetch(): Result? {
        for (host in NTP_HOSTS) {
            ntp(host)?.let { return it }
        }
        http(HTTP_TAOBAO, "api.m.taobao.com")?.let { return it }
        Log.w(TAG, "all time sources failed")
        return null
    }

    // ---- NTP -------------------------------------------------------------------

    private fun ntp(host: String, timeoutMs: Int = 2500): Result? {
        var socket: DatagramSocket? = null
        return try {
            // 48 字节请求包：LI=0 / VN=3 / Mode=3(客户端)，其余全 0
            val request = ByteArray(48).also { it[0] = 0x1B.toByte() }
            socket = DatagramSocket().also { it.soTimeout = timeoutMs }
            val address = InetAddress.getByName(host)

            val sendAt = System.currentTimeMillis()
            socket.send(DatagramPacket(request, request.size, address, NTP_PORT))

            val buffer = ByteArray(48)
            socket.receive(DatagramPacket(buffer, buffer.size))
            val receivedAt = System.currentTimeMillis()

            val mode = buffer[0].toInt() and 0x07
            val stratum = buffer[1].toInt() and 0xFF
            // mode 4 = 服务器应答；stratum 0 = Kiss-o'-Death（被限流/拒绝）
            if (mode != 4 || stratum == 0) {
                Log.w(TAG, "ntp $host rejected: mode=$mode stratum=$stratum")
                return null
            }

            val seconds = readUInt32(buffer, 40)
            val fraction = readUInt32(buffer, 44)
            if (seconds == 0L) return null

            val serverMillis = (seconds - NTP_UNIX_EPOCH_DIFF) * 1000L +
                fraction * 1000L / (1L shl 32)
            // 服务器时间大致对应往返的中点，补一半 RTT 抵消网络延迟
            Result(serverMillis + (receivedAt - sendAt) / 2, host)
        } catch (e: Throwable) {
            Log.w(TAG, "ntp $host failed: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    // ---- HTTP ------------------------------------------------------------------

    private fun http(url: String, label: String, timeoutMs: Int = 3000): Result? {
        var conn: HttpURLConnection? = null
        return try {
            val sendAt = System.currentTimeMillis()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
                setRequestProperty("Accept", "*/*")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val receivedAt = System.currentTimeMillis()

            val millis = HTTP_TIME_PATTERN.find(body)?.groupValues?.get(1)?.toLongOrNull()
            if (millis == null) {
                Log.w(TAG, "http $label: no timestamp in body")
                return null
            }
            Result(millis + (receivedAt - sendAt) / 2, label)
        } catch (e: Throwable) {
            Log.w(TAG, "http $label failed: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
