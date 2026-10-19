package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.LruCache

/**
 * 从应用图标里取一个"品牌色"：把图标画到 16x16 缩略图上，只统计高饱和像素，
 * 再统一把饱和度/亮度拉回可控区间。结果缓存在内存里，卡片与氛围光共用。
 */
object AppPalette {

    private const val SAMPLE = 16
    private const val SAT_THRESHOLD = 0.28f

    private val cache = LruCache<String, Int>(64)
    private val neutral = 0xFF3F4756.toInt()

    fun colorOf(context: Context, packageName: String): Int {
        cache.get(packageName)?.let { return it }
        val color = runCatching { extract(context, packageName) }.getOrDefault(neutral)
        cache.put(packageName, color)
        return color
    }

    private fun extract(context: Context, packageName: String): Int {
        val drawable = AppIconCache.get(context, packageName)
        val bitmap = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, SAMPLE, SAMPLE)
        drawable.draw(canvas)

        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var sw = 0.0
        var ar = 0.0
        var ag = 0.0
        var ab = 0.0
        var aw = 0.0
        val hsv = FloatArray(3)

        for (y in 0 until SAMPLE) {
            for (x in 0 until SAMPLE) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                if (alpha < 40) continue
                val a = alpha / 255.0
                Color.colorToHSV(pixel, hsv)
                ar += Color.red(pixel) * a
                ag += Color.green(pixel) * a
                ab += Color.blue(pixel) * a
                aw += a
                if (hsv[1] >= SAT_THRESHOLD) {
                    sr += Color.red(pixel) * a * hsv[1]
                    sg += Color.green(pixel) * a * hsv[1]
                    sb += Color.blue(pixel) * a * hsv[1]
                    sw += a * hsv[1]
                }
            }
        }
        bitmap.recycle()

        val useSaturated = sw > 0.0
        val weight = if (useSaturated) sw else aw
        if (weight <= 0.0) return neutral

        val r = ((if (useSaturated) sr else ar) / weight).toInt()
        val g = ((if (useSaturated) sg else ag) / weight).toInt()
        val b = ((if (useSaturated) sb else ab) / weight).toInt()
        Color.colorToHSV(Color.rgb(r, g, b), hsv)
        // 图标本身是灰白的（绝大多数系统工具）就不染品牌色，走中性蓝灰
        if (hsv[1] < 0.12f) return neutral

        hsv[1] = hsv[1].coerceAtLeast(0.55f)
        hsv[2] = 0.62f
        return Color.HSVToColor(hsv)
    }
}
