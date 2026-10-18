package com.openlauncher.tv.ui.home

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.local.AppItemEntity
import com.openlauncher.tv.ui.settings.SettingsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeFragment : RowsSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRowsAdapter()
        observeData()
    }

    private fun setupRowsAdapter() {
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is AppItemEntity -> launchApp(item)
                is ActionItem -> handleActionItem(item)
            }
        }
    }

    private fun observeData() {
        val app = requireActivity().application as LauncherApplication

        viewLifecycleOwner.lifecycleScope.launch {
            app.database.launcherDao().getAllCategoriesFlow().collectLatest { categories ->
                val cardPresenter = AppCardPresenter()
                rowsAdapter.clear()

                // 第一行：固定的快捷设置栏（系统设置 & 启动器设置）
                val settingAdapter = ArrayObjectAdapter(ActionItemPresenter())
                settingAdapter.add(ActionItem(1, "电视系统设置", android.R.drawable.ic_menu_preferences))
                settingAdapter.add(ActionItem(2, "启动器设置", android.R.drawable.ic_menu_manage))
                settingAdapter.add(ActionItem(3, "儿童模式管理", android.R.drawable.ic_lock_lock))
                rowsAdapter.add(ListRow(HeaderItem(0, "系统与控制"), settingAdapter))

                // 后续各行：分类与已安装应用
                for (cat in categories) {
                    val listRowAdapter = ArrayObjectAdapter(cardPresenter)
                    val apps = app.database.launcherDao().getAppsByCategoryIdFlow(cat.id).first()
                    for (appItem in apps) {
                        listRowAdapter.add(appItem)
                    }
                    if (listRowAdapter.size() > 0) {
                        rowsAdapter.add(ListRow(HeaderItem(cat.id, cat.name), listRowAdapter))
                    }
                }
            }
        }
    }

    private fun launchApp(appItem: AppItemEntity) {
        val app = requireActivity().application as LauncherApplication

        viewLifecycleOwner.lifecycleScope.launch {
            val isKidsMode = app.preferencesRepository.isKidsModeEnabled.first()
            val remaining = app.preferencesRepository.remainingSeconds.first()

            // 儿童模式下检查是否超时且非白名单应用
            if (isKidsMode && remaining <= 0 && !appItem.isWhitelistForKids) {
                Toast.makeText(context, "今日观看时长已耗尽，请家长输入PIN码解锁", Toast.LENGTH_LONG).show()
                return@launch
            }

            try {
                val intent = Intent().apply {
                    component = ComponentName(appItem.packageName, appItem.activityName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "无法启动应用: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleActionItem(item: ActionItem) {
        when (item.id) {
            1 -> {
                // 打开原生电视设置
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                }
            }
            2, 3 -> {
                // 打开启动器自身设置菜单
                val intent = Intent(requireContext(), SettingsActivity::class.java)
                startActivity(intent)
            }
        }
    }

    data class ActionItem(val id: Int, val title: String, val iconRes: Int)
}
