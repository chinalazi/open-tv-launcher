package com.openlauncher.tv.ui.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.databinding.ActivityTimeSettingsBinding
import com.openlauncher.tv.ui.home.AliTimeSync
import com.openlauncher.tv.ui.home.AppTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 日期与时间。
 *
 * 这台电视上没有可用的系统「日期与时间」页面（`DATE_SETTINGS` 解析不到，
 * `com.xiaomi.mitv.settings/.entry.GeneralActivity` 会拉起但不绘制），
 * 所以校时自己做，做成两个动作：
 *
 * 1. **同步阿里时间** —— 向 `ntp.aliyun.com` 取一次标准时间。
 *    能写系统时钟就直接写（需要 SET_TIME 权限，本机固件没开放）；
 *    写不动就把差值记成偏移，只让 launcher 的时钟对上。
 * 2. **跟随系统** —— 清掉偏移，桌面回到系统时间。
 *
 * 为什么不做手工改年月日时分：遥控器上挪五个字段太难受，而且改的也只是桌面显示，
 * 系统时钟一样改不动 —— 那套表单纯属折腾。
 *
 * 关于真正改系统时钟：`/data/system/record.time` 虽然是 666，但 SELinux（Enforcing）
 * 不让 shell / 普通应用写，adb 里 `echo > record.time` 会直接 Permission denied，
 * 应用内更没有 `reboot` 权限。所以那条路只有在有 root 时才能用，见 develop.md。
 */
class TimeSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimeSettingsBinding

    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val dateFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderClocks()
            handler.postDelayed(this, 1000L)
        }
    }

    private var syncJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppTime.load(this)

        binding.btnSync.setOnClickListener { syncWithAli() }
        binding.btnFollowSystem.setOnClickListener { followSystem() }

        renderClocks()
        renderAliRow()
        renderHint(null)

        binding.btnSync.post { binding.btnSync.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        AppTime.load(this)
        renderClocks()
        renderAliRow()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
    }

    // ---- 显示 -------------------------------------------------------------------

    /** 大字是桌面时钟（带偏移），下面一行是系统时钟，两者不一样时一眼能看出。 */
    private fun renderClocks() {
        val shown = Date(AppTime.now())
        binding.tvNowTime.text = clockFormat.format(shown)
        binding.tvNowDate.text = dateFormat.format(shown)
        binding.valueSystemTime.text = clockFormat.format(Date(System.currentTimeMillis()))
    }

    private fun renderAliRow() {
        val at = AppTime.lastSyncAt()
        if (at == 0L) {
            binding.valueAliTime.text = "未同步"
            return
        }
        val source = AppTime.lastSyncSource().ifEmpty { "阿里校时" }
        binding.valueAliTime.text =
            "${clockFormat.format(Date(at))} · $source · ${describeDiff(AppTime.lastSyncDiff())}"
    }

    private fun renderHint(error: String?) {
        binding.tvTimeHint.text = error ?: buildString {
            val offset = AppTime.offset()
            if (offset != 0L) {
                append("现在桌面时间比系统${direction(offset)} ${formatSpan(abs(offset))}。\n")
            }
            append("「同步阿里时间」向 ntp.aliyun.com 取一次标准时间：")
            append("能改系统时钟就直接改，改不动就让桌面时钟先对齐。\n")
            append("「跟随系统」清掉对齐，桌面回到系统时钟。")
        }
    }

    // ---- 交互 -------------------------------------------------------------------

    private fun syncWithAli() {
        if (syncJob?.isActive == true) return

        binding.btnSync.text = "校时中…"
        binding.tvTimeHint.text = "正在连接阿里校时服务器（ntp.aliyun.com）…"

        syncJob = lifecycleScope.launch {
            // NTP 是阻塞 socket，必须离开主线程
            val result = withContext(Dispatchers.IO) { AliTimeSync.fetch() }

            binding.btnSync.text = "同步阿里时间"
            if (isFinishing || isDestroyed) return@launch

            if (result == null) {
                renderHint(
                    "连不上阿里校时服务器。\n" +
                        "先确认电视已联网（设置 → Wi-Fi 与网络），或者隔一会儿再试。\n" +
                        "桌面时钟不受影响，仍在跟随系统时间。"
                )
                return@launch
            }
            applySynced(result)
        }
    }

    private fun applySynced(result: AliTimeSync.Result) {
        val diff = result.epochMillis - System.currentTimeMillis()
        AppTime.recordSync(this, System.currentTimeMillis(), result.source, diff)

        if (abs(diff) < 1_000L) {
            AppTime.clearOffset(this)
            renderAll(
                "阿里时间与系统时钟一致（相差 ${abs(diff)} 毫秒），不需要校准。\n" +
                    "校时源：${result.source}"
            )
            toast("系统时间准确")
            return
        }

        // 先试真正写系统时钟；这台电视会失败，失败就只对桌面生效
        val error = AppTime.setSystemTime(this, result.epochMillis)
        if (error == null) {
            AppTime.clearOffset(this)
            renderAll(
                "系统时钟已按阿里校时改成 ${clockFormat.format(Date(result.epochMillis))}。\n" +
                    "校时源：${result.source}"
            )
            toast("系统时间已同步")
        } else {
            AppTime.setOffset(this, diff)
            renderAll(
                "已按阿里校时把桌面时钟调${direction(diff)} ${formatSpan(abs(diff))}，桌面立刻正确。\n" +
                    "系统时钟本身改不动（$error）——普通应用没有这个权限，需要 root 才行。\n" +
                    "校时源：${result.source}"
            )
            toast("桌面时钟已对齐阿里时间")
        }
    }

    private fun followSystem() {
        AppTime.clearOffset(this)
        renderAll(null)
        toast("桌面时间已恢复为系统时间")
    }

    private fun renderAll(hint: String?) {
        renderClocks()
        renderAliRow()
        renderHint(hint)
    }

    // ---- 文案 -------------------------------------------------------------------

    /** 正数 = 系统慢了（要往前拨），负数 = 系统快了。 */
    private fun direction(diff: Long): String = if (diff > 0) "慢" else "快"

    private fun describeDiff(diff: Long): String = when {
        abs(diff) < 1_000L -> "相差 ${abs(diff)} 毫秒"
        else -> "系统${direction(diff)} ${formatSpan(abs(diff))}"
    }

    private fun formatSpan(ms: Long): String {
        val seconds = ms / 1000.0
        return when {
            seconds < 60 -> String.format(Locale.US, "%.1f 秒", seconds)
            seconds < 3600 -> "${(seconds / 60).toInt()} 分 ${(seconds.toInt() % 60)} 秒"
            else -> "${(seconds / 3600).toInt()} 小时 ${((seconds.toInt() % 3600) / 60)} 分"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
