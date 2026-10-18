package com.openlauncher.tv.ui.home

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter

class ActionItemPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val textView = TextView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            width = 240
            height = 110
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.parseColor("#334155"))
        }
        return ViewHolder(textView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val action = item as? HomeFragment.ActionItem ?: return
        val tv = viewHolder.view as TextView
        tv.text = action.title
        tv.setCompoundDrawablesWithIntrinsicBounds(0, action.iconRes, 0, 0)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val tv = viewHolder.view as TextView
        tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
    }
}
