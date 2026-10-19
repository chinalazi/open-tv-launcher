package com.openlauncher.tv.ui.home

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.ui.common.AmbientBackdropView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 全部应用：6 列网格，长按同样可以加入白名单 / 隐藏。 */
class AllAppsActivity : FragmentActivity() {

    private val launcherApp: LauncherApplication get() = application as LauncherApplication

    private lateinit var ambient: AmbientBackdropView
    private lateinit var grid: VerticalGridView
    private lateinit var countView: TextView
    private lateinit var adapter: ArrayObjectAdapter

    @Suppress("UNCHECKED_CAST")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_apps)

        ambient = findViewById(R.id.ambient_backdrop)
        ambient.setGlowColor(0xFF4B3E8C.toInt(), false)
        countView = findViewById(R.id.tv_count)
        grid = findViewById(R.id.grid_apps)

        findViewById<View>(R.id.pill_back).setOnClickListener { finish() }

        grid.setNumColumns(6)
        grid.setHorizontalSpacing(dp(14))
        grid.setVerticalSpacing(dp(14))

        adapter = ArrayObjectAdapter(
            AppTilePresenter(
                compact = true,
                onClick = { AppLauncher.launch(this, it) },
                onLongClick = { AppMenu.show(this, it) { AppLauncher.launch(this, it) } },
                onFocusChanged = { tile, _, focused ->
                    if (focused) ambient.setGlowColor(tile.brandColor)
                }
            )
        )
        grid.adapter = adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>

        observeApps()
    }

    private fun observeApps() {
        lifecycleScope.launch {
            launcherApp.database.launcherDao().getAllVisibleAppsFlow().collectLatest { apps ->
                adapter.clear()
                apps.forEach { adapter.add(it) }
                countView.text = "共 ${apps.size} 个应用"
                if (apps.isNotEmpty()) {
                    grid.postDelayed({
                        if (currentFocus == null || currentFocus === grid) {
                            grid.getChildAt(0)?.requestFocus()
                        }
                    }, 80L)
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
