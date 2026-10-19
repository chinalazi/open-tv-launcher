package com.openlauncher.tv.ui.common

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 影院感的氛围背光：一团跟随聚焦应用主色的巨大柔光，右下角再压一层冷紫，
 * 全部由两个径向渐变直接绘制（不用模糊，1GB 内存机型也扛得住）。
 */
class AmbientBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glow = DEFAULT_GLOW
    private var animator: ValueAnimator? = null
    private var primaryShader: Shader? = null
    private var secondaryShader: Shader? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** 设置氛围主色；统一压到 55% 不透明度，避免盖过前景内容。 */
    fun setGlowColor(color: Int, animate: Boolean = true) {
        val target = (color and 0x00FFFFFF) or (GLOW_ALPHA shl 24)
        if (target == glow) return
        animator?.cancel()
        if (!animate) {
            glow = target
            buildShaders()
            invalidate()
            return
        }
        animator = ValueAnimator.ofObject(ArgbEvaluator(), glow, target).apply {
            duration = 420L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                glow = it.animatedValue as Int
                buildShaders()
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShaders()
    }

    private fun buildShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        primaryShader = RadialGradient(
            w * 0.34f, h * 0.44f, w * 0.62f,
            intArrayOf(glow, glow and 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        secondaryShader = RadialGradient(
            w * 0.80f, h * 0.86f, w * 0.55f,
            intArrayOf(SECONDARY, SECONDARY and 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(BACKGROUND)
        primaryShader?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        secondaryShader?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
    }

    private companion object {
        val BACKGROUND = 0xFF05070C.toInt()
        const val GLOW_ALPHA = 0x8C
        val DEFAULT_GLOW = 0x8CE50914.toInt()
        val SECONDARY = 0x3D3C1464
    }
}
