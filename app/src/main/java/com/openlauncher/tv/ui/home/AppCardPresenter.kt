package com.openlauncher.tv.ui.home

import android.content.Context
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.openlauncher.tv.R
import com.openlauncher.tv.data.local.AppItemEntity

class AppCardPresenter(
    private val onLongClick: (AppItemEntity) -> Unit = {}
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val cardView = ImageCardView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(dp(context, 264), dp(context, 168))
            setBackgroundColor(ContextCompat.getColor(context, R.color.card_bg))
            foreground = ContextCompat.getDrawable(context, R.drawable.card_foreground)
        }
        cardView.setOnFocusChangeListener { view, focused ->
            val scale = if (focused) 1.08f else 1.0f
            view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(if (focused) 220L else 150L)
                .start()
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val appItem = item as? AppItemEntity ?: return
        val cardView = viewHolder.view as ImageCardView

        cardView.titleText = appItem.label
        cardView.contentText = appItem.packageName
        cardView.mainImage = AppIconCache.get(cardView.context, appItem.packageName)
        cardView.setOnLongClickListener {
            onLongClick(appItem)
            true
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.badgeImage = null
        cardView.mainImage = null
        cardView.setOnLongClickListener(null)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
