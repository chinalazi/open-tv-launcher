package com.openlauncher.tv.ui.lock

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.parental.PinResult
import com.openlauncher.tv.databinding.ActivityLockBinding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 超时锁屏。由 [LockScreenLauncher] 从计时服务或桌面拉起，
 * 因为它是独立 Activity，可以盖在正在播放的视频之上（Android 10 以下不受后台启动限制）。
 */
class LockScreenActivity : FragmentActivity() {

    private lateinit var binding: ActivityLockBinding
    private val launcherApp: LauncherApplication get() = application as LauncherApplication
    private var verifying = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )

        binding.pinPad.setOnPinComplete { pin -> verify(pin) }
        binding.btnLockExtra.setOnClickListener {
            launcherApp.parental.grantExtraMinutes(30)
            toast("已增加 30 分钟")
        }
        binding.btnLockUnlimited.setOnClickListener {
            launcherApp.parental.grantUnlimitedToday()
            toast("今日不再限制观看时长")
        }

        observeLockState()

        if (!isLockedNow()) {
            finish()
            return
        }
        binding.pinPad.focusFirst()
    }

    override fun onResume() {
        super.onResume()
        if (!isLockedNow()) {
            finish()
        }
    }

    private fun isLockedNow(): Boolean {
        val state = launcherApp.parental.state.value
        return state.kidsMode && state.locked
    }

    private fun observeLockState() {
        lifecycleScope.launch {
            launcherApp.parental.state
                .map { it.kidsMode && it.locked }
                .distinctUntilChanged()
                .collect { locked -> if (!locked) finish() }
        }
    }

    private fun verify(pin: String) {
        if (verifying) return
        verifying = true
        binding.pinPad.clear()
        binding.tvLockHint.text = "校验中…"

        lifecycleScope.launch {
            when (val result = launcherApp.parental.verifyPin(pin)) {
                PinResult.Ok -> {
                    verifying = false
                    binding.pinPad.visibility = View.GONE
                    binding.lockActions.visibility = View.VISIBLE
                    binding.tvLockHint.text = "验证成功，请选择继续观看的时长"
                    binding.btnLockExtra.requestFocus()
                }

                is PinResult.Wrong -> {
                    verifying = false
                    binding.pinPad.clear()
                    binding.tvLockHint.text = "PIN 错误，还可尝试 ${result.attemptsLeft} 次"
                }

                is PinResult.LockedOut -> {
                    verifying = false
                    binding.pinPad.clear()
                    val minutes = ((result.untilMs - System.currentTimeMillis()) / 60_000L + 1)
                        .coerceAtLeast(1L)
                    binding.tvLockHint.text = "尝试过多，请 $minutes 分钟后再试"
                }

                PinResult.NotSet -> {
                    verifying = false
                    binding.pinPad.clear()
                    binding.tvLockHint.text = "尚未设置家长 PIN，无法解锁"
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_ESCAPE -> return true
        }
        if (binding.pinPad.visibility == View.VISIBLE && binding.pinPad.handleKey(keyCode)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("锁屏界面按返回键无效")
    override fun onBackPressed() {
        // 不允许返回
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
