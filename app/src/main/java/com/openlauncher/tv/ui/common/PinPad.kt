package com.openlauncher.tv.ui.common

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.openlauncher.tv.R
import com.openlauncher.tv.data.parental.ParentalRepository

/**
 * 遥控器友好的 PIN 九宫格。
 * 不依赖系统输入法（部分电视的默认 IME 指向未安装的输入法，EditText 会完全无法输入）。
 */
class PinPad @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleView = TextView(context)
    private val displayView = TextView(context)
    private val value = StringBuilder()
    private val digitButtons = ArrayList<Button>()
    private var onComplete: ((String) -> Unit)? = null

    var maxLength: Int = ParentalRepository.PIN_LENGTH
        set(value) {
            field = value.coerceIn(4, 6)
            renderDisplay()
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        titleView.apply {
            textSize = 18f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            text = "请输入家长 PIN 码"
        }
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        displayView.apply {
            textSize = 34f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_panel)
        }
        addView(
            displayView,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(12) }
        )

        for (labels in listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(KEY_CLEAR, "0", KEY_SUBMIT)
        )) {
            addView(
                createRow(labels),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
        }

        renderDisplay()
    }

    fun setOnPinComplete(listener: (String) -> Unit) {
        onComplete = listener
    }

    fun focusFirst(): Boolean = digitButtons.firstOrNull()?.requestFocus() ?: false

    fun clear() {
        value.setLength(0)
        renderDisplay()
    }

    private fun createRow(labels: List<String>): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (label in labels) {
            val button = Button(context).apply {
                text = label
                textSize = if (label.length > 1) 16f else 24f
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.pin_key)
                isFocusable = true
                isFocusableInTouchMode = true
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                stateListAnimator = null
            }
            button.setOnClickListener {
                when (label) {
                    KEY_CLEAR -> clear()
                    KEY_SUBMIT -> submit()
                    else -> append(label)
                }
            }
            row.addView(
                button,
                LayoutParams(0, dp(62), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
            )
            if (label.length == 1) digitButtons.add(button)
        }
        return row
    }

    /** 遥控器/键盘数字键直入（由宿主在 dispatchKeyEvent / onKeyDown 里转发）。 */
    fun handleKey(keyCode: Int): Boolean {
        return when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                backspace()
                true
            }
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                append((keyCode - KeyEvent.KEYCODE_0).toString())
                true
            }
            else -> false
        }
    }

    private fun append(digit: String) {
        if (value.length >= maxLength) return
        value.append(digit)
        renderDisplay()
        if (value.length == maxLength) submit()
    }

    private fun backspace() {
        if (value.isEmpty()) return
        value.setLength(value.length - 1)
        renderDisplay()
    }

    private fun submit() {
        val pin = value.toString()
        if (pin.length != maxLength) return
        onComplete?.invoke(pin)
    }

    private fun renderDisplay() {
        val filled = "*".repeat(value.length)
        val empty = "-".repeat((maxLength - value.length).coerceAtLeast(0))
        displayView.text = filled + empty
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val KEY_CLEAR = "清除"
        private const val KEY_SUBMIT = "确认"
    }
}
