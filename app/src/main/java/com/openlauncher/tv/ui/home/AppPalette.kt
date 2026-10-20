package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.LruCache

/**
 * 取应用图标的主色，用来做「氛围光」（Apple TV 聚焦应用时背景泛出的那层光）。
 * 只在小尺寸位图上抽样平均，成本可控；失败就退回到稳定的色板。
 */
object AppPalette {

    private val cache = LruCache<String, Int>(128)

    private val FALLBACK = intArrayOf(
        0xFF0A84FF.toInt(), // 蓝
        0xFF30D158.toInt(), // 绿
        0xFFFF9F0A.toInt(), // 橙
        0xFFFF375F.toInt(), // 粉红
        0xFFBF5AF2.toInt(), // 紫
        0xFF64D2FF.toInt(), // 青
        0xFFFFD60A.toInt(), // 黄
        0xFF5E5CE6.toInt()  // 靛
    )

    fun colorOf(context: Context, packageName: String): Int {
        cache.get(packageName)?.let { return it }
        val color = runCatching { fromIcon(context, packageName) }.getOrNull()
            ?: fallback(packageName)
        cache.put(packageName, color)
        return color
    }

    private fun fromIcon(context: Context, packageName: String): Int {
        val size = 36
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val drawable = AppIconCache.get(context, packageName)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)

            var r = 0.0
            var g = 0.0
            var b = 0.0
            var weight = 0.0
            var samples = 0
            val hsv = FloatArray(3)
            for (x in 0 until size step 2) {
                for (y in 0 until size step 2) {
                    val px = bitmap.getPixel(x, y)
                    if (px ushr 24 < 200) continue
                    Color.colorToHSV(px, hsv)
                    val w = (hsv[1] * hsv[2]).toDouble().coerceAtLeast(0.04)
                    r += Color.red(px) * w
                    g += Color.green(px) * w
                    b += Color.blue(px) * w
                    weight += w
                    samples++
                }
            }
            if (samples == 0 || weight <= 0.0) return fallback(packageName)

            val average = Color.rgb(
                (r / weight).toInt().coerceIn(0, 255),
                (g / weight).toInt().coerceIn(0, 255),
                (b / weight).toInt().coerceIn(0, 255)
            )
            Color.colorToHSV(average, hsv)
            hsv[1] = hsv[1].coerceIn(0.45f, 0.72f)
            hsv[2] = hsv[2].coerceIn(0.52f, 0.80f)
            return Color.HSVToColor(hsv)
        } finally {
            bitmap.recycle()
        }
    }

    private fun fallback(packageName: String): Int {
        val index = (packageName.hashCode().toLong() and 0x7FFFFFFF) % FALLBACK.size
        return FALLBACK[index.toInt()]
    }
}
