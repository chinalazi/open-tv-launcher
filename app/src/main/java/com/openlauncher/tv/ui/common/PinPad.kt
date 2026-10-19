package com.openlauncher.tv.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.openlauncher.tv.R
import com.openlauncher.tv.data.parental.ParentalRepository

/**
 * 遥控器友好的 PIN 九宫格：上方圆点显示输入位数，下方玻璃键位。
 * 不依赖系统输入法（部分电视默认 IME 指向未安装的输入法，EditText 完全无法输入）。
 */
class PinPad @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val dotsView = PinDotsView(context)
    private val value = StringBuilder()
    private val digitKeys = ArrayList<TextView>()
    private var onComplete: ((String) -> Unit)? = null
    private var inputEnabled = true

    var maxLength: Int = ParentalRepository.PIN_LENGTH
        set(newValue) {
            field = newValue.coerceIn(4, 6)
            dotsView.total = field
            render()
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setClipChildren(false)
        setClipToPadding(false)

        addView(dotsView, LayoutParams(LayoutParams.MATCH_PARENT, dp(38)))

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

        dotsView.total = maxLength
        render()
    }

    fun setOnPinComplete(listener: (String) -> Unit) {
        onComplete = listener
    }

    fun focusFirst(): Boolean = digitKeys.firstOrNull()?.requestFocus() ?: false

    /** 校验成功后禁止继续输入（键位变暗），避免重复提交。 */
    fun setInputEnabled(enabled: Boolean) {
        inputEnabled = enabled
        for (i in 0 until childCount) {
            val row = getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val key = row.getChildAt(j) as? TextView ?: continue
                key.isEnabled = enabled
                key.isClickable = enabled
                key.alpha = if (enabled) 1f else 0.45f
            }
        }
    }

    fun clear() {
        value.setLength(0)
        render()
    }

    private fun createRow(labels: List<String>): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        for (label in labels) {
            val key = createKey(label)
            row.addView(
                key,
                LayoutParams(0, dp(52), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
            )
            if (label.length == 1) digitKeys.add(key)
        }
        return row
    }

    private fun createKey(label: String): TextView = TextView(context).apply {
        text = label
        textSize = if (label.length > 1) 13.5f else 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.pin_key)
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener {
            when (label) {
                KEY_CLEAR -> clear()
                KEY_SUBMIT -> submit()
                else -> append(label)
            }
        }
        setOnFocusChangeListener { view, focused ->
            val scale = if (focused) 1.05f else 1f
            view.animate().scaleX(scale).scaleY(scale).setDuration(140L).start()
        }
    }

    /** 遥控器 / 键盘数字键直入（由宿主在 onKeyDown 里转发）。 */
    fun handleKey(keyCode: Int): Boolean {
        if (!inputEnabled) return false
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
        if (!inputEnabled || value.length >= maxLength) return
        value.append(digit)
        render()
        if (value.length == maxLength) submit()
    }

    private fun backspace() {
        if (value.isEmpty()) return
        value.setLength(value.length - 1)
        render()
    }

    private fun submit() {
        if (!inputEnabled) return
        if (value.length != maxLength) return
        onComplete?.invoke(value.toString())
    }

    private fun render() {
        dotsView.filled = value.length
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /** 输入位数圆点：未填为空心玻璃点，已填为白色实点。 */
    private class PinDotsView(context: Context) : View(context) {

        var total: Int = ParentalRepository.PIN_LENGTH
            set(newValue) {
                field = newValue
                invalidate()
            }

        var filled: Int = 0
            set(newValue) {
                field = newValue
                invalidate()
            }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.2f)
            color = 0x38FFFFFF
        }

        override fun onDraw(canvas: Canvas) {
            val dot = dp(13f)
            val gap = dp(12f)
            val radius = dot / 2f
            val contentWidth = total * dot + (total - 1) * gap
            var left = (width - contentWidth) / 2f
            val centerY = height / 2f

            for (i in 0 until total) {
                val cx = left + radius
                if (i < filled) {
                    fillPaint.color = 0xFFFFFFFF.toInt()
                    canvas.drawCircle(cx, centerY, radius, fillPaint)
                } else {
                    fillPaint.color = 0x1AFFFFFF
                    canvas.drawCircle(cx, centerY, radius, fillPaint)
                    canvas.drawCircle(cx, centerY, radius, strokePaint)
                }
                left += dot + gap
            }
        }

        private fun dp(value: Float): Float =
            value * resources.displayMetrics.density
    }

    private companion object {
        const val KEY_CLEAR = "清除"
        const val KEY_SUBMIT = "确认"
    }
}
