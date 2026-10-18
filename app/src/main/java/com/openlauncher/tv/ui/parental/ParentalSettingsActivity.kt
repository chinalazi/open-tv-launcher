package com.openlauncher.tv.ui.parental

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.parental.ParentalRepository
import com.openlauncher.tv.data.parental.ParentalState
import com.openlauncher.tv.data.parental.PinResult
import com.openlauncher.tv.databinding.ActivityParentalSettingsBinding
import com.openlauncher.tv.service.TimeTrackingService
import kotlinx.coroutines.launch

/**
 * 家长控制中心。进入前必须通过 PIN 校验（首次尚未设置 PIN 时除外），
 * 避免儿童直接关掉儿童模式或重置密码。
 */
class ParentalSettingsActivity : FragmentActivity() {

    private lateinit var binding: ActivityParentalSettingsBinding
    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    private enum class GateMode { ENTER, CHANGE_OLD, CHANGE_NEW }

    private var gateMode = GateMode.ENTER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentalSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.gatePad.setOnPinComplete { pin -> onPinEntered(pin) }
        setupContentActions()
        observeState()

        if (launcherApp.parental.state.value.pinSet) {
            showGate(GateMode.ENTER)
        } else {
            showContent()
            toast("尚未设置家长 PIN，请先设置后再开启儿童模式")
        }
    }

    // ---- 状态 -------------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            launcherApp.parental.state.collect { state ->
                if (binding.contentLayer.visibility == View.VISIBLE) renderContent(state)
            }
        }
    }

    private fun renderContent(state: ParentalState) {
        binding.switchKidsMode.setOnCheckedChangeListener(null)
        binding.switchKidsMode.isChecked = state.kidsMode
        binding.switchKidsMode.setOnCheckedChangeListener { _, checked -> onKidsModeToggled(checked) }

        binding.tvPinStatus.text = if (state.pinSet) "家长 PIN：已设置" else "家长 PIN：未设置"
        val used = state.usedTodaySec / 60
        binding.tvLimitSummary.text = buildString {
            append("单次 ${state.singleLimitMin} 分钟")
            append(" · 每日上限 ${state.dailyLimitMin} 分钟")
            append(" · 今日已看 $used 分钟")
            if (state.unlimitedToday) {
                append(" · 今日已解锁不限制")
            } else {
                val remain = state.remainingSec
                append(" · 剩余 ${remain / 60} 分 ${remain % 60} 秒")
            }
        }
    }

    private fun setupContentActions() {
        binding.switchKidsMode.setOnCheckedChangeListener { _, checked -> onKidsModeToggled(checked) }

        binding.btnSingle15.setOnClickListener { setSingleLimit(15) }
        binding.btnSingle30.setOnClickListener { setSingleLimit(30) }
        binding.btnSingle45.setOnClickListener { setSingleLimit(45) }
        binding.btnSingle60.setOnClickListener { setSingleLimit(60) }

        binding.btnDaily60.setOnClickListener { setDailyLimit(60) }
        binding.btnDaily120.setOnClickListener { setDailyLimit(120) }
        binding.btnDaily180.setOnClickListener { setDailyLimit(180) }
        binding.btnDaily240.setOnClickListener { setDailyLimit(240) }

        binding.btnResetToday.setOnClickListener {
            launcherApp.parental.resetToday()
            toast("已重置今日计时")
        }

        binding.btnChangePin.setOnClickListener {
            showGate(GateMode.CHANGE_OLD)
        }

        binding.btnUsageAccess.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                toast("该系统不支持打开使用情况访问设置")
            }
        }
    }

    private fun onKidsModeToggled(checked: Boolean) {
        val state = launcherApp.parental.state.value
        if (checked && !state.pinSet) {
            toast("请先设置家长 PIN 码")
            binding.switchKidsMode.setOnCheckedChangeListener(null)
            binding.switchKidsMode.isChecked = false
            binding.switchKidsMode.setOnCheckedChangeListener { _, c -> onKidsModeToggled(c) }
            return
        }
        launcherApp.parental.setKidsMode(checked)
        if (checked) {
            TimeTrackingService.startService(this)
            toast("儿童模式已开启")
        } else {
            TimeTrackingService.stopService(this)
            toast("儿童模式已关闭")
        }
    }

    private fun setSingleLimit(minutes: Int) {
        launcherApp.parental.setSingleLimitMinutes(minutes)
        toast("单次观看时长：$minutes 分钟")
    }

    private fun setDailyLimit(minutes: Int) {
        launcherApp.parental.setDailyLimitMinutes(minutes)
        toast("每日上限：$minutes 分钟")
    }

    // ---- PIN 门禁 ---------------------------------------------------------------

    private fun showGate(mode: GateMode) {
        gateMode = mode
        binding.gateLayer.visibility = View.VISIBLE
        binding.contentLayer.visibility = View.GONE
        binding.gatePad.clear()
        binding.gatePad.maxLength = ParentalRepository.PIN_LENGTH
        binding.tvGateTitle.text = when (mode) {
            GateMode.ENTER -> "家长验证"
            GateMode.CHANGE_OLD -> "修改家长 PIN"
            GateMode.CHANGE_NEW -> "设置新的 PIN"
        }
        binding.tvGateHint.text = when (mode) {
            GateMode.ENTER -> "请输入 4 位家长 PIN 码"
            GateMode.CHANGE_OLD -> "请先输入当前 PIN 码"
            GateMode.CHANGE_NEW -> "请输入新的 4 位 PIN 码"
        }
        binding.gatePad.focusFirst()
    }

    private fun showContent() {
        binding.gateLayer.visibility = View.GONE
        binding.contentLayer.visibility = View.VISIBLE
        renderContent(launcherApp.parental.state.value)
        binding.switchKidsMode.requestFocus()
    }

    private fun onPinEntered(pin: String) {
        lifecycleScope.launch {
            when (gateMode) {
                GateMode.ENTER -> when (val result = launcherApp.parental.verifyPin(pin)) {
                    PinResult.Ok -> {
                        binding.gatePad.clear()
                        showContent()
                    }

                    is PinResult.Wrong -> {
                        binding.gatePad.clear()
                        toast("PIN 码错误，还可尝试 ${result.attemptsLeft} 次")
                    }

                    is PinResult.LockedOut -> {
                        binding.gatePad.clear()
                        toast("尝试次数过多，请 5 分钟后再试")
                    }

                    PinResult.NotSet -> {
                        binding.gatePad.clear()
                        showContent()
                    }
                }

                GateMode.CHANGE_OLD -> when (launcherApp.parental.verifyPin(pin)) {
                    PinResult.Ok -> showGate(GateMode.CHANGE_NEW)
                    is PinResult.Wrong -> {
                        binding.gatePad.clear()
                        toast("当前 PIN 码不正确")
                    }

                    is PinResult.LockedOut -> {
                        binding.gatePad.clear()
                        toast("尝试次数过多，请 5 分钟后再试")
                    }

                    PinResult.NotSet -> showGate(GateMode.CHANGE_NEW)
                }

                GateMode.CHANGE_NEW -> {
                    launcherApp.parental.setPin(pin)
                    binding.gatePad.clear()
                    toast("家长 PIN 码已更新")
                    showContent()
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
