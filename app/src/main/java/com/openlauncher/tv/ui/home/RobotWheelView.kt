package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 机器人动作环状菜单。
 *
 * 电视上没有指针，环状菜单最顺手的操作是「左右转」而不是「用方向键找焦点」：
 * 选中的那一条永远停在 12 点方向，按左右键整圈滚动，按 OK 确定，按返回关掉。
 * 整块画在一块 Canvas 上，没有子 View，焦点问题一次性消失。
 */
class RobotWheelView(context: Context) : View(context) {

    private val items = DesktopPetView.PetAction.wheel
    private val density = resources.displayMetrics.density

    private var pos = 0f          // 连续的位置（以「条」为单位，可以超出 0..n 范围）
    private var target = 0f
    private var lastFrame = 0L
    private var openAnim = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    var onConfirm: ((DesktopPetView.PetAction) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val count: Int get() = items.size

    private val selectedIndex: Int
        get() {
            val r = pos.roundToInt() % count
            return if (r < 0) r + count else r
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun bind(currentId: String?) {
        val idx = items.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        pos = idx.toFloat()
        target = pos
        openAnim = 0f
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    private fun rotate(delta: Int) {
        target += delta
        postInvalidateOnAnimation()
    }

    private fun confirm() {
        onConfirm?.invoke(items[selectedIndex])
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                rotate(-1); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                rotate(1); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                confirm(); return true
            }
            KeyEvent.KEYCODE_BACK -> {
                onCancel?.invoke(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 触屏（adb 调试时用）：点哪个就把它转到 12 点并确认。 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val cx = width / 2f
            val cy = height / 2f
            val deg = Math.toDegrees(
                atan2((event.y - cy).toDouble(), (event.x - cx).toDouble())
            ).toFloat()
            val stepDeg = 360f / count
            var offset = (deg + 90f) / stepDeg          // 点中的是「当前选中往前第几个」
            offset -= (offset / count).roundToInt() * count   // 折回 -n/2..n/2
            target = pos + offset
            confirm()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.05f)
        lastFrame = now

        openAnim = approach(openAnim, 1f, 12f, dt)
        pos = approach(pos, target, 15f, dt)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // 遮罩：厚一点，桌面亮壁纸会盖过菜单
        fill.color = withAlpha(0xFF07110E.toInt(), (228 * openAnim).toInt())
        canvas.drawRect(0f, 0f, w, h, fill)

        val radius = min(w, h) * 0.30f * (0.93f + 0.07f * openAnim)
        val nodeR = 48f * density
        val stepDeg = 360f / count

        // 环形轨道
        stroke.color = 0x33FFFFFF
        stroke.strokeWidth = 1.2f * density
        canvas.drawCircle(cx, cy, radius, stroke)

        // 中心：当前选中项的名字与说明
        val cur = items[selectedIndex]
        text.typeface = medium
        text.color = 0xF2FFFFFF.toInt()
        text.textSize = sp(25f)
        canvas.drawText(cur.label, cx, cy - 2f * density, text)
        text.typeface = null
        text.color = 0xA39FC3B6.toInt()
        text.textSize = sp(12f)
        canvas.drawText(cur.desc, cx, cy + 26f * density, text)
        text.color = 0x59FFFFFF
        text.textSize = sp(9f)
        canvas.drawText("${selectedIndex + 1} / $count", cx, cy + 50f * density, text)

        // 环上的条目
        for (i in 0 until count) {
            val delta = i - pos
            val dist = abs(delta)
            val angRad = (-90f + delta * stepDeg) * PI.toFloat() / 180f
            val nx = cx + cos(angRad) * radius
            val ny = cy + sin(angRad) * radius
            val focusness = (1f - dist / 2.2f).coerceIn(0f, 1f)
            val r = nodeR * (0.82f + 0.20f * focusness)
            val alpha = (0.52f + 0.48f * focusness).coerceIn(0f, 1f)

            if (focusness > 0.55f) {
                fill.color = withAlpha(0xFF12B57F.toInt(), (238 * openAnim).toInt())
                canvas.drawCircle(nx, ny, r, fill)
                stroke.color = withAlpha(Color.WHITE, (72 * openAnim).toInt())
                stroke.strokeWidth = 3f * density
                canvas.drawCircle(nx, ny, r + 3.5f * density, stroke)
            } else {
                fill.color = withAlpha(0xFF0E2A24.toInt(), (215 * alpha * openAnim).toInt())
                canvas.drawCircle(nx, ny, r, fill)
                stroke.color = withAlpha(Color.WHITE, (40 * alpha * openAnim).toInt())
                stroke.strokeWidth = 1.4f * density
                canvas.drawCircle(nx, ny, r, stroke)
            }

            text.color = if (focusness > 0.55f) 0xFFF4FFFB.toInt()
            else withAlpha(0xFFD5EAE1.toInt(), (235 * alpha).toInt())
            text.textSize = sp(13.5f)
            text.typeface = if (focusness > 0.55f) medium else null
            canvas.drawText(items[i].label, nx, ny + text.textSize * 0.36f, text)
        }
        text.typeface = null

        // 12 点方向的指示三角（在选中节点外侧）
        val tipY = cy - radius - nodeR - 6f * density
        fill.color = withAlpha(0xFF12B57F.toInt(), (225 * openAnim).toInt())
        path.reset()
        path.moveTo(cx, tipY + 11f * density)
        path.lineTo(cx - 9f * density, tipY - 5f * density)
        path.lineTo(cx + 9f * density, tipY - 5f * density)
        path.close()
        canvas.drawPath(path, fill)

        // 底部操作提示
        text.color = 0x8CF2FFF9.toInt()
        text.textSize = sp(11f)
        canvas.drawText("左右键 选择动作        OK 确定        返回 关闭", cx, h - 30f * density, text)

        if (abs(target - pos) > 0.002f || openAnim < 0.999f) postInvalidateOnAnimation()
    }

    private fun sp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )

    private fun approach(cur: Float, target: Float, k: Float, dt: Float): Float =
        cur + (target - cur) * (1f - exp(-k * dt))

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
