package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.openlauncher.tv.data.local.AppItemEntity

class AppCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(260, 160)
            setBackgroundColor(Color.parseColor("#252830"))
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val appItem = item as? AppItemEntity ?: return
        val cardView = viewHolder.view as ImageCardView
        val context = cardView.context

        cardView.titleText = appItem.label
        cardView.contentText = appItem.packageName

        try {
            val pm = context.packageManager
            val icon = pm.getApplicationIcon(appItem.packageName)
            cardView.mainImageView.setImageDrawable(icon)
        } catch (e: Exception) {
            cardView.mainImageView.setImageResource(android.R.drawable.sym_def_app_icon)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.badgeImage = null
        cardView.mainImage = null
    }
}
