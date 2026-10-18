package com.openlauncher.tv.ui.settings

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.local.AppItemEntity
import com.openlauncher.tv.data.local.CategoryEntity
import com.openlauncher.tv.databinding.ActivityCategoryManagerBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CategoryManagerActivity : FragmentActivity() {

    private lateinit var binding: ActivityCategoryManagerBinding
    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAddCommon.setOnClickListener { addCategory("常用应用") }
        binding.btnAddMedia.setOnClickListener { addCategory("影音播放") }
        binding.btnAddGame.setOnClickListener { addCategory("电视游戏") }
        binding.btnAddKids.setOnClickListener { addCategory("儿童专区") }
        binding.btnAddStudy.setOnClickListener { addCategory("学习工具") }

        observeData()
    }

    private fun observeData() {
        val dao = launcherApp.database.launcherDao()
        lifecycleScope.launch {
            combine(
                dao.getAllCategoriesFlow(),
                dao.getHiddenAppsFlow()
            ) { categories, hidden -> categories to hidden }
                .collectLatest { (categories, hidden) ->
                    renderCategories(categories)
                    renderHiddenApps(hidden)
                }
        }
    }

    // ---- 分类 -------------------------------------------------------------------

    private fun renderCategories(categories: List<CategoryEntity>) {
        binding.categoryRows.removeAllViews()
        if (categories.isEmpty()) return
        categories.forEachIndexed { index, category ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                createButton(category.name) { showRenameDialog(category) },
                LinearLayout.LayoutParams(dp(300), dp(54)).apply { marginEnd = dp(8) }
            )
            row.addView(
                createButton("↑") { moveCategory(category, -1) },
                LinearLayout.LayoutParams(dp(56), dp(54)).apply { marginEnd = dp(8) }
            )
            row.addView(
                createButton("↓") { moveCategory(category, 1) },
                LinearLayout.LayoutParams(dp(56), dp(54)).apply { marginEnd = dp(8) }
            )
            row.addView(
                createButton("删除") { confirmDelete(category) },
                LinearLayout.LayoutParams(dp(96), dp(54)).apply { marginEnd = dp(8) }
            )
            if (index == 0) {
                row.addView(createLabel("（新建应用默认进这里）"))
            }
            binding.categoryRows.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            )
        }
    }

    private fun addCategory(name: String) {
        lifecycleScope.launch {
            val dao = launcherApp.database.launcherDao()
            if (dao.getAllCategories().any { it.name == name }) {
                toast("分类「$name」已存在")
                return@launch
            }
            dao.insertCategory(CategoryEntity(name = name, sortOrder = dao.maxSortOrder() + 1))
            toast("已添加「$name」")
        }
    }

    private fun moveCategory(category: CategoryEntity, delta: Int) {
        lifecycleScope.launch {
            val dao = launcherApp.database.launcherDao()
            val list = dao.getAllCategories().toMutableList()
            val index = list.indexOfFirst { it.id == category.id }
            val target = index + delta
            if (index < 0 || target !in list.indices) return@launch
            val item = list.removeAt(index)
            list.add(target, item)
            list.forEachIndexed { order, entity ->
                if (entity.sortOrder != order) dao.updateCategory(entity.copy(sortOrder = order))
            }
        }
    }

    private fun showRenameDialog(category: CategoryEntity) {
        val presets = arrayOf("常用应用", "影音播放", "电视游戏", "系统工具", "儿童专区", "学习工具")
        AlertDialog.Builder(this)
            .setTitle("重命名「${category.name}」")
            .setItems(presets) { _, which ->
                lifecycleScope.launch {
                    launcherApp.database.launcherDao()
                        .updateCategory(category.copy(name = presets[which]))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(category: CategoryEntity) {
        AlertDialog.Builder(this)
            .setTitle("删除分类「${category.name}」？")
            .setMessage("该分类下的应用会移动到第一个分类，不会被卸载。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val dao = launcherApp.database.launcherDao()
                    val all = dao.getAllCategories()
                    if (all.size <= 1) {
                        toast("至少需要保留一个分类")
                        return@launch
                    }
                    val fallback = all.first { it.id != category.id }.id
                    dao.moveAppsToCategory(category.id, fallback)
                    dao.deleteCategory(category)
                    toast("已删除「${category.name}」")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 隐藏应用 ---------------------------------------------------------------

    private fun renderHiddenApps(hidden: List<AppItemEntity>) {
        binding.hiddenRows.removeAllViews()
        binding.tvEmptyHint.visibility = if (hidden.isEmpty()) View.VISIBLE else View.GONE

        for (item in hidden) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(createLabel("${item.label}  (${item.packageName})"))
            row.addView(
                createButton("恢复显示") {
                    lifecycleScope.launch {
                        launcherApp.database.launcherDao().setHidden(item.id, false)
                    }
                },
                LinearLayout.LayoutParams(dp(120), dp(50)).apply { marginEnd = dp(8) }
            )
            binding.hiddenRows.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            )
        }
    }

    // ---- 小工具 -----------------------------------------------------------------

    private fun createButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@CategoryManagerActivity, R.color.text_primary))
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@CategoryManagerActivity, R.color.pin_key_bg)
            )
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
        }

    private fun createLabel(text: String): android.widget.TextView =
        android.widget.TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@CategoryManagerActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
