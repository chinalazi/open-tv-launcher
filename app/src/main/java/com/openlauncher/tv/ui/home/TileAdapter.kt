package com.openlauncher.tv.ui.home

import android.view.View
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
    private val callbacks: TileCallbacks,
    /** 一行最后一个磁贴按「右」时把焦点交给谁（右侧快捷按钮）。 */
    private val rightNeighborId: Int = View.NO_ID,
    /** 一行里所有磁贴按「上」时把焦点交给谁（第一行 → 画框上的齿轮）。 */
    private val upNeighborId: Int = View.NO_ID
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
        // RecyclerView 会把焦点搜索限制在自己内部，最后一个磁贴得手动接住「右」键
        tile.nextFocusRightId =
            if (position == items.size - 1) rightNeighborId else View.NO_ID
        tile.nextFocusUpId = upNeighborId
        // RecyclerView 的焦点搜索在跨容器时会失败一次（表现为按两下才能上去），
        // 第一行干脆用按键监听直接跳，按一下就到画框上的齿轮
        if (upNeighborId != View.NO_ID) {
            tile.setOnKeyListener { view, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                    event.action == android.view.KeyEvent.ACTION_DOWN
                ) {
                    view.rootView.findViewById<View>(upNeighborId)?.requestFocus()
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
