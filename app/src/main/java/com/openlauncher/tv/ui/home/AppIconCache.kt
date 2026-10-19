package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat

/** 应用图标缓存：避免每次绑定卡片都同步走一次 PackageManager。 */
object AppIconCache {

    private val cache = LruCache<String, Drawable>(80)

    fun get(context: Context, packageName: String): Drawable {
        cache.get(packageName)?.let { return it }
        val drawable = runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrNull()
            ?: ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)!!
        cache.put(packageName, drawable)
        return drawable
    }
}
