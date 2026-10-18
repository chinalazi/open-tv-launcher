package com.openlauncher.tv.app

import android.app.Application
import com.openlauncher.tv.data.local.AppDatabase
import com.openlauncher.tv.data.repository.AppRepository
import com.openlauncher.tv.data.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LauncherApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val database by lazy { AppDatabase.getDatabase(this, applicationScope) }
    val preferencesRepository by lazy { PreferencesRepository(this) }
    val appRepository by lazy { AppRepository(this, database.launcherDao()) }

    override fun onCreate() {
        super.onCreate()
        // 首次启动静默初始化已安装应用
        applicationScope.launch {
            appRepository.scanAndSyncInstalledApps()
        }
    }
}
