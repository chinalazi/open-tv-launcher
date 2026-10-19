package com.openlauncher.tv.ui.home

import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import androidx.recyclerview.widget.RecyclerView
import com.openlauncher.tv.data.local.AppItemEntity

/** 全部应用网格用的 Leanback Presenter。 */
class AppTilePresenter(
    private val compact: Boolean = true,
    private val onClick: (AppItemEntity) -> Unit,
    private val onLongClick: (AppItemEntity) -> Unit,
    private val onFocusChanged: (AppTileView, AppItemEntity, Boolean) -> Unit = { _, _, _ -> }
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder =
        ViewHolder(AppTileView(parent.context))

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val tile = viewHolder.view as AppTileView
        val appItem = item as? AppItemEntity ?: return

        tile.bindApp(appItem, compact)
        tile.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(tile, if (compact) 104 else 114)
        )
        tile.onTileFocus = { view, focused -> onFocusChanged(view, appItem, focused) }
        tile.setOnClickListener { onClick(appItem) }
        tile.setOnLongClickListener {
            onLongClick(appItem)
            true
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val tile = viewHolder.view as AppTileView
        tile.onTileFocus = null
        tile.setOnClickListener(null)
        tile.setOnLongClickListener(null)
    }

    private fun dp(view: AppTileView, value: Int): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()
}
