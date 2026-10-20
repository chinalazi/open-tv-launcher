package com.openlauncher.tv.ui.home

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.openlauncher.tv.data.local.AppItemEntity

/** 一行里可能出现两种磁贴：应用，或固定入口（设置）。 */
sealed class TileItem {
    data class App(val entity: AppItemEntity) : TileItem()
    data class Action(
        val id: String,
        val title: String,
        val iconRes: Int,
        val color: Int
    ) : TileItem()
}

class TileCallbacks(
    val onFocused: (TileItem) -> Unit,
    val onClicked: (TileItem) -> Unit,
    val onLongClicked: (TileItem) -> Unit
)

class TileAdapter(
    private val items: List<TileItem>,
    private val callbacks: TileCallbacks
) : RecyclerView.Adapter<TileAdapter.TileHolder>() {

    class TileHolder(val tile: AppTileView) : RecyclerView.ViewHolder(tile)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
        val tile = AppTileView(parent.context)
        val lp = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = tile.dp(8)
        tile.layoutParams = lp
        return TileHolder(tile)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        val item = items[position]
        val tile = holder.tile
        when (item) {
            is TileItem.App -> tile.bindApp(item.entity)
            is TileItem.Action -> tile.bindAction(item.iconRes, item.title, item.color)
        }
        tile.setOnClickListener { callbacks.onClicked(item) }
        tile.setOnLongClickListener {
            if (item is TileItem.App) {
                callbacks.onLongClicked(item)
                true
            } else {
                false
            }
        }
        tile.focusChanged = { focused -> if (focused) callbacks.onFocused(item) }
    }

    override fun getItemCount(): Int = items.size
}
