package com.openlauncher.tv.ui.lock

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.parental.PinResult
import com.openlauncher.tv.ui.common.AmbientBackdropView
import com.openlauncher.tv.ui.common.PinPad
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 超时锁屏。由 [LockScreenLauncher] 从计时服务或桌面拉起，
 * 因为它是独立 Activity，可以盖在正在播放的视频之上（Android 10 以下不受后台启动限制）。
 */
class LockScreenActivity : FragmentActivity() {

    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    private lateinit var pinPad: PinPad
    private lateinit var hintView: TextView
    private lateinit var actionsView: View
    private lateinit var extraButton: TextView
    private lateinit var metaView: TextView
    private lateinit var clockView: TextView

    private var verifying = false

    private val ticker = Handler(Looper.getMainLooper())
    private val tickerTask = object : Runnable {
        override fun run() {
            updateMeta()
            ticker.postDelayed(this, 30_000L)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        pinPad = findViewById(R.id.pin_pad)
        hintView = findViewById(R.id.tv_lock_hint)
        actionsView = findViewById(R.id.lock_actions)
        extraButton = findViewById(R.id.btn_lock_extra)
        metaView = findViewById(R.id.tv_lock_meta)
        clockView = findViewById(R.id.tv_lock_clock)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )

        findViewById<AmbientBackdropView>(R.id.ambient_backdrop)
            .setGlowColor(0xFFFF2D55.toInt(), false)

        pinPad.setOnPinComplete { pin -> verify(pin) }
        extraButton.setOnClickListener {
            launcherApp.parental.grantExtraMinutes(30)
            toast("已增加 30 分钟")
        }
        findViewById<TextView>(R.id.btn_lock_unlimited).setOnClickListener {
            launcherApp.parental.grantUnlimitedToday()
            toast("今日不再限制观看时长")
        }

        observeLockState()
        updateMeta()

        if (!isLockedNow()) {
            finish()
            return
        }
        pinPad.focusFirst()
    }

    override fun onResume() {
        super.onResume()
        if (!isLockedNow()) {
            finish()
            return
        }
        ticker.removeCallbacks(tickerTask)
        ticker.post(tickerTask)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tickerTask)
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

    private fun updateMeta() {
        val state = launcherApp.parental.state.value
        val used = state.usedTodaySec
        metaView.text = String.format(
            Locale.US, "儿童模式 · 今日已看 %d:%02d", used / 60, used % 60
        )
        clockView.text = SimpleDateFormat("HH:mm · M 月 d 日", Locale.CHINESE).format(Date())
    }

    private fun verify(pin: String) {
        if (verifying) return
        verifying = true
        pinPad.clear()
        pinPad.setInputEnabled(false)
        hintView.text = "校验中…"

        lifecycleScope.launch {
            when (val result = launcherApp.parental.verifyPin(pin)) {
                PinResult.Ok -> {
                    verifying = false
                    actionsView.visibility = View.VISIBLE
                    hintView.text = "验证成功，请选择继续观看的时长"
                    extraButton.requestFocus()
                }

                is PinResult.Wrong -> {
                    verifying = false
                    pinPad.setInputEnabled(true)
                    pinPad.clear()
                    hintView.text = "PIN 错误，还可尝试 ${result.attemptsLeft} 次"
                }

                is PinResult.LockedOut -> {
                    verifying = false
                    pinPad.setInputEnabled(true)
                    pinPad.clear()
                    val minutes = ((result.untilMs - System.currentTimeMillis()) / 60_000L + 1)
                        .coerceAtLeast(1L)
                    hintView.text = "尝试过多，请 $minutes 分钟后再试"
                }

                PinResult.NotSet -> {
                    verifying = false
                    pinPad.setInputEnabled(true)
                    pinPad.clear()
                    hintView.text = "尚未设置家长 PIN，无法解锁"
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_ESCAPE -> if (actionsView.visibility != View.VISIBLE) return true
        }
        if (pinPad.visibility == View.VISIBLE && pinPad.handleKey(keyCode)) {
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
