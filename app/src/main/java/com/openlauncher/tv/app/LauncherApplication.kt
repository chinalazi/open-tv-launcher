package com.openlauncher.tv.app

import android.app.Application
import com.openlauncher.tv.data.local.AppDatabase
import com.openlauncher.tv.data.repository.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LauncherApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.getDatabase(this) }
    val appRepository by lazy { AppRepository(this, database.launcherDao()) }

    override fun onCreate() {
        super.onCreate()
        // 启动后异步对齐一次应用列表（安装/卸载时另有广播增量处理）
        applicationScope.launch {
            runCatching { appRepository.scanAndSyncInstalledApps() }
        }
    }
}
