package com.openlauncher.tv.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.local.CategoryEntity
import com.openlauncher.tv.databinding.ActivityCategoryManagerBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CategoryManagerActivity : FragmentActivity() {

    private lateinit var binding: ActivityCategoryManagerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as LauncherApplication

        lifecycleScope.launch {
            app.database.launcherDao().getAllCategoriesFlow().collectLatest { categories ->
                val sb = StringBuilder("当前桌面分类列表：\n\n")
                for ((index, cat) in categories.withIndex()) {
                    sb.append("${index + 1}. ${cat.name}")
                    if (cat.isSystemDefault) {
                        sb.append(" (系统默认)")
                    }
                    sb.append("\n")
                }
                binding.tvCategoryList.text = sb.toString()
            }
        }

        binding.btnAddCategory.setOnClickListener {
            val name = binding.etNewCategory.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "分类名称不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val count = app.database.launcherDao().getCategoryCount()
                app.database.launcherDao().insertCategory(
                    CategoryEntity(name = name, sortOrder = count)
                )
                binding.etNewCategory.text.clear()
                Toast.makeText(this@CategoryManagerActivity, "分类 [$name] 添加成功", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
