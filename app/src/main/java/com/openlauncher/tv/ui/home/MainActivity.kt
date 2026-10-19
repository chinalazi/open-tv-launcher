package com.openlauncher.tv.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.parental.ParentalState
import com.openlauncher.tv.databinding.ActivityMainBinding
import com.openlauncher.tv.service.TimeTrackingService
import com.openlauncher.tv.ui.lock.LockScreenLauncher
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    private var trackingServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.home_container, HomeFragment())
                .commit()
        }

        observeParentalState()
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // 回到桌面时若仍处于锁定状态，立刻把锁屏盖回来（防止按 HOME 绕过）
        val state = launcherApp.parental.state.value
        if (state.kidsMode && state.locked) {
            LockScreenLauncher.show(this)
        }
    }

    // ---- 防沉迷状态 -------------------------------------------------------------

    private fun observeParentalState() {
        lifecycleScope.launch {
            launcherApp.parental.state
                .map { Triple(it.kidsMode, it.locked, it.remainingSec) }
                .distinctUntilChanged()
                .collect { applyState(launcherApp.parental.state.value) }
        }
    }

    private fun applyState(state: ParentalState) {
        updateKidsStatus(state)
        updateTrackingService(state.kidsMode)
    }

    private fun updateKidsStatus(state: ParentalState) {
        if (!state.kidsMode) {
            binding.tvKidsStatus.visibility = View.GONE
            return
        }
        binding.tvKidsStatus.visibility = View.VISIBLE
        val warning = state.remainingSec in 1..180
        binding.tvKidsStatus.setTextColor(
            ContextCompat.getColor(this, if (warning) R.color.status_warn else R.color.status_normal)
        )
        binding.tvKidsStatus.text = when {
            state.unlimitedToday -> "今日不限时"
            else -> {
                val remain = state.remainingSec
                "剩余 ${remain / 60}:${(remain % 60).toString().padStart(2, '0')}"
            }
        }
    }

    private fun updateTrackingService(kidsMode: Boolean) {
        if (kidsMode == trackingServiceRunning) return
        trackingServiceRunning = kidsMode
        if (kidsMode) {
            TimeTrackingService.startService(this)
        } else {
            TimeTrackingService.stopService(this)
        }
        Log.d(TAG, "trackingService running=$kidsMode")
    }

    // ---- 其他 -------------------------------------------------------------------

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001) }
        }
    }

    @Deprecated("桌面根界面按返回键不退出")
    override fun onBackPressed() {
        // 作为默认桌面，Back 不退出
    }

    companion object {
        private const val TAG = "OpenTVMain"
    }
}
