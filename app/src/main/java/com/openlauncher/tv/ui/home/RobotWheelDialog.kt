package com.openlauncher.tv.ui.home

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import com.openlauncher.tv.R

/**
 * 机器人动作环状菜单的全屏窗口。遮罩由 [RobotWheelView] 自己画，窗口本身透明。
 */
class RobotWheelDialog(
    context: Context,
    private val currentId: String?
) : Dialog(context, R.style.DialogSheet) {

    var onPick: ((String) -> Unit)? = null

    private lateinit var wheel: RobotWheelView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wheel = RobotWheelView(context)
        setContentView(
            wheel,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        wheel.bind(currentId)
        wheel.onConfirm = { action ->
            onPick?.invoke(action.id)
            dismiss()
        }
        wheel.onCancel = { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        wheel.requestFocus()
    }
}
