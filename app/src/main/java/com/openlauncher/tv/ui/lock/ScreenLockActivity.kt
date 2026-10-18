package com.openlauncher.tv.ui.lock

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.databinding.ActivityScreenLockBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ScreenLockActivity : FragmentActivity() {

    private lateinit var binding: ActivityScreenLockBinding
    private val enteredPin = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupKeypad()
    }

    private fun setupKeypad() {
        val numberButtons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3, binding.btn4,
            binding.btn5, binding.btn6, binding.btn7, binding.btn8, binding.btn9
        )

        for (btn in numberButtons) {
            btn.setOnClickListener {
                if (enteredPin.length < 4) {
                    enteredPin.append((it as Button).text)
                    updatePinDisplay()
                }
            }
        }

        binding.btnClear.setOnClickListener {
            enteredPin.clear()
            updatePinDisplay()
        }

        binding.btnUnlock.setOnClickListener {
            checkPinAndUnlock()
        }
    }

    private fun updatePinDisplay() {
        val stars = "*".repeat(enteredPin.length)
        val placeholders = "-".repeat(4 - enteredPin.length)
        binding.tvPinDisplay.text = "$stars$placeholders"
    }

    private fun checkPinAndUnlock() {
        val app = application as LauncherApplication
        val input = enteredPin.toString()

        lifecycleScope.launch {
            val isValid = app.preferencesRepository.verifyPin(input)
            if (isValid) {
                // 验证通过，延长单次观看时长（恢复设定的单次时长）
                val limitMinutes = app.preferencesRepository.singleLimitMinutes.first()
                app.preferencesRepository.updateRemainingSeconds(limitMinutes * 60)
                Toast.makeText(this@ScreenLockActivity, "PIN码验证成功，已延长时间", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this@ScreenLockActivity, "PIN码错误，请重试", Toast.LENGTH_SHORT).show()
                enteredPin.clear()
                updatePinDisplay()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 拦截返回键，强制停留在锁屏页面
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
