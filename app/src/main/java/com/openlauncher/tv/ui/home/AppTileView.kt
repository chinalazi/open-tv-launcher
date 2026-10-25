package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.openlauncher.tv.data.local.AppItemEntity

/**
 * 极简磁贴：一块很淡的半透明圆角方块 + 真实应用图标 + 一行名字。
 *
 * 和旧版的区别是「不抢戏」——不再按应用主色铺渐变底，颜色只保留在图标本身，
 * 聚焦时用一个绿色描边环告诉用户焦点在哪。首页与「全部应用」共用这套外观，
 * 只是通过 [sizeDp] 给出不同尺寸。
 */
class AppTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    /** 指定方块边长（dp）。0 表示按屏宽自动取尺寸。 */
    sizeDp: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconBox = FrameLayout(context)
    private val iconView = ImageView(context)
    private val labelView = TextView(context)

    private var normalBg: GradientDrawable? = null
    private var focusBg: GradientDrawable? = null

    /** 聚焦回调，交给 RecyclerView 适配器转发给桌面。 */
    var focusChanged: ((Boolean) -> Unit)? = null

    private val tileSize: Int = when {
        sizeDp > 0 -> sizeDp
        resources.configuration.screenWidthDp >= 1280 -> 104
        resources.configuration.screenWidthDp >= 960 -> 84
        else -> 74
    }

    private val cornerRadius: Int = (tileSize * 0.28f).toInt()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        clipChildren = false
        clipToPadding = false
        setPadding(dp(4), dp(4), dp(4), dp(4))

        iconView.scaleType = ImageView.ScaleType.FIT_CENTER
        val tilePx = dp(tileSize)
        val iconPx = dp((tileSize * 0.56f).toInt())
        iconBox.addView(
            iconView,
            FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER)
        )
        addView(iconBox, LinearLayout.LayoutParams(tilePx, tilePx))

        labelView.apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(LABEL_NORMAL)
            textSize = (tileSize * 0.16f).coerceAtLeast(8f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = 0.02f
        }
        addView(
            labelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp((tileSize * 0.11f).toInt())
            }
        )
    }

    fun bindApp(item: AppItemEntity) {
        labelView.text = item.label
        iconView.setImageDrawable(AppIconCache.get(context, item.packageName))
        iconView.clearColorFilter()
        contentDescription = item.label
        buildBackground()
    }

    fun bindAction(@DrawableRes iconRes: Int, title: String, iconTint: Int? = null) {
        labelView.text = title
        iconView.setImageResource(iconRes)
        if (iconTint == null) iconView.clearColorFilter() else iconView.setColorFilter(iconTint)
        contentDescription = title
        buildBackground()
    }

    private fun buildBackground() {
        val radius = dp(cornerRadius).toFloat()
        normalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(0x12FFFFFF)
        }
        focusBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(0x1FFFFFFF)
            setStroke(dp(3), ACCENT)
        }
        iconBox.background = if (isFocused) focusBg else normalBg
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        iconBox.background = when {
            gainFocus -> focusBg ?: iconBox.background
            else -> normalBg ?: iconBox.background
        }
        labelView.setTextColor(if (gainFocus) LABEL_FOCUSED else LABEL_NORMAL)

        animate().cancel()
        animate()
            .scaleX(if (gainFocus) 1.05f else 1f)
            .scaleY(if (gainFocus) 1.05f else 1f)
            .setDuration(if (gainFocus) 170L else 120L)
            .start()

        focusChanged?.invoke(gainFocus)
    }

    fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private val ACCENT = 0xFF35D08B.toInt()
        private val LABEL_NORMAL = 0xFF8CA096.toInt()
        private val LABEL_FOCUSED = 0xFFE9F2ED.toInt()
    }
}
