package com.openlauncher.tv.ui.home

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

/**
 * 首页应用网格：固定格子宽度，按可用宽度分行，**每一行都水平居中**。
 *
 * 为什么不用 RecyclerView + GridLayoutManager：应用只有 7 个时，最后一行会贴着左边排，
 * 右边留一大块空，看着像没做完。这里每行单独算宽度再居中，4+3 也能排成正三角。
 *
 * 数据量最大 12 个，没有必要做复用，直接 addView 更省心。
 */
class AppGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    /** 一行最多几格。 */
    var columns: Int = 6

    /** 单格宽度（px），包含格子两侧的留白。 */
    var cellWidth: Int = 0

    /** 行间距（px）。 */
    var rowGap: Int = 0

    private var cellHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = width - paddingLeft - paddingRight
        if (cellWidth <= 0 && childCount > 0) {
            cellWidth = available / columns.coerceAtLeast(1)
        }

        var tallest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(
                MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            tallest = max(tallest, child.measuredHeight)
        }
        cellHeight = tallest

        val perRow = columns.coerceAtLeast(1)
        val rows = if (childCount == 0) 0 else (childCount + perRow - 1) / perRow
        val height = if (rows == 0) {
            paddingTop + paddingBottom
        } else {
            rows * cellHeight + (rows - 1) * rowGap + paddingTop + paddingBottom
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val perRow = columns.coerceAtLeast(1)
        val available = width - paddingLeft - paddingRight
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val row = i / perRow
            val col = i % perRow
            val countInRow = min(perRow, childCount - row * perRow)
            val rowWidth = countInRow * cellWidth
            val startX = paddingLeft + (available - rowWidth) / 2
            val x = startX + col * cellWidth
            val y = paddingTop + row * (cellHeight + rowGap)
            child.layout(x, y, x + cellWidth, y + cellHeight)
        }
    }
}
