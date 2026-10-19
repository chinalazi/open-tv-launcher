package com.openlauncher.tv.ui.home

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.openlauncher.tv.R
import com.openlauncher.tv.app.LauncherApplication
import com.openlauncher.tv.data.local.AppItemEntity
import kotlinx.coroutines.launch

/** 长按卡片的玻璃菜单：打开 / 白名单 / 隐藏。 */
object AppMenu {

    fun show(activity: FragmentActivity, item: AppItemEntity, onOpen: () -> Unit) {
        val dialog = Dialog(activity, R.style.Theme_OpenTVLauncher_Dialog)
        dialog.setContentView(R.layout.dialog_app_menu)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.findViewById<TextView>(R.id.menu_title)?.text = item.label
        dialog.findViewById<TextView>(R.id.menu_sub)?.text = item.packageName
        val container = dialog.findViewById<LinearLayout>(R.id.menu_items) ?: return

        var firstRow: View? = null

        fun addItem(label: String, action: () -> Unit) {
            val row = TextView(activity).apply {
                text = label
                textSize = 15f
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_menu_item)
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(activity, 16), 0, dp(activity, 16), 0)
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 52)
            ).apply { bottomMargin = dp(activity, 6) }
            container.addView(row, lp)
            if (firstRow == null) firstRow = row
        }

        addItem("打开应用") { onOpen() }
        addItem(
            if (item.isWhitelistForKids) "移出白名单（重新计时）" else "加入白名单（不计时）"
        ) {
            toggleWhitelist(activity, item)
        }
        addItem(if (item.isHidden) "取消隐藏" else "隐藏应用") {
            toggleHidden(activity, item)
        }

        dialog.setOnShowListener { firstRow?.requestFocus() }
        dialog.show()
    }

    private fun toggleWhitelist(activity: FragmentActivity, item: AppItemEntity) {
        val app = activity.application as LauncherApplication
        app.applicationScope.launch {
            app.database.launcherDao().setKidsWhitelist(item.id, !item.isWhitelistForKids)
        }
        Toast.makeText(
            activity,
            if (item.isWhitelistForKids) "已移出白名单，该应用重新计时" else "已加入白名单，该应用不计入观看时长",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleHidden(activity: FragmentActivity, item: AppItemEntity) {
        val app = activity.application as LauncherApplication
        app.applicationScope.launch {
            app.database.launcherDao().setHidden(item.id, !item.isHidden)
        }
        Toast.makeText(
            activity,
            if (item.isHidden) "已恢复显示" else "已隐藏「${item.label}」，可在 设置 → 分类与应用管理 中恢复",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dp(activity: FragmentActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
