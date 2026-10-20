package com.openlauncher.tv.ui.home

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 背景氛围光：焦点落到磁贴上时，整块背景泛出该应用的主色（Apple TV 的观感）。
 * 用一层径向渐变 + 一层底部压暗的线性渐变，纯 Canvas 绘制，弱芯片也扛得住。
 */
class AmbientGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var glowShader: Shader? = null
    private var shadeShader: Shader? = null

    private var color = DEFAULT_COLOR
    private var animator: ValueAnimator? = null

    fun setBrandColor(target: Int, animate: Boolean = true) {
        if (target == color && glowShader != null) return
        animator?.cancel()
        if (!animate) {
            color = target
            rebuild()
            invalidate()
            return
        }
        animator = ValueAnimator.ofObject(ArgbEvaluator(), color, target).apply {
            duration = 420L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                color = animation.animatedValue as Int
                rebuild()
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w * 0.58f
        val cy = h * 0.22f
        val radius = maxOf(w * 0.72f, h * 1.05f)
        glowShader = RadialGradient(
            cx, cy, radius,
            intArrayOf(withAlpha(0.62f), withAlpha(0.24f), withAlpha(0f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        shadeShader = LinearGradient(
            0f, h * 0.42f, 0f, h,
            0x00000000, 0xB3000000.toInt(),
            Shader.TileMode.CLAMP
        )
    }

    private fun withAlpha(fraction: Float): Int {
        val alpha = (fraction.coerceIn(0f, 1f) * 255f).toInt()
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        glowShader?.let { shader ->
            glowPaint.shader = shader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
            glowPaint.shader = null
        }

        shadeShader?.let { shader ->
            shadePaint.shader = shader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
            shadePaint.shader = null
        }
    }

    companion object {
        private val DEFAULT_COLOR = 0xFF0A84FF.toInt()
    }
}
