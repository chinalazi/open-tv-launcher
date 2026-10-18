package com.openlauncher.tv.ui.parental

import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.databinding.ActivityParentalSettingsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ParentalSettingsActivity : FragmentActivity() {

    private lateinit var binding: ActivityParentalSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentalSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as LauncherApplication

        lifecycleScope.launch {
            val enabled = app.preferencesRepository.isKidsModeEnabled.first()
            binding.switchKidsMode.isChecked = enabled
        }

        binding.switchKidsMode.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                val hasPin = app.preferencesRepository.hasPinSet.first()
                if (isChecked && !hasPin) {
                    Toast.makeText(this@ParentalSettingsActivity, "请先设置家长PIN码再启用儿童模式", Toast.LENGTH_LONG).show()
                    binding.switchKidsMode.isChecked = false
                    return@launch
                }
                app.preferencesRepository.setKidsModeEnabled(isChecked)
            }
        }

        binding.btnTime30.setOnClickListener { setTimeLimit(30) }
        binding.btnTime45.setOnClickListener { setTimeLimit(45) }
        binding.btnTime60.setOnClickListener { setTimeLimit(60) }

        binding.btnSavePin.setOnClickListener {
            val pin = binding.etPin.text.toString().trim()
            if (pin.length != 4) {
                Toast.makeText(this, "请输入4位数字PIN码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                app.preferencesRepository.setPin(pin)
                Toast.makeText(this@ParentalSettingsActivity, "家长PIN码已保存", Toast.LENGTH_SHORT).show()
                binding.etPin.text.clear()
            }
        }
    }

    private fun setTimeLimit(minutes: Int) {
        val app = application as LauncherApplication
        lifecycleScope.launch {
            app.preferencesRepository.setSingleLimitMinutes(minutes)
            Toast.makeText(this@ParentalSettingsActivity, "已设置单次观看时长: $minutes 分钟", Toast.LENGTH_SHORT).show()
        }
    }
}
