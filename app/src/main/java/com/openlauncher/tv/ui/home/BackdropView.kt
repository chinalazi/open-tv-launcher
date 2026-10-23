package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * 全屏背景：几层柔和的薄荷绿渐变叠起来。
 *
 * 早期这里是把每日壁纸缩略图放大铺满；壁纸功能撤掉之后改成纯渐变，
 * 好处是零 IO、零内存、第一帧就是最终样子，低配电视上也不会因为加载大图闪一下。
 * 图形元素只有两团光斑和一层极淡的斜向条纹，够压住底又不会和磁贴抢注意力。
 */
class BackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        basePaint.shader = LinearGradient(
            0f, 0f, 0f, 1080f,
            intArrayOf(0xFFE6F1EC.toInt(), 0xFFD8E8E0.toInt(), 0xFFC6DCD2.toInt()),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        glowPaint.shader = RadialGradient(
            w * 0.15f, h * 0.08f, h * 1.25f,
            intArrayOf(0x4D12B57F, 0x1412B57F, 0x0012B57F),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        stripePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0x00FFFFFF, 0x0DFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRect(0f, 0f, w, h, basePaint)
        canvas.drawRect(0f, 0f, w, h, stripePaint)
        canvas.drawRect(0f, 0f, w, h, glowPaint)
    }
}
