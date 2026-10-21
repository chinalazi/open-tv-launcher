package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 一个分类「货架」：标题 + 一行可横向滚动的磁贴。
 * RecyclerView 本身不参与焦点，焦点只在磁贴之间移动，符合 Apple TV 的手感。
 */
class ShelfRowView(
    context: Context,
    private val callbacks: TileCallbacks
) : LinearLayout(context) {

    private val titleView = TextView(context)
    private val recycler = RecyclerView(context)

    /** 当前这一行装了哪些磁贴，用于数据刷新后把焦点放回原来的应用。 */
    var items: List<TileItem> = emptyList()
        private set

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false

        titleView.apply {
            textSize = 15f
            setTextColor(0xD9FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.02f
            setPadding(dp(16), 0, 0, dp(6))
            setShadowLayer(6f, 0f, 1f, 0x66001410)
        }
        addView(
            titleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        recycler.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            isFocusable = false
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(4), dp(8), dp(4), dp(12))
            itemAnimator = null
        }
        addView(
            recycler,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    fun bind(
        title: String,
        tiles: List<TileItem>,
        rightNeighborId: Int = View.NO_ID,
        upNeighborId: Int = View.NO_ID
    ) {
        titleView.text = title
        items = tiles
        recycler.adapter = TileAdapter(tiles, callbacks, rightNeighborId, upNeighborId)
    }

    fun focusFirst() = focusAt(0)

    /** 让第 position 块磁贴拿到焦点（布局还没完成时自动重试）。 */
    fun focusAt(position: Int, attempt: Int = 0) {
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        val target = manager.findViewByPosition(position)
        if (target == null) {
            if (attempt == 0) manager.scrollToPositionWithOffset(position, 0)
            if (attempt < 6) recycler.post { focusAt(position, attempt + 1) }
            return
        }
        target.requestFocus()
        // 把焦点磁贴对齐到货架左端，避免重建后露出上一条的半截磁贴
        manager.scrollToPositionWithOffset(position, 0)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
