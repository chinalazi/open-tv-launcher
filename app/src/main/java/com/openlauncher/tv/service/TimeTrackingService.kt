package com.openlauncher.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.parental.ParentalState
import com.openlauncher.tv.ui.lock.LockScreenLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 儿童模式计时服务。
 * 计时每秒发生在内存里（StateFlow），只在关键节点落盘，避免每秒写 DataStore。
 */
class TimeTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null
    private lateinit var app: LauncherApplication

    override fun onCreate() {
        super.onCreate()
        app = application as LauncherApplication
        createNotificationChannel()
        startInForeground()
        startTimerLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = buildNotification("儿童防沉迷监护中")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = scope.launch {
            var whitelist: Set<String> = emptySet()
            var foregroundPkg: String? = null
            var ticks = 0
            var lastLockNoticeAt = 0L
            var lockNotified = false

            while (isActive) {
                delay(1_000L)

                val state = app.parental.state.value
                if (!state.kidsMode) {
                    stopSelf()
                    break
                }

                ticks++
                // 白名单与前台应用每 5 秒刷新一次，避免每秒查询系统
                if (ticks % 5 == 0) {
                    whitelist = runCatching {
                        app.database.launcherDao().getKidWhitelistPackages().toSet()
                    }.getOrDefault(emptySet())
                    foregroundPkg = if (whitelist.isEmpty()) {
                        null
                    } else {
                        runCatching { ForegroundApp.currentForegroundPackage(this@TimeTrackingService) }
                            .getOrNull()
                    }
                }

                val screenOn = getSystemService(PowerManager::class.java)?.isInteractive == true
                val current = foregroundPkg
                val exemptByWhitelist = current != null && whitelist.contains(current)

                if (screenOn && !exemptByWhitelist && !state.locked) {
                    val justLocked = app.parental.consumeOneSecond()
                    val now = System.currentTimeMillis()
                    if (justLocked && now - lastLockNoticeAt > 10_000L) {
                        lastLockNoticeAt = now
                        notifyText("观看时长已用完，请在桌面输入 PIN 码解锁")
                    }
                }

                if (ticks % 30 == 0 && !state.locked) {
                    notifyText(describeRemaining(app.parental.state.value))
                }

                // 进入锁定态：把锁屏盖到当前前台应用之上（体感上比"回桌面才锁"及时得多）
                val latest = app.parental.state.value
                if (latest.locked && !lockNotified) {
                    lockNotified = true
                    LockScreenLauncher.show(this@TimeTrackingService)
                } else if (!latest.locked) {
                    lockNotified = false
                }
            }
        }
    }

    private fun describeRemaining(state: ParentalState): String {
        if (state.unlimitedToday) return "今日已不受限"
        val remain = state.remainingSec
        return "今日剩余 %d 分 %02d 秒".format(remain / 60, remain % 60)
    }

    private fun notifyText(text: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("儿童健康观看助手")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "儿童防沉迷服务",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "parental_control_channel"
        const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, TimeTrackingService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stopService(context: Context) {
            runCatching {
                context.stopService(Intent(context, TimeTrackingService::class.java))
            }
        }
    }
}
