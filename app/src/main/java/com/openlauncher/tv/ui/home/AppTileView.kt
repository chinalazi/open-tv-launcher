package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.openlauncher.tv.R
import com.openlauncher.tv.data.local.AppItemEntity

/**
 * 大圆角品牌色磁贴。首页与「全部应用」共用，聚焦时白描边 + 上浮放大。
 * 尺寸按传入的基准宽度等比缩放，保证 960dp（1080p@2x）与 1280dp 的电视上观感一致。
 */
class AppTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val rowView = LinearLayout(context)
    private val iconView = ImageView(context)
    private val labelView = TextView(context)

    private var bgNormal: GradientDrawable? = null
    private var bgFocused: GradientDrawable? = null
    private var radiusDp = 24

    /** 卡片主色，供氛围光取用 */
    var brandColor: Int = NEUTRAL
        private set

    var onTileFocus: ((AppTileView, Boolean) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        clipChildren = false
        clipToPadding = false

        rowView.orientation = LinearLayout.HORIZONTAL
        rowView.gravity = Gravity.CENTER
        rowView.clipChildren = false
        rowView.clipToPadding = false

        iconView.scaleType = ImageView.ScaleType.FIT_CENTER
        rowView.addView(iconView, LinearLayout.LayoutParams(0, 0))
        labelView.apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        rowView.addView(
            labelView,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )

        addView(rowView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        setOnFocusChangeListener { _, focused ->
            applyFocus(focused)
            onTileFocus?.invoke(this, focused)
        }
    }

    fun bindApp(item: AppItemEntity, compact: Boolean = false, baseWidthDp: Int = BASE_WIDTH) {
        applyMetrics(baseWidthDp, compact)
        labelView.text = item.label
        iconView.setImageDrawable(AppIconCache.get(context, item.packageName))
        brandColor = AppPalette.colorOf(context, item.packageName)
        buildBackgrounds(isMore = false)
    }

    fun bindMore(compact: Boolean = false, baseWidthDp: Int = BASE_WIDTH) {
        applyMetrics(baseWidthDp, compact)
        labelView.text = "更多"
        iconView.setImageResource(R.drawable.ic_more_dots)
        brandColor = MORE_COLOR
        buildBackgrounds(isMore = true)
    }

    /** 按基准宽度等比缩放图标、字号、内边距与圆角。 */
    private fun applyMetrics(baseWidthDp: Int, compact: Boolean) {
        val scale = (baseWidthDp.toFloat() / BASE_WIDTH).coerceIn(0.6f, 1.1f)
        val iconSize = (32 * scale).toInt().coerceAtLeast(20)
        val iconGap = (9 * scale).toInt().coerceAtLeast(6)
        val padding = (12 * scale).toInt().coerceAtLeast(8)
        radiusDp = ((if (compact) 20 else 24) * scale).toInt().coerceAtLeast(12)

        rowView.setPadding(dp(padding), 0, dp(padding), 0)
        val lp = iconView.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(dp(iconSize), dp(iconSize))
        lp.width = dp(iconSize)
        lp.height = dp(iconSize)
        lp.rightMargin = dp(iconGap)
        iconView.layoutParams = lp

        labelView.textSize = (15f * scale).coerceIn(11f, 15f)
    }

    private fun buildBackgrounds(isMore: Boolean) {
        val normalColors: IntArray
        val focusColors: IntArray
        if (isMore) {
            normalColors = intArrayOf(0x12FFFFFF, 0x08FFFFFF)
            focusColors = intArrayOf(0x2EFFFFFF, 0x17FFFFFF)
        } else {
            normalColors = intArrayOf(shade(brandColor, 0.46f), shade(brandColor, 0.17f))
            focusColors = intArrayOf(shade(brandColor, 0.60f), shade(brandColor, 0.22f))
        }

        bgNormal = GradientDrawable(GradientDrawable.Orientation.TL_BR, normalColors).apply {
            cornerRadius = dp(radiusDp).toFloat()
            if (isMore) {
                setStroke(dp(1), 0x47FFFFFF, dp(5).toFloat(), dp(6).toFloat())
            } else {
                setStroke(dp(1), 0x24FFFFFF)
            }
        }
        bgFocused = GradientDrawable(GradientDrawable.Orientation.TL_BR, focusColors).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(2), 0xFFFFFFFF.toInt())
        }
        background = if (isFocused) bgFocused else bgNormal
    }

    private fun applyFocus(focused: Boolean) {
        background = if (focused) bgFocused else bgNormal
        val scale = if (focused) 1.075f else 1f
        animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationZ(if (focused) dp(16).toFloat() else 0f)
            .setDuration(if (focused) 180L else 140L)
            .start()
    }

    private fun shade(color: Int, factor: Float): Int {
        if (color == 0) return color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * factor).coerceIn(0.03f, 1f)
        hsv[1] = (hsv[1] * 1.05f).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val BASE_WIDTH = 176
        const val BASE_HEIGHT = 114
        val NEUTRAL = 0xFF3F4756.toInt()
        val MORE_COLOR = 0xFF4A5470.toInt()
    }
}
