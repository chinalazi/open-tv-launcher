package com.openlauncher.tv.ui.home

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.local.AppItemEntity
import com.openlauncher.tv.data.local.CategoryEntity
import com.openlauncher.tv.ui.parental.ParentalSettingsActivity
import com.openlauncher.tv.ui.settings.SettingsActivity
import com.openlauncher.tv.ui.settings.SystemSettingsLauncher
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

    /**
     * 分类与应用列表用同一个 Flow 组合渲染：装/卸应用、改名、隐藏都会自动刷新，不需要重启。
     */
    private fun observeData() {
        val app = requireActivity().application as LauncherApplication
        val dao = app.database.launcherDao()

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                dao.getAllCategoriesFlow(),
                dao.getAllVisibleAppsFlow()
            ) { categories, apps -> categories to apps }
                .collectLatest { (categories, apps) -> renderRows(categories, apps) }
        }
    }

    private fun renderRows(categories: List<CategoryEntity>, apps: List<AppItemEntity>) {
        val cardPresenter = AppCardPresenter { item -> showAppMenu(item) }
        val grouped = apps.groupBy { it.categoryId }

        rowsAdapter.clear()

        // 第一行：常驻快捷入口
        val actionAdapter = ArrayObjectAdapter(ActionItemPresenter())
        actionAdapter.add(ActionItem(1, "电视系统设置", android.R.drawable.ic_menu_preferences))
        actionAdapter.add(ActionItem(2, "启动器设置", android.R.drawable.ic_menu_manage))
        actionAdapter.add(ActionItem(3, "儿童模式与防沉迷", android.R.drawable.ic_lock_lock))
        rowsAdapter.add(ListRow(HeaderItem(0, "系统与控制"), actionAdapter))

        for (category in categories) {
            val items = grouped[category.id].orEmpty()
            if (items.isEmpty()) continue
            val rowAdapter = ArrayObjectAdapter(cardPresenter)
            items.forEach { rowAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(category.id, category.name), rowAdapter))
        }
    }

    private fun launchApp(appItem: AppItemEntity) {
        val app = requireActivity().application as LauncherApplication
        val state = app.parental.state.value
        if (state.kidsMode && state.locked && !appItem.isWhitelistForKids) {
            toast("观看时间已用完，请家长输入 PIN 码解锁")
            return
        }

        try {
            val intent = Intent().apply {
                component = ComponentName(appItem.packageName, appItem.activityName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast("无法启动「${appItem.label}」")
        }
    }

    /** 长按卡片：加入/移出儿童白名单，或隐藏应用。 */
    private fun showAppMenu(item: AppItemEntity) {
        val whitelistOption =
            if (item.isWhitelistForKids) "从白名单移除（重新计入时长）" else "加入白名单（不计入观看时长）"
        val hideOption = if (item.isHidden) "取消隐藏" else "隐藏应用"
        AlertDialog.Builder(requireContext())
            .setTitle(item.label)
            .setItems(arrayOf(whitelistOption, hideOption)) { _, which -> applyAppAction(item, which) }
            .show()
    }

    private fun applyAppAction(item: AppItemEntity, which: Int) {
        val dao = (requireActivity().application as LauncherApplication).database.launcherDao()
        viewLifecycleOwner.lifecycleScope.launch {
            when (which) {
                0 -> {
                    dao.setKidsWhitelist(item.id, !item.isWhitelistForKids)
                    toast(if (item.isWhitelistForKids) "已移出白名单" else "已加入白名单，该应用不计入观看时长")
                }

                1 -> {
                    dao.setHidden(item.id, !item.isHidden)
                    toast(if (item.isHidden) "已恢复显示" else "已隐藏「${item.label}」，可在 设置 → 分类与应用管理 中恢复")
                }
            }
        }
    }

    private fun handleActionItem(item: ActionItem) {
        when (item.id) {
            1 -> {
                if (!SystemSettingsLauncher.open(requireContext(), SystemSettingsLauncher.Target.ROOT)) {
                    toast("这台电视没有提供系统设置入口")
                }
            }

            2 -> startActivity(Intent(requireContext(), SettingsActivity::class.java))

            3 -> startActivity(Intent(requireContext(), ParentalSettingsActivity::class.java))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    data class ActionItem(val id: Int, val title: String, val iconRes: Int)
}
