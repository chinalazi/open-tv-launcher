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
 * Apple TV 风格的磁贴：圆角方块（底色取自应用主色）+ 图标 + 下方应用名。
 * 聚焦时放大、抬升、描白边。整块尺寸按屏宽自适应，保证 960dp / 1280dp 的电视观感一致。
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
        // 960dp（1080p 电视）：一行要放得下「第三方应用 + 设置 + 更多」
        resources.configuration.screenWidthDp >= 960 -> 80
        else -> 74
    }

    private val cornerRadius: Int = (tileSize * 0.23f).toInt()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        clipChildren = false
        clipToPadding = false
        setPadding(dp(6), dp(6), dp(6), dp(6))

        iconView.scaleType = ImageView.ScaleType.FIT_CENTER
        val tilePx = dp(tileSize)
        val iconPx = dp((tileSize * 0.54f).toInt())
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
            setShadowLayer(7f, 0f, 1f, 0x73000F0C)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        addView(
            labelView,
            LinearLayout.LayoutParams(tilePx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(9)
                bottomMargin = dp(4)
            }
        )
    }

    fun bindApp(item: AppItemEntity) {
        labelView.text = item.label
        iconView.setImageDrawable(AppIconCache.get(context, item.packageName))
        contentDescription = item.label
        applyLook(AppPalette.colorOf(context, item.packageName))
    }

    fun bindAction(@DrawableRes iconRes: Int, title: String, color: Int, iconTint: Int? = null) {
        labelView.text = title
        iconView.setImageResource(iconRes)
        if (iconTint == null) iconView.clearColorFilter() else iconView.setColorFilter(iconTint)
        contentDescription = title
        applyLook(color)
    }

    private fun applyLook(color: Int) {
        val radius = dp(cornerRadius).toFloat()
        normalBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(shade(color, 1.02f), shade(color, 0.50f))
        ).apply {
            cornerRadius = radius
            setStroke(dp(1), 0x2EFFFFFF)
        }
        focusBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(shade(color, 1.30f), shade(color, 0.70f))
        ).apply {
            cornerRadius = radius
            setStroke(dp(2), 0xF2FFFFFF.toInt())
        }
        background = null
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
            .scaleX(if (gainFocus) 1.08f else 1f)
            .scaleY(if (gainFocus) 1.08f else 1f)
            .translationZ(if (gainFocus) dp(14).toFloat() else 0f)
            .setDuration(if (gainFocus) 180L else 130L)
            .start()

        focusChanged?.invoke(gainFocus)
    }

    fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun shade(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * factor).coerceIn(0.05f, 1f)
        return Color.HSVToColor(hsv)
    }

    companion object {
        private val LABEL_NORMAL = 0xCCFFFFFF.toInt()
        private val LABEL_FOCUSED = Color.WHITE
    }
}
