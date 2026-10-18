package com.openlauncher.tv.ui.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.databinding.ActivityMainBinding
import com.openlauncher.tv.service.TimeTrackingService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.home_container, HomeFragment())
                .commit()
        }

        observeKidsMode()
    }

    private fun observeKidsMode() {
        val app = application as LauncherApplication
        lifecycleScope.launch {
            app.preferencesRepository.isKidsModeEnabled.collectLatest { enabled ->
                if (enabled) {
                    TimeTrackingService.startService(this@MainActivity)
                } else {
                    TimeTrackingService.stopService(this@MainActivity)
                    binding.tvKidsStatus.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            app.preferencesRepository.remainingSeconds.collectLatest { remaining ->
                val isKids = app.preferencesRepository.isKidsModeEnabled
                // 实时展示剩余可用时长
                val mins = remaining / 60
                val secs = remaining % 60
                binding.tvKidsStatus.apply {
                    visibility = View.VISIBLE
                    text = String.format("儿童模式剩余时长: %02d:%02d", mins, secs)
                }
            }
        }
    }

    override fun onBackPressed() {
        // 作为 Launcher，在最顶层按下 Back 键时不退出
    }
}
