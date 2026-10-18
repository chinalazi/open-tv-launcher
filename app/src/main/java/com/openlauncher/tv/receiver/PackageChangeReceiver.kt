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
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED -> {
                app.applicationScope.launch {
                    app.appRepository.scanAndSyncInstalledApps()
                }
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                val packageName = intent.data?.schemeSpecificPart
                if (!packageName.isNullOrEmpty()) {
                    app.applicationScope.launch {
                        app.database.launcherDao().deleteAppsByPackage(packageName)
                    }
                }
            }
        }
    }
}
