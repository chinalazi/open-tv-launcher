package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * 全屏壁纸背景：把一张极小的缩略图放大铺满（天然糊化，低配电视上比高斯模糊便宜得多），
 * 再压一层从上到下的暗色渐变，保证磁贴和文字始终清晰。
 *
 * 没缓存到图时画一个内置的深色渐变兜底，绝不出现白屏。
 */
class BackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var bitmap: Bitmap? = null
    private val srcRect = Rect()
    private val dstRect = RectF()

    init {
        // 兜底色：薄荷浅绿，和提亮后的壁纸接近，第一帧不会突兀
        fallbackPaint.shader = LinearGradient(
            0f, 0f, 0f, 1080f,
            intArrayOf(0xFFE2EFE9.toInt(), 0xFFD2E4DB.toInt(), 0xFFC2D8CD.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    fun setBitmap(value: Bitmap?) {
        bitmap = value
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 半透明白把照片压成"洗过"的低对比底色，文字和磁贴才能压得住
        scrimPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x4DFFFFFF.toInt(), 0x66FFFFFF.toInt(), 0x8CFFFFFF.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        // 左上角补一层淡绿光晕，避免整屏发灰
        tintPaint.shader = RadialGradient(
            w * 0.18f, h * 0.12f, h * 1.15f,
            intArrayOf(0x3D12B57F, 0x1212B57F, 0x0012B57F),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val bmp = bitmap
        if (bmp == null || bmp.isRecycled) {
            canvas.drawRect(0f, 0f, w, h, fallbackPaint)
        } else {
            // centerCrop：等比放大到铺满，再居中裁切
            val scale = maxOf(w / bmp.width, h / bmp.height)
            val drawW = bmp.width * scale
            val drawH = bmp.height * scale
            val left = (w - drawW) / 2f
            val top = (h - drawH) / 2f
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
        }

        canvas.drawRect(0f, 0f, w, h, scrimPaint)
        canvas.drawRect(0f, 0f, w, h, tintPaint)
    }

    override fun setAlpha(alpha: Float) {
        super.setAlpha(alpha)
        bitmapPaint.alpha = (Color.alpha(Color.WHITE) * alpha).toInt()
        invalidate()
    }
}
