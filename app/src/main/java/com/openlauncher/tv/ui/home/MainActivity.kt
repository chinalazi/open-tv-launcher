package com.openlauncher.tv.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.parental.ParentalRepository
import com.openlauncher.tv.data.parental.ParentalState
import com.openlauncher.tv.data.parental.PinResult
import com.openlauncher.tv.databinding.ActivityMainBinding
import com.openlauncher.tv.service.TimeTrackingService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    private var lockLayerShown = false
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

        setupLockPad()
        setupLockActions()
        observeParentalState()
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // 回到桌面（含按 HOME 键）时重新评估锁定状态：锁屏遮罩会立刻回到最前
        lockLayerShown = false
        applyState(launcherApp.parental.state.value)
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

        val showLock = state.kidsMode && state.locked
        binding.lockLayer.visibility = if (showLock) View.VISIBLE else View.GONE
        binding.homeContainer.descendantFocusability = if (showLock) {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        if (showLock && !lockLayerShown) {
            binding.pinPad.clear()
            binding.pinPad.visibility = View.VISIBLE
            binding.lockActions.visibility = View.GONE
            binding.tvLockHint.text = "请家长输入 ${ParentalRepository.PIN_LENGTH} 位 PIN 码解锁"
            binding.pinPad.focusFirst()
        } else if (!showLock && lockLayerShown) {
            binding.pinPad.clear()
        }
        lockLayerShown = showLock
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
    }

    // ---- 锁屏交互 ---------------------------------------------------------------

    private fun setupLockPad() {
        binding.pinPad.setOnPinComplete { pin -> verifyPin(pin) }
    }

    private fun verifyPin(pin: String) {
        lifecycleScope.launch {
            when (val result = launcherApp.parental.verifyPin(pin)) {
                PinResult.Ok -> {
                    binding.pinPad.clear()
                    binding.pinPad.visibility = View.GONE
                    binding.lockActions.visibility = View.VISIBLE
                    binding.tvLockHint.text = "验证成功，请选择继续观看的时长"
                    binding.btnLockExtra.requestFocus()
                }

                is PinResult.Wrong -> {
                    binding.pinPad.clear()
                    toast("PIN 码错误，还可尝试 ${result.attemptsLeft} 次")
                }

                is PinResult.LockedOut -> {
                    binding.pinPad.clear()
                    val minutes = ((result.untilMs - System.currentTimeMillis()) / 60_000L + 1)
                        .coerceAtLeast(1L)
                    toast("尝试次数过多，请 $minutes 分钟后再试")
                }

                PinResult.NotSet -> {
                    binding.pinPad.clear()
                    toast("尚未设置家长 PIN 码，请先到设置里设置")
                }
            }
        }
    }

    private fun setupLockActions() {
        binding.btnLockExtra.setOnClickListener {
            launcherApp.parental.grantExtraMinutes(30)
            toast("已增加 30 分钟")
        }
        binding.btnLockUnlimited.setOnClickListener {
            launcherApp.parental.grantUnlimitedToday()
            toast("今日不再限制观看时长")
        }
    }

    // ---- 按键 -------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (lockLayerShown) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_ESCAPE -> return true
            }
            if (binding.pinPad.visibility == View.VISIBLE && binding.pinPad.handleKey(keyCode)) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("桌面根界面按返回键不退出")
    override fun onBackPressed() {
        // 作为默认桌面，Back 不退出
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
