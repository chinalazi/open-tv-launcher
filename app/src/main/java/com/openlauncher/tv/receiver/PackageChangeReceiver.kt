package com.openlauncher.tv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openlauncher.tv.app.LauncherApplication
import kotlinx.coroutines.launch

class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? LauncherApplication ?: return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                app.applicationScope.launch {
                    runCatching { app.appRepository.scanAndSyncInstalledApps() }
                }
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                // 应用升级时系统会先发 REMOVED（带 EXTRA_REPLACING），此时不能删用户配置
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                val packageName = intent.data?.schemeSpecificPart
                if (!packageName.isNullOrEmpty()) {
                    app.applicationScope.launch {
                        runCatching { app.database.launcherDao().deleteAppsByPackage(packageName) }
                    }
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                app.applicationScope.launch {
                    runCatching { app.appRepository.scanAndSyncInstalledApps() }
                }
            }
        }
    }
}
