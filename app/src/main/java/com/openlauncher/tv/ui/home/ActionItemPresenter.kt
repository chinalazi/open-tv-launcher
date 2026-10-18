package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.openlauncher.tv.R

class ActionItemPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val textView = TextView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = ViewGroup.LayoutParams(dp(context, 220), dp(context, 100))
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(ContextCompat.getColor(context, R.color.card_bg))
            foreground = ContextCompat.getDrawable(context, R.drawable.card_foreground)
        }
        textView.setOnFocusChangeListener { view, focused ->
            val scale = if (focused) 1.08f else 1.0f
            view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(if (focused) 220L else 150L)
                .start()
        }
        return ViewHolder(textView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val action = item as? HomeFragment.ActionItem ?: return
        val textView = viewHolder.view as TextView
        textView.text = action.title
        // 系统图标着色后放到上方
        val icon = ContextCompat.getDrawable(textView.context, action.iconRes)
        icon?.setBounds(0, 0, dp(textView.context, 36), dp(textView.context, 36))
        textView.setCompoundDrawables(null, icon, null, null)
        textView.compoundDrawablePadding = dp(textView.context, 6)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val textView = viewHolder.view as TextView
        textView.setCompoundDrawables(null, null, null, null)
    }

    @Suppress("unused")
    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
