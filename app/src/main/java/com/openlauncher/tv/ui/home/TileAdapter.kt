package com.openlauncher.tv.ui.home

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.openlauncher.tv.data.local.AppItemEntity

/** 宫格里可能出现两种磁贴：应用，或固定入口。 */
sealed class TileItem {
    data class App(val entity: AppItemEntity) : TileItem()
    data class Action(
        val id: String,
        val title: String,
        val iconRes: Int
    ) : TileItem()
}

class TileCallbacks(
    val onFocused: (TileItem) -> Unit,
    val onClicked: (TileItem) -> Unit,
    val onLongClicked: (TileItem) -> Unit
)

/**
 * 应用宫格。列宽交给 GridLayoutManager 均分（item 宽度 MATCH_PARENT），
 * 所以不用自己算间距，6 列 / 8 列都自动铺满。
 *
 * 两个跨容器的坑必须手动接：
 * - 第一行按「上」要出宫格（交给顶栏按钮），RecyclerView 的焦点搜索不外溢；
 * - 最后一行按「下」没有去处，直接吃掉按键，免得焦点跑到屏幕外。
 */
class TileAdapter(
    private val items: List<TileItem>,
    private val callbacks: TileCallbacks,
    private val spanCount: Int = 6,
    private val sizeDp: Int = 0,
    /** 单个格子的宽度（dp），包含两侧留白。0 表示交给网格均分。 */
    private val itemWidthDp: Int = 0,
    /** 第一行按「上」时把焦点交给谁（顶栏按钮）。 */
    private val upNeighborId: Int = View.NO_ID,
    /** 第一列按「上」交给最左边那个按钮，视觉上更顺。 */
    private val upLeftNeighborId: Int = View.NO_ID
) : RecyclerView.Adapter<TileAdapter.TileHolder>() {

    class TileHolder(val tile: AppTileView) : RecyclerView.ViewHolder(tile)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
        val tile = AppTileView(parent.context, sizeDp = sizeDp)
        val lp = RecyclerView.LayoutParams(
            if (itemWidthDp > 0) tile.dp(itemWidthDp) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = tile.dp(9)
        tile.layoutParams = lp
        return TileHolder(tile)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        val item = items[position]
        val tile = holder.tile
        val isFirstRow = position / spanCount == 0
        val isLastRow = position / spanCount == (items.size - 1) / spanCount

        when (item) {
            is TileItem.App -> tile.bindApp(item.entity)
            is TileItem.Action -> tile.bindAction(item.iconRes, item.title)
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

        val upId = if (position % spanCount == 0 && upLeftNeighborId != View.NO_ID) {
            upLeftNeighborId
        } else {
            upNeighborId
        }
        tile.setOnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP && isFirstRow && upId != View.NO_ID -> {
                    view.rootView.findViewById<View>(upId)?.requestFocus()
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN && isLastRow -> true
                else -> false
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
