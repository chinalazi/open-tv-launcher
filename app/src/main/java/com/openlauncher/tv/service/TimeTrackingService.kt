package com.openlauncher.tv.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.ui.lock.ScreenLockActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class TimeTrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var timerJob: Job? = null

    companion object {
        const val CHANNEL_ID = "parental_control_channel"
        const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, TimeTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TimeTrackingService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("儿童防沉迷监护中..."))
        startTimerLoop()
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        val app = application as LauncherApplication

        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L) // 每秒心跳一次

                val isKidsMode = app.preferencesRepository.isKidsModeEnabled.first()
                if (!isKidsMode) {
                    stopSelf()
                    break
                }

                var remaining = app.preferencesRepository.remainingSeconds.first()
                if (remaining > 0) {
                    remaining--
                    app.preferencesRepository.updateRemainingSeconds(remaining)

                    // 剩余时间归零：触发防沉迷全屏锁屏
                    if (remaining == 0) {
                        triggerScreenLock()
                    }
                }
            }
        }
    }

    private fun triggerScreenLock() {
        val lockIntent = Intent(this, ScreenLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(lockIntent)
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("儿童健康观看助手")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "儿童防沉迷服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        serviceScope.cancel()
    }
}
