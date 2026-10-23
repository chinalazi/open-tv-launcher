package com.openlauncher.tv.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 电视桌面机器人伴侣（双人版）：
 * 左边是原来那只青色的小蓝，右边新加一只玫粉的小玫，共用同一套绘制与物理代码，
 * 只是配色和几个装饰图层不同，所以两只天生同风格。
 *
 * 动作分两类：
 *  - 单人：自由巡航；
 *  - 双人：打招呼 / 击掌 / 双人舞 / 追着玩 / 送光花 / 爱心共鸣 / 依偎 / 拌嘴 / 抱抱。
 *
 * 默认「自动轮播」：一个个动作演过去，看腻了可以用桌面右下角的机器人按钮换成指定动作。
 *
 * 性能：纯 Canvas 几何绘制，画笔 / RectF / Path 全部复用，不建 Bitmap、不加载图片，
 * 老电视（小米 4A）上也能稳住帧率。发光一律用「三圈递减透明度」手绘，不用 setShadowLayer，
 * 因为后者在弱 GPU 上是软件路径，非常慢。
 */
class DesktopPetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 桌面机器人能演的动作。id 和设置轮盘里的条目一一对应。 */
    enum class PetAction(val id: String, val label: String, val desc: String, val durationMs: Long) {
        AUTO("auto", "自动轮播", "按顺序把下面的动作都演一遍", 0L),
        GREET("greet", "打招呼", "靠拢挥手，冒对话气泡", 4400L),
        HIGHFIVE("highfive", "击掌", "掌心相击，炸出火花", 3600L),
        DANCE("dance", "双人舞", "绕同一个圆心公转", 6600L),
        CHASE("chase", "追着玩", "一个跑一个追，带速度线", 6200L),
        GIFT("gift", "送光花", "凝出一朵光花递过去", 5000L),
        HEART("heart", "爱心共鸣", "核心对射，中间聚成心", 4800L),
        LEAN("lean", "依偎", "歪头靠肩，飘小心心", 5200L),
        QUARREL("quarrel", "拌嘴", "摇头不服，最后和好", 5400L),
        HUG("hug", "抱抱", "张开手臂环抱，爆爱心", 4400L),
        FREE("free", "自由巡航", "各自漂浮漫游，互不打扰", 0L);

        companion object {
            fun fromId(id: String?): PetAction = values().firstOrNull { it.id == id } ?: AUTO
            /** 轮盘里的顺序 */
            val wheel = listOf(AUTO, GREET, HIGHFIVE, DANCE, CHASE, GIFT, HEART, LEAN, QUARREL, HUG, FREE)
            /** 自动轮播时依次播放的动作 */
            val autoSequence = listOf(GREET, HIGHFIVE, DANCE, CHASE, GIFT, HEART, LEAN, QUARREL, HUG)
        }
    }

    private class Skin(
        val chassis: Int,
        val border: Int,
        val visor: Int,
        val neon: Int,
        val core: Int,
        val female: Boolean
    )

    private companion object {
        private const val MAX_SPEED = 150f      // px / s
        private const val TAU = (PI * 2).toFloat()
        private const val EDGE_PAD = 90f

        private val MALE = Skin(
            chassis = 0xFFF8FAFC.toInt(), border = 0xFFCBD5E1.toInt(), visor = 0xFF090D16.toInt(),
            neon = 0xFF00F0FF.toInt(), core = 0xFF00F0FF.toInt(), female = false
        )
        private val FEMALE = Skin(
            chassis = 0xFFFFF6FA.toInt(), border = 0xFFF4C6DA.toInt(), visor = 0xFF1B0A13.toInt(),
            neon = 0xFFFF5C9D.toInt(), core = 0xFFFF6FA8.toInt(), female = true
        )

        private const val FACE_NORMAL = 0
        private const val FACE_BLINK = 1
        private const val FACE_HAPPY = 2
        private const val FACE_LOVE = 3
        private const val FACE_DIZZY = 4
        private const val FACE_ANGRY = 5
        private const val FACE_WIDE = 6

        private const val MOUTH_NONE = 0
        private const val MOUTH_SMILE = 1
        private const val MOUTH_SAD = 2
    }

    private inner class Robot(val skin: Skin) {
        val sizeFactor = if (skin.female) 0.96f else 1f
        var x = 0f
        var y = 0f
        var targetX = 0f
        var targetY = 0f
        var targetAt = 0f
        var tilt = 0f
        var roll = 0f
        var face = 1
        var tick = 0f
        var blinkT = 0f
        var blinking = false
        var eye = FACE_NORMAL
        var mouth = MOUTH_NONE
        var armLX = -32f
        var armLY = 11f
        var armRX = 32f
        var armRY = 11f
        var headX = 0f
        var headY = 0f

        fun resetPose() {
            eye = FACE_NORMAL
            mouth = MOUTH_NONE
            armLX = -32f; armLY = 11f
            armRX = 32f; armRY = 11f
            headX = 0f; headY = 0f
        }

        val centerY: Float get() = -3f + headY
    }

    private val male = Robot(MALE)
    private val female = Robot(FEMALE)

    private val random = Random()

    /**
     * 机器人的「舞台」：屏幕坐标系里允许活动的矩形，默认（null）等于整块自己。
     *
     * 为什么需要它：桌面左上角被联网监视器整块占掉了，机器人是一层全屏自绘、盖在所有控件
     * 之上，飘过去就把速率数字糊住了。动作编排（打招呼站中间、双人舞绕屏幕中心转）本来就
     * 是按「整块屏幕」写的，与其改十来个动作函数，不如把坐标系本身缩到一块空地上：
     * 逻辑尺寸用舞台宽高，绘制时整体平移到舞台左上角。
     */
    var stageRect: RectF? = null
        set(value) {
            field = value
            initialized = false      // 舞台换了，两只的位置要按新尺寸重算
        }

    /** 舞台变小后别跟着把机器人缩小：固定倍率顶掉按尺寸算出来的缩放。0 = 自动。 */
    var fixedScale = 0f

    /** 当前舞台宽高，动作编排里的 width / height 全部指它。 */
    private var worldW = 0f
    private var worldH = 0f

    // ---- 画笔池 ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    // ---- 几何复用 ----
    private val rect = RectF()
    private val path = Path()

    // ---- 动作状态 ----
    private var action = PetAction.AUTO
    private var actionTime = 0f          // 秒
    private var actionDur = 0f           // 秒；0 = 不自动结束
    private var autoIndex = 0
    private var stage = 0                // 动作内部阶段标记（替代一堆布尔）
    private var lastRemoteAt = SystemClock.uptimeMillis()

    // ---- 通用动画量 ----
    private var tick = 0f
    private var orbX = 0f
    private var orbY = 0f
    private var orbR = 0f
    private var hugArms = 0f
    private var beam = 0f

    private var running = false
    private var initialized = false
    private var lastFrameAt = 0L

    // ---- 粒子 / 气泡 ----
    private class Particle {
        var kind = 0                      // 0 心 1 火花 2 音符 3 冲击环
        var x = 0f; var y = 0f
        var vx = 0f; var vy = 0f
        var size = 0f
        var rot = 0f; var rotV = 0f
        var life = 0f; var max = 1f
        var color = 0
    }

    private class Bubble {
        var robot: Robot? = null
        var text = ""
        var life = 0f
        var max = 1f
    }

    private val particles = ArrayList<Particle>(64)
    private val bubbleList = ArrayList<Bubble>(4)

    init {
        isFocusable = false
        isClickable = false
        setWillNotDraw(false)
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pauseAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) resumeAnimation() else pauseAnimation()
    }

    fun resumeAnimation() {
        if (!running) {
            running = true
            lastFrameAt = 0L
            postInvalidateOnAnimation()
        }
    }

    fun pauseAnimation() {
        running = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 真正的初始化放在 onDraw 里做：只有到那时才知道舞台多大
        initialized = false
    }

    // ---------------------------------------------------------------- 外部接口

    /** 遥控器有操作：眼睛看过来，偶尔凑近一点。 */
    fun onRemoteActivity() {
        lastRemoteAt = SystemClock.uptimeMillis()
        male.headX = (random.nextFloat() - 0.5f) * 6f
        female.headX = (random.nextFloat() - 0.5f) * 6f
        if (action == PetAction.FREE) {
            // 别直接摇到屏幕中上部：那块是联网监视器，机器人会盖住数字。
            // 统一走 pickTarget，落点永远在舞台里。
            if (random.nextFloat() < 0.25f) pickTarget(male)
            if (random.nextFloat() < 0.25f) pickTarget(female)
        }
        postInvalidateOnAnimation()
    }

    /** 遥控器按下确认键。 */
    fun onUserAction() {
        lastRemoteAt = SystemClock.uptimeMillis()
        // 只是给个即时反应：两只一起眨下眼、歪下头，不打断正在演的动作
        listOf(male, female).forEach {
            it.blinking = true
            it.blinkT = 0.12f
            it.headX = (random.nextFloat() - 0.5f) * 8f
        }
        postInvalidateOnAnimation()
    }

    /** 设置轮盘里选中某个动作。 */
    fun setAction(id: String?) {
        val next = PetAction.fromId(id)
        action = next
        actionTime = 0f
        stage = 0
        beam = 0f
        orbR = 0f
        hugArms = 0f
        actionDur = next.durationMs / 1000f
        bubbleList.clear()
        if (next == PetAction.AUTO) {
            autoIndex = 0
            playAutoItem()
        }
        postInvalidateOnAnimation()
    }

    fun currentAction(): PetAction = action

    fun currentActionId(): String = action.id

    // ---------------------------------------------------------------- 动作切换

    private fun playAutoItem() {
        val next = PetAction.autoSequence[autoIndex % PetAction.autoSequence.size]
        actionTime = 0f
        stage = 0
        beam = 0f
        orbR = 0f
        hugArms = 0f
        actionDur = next.durationMs / 1000f
        playingAction = next
        postInvalidateOnAnimation()
    }

    /** AUTO 模式下真正在演的动作；单独选动作时就是它自己。 */
    private var playingAction: PetAction = PetAction.GREET

    private fun advanceAuto() {
        if (action == PetAction.AUTO) {
            autoIndex = (autoIndex + 1) % PetAction.autoSequence.size
            playAutoItem()
        } else {
            playingAction = action
        }
    }

    private fun finishAction() {
        if (action == PetAction.AUTO) {
            autoIndex = (autoIndex + 1) % PetAction.autoSequence.size
            playAutoItem()
        } else {
            // 单次动作演完回到自动轮播
            setAction(PetAction.AUTO.id)
        }
    }

    // ---------------------------------------------------------------- 主绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val st = stageRect
        worldW = st?.width() ?: width.toFloat()
        worldH = st?.height() ?: height.toFloat()
        if (worldW <= 0f || worldH <= 0f) return
        val ox = st?.left ?: 0f
        val oy = st?.top ?: 0f

        // 逻辑坐标一律以舞台左上角为原点，绘制时整体平移过去
        canvas.save()
        if (ox != 0f || oy != 0f) canvas.translate(ox, oy)

        val w = worldW
        val h = worldH

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0.016f else ((now - lastFrameAt) / 1000f).coerceIn(0.001f, 0.05f)
        lastFrameAt = now

        if (!initialized) {
            male.x = w * 0.58f; male.y = h * 0.44f
            female.x = w * 0.74f; female.y = h * 0.64f
            pickTarget(male); pickTarget(female)
            initialized = true
            setAction(action.id)
        }

        tick += dt * 3f
        update(dt.toDouble().toFloat(), w, h)
        updateParticles(dt)
        updateBubbles(dt)

        val s = viewScale()

        if (hugArms > 0.05f) drawHugArms(canvas)

        if (male.y <= female.y) {
            drawRobot(canvas, male, s * male.sizeFactor)
            drawRobot(canvas, female, s * female.sizeFactor)
        } else {
            drawRobot(canvas, female, s * female.sizeFactor)
            drawRobot(canvas, male, s * male.sizeFactor)
        }

        drawBeam(canvas)
        if (orbR > 1f) drawFlowerOrb(canvas, orbX, orbY, orbR, s)
        drawParticles(canvas, s)
        drawBubbles(canvas, s)

        canvas.restore()

        // 全屏重绘很吃 GPU，限到 ~45fps，动画照样顺
        if (running) postInvalidateDelayed(22L)
    }

    // ---------------------------------------------------------------- 状态机

    private fun update(dt: Float, w: Float, h: Float) {
        actionTime += dt
        if (actionDur > 0f && actionTime > actionDur) {
            finishAction()
            return
        }
        male.resetPose()
        female.resetPose()
        val current = if (action == PetAction.AUTO) playingAction else action
        val t = actionTime

        when (current) {
            PetAction.FREE -> updateFree(dt, w, h)
            PetAction.GREET -> updateGreet(dt, t)
            PetAction.HIGHFIVE -> updateHighFive(dt, t)
            PetAction.DANCE -> updateDance(dt, t)
            PetAction.CHASE -> updateChase(dt, t)
            PetAction.GIFT -> updateGift(dt, t, w, h)
            PetAction.HEART -> updateHeart(dt, t)
            PetAction.LEAN -> updateLean(dt, t)
            PetAction.QUARREL -> updateQuarrel(dt, t)
            PetAction.HUG -> updateHug(dt, t)
            PetAction.AUTO -> updateFree(dt, w, h)
        }

        if (current != PetAction.FREE) faceEachOther()
        applyBlink(dt)
        male.tick += dt * 3f
        female.tick += dt * 3f
    }

    private fun faceEachOther() {
        if (male.eye == FACE_ANGRY || female.eye == FACE_ANGRY) return
        if (abs(female.x - male.x) < 4f) return
        male.face = if (female.x >= male.x) 1 else -1
        female.face = if (male.x < female.x) -1 else 1
    }

    private fun applyBlink(dt: Float) {
        listOf(male, female).forEach { r ->
            if (r.eye == FACE_NORMAL || r.eye == FACE_WIDE) {
                r.blinkT -= dt
                if (r.blinkT <= 0f) {
                    if (r.blinking) {
                        r.blinking = false
                        r.blinkT = 2f + random.nextFloat() * 3.5f
                    } else {
                        r.blinking = true
                        r.blinkT = 0.1f
                    }
                }
                if (r.blinking) r.eye = FACE_BLINK
            } else {
                r.blinking = false
            }
        }
    }

    private fun pickTarget(r: Robot) {
        // 舞台本身就是空地，不用再躲别的东西了
        val tx = worldW * (0.12f + 0.76f * random.nextFloat())
        val ty = worldH * (0.15f + 0.72f * random.nextFloat())
        r.targetX = tx
        r.targetY = ty
        r.targetAt = actionTime
    }

    private fun updateFree(dt: Float, w: Float, h: Float) {
        listOf(male, female).forEach { r ->
            val far = hypot(r.targetX - r.x, r.targetY - r.y)
            if (far < 40f || actionTime - r.targetAt > 6.5f) pickTarget(r)
            val px = r.x
            r.x = approach(r.x, r.targetX, 1.1f, dt)
            r.y = approach(r.y, r.targetY + sin(r.tick * 1.5f) * 8f, 1.1f, dt)
            val vx = (r.x - px) / dt
            r.tilt = approach(r.tilt, (vx * 0.035f).coerceIn(-13f, 13f), 4f, dt)
            if (vx > 40f) r.face = 1 else if (vx < -40f) r.face = -1
        }
        // 太近就互相让开
        if (hypot(male.x - female.x, male.y - female.y) < 300f) {
            val dx = male.x - female.x
            val dy = male.y - female.y
            val d = maxOf(1f, hypot(dx, dy))
            male.targetX = (male.x + dx / d * 280f).coerceIn(240f, w - 240f)
            male.targetY = (male.y + dy / d * 200f).coerceIn(180f, h - 200f)
            female.targetX = (female.x - dx / d * 280f).coerceIn(240f, w - 240f)
            female.targetY = (female.y - dy / d * 200f).coerceIn(180f, h - 200f)
        }
    }

    private fun updateGreet(dt: Float, t: Float) {
        val s = viewScale()
        moveTo(male, worldW * 0.5f - 110f * s, worldH * 0.52f, 5f, dt)
        moveTo(female, worldW * 0.5f + 110f * s, worldH * 0.52f, 5f, dt)
        if (t > 0.7f) {
            val sway = sin((t - 0.7f) * 11f)
            male.armRX = 33f + sway * 3f
            male.armRY = -30f + abs(sway) * 6f
            male.eye = FACE_HAPPY
            male.mouth = MOUTH_SMILE
        }
        if (t > 1.15f) {
            val sway = sin((t - 1.15f) * 11f)
            female.armLX = -31f - sway * 3f
            female.armLY = -28f + abs(sway) * 6f
            female.eye = FACE_HAPPY
            female.mouth = MOUTH_SMILE
        }
        if (t > 0.8f && stage == 0) {
            stage = 1
            say(male, "你好呀～", 2.2f)
        }
        if (t > 1.35f && stage == 1) {
            stage = 2
            say(female, "嗨 ♪", 2.0f)
            spawnNote(female.x + randomRange(-30f, 30f), female.y - 40f, 0xFFFFB3D1.toInt())
        }
    }

    private fun updateHighFive(dt: Float, t: Float) {
        val s = viewScale()
        val cx = worldW * 0.5f
        moveTo(male, cx - 150f * s, worldH * 0.52f, 6f, dt)
        moveTo(female, cx + 150f * s, worldH * 0.52f, 6f, dt)

        val wind = (t / 0.55f).coerceIn(0f, 1f)
        val strike = ((t - 0.55f) / 0.35f).coerceIn(0f, 1f)
        val e = strike * strike * (3 - 2 * strike)
        val reach = lerp(lerp(32f, 12f, wind), (cx - male.x) / (s) + 2f, e)
        male.armRX = reach
        male.armRY = lerp(lerp(11f, 6f, wind), -2f, e)
        female.armLX = -((cx - female.x) / s + 2f)
        female.armLY = male.armRY

        if (strike > 0.82f && stage == 0) {
            stage = 1
            spawnSparks(cx, worldH * 0.52f - 8f, 40, Color.WHITE)
            spawnRing(cx, worldH * 0.52f - 6f, 0xFF7EF7FF.toInt())
            spawnRing(cx, worldH * 0.52f - 6f, Color.WHITE)
        }
        if (stage >= 1) {
            male.eye = FACE_HAPPY; female.eye = FACE_HAPPY
            male.mouth = MOUTH_SMILE; female.mouth = MOUTH_SMILE
        }
        if (stage >= 1 && t > 1.6f) {
            moveTo(male, cx - 130f * s, worldH * 0.52f, 2f, dt)
            moveTo(female, cx + 130f * s, worldH * 0.52f, 2f, dt)
            male.armRX = 44f; male.armRY = 4f
            female.armLX = -44f; female.armLY = 4f
        }
    }

    private fun updateDance(dt: Float, t: Float) {
        val r0 = 172f * viewScale()
        val cx = worldW * 0.5f
        val cy = worldH * 0.5f
        val ang = t * 1.75f
        val bounce = sin(t * 3.6f)
        moveTo(male, cx + cos(ang) * r0, cy + sin(ang) * r0 * 0.42f - bounce * 10f, 9f, dt)
        moveTo(female, cx - cos(ang) * r0, cy - sin(ang) * r0 * 0.42f + bounce * 10f, 9f, dt)
        male.tilt = sin(t * 3.6f + 0.4f) * 11f
        female.tilt = -sin(t * 3.6f + 1.1f) * 11f
        male.eye = FACE_HAPPY; female.eye = FACE_HAPPY
        female.mouth = MOUTH_SMILE
        male.armLX = -38f; male.armLY = -12f + bounce * 4f
        male.armRX = 38f; male.armRY = 8f - bounce * 4f
        female.armLX = -38f; female.armLY = 8f + bounce * 4f
        female.armRX = 38f; female.armRY = -12f - bounce * 4f
        if (random.nextFloat() < dt * 6f) {
            spawnNote(cx + randomRange(-200f, 200f) * viewScale(), cy + randomRange(-150f, 60f),
                if (random.nextBoolean()) 0xFF7EF7C8.toInt() else 0xFFFFB3D1.toInt())
        }
    }

    private fun updateChase(dt: Float, t: Float) {
        val cx = worldW * 0.5f
        val fx = cx + sin(t * 0.95f) * worldW * 0.29f
        val fy = worldH * 0.48f + sin(t * 1.9f) * worldH * 0.17f
        val prevX = female.x
        moveTo(female, fx, fy, 5.5f, dt)
        val gap = 190f * viewScale()
        moveTo(male, female.x - gap * (if (female.x > male.x) 1f else -1f), female.y - 24f, 3.6f, dt)
        val vfx = female.x - prevX
        if (vfx > 20f) female.face = 1 else if (vfx < -20f) female.face = -1
        female.eye = FACE_WIDE
        female.mouth = MOUTH_SMILE
        female.armLX = -36f; female.armLY = -6f
        female.armRX = 36f; female.armRY = 2f
        male.armLX = -30f; male.armLY = 14f
        male.armRX = 44f; male.armRY = 8f
        if (abs(vfx) > 3f && random.nextFloat() < 0.5f) {
            spawnSpark(female.x - (if (vfx > 0) 34f else -34f), female.y + randomRange(-16f, 16f),
                0xFF8CFFDD.toInt(), 1)
        }
    }

    private fun updateGift(dt: Float, t: Float, w: Float, h: Float) {
        val s = viewScale()
        moveTo(male, w * 0.5f - 150f * s, h * 0.52f, 5f, dt)
        moveTo(female, w * 0.5f + 150f * s, h * 0.52f, 5f, dt)
        if (t > 0.5f) {
            male.armRX = 34f; male.armRY = 16f
            val glow = ((t - 0.5f) / 0.6f).coerceIn(0f, 1f)
            val ox = male.x + 34f * s * male.sizeFactor
            val oy = male.y + 34f * s * male.sizeFactor
            if (t <= 0.9f) {
                orbX = ox
                orbY = oy
                orbR = 33f * glow * 0.8f
            } else {
                if (t < 2f) {
                    val k = ((t - 0.9f) / 1.1f).coerceIn(0f, 1f)
                    orbX = lerp(ox, female.x - 4f, k)
                    orbY = lerp(oy, female.y + 40f, k) - sin(k * PI.toFloat()) * 42f
                    orbR = 33f * glow * (1f - k * 0.25f)
                } else {
                    orbX = female.x - 4f
                    orbY = female.y + 42f
                    orbR = 33f * glow * 0.75f
                }
            }
            if (t > 0.9f && t <= 2f) {
                female.armLX = -34f; female.armLY = 14f
            }
        }
        if (t > 2f && stage == 0) {
            stage = 1
            spawnSparks(female.x, female.y + 10f, 14, 0xFFFFD3E4.toInt())
            repeat(6) {
                spawnHeart(female.x + randomRange(-52f, 46f), female.y + randomRange(30f, 62f),
                    randomRange(8f, 14f), 0xFFFF6FA8.toInt())
            }
            say(female, "好漂亮！", 2f)
        }
        if (t > 2f) {
            female.armLX = -22f; female.armLY = 20f
            female.eye = FACE_LOVE
            female.mouth = MOUTH_SMILE
            male.eye = FACE_HAPPY
            male.mouth = MOUTH_SMILE
            male.armRX = 30f; male.armRY = 20f
        }
    }

    private fun updateHeart(dt: Float, t: Float) {
        val s = viewScale()
        moveTo(male, worldW * 0.5f - 180f * s, worldH * 0.52f, 5f, dt)
        moveTo(female, worldW * 0.5f + 180f * s, worldH * 0.52f, 5f, dt)
        if (t > 0.8f) {
            male.eye = FACE_HAPPY; female.eye = FACE_HAPPY
            female.mouth = MOUTH_SMILE
            male.armLX = -38f; male.armLY = 16f
            male.armRX = 38f; male.armRY = 16f
            female.armLX = -38f; female.armLY = 16f
            female.armRX = 38f; female.armRY = 16f
            beam = ((t - 0.8f) / 1.4f).coerceIn(0f, 1f)
            if (random.nextFloat() < dt * 12f) {
                val fromMale = random.nextBoolean()
                spawnHeart(if (fromMale) male.x + 40f else female.x - 40f,
                    worldH * 0.44f + randomRange(-30f, 30f), randomRange(5f, 9f), 0xFFFF8FBF.toInt())
            }
        }
    }

    private fun updateLean(dt: Float, t: Float) {
        val s = viewScale()
        moveTo(male, worldW * 0.5f - 100f * s, worldH * 0.545f, 4.5f, dt)
        moveTo(female, worldW * 0.5f + 105f * s, worldH * 0.555f, 4.5f, dt)
        if (t > 1f) {
            val k = ((t - 1f) / 0.9f).coerceIn(0f, 1f)
            male.tilt = lerp(0f, 6f, k)
            female.tilt = lerp(0f, -18f, k)
            female.headX = lerp(0f, -4f, k)
            female.headY = lerp(0f, 3f, k)
            female.eye = FACE_HAPPY
            male.eye = FACE_HAPPY
            female.mouth = MOUTH_SMILE
            male.armRX = 30f; male.armRY = 4f
            female.armLX = -26f; female.armLY = 8f
            if (random.nextFloat() < dt * 2.6f) {
                spawnHeart(worldW * 0.5f + randomRange(-40f, 60f), worldH * 0.44f,
                    randomRange(8f, 14f), 0xFFFF6FA8.toInt())
            }
        }
    }

    private fun updateQuarrel(dt: Float, t: Float) {
        val s = viewScale()
        moveTo(male, worldW * 0.5f - 210f * s, worldH * 0.52f, 6f, dt)
        moveTo(female, worldW * 0.5f + 210f * s, worldH * 0.52f, 6f, dt)
        val turnAway = t > 2.6f && t < 3.4f
        if (turnAway) {
            male.face = -1; female.face = 1
        }
        val shake = if (t < 2.4f) sin(t * 23f) * 8f else 0f
        male.x += shake
        female.x -= shake * 0.85f
        male.headX = sin(t * 21f) * 3f
        female.headX = -sin(t * 21f) * 3f
        male.eye = FACE_ANGRY
        female.eye = FACE_ANGRY
        female.mouth = MOUTH_SAD
        male.armLX = -42f; male.armLY = -4f
        male.armRX = 42f; male.armRY = -4f
        female.armLX = -42f; female.armLY = -4f
        female.armRX = 42f; female.armRY = -4f
        if (t > 0.45f && stage == 0) { stage = 1; say(male, "！", 1.5f) }
        if (t > 0.95f && stage == 1) { stage = 2; say(female, "……", 1.6f) }
        if (t > 1.7f && stage == 2) { stage = 3; say(female, "哼！", 1.2f) }
        if (t > 3f && stage == 3) { stage = 4 }
        if (t > 3.5f) {
            male.eye = FACE_HAPPY; female.eye = FACE_HAPPY
            male.mouth = MOUTH_SMILE; female.mouth = MOUTH_SMILE
            male.headX = sin(t * 6f) * 2f
            if (stage == 4) {
                stage = 5
                spawnHeart(female.x - 40f, female.y - 20f, 12f, 0xFFFF6FA8.toInt())
                say(male, "哈哈", 1.4f)
            }
            if (random.nextFloat() < dt * 3f) {
                spawnHeart(worldW * 0.5f, worldH * 0.45f, randomRange(6f, 10f), 0xFFFF6FA8.toInt())
            }
        }
    }

    private fun updateHug(dt: Float, t: Float) {
        val s = viewScale()
        moveTo(male, worldW * 0.5f - 96f * s, worldH * 0.545f, 5f, dt)
        moveTo(female, worldW * 0.5f + 100f * s, worldH * 0.55f, 5f, dt)
        if (t > 0.8f) {
            val k = ((t - 0.8f) / 0.7f).coerceIn(0f, 1f)
            hugArms = k
            moveTo(male, worldW * 0.5f - 82f * s, worldH * 0.545f, 3f, dt)
            moveTo(female, worldW * 0.5f + 86f * s, worldH * 0.55f, 3f, dt)
            male.eye = FACE_HAPPY; female.eye = FACE_HAPPY
            male.mouth = MOUTH_SMILE; female.mouth = MOUTH_SMILE
            male.tilt = 3f; female.tilt = -3f
            male.armLX = -20f; male.armLY = 12f
            female.armRX = 20f; female.armRY = 12f
            if (stage == 0) {
                stage = 1
                val cx = (male.x + female.x) / 2f
                val cy = (male.y + female.y) / 2f
                for (i in 0 until 12) {
                    val a = random.nextFloat() * TAU
                    spawnHeartDir(cx + cos(a) * 60f, cy + sin(a) * 40f,
                        cos(a) * 130f, sin(a) * 90f - 40f, randomRange(7f, 15f), 0xFFFF6FA8.toInt())
                }
                spawnRing(cx, cy + 10f, 0xFFFF8FBF.toInt())
            }
            if (random.nextFloat() < dt * 2.2f) {
                spawnHeart(worldW * 0.5f + randomRange(-50f, 50f), worldH * 0.44f,
                    randomRange(6f, 11f), 0xFFFF6FA8.toInt())
            }
        }
    }

    // ---------------------------------------------------------------- 位移小工具

    private fun viewScale(): Float =
        if (fixedScale > 0f) fixedScale else min(worldW, worldH) / 720f * 0.85f

    private fun moveTo(r: Robot, tx: Float, ty: Float, k: Float, dt: Float) {
        r.x = approach(r.x, tx, k, dt)
        r.y = approach(r.y, ty, k, dt)
        r.x = r.x.coerceIn(EDGE_PAD, worldW - EDGE_PAD)
        r.y = r.y.coerceIn(EDGE_PAD, worldH - EDGE_PAD)
    }

    private fun approach(cur: Float, target: Float, k: Float, dt: Float): Float =
        cur + (target - cur) * (1f - exp(-k * dt))

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun randomRange(a: Float, b: Float): Float = a + random.nextFloat() * (b - a)

    // ---------------------------------------------------------------- 机器人绘制

    private fun drawRobot(canvas: Canvas, r: Robot, s: Float) {
        val skin = r.skin
        val bob = sin(r.tick * 1.2f) * 3.4f * s

        canvas.save()
        canvas.translate(r.x, r.y)
        canvas.rotate(r.tilt + r.roll)
        canvas.scale(r.face.toFloat(), 1f)

        // 推进器
        val thr = 0.5f + sin(r.tick * 3.6f) * 0.28f
        val ty = if (skin.female) 44f else 27f
        glowOval(canvas, -17f * s, ty * s + bob, 34f * s, 9f * s, skin.neon, 0.45f + thr * 0.35f)

        if (skin.female) drawSkirt(canvas, skin, s, bob)

        if (skin.female) drawPonytail(canvas, skin, s, bob) else drawAntenna(canvas, skin, s, bob)

        drawHand(canvas, r, s, bob, r.armLX, r.armLY)
        drawHand(canvas, r, s, bob, r.armRX, r.armRY)

        // 机身
        val bw = if (skin.female) 25f else 28f
        val brr = if (skin.female) 29f else 26f
        fill.color = skin.chassis
        stroke.color = skin.border
        stroke.strokeWidth = 1.4f * s
        rect.set(-bw * s, (-30f + r.headY) * s + bob, bw * s, (30f + r.headY) * s + bob)
        canvas.drawRoundRect(rect, brr * s, brr * s, fill)
        canvas.drawRoundRect(rect, brr * s, brr * s, stroke)

        if (skin.female) {
            fill.color = skin.chassis
            rect.set(-31f * s, (-17f + r.headY) * s + bob, -23f * s, (-3f + r.headY) * s + bob)
            canvas.drawRoundRect(rect, 4f * s, 4f * s, fill)
            canvas.drawRoundRect(rect, 4f * s, 4f * s, stroke)
            rect.set(23f * s, (-17f + r.headY) * s + bob, 31f * s, (-3f + r.headY) * s + bob)
            canvas.drawRoundRect(rect, 4f * s, 4f * s, fill)
            canvas.drawRoundRect(rect, 4f * s, 4f * s, stroke)
            // 耳环
            glowDot(canvas, -27f * s, (2f + r.headY) * s + bob, 2.1f * s, skin.neon, 0.9f)
            glowDot(canvas, 27f * s, (2f + r.headY) * s + bob, 2.1f * s, skin.neon, 0.9f)
        }

        // 面罩
        val vw = if (skin.female) 20f else 21f
        val vh = if (skin.female) 29f else 30f
        fill.color = skin.visor
        rect.set(-vw * s, (-18f + r.headY) * s + bob, vw * s, (-18f + vh + r.headY) * s + bob)
        canvas.drawRoundRect(rect, 13f * s, 13f * s, fill)

        if (skin.female) {
            // 发带
            fill.color = withAlpha(skin.neon, 200)
            rect.set(-13f * s, (-31f + r.headY) * s + bob, 13f * s, (-26.5f + r.headY) * s + bob)
            canvas.drawRoundRect(rect, 2.2f * s, 2.2f * s, fill)
        }

        drawEyes(canvas, r, s, bob)
        if (skin.female) drawMouth(canvas, r, s, bob)

        // 胸口核心
        if (skin.female) {
            val pulse = (sin(r.tick * 2.8f) + 1f) * 0.5f
            fill.color = withAlpha(skin.core, (140 + (pulse * 110).toInt()))
            heartPath(canvas, 0f, 19f * s + bob, 4.6f * s)
            canvas.drawPath(path, fill)
            glowDot(canvas, 0f, 19f * s + bob, 5.5f * s, skin.core, 0.22f + pulse * 0.2f)
        } else {
            val pulse = (sin(r.tick * 2.8f) + 1f) * 0.5f
            glowDot(canvas, 0f, 21f * s + bob, 3.4f * s, skin.core, 0.45f + pulse * 0.5f)
        }

        canvas.restore()
    }

    private fun drawAntenna(canvas: Canvas, skin: Skin, s: Float, bob: Float) {
        stroke.color = 0xFF64748B.toInt()
        stroke.strokeWidth = 2.4f * s
        canvas.drawLine(0f, -28f * s + bob, 0f, -42f * s + bob, stroke)
        glowDot(canvas, 0f, -43f * s + bob, 3.5f * s, skin.neon, 1f)
    }

    private fun drawPonytail(canvas: Canvas, skin: Skin, s: Float, bob: Float) {
        val sway = sin(female.tick * 1.7f) * 4.5f * s
        stroke.strokeCap = Paint.Cap.ROUND
        // 柔光层
        stroke.color = withAlpha(0xFFFF86B8.toInt(), 70)
        stroke.strokeWidth = 16f * s
        path.reset()
        path.moveTo(15f * s, -24f * s + bob)
        path.quadTo(34f * s, -30f * s + sway, 33f * s, -1f * s + sway * 1.8f)
        canvas.drawPath(path, stroke)
        // 本色
        stroke.color = 0xFFFF86B8.toInt()
        stroke.strokeWidth = 8.5f * s
        canvas.drawPath(path, stroke)
        // 高光
        stroke.color = 0x9FFFFFFF.toInt()
        stroke.strokeWidth = 2.2f * s
        path.reset()
        path.moveTo(18f * s, -22f * s + bob)
        path.quadTo(32f * s, -27f * s + sway, 31f * s, -3f * s + sway * 1.8f)
        canvas.drawPath(path, stroke)
        // 发带
        fill.color = skin.neon
        rect.set(14f * s, -28f * s + bob, 23f * s, -22f * s + bob)
        canvas.drawRoundRect(rect, 3f * s, 3f * s, fill)
        // 发梢光点
        glowDot(canvas, 33f * s, -1f * s + sway * 1.8f + bob, 3.4f * s, skin.neon, 1f)
    }

    private fun drawSkirt(canvas: Canvas, skin: Skin, s: Float, bob: Float) {
        fill.color = 0xFFFFF0F6.toInt()
        stroke.color = skin.border
        stroke.strokeWidth = 1.4f * s
        path.reset()
        path.moveTo(-20f * s, 9f * s + bob)
        path.quadTo(-30f * s, 24f * s + bob, -36f * s, 34f * s + bob)
        path.quadTo(0f, 41f * s + bob, 36f * s, 34f * s + bob)
        path.quadTo(30f * s, 24f * s + bob, 20f * s, 9f * s + bob)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        // 裙摆发光缝线
        stroke.color = withAlpha(skin.neon, 215)
        stroke.strokeWidth = 2.2f * s
        path.reset()
        path.moveTo(-31f * s, 30f * s + bob)
        path.quadTo(0f, 36f * s + bob, 31f * s, 30f * s + bob)
        canvas.drawPath(path, stroke)
    }

    private fun drawHand(canvas: Canvas, r: Robot, s: Float, bob: Float, ax: Float, ay: Float) {
        val skin = r.skin
        val hx = ax * s
        val hy = ay * s + bob
        // 手掌离得远时画一条虚线能量牵引
        if (ax > 40f || ax < -40f || ay < -10f) {
            stroke.color = withAlpha(skin.neon, 80)
            stroke.strokeWidth = 1.6f * s
            path.reset()
            path.moveTo((if (ax >= 0) 20f else -20f) * s, 11f * s + bob)
            path.quadTo(ax * s * 0.95f, (ay - 6f) * s + bob, hx, hy)
            canvas.drawPath(path, stroke)
        }
        fill.color = skin.chassis
        stroke.color = skin.border
        stroke.strokeWidth = 1.3f * s
        val w = (if (skin.female) 9f else 10f) * s
        val h = (if (skin.female) 13f else 14f) * s
        rect.set(hx - w / 2f, hy - h / 2f, hx + w / 2f, hy + h / 2f)
        canvas.drawOval(rect, fill)
        canvas.drawOval(rect, stroke)
        glowDot(canvas, hx, hy, 2.2f * s, skin.neon, 0.85f)
    }

    private fun drawEyes(canvas: Canvas, r: Robot, s: Float, bob: Float) {
        val skin = r.skin
        val ex = (if (skin.female) 9.6f else 10f) * s
        val y = r.centerY * s + bob
        stroke.strokeCap = Paint.Cap.ROUND

        when (r.eye) {
            FACE_BLINK -> {
                stroke.color = skin.neon
                stroke.strokeWidth = 2f * s
                canvas.drawLine(-ex - 4.4f * s, y, -ex + 4.4f * s, y, stroke)
                canvas.drawLine(ex - 4.4f * s, y, ex + 4.4f * s, y, stroke)
            }
            FACE_HAPPY -> {
                stroke.color = skin.neon
                stroke.strokeWidth = 2.6f * s
                rect.set(-ex - 4.6f * s, y - 2.6f * s, -ex + 4.6f * s, y + 6.6f * s)
                canvas.drawArc(rect, 180f, 180f, false, stroke)
                rect.set(ex - 4.6f * s, y - 2.6f * s, ex + 4.6f * s, y + 6.6f * s)
                canvas.drawArc(rect, 180f, 180f, false, stroke)
            }
            FACE_LOVE -> {
                fill.color = skin.neon
                heartPath(canvas, -ex, y, 5.4f * s)
                canvas.drawPath(path, fill)
                heartPath(canvas, ex, y, 5.4f * s)
                canvas.drawPath(path, fill)
            }
            FACE_DIZZY -> {
                stroke.color = skin.neon
                stroke.strokeWidth = 1.8f * s
                val rot = r.tick * 60f
                canvas.save()
                canvas.rotate(rot, -ex, y)
                rect.set(-ex - 4.2f * s, y - 4.2f * s, -ex + 4.2f * s, y + 4.2f * s)
                canvas.drawArc(rect, 20f, 260f, false, stroke)
                canvas.restore()
                canvas.save()
                canvas.rotate(-rot, ex, y)
                rect.set(ex - 4.2f * s, y - 4.2f * s, ex + 4.2f * s, y + 4.2f * s)
                canvas.drawArc(rect, 20f, 260f, false, stroke)
                canvas.restore()
            }
            FACE_ANGRY -> {
                stroke.color = skin.neon
                stroke.strokeWidth = 2.6f * s
                canvas.drawLine(-ex - 4f * s, y - 5f * s, -ex + 4f * s, y + 4f * s, stroke)
                canvas.drawLine(ex + 4f * s, y - 5f * s, ex - 4f * s, y + 4f * s, stroke)
            }
            else -> {
                val wide = r.eye == FACE_WIDE
                val ew = (if (skin.female) 4.6f else 4.2f) * (if (wide) 1.15f else 1f)
                val eh = (if (skin.female) 6.4f else 5.6f) * (if (wide) 1.15f else 1f)
                fill.color = skin.neon
                rect.set(-ex - ew * s, y - eh * s, -ex + ew * s, y + eh * s)
                canvas.drawRoundRect(rect, ew * s, ew * s, fill)
                rect.set(ex - ew * s, y - eh * s, ex + ew * s, y + eh * s)
                canvas.drawRoundRect(rect, ew * s, ew * s, fill)
                // 女生睫毛
                if (skin.female) {
                    stroke.color = skin.neon
                    stroke.strokeWidth = 1.7f * s
                    canvas.drawLine(-ex - ew * s, y - eh * s + 1.2f * s, -ex - ew * s - 3.2f * s, y - eh * s - 2.4f * s, stroke)
                    canvas.drawLine(ex + ew * s, y - eh * s + 1.2f * s, ex + ew * s + 3.2f * s, y - eh * s - 2.4f * s, stroke)
                }
                // 高光
                fill.color = 0xEFFFFFFF.toInt()
                canvas.drawCircle(-ex - 1.5f * s, y - 2.6f * s, 1.35f * s, fill)
                canvas.drawCircle(ex - 1.5f * s, y - 2.6f * s, 1.35f * s, fill)
            }
        }
    }

    private fun drawMouth(canvas: Canvas, r: Robot, s: Float, bob: Float) {
        val y = (6.5f + r.headY) * s + bob
        stroke.color = withAlpha(r.skin.neon, 230)
        stroke.strokeWidth = 1.8f * s
        when (r.mouth) {
            MOUTH_SMILE -> {
                rect.set(-3.6f * s, y - 6f * s, 3.6f * s, y + 1.2f * s)
                canvas.drawArc(rect, 20f, 140f, false, stroke)
            }
            MOUTH_SAD -> {
                rect.set(-3.4f * s, y - 3f * s, 3.4f * s, y + 4.2f * s)
                canvas.drawArc(rect, 200f, 140f, false, stroke)
            }
            else -> {
                rect.set(-2.8f * s, y - 4.4f * s, 2.8f * s, y + 1.2f * s)
                canvas.drawArc(rect, 20f, 140f, false, stroke)
            }
        }
    }

    // ---------------------------------------------------------------- 特效

    private fun drawHugArms(canvas: Canvas) {
        val mid = (male.x + female.x) / 2f
        stroke.color = 0xE6FFFFFF.toInt()
        stroke.strokeWidth = 5f
        // 端点各自落在对方机身内侧，露在两人之间的那一段才像"搂住"，
        // 之前画到对方身后，会在外面戳出一截白条，看着像吊床
        path.reset()
        path.moveTo(male.x + 26f, male.y + 10f)
        path.quadTo(mid, male.y + 20f, female.x, female.y + 8f)
        canvas.drawPath(path, stroke)
        path.reset()
        path.moveTo(female.x - 26f, female.y + 10f)
        path.quadTo(mid, female.y + 20f, male.x, male.y + 8f)
        canvas.drawPath(path, stroke)
    }

    private fun drawBeam(canvas: Canvas) {
        if (beam <= 0.01f) return
        val cx = (male.x + female.x) / 2f
        val cy = worldH * 0.44f
        stroke.strokeWidth = 3f * beam
        stroke.color = withAlpha(MALE.neon, 200)
        canvas.drawLine(male.x + 34f, male.y + 20f, cx, cy + 20f, stroke)
        stroke.color = withAlpha(FEMALE.neon, 200)
        canvas.drawLine(female.x - 34f, female.y + 20f, cx, cy + 20f, stroke)

        val pulse = 1f + sin(actionTime * 4.2f) * 0.07f
        val hs = 78f * beam * pulse
        fill.color = withAlpha(0xFFFF6FA8.toInt(), 120)
        heartPath(canvas, cx, cy, hs)
        canvas.drawPath(path, fill)
        stroke.color = 0xE6FFE8F3.toInt()
        stroke.strokeWidth = 2.6f
        heartPath(canvas, cx, cy, hs)
        canvas.drawPath(path, stroke)
        stroke.color = withAlpha(0xFFFF6FA8.toInt(), 220)
        stroke.strokeWidth = 1.2f
        for (i in 0 until 8) {
            val a = actionTime * 1.3f + i * TAU / 8f
            val rr = hs * (0.95f + sin(actionTime * 3f + i) * 0.12f)
            canvas.drawCircle(cx + cos(a) * rr * 0.72f, cy + sin(a) * rr * 0.5f, 2.1f, fill)
        }
    }

    private fun drawFlowerOrb(canvas: Canvas, x: Float, y: Float, r: Float, s: Float) {
        if (r <= 1f) return
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(actionTime * 80f)
        for (i in 0 until 6) {
            canvas.save()
            canvas.rotate(i * 360f / 6f)
            fill.color = withAlpha(0xFFFF6FAE.toInt(), 235)
            path.reset()
            path.moveTo(0f, 0f)
            path.quadTo(r * 0.42f, -r * 0.55f, 0f, -r)
            path.quadTo(-r * 0.42f, -r * 0.55f, 0f, 0f)
            canvas.drawPath(path, fill)
            fill.color = withAlpha(0xFFFF9CC5.toInt(), 255)
            path.reset()
            path.moveTo(0f, 0f)
            path.quadTo(r * 0.2f, -r * 0.4f, 0f, -r * 0.7f)
            path.quadTo(-r * 0.2f, -r * 0.4f, 0f, 0f)
            canvas.drawPath(path, fill)
            canvas.restore()
        }
        glowDot(canvas, 0f, 0f, r * 0.3f, 0xFFFFF1F7.toInt(), 1f)
        canvas.restore()
    }

    // ---------------------------------------------------------------- 粒子

    private fun newParticle(): Particle {
        // 复用已死亡的槽位，避免频繁分配
        for (i in particles.indices) if (particles[i].life <= 0f) return particles[i]
        val p = Particle()
        particles.add(p)
        return p
    }

    private fun spawnHeart(x: Float, y: Float, size: Float, color: Int) {
        spawnHeartDir(x, y, randomRange(-28f, 28f), randomRange(-90f, -45f), size, color)
    }

    private fun spawnHeartDir(x: Float, y: Float, vx: Float, vy: Float, size: Float, color: Int) {
        if (particles.size > 90) return
        val p = newParticle()
        p.kind = 0
        p.x = x; p.y = y; p.vx = vx; p.vy = vy
        p.size = size
        p.rot = randomRange(-0.35f, 0.35f)
        p.rotV = randomRange(-0.8f, 0.8f)
        p.max = randomRange(1.1f, 1.9f)
        p.life = p.max
        p.color = color
    }

    private fun spawnSparks(x: Float, y: Float, n: Int, color: Int) {
        for (i in 0 until n) {
            if (particles.size > 110) return
            val a = random.nextFloat() * TAU
            val sp = randomRange(120f, 420f)
            val p = newParticle()
            p.kind = 1
            p.x = x; p.y = y
            p.vx = cos(a) * sp; p.vy = sin(a) * sp
            p.size = randomRange(2.2f, 4.6f)
            p.max = randomRange(0.25f, 0.6f)
            p.life = p.max
            p.color = color
        }
    }

    private fun spawnSpark(x: Float, y: Float, color: Int, count: Int) {
        for (i in 0 until count) {
            if (particles.size > 110) return
            val p = newParticle()
            p.kind = 1
            p.x = x; p.y = y
            p.vx = randomRange(-60f, 60f); p.vy = randomRange(-40f, 40f)
            p.size = randomRange(1.2f, 2.2f)
            p.max = 0.3f
            p.life = 0.3f
            p.color = color
        }
    }

    private fun spawnNote(x: Float, y: Float, color: Int) {
        if (particles.size > 90) return
        val p = newParticle()
        p.kind = 2
        p.x = x; p.y = y
        p.vx = randomRange(-22f, 22f)
        p.vy = randomRange(-110f, -70f)
        p.size = randomRange(11f, 17f)
        p.rot = randomRange(-0.3f, 0.3f)
        p.rotV = randomRange(-1.2f, 1.2f)
        p.max = randomRange(1.2f, 2f)
        p.life = p.max
        p.color = color
    }

    private fun spawnRing(x: Float, y: Float, color: Int) {
        val p = newParticle()
        p.kind = 3
        p.x = x; p.y = y
        p.max = 0.55f
        p.life = 0.55f
        p.color = color
    }

    private fun updateParticles(dt: Float) {
        var i = 0
        while (i < particles.size) {
            val p = particles[i]
            if (p.life <= 0f) {
                i++
                continue
            }
            p.life -= dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.rotV * dt
            when (p.kind) {
                0, 2 -> {
                    p.vy += 12f * dt
                    p.vx *= 0.99f
                }
                1 -> {
                    p.vx *= 0.94f
                    p.vy *= 0.94f
                }
            }
            i++
        }
    }

    private fun drawParticles(canvas: Canvas, s: Float) {
        for (p in particles) {
            if (p.life <= 0f) continue
            val a = (p.life / p.max).coerceIn(0f, 1f)
            when (p.kind) {
                0 -> {
                    canvas.save()
                    canvas.translate(p.x, p.y)
                    canvas.rotate(p.rot * 57.3f)
                    fill.color = withAlpha(p.color, (255 * a).toInt())
                    heartPath(canvas, 0f, 0f, p.size * 1.35f)
                    canvas.drawPath(path, fill)
                    canvas.restore()
                }
                1 -> {
                    stroke.color = withAlpha(p.color, (255 * a).toInt())
                    stroke.strokeWidth = p.size
                    canvas.drawLine(p.x, p.y, p.x - p.vx * 0.012f, p.y - p.vy * 0.012f, stroke)
                }
                2 -> {
                    canvas.save()
                    canvas.translate(p.x, p.y)
                    canvas.rotate(p.rot * 57.3f)
                    fill.color = withAlpha(p.color, (255 * a).toInt())
                    canvas.drawCircle(-p.size * 0.22f, p.size * 0.5f, p.size * 0.34f, fill)
                    stroke.color = withAlpha(p.color, (255 * a).toInt())
                    stroke.strokeWidth = 2.2f
                    canvas.drawLine(p.size * 0.08f, p.size * 0.48f, p.size * 0.08f, -p.size * 0.5f, stroke)
                    canvas.restore()
                }
                3 -> {
                    val r = 10f + (1f - a) * 150f
                    stroke.color = withAlpha(p.color, (255 * a * 0.85f).toInt())
                    stroke.strokeWidth = 3f * a + 0.6f
                    canvas.drawCircle(p.x, p.y, r, stroke)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 气泡

    private fun say(r: Robot, content: String, life: Float) {
        if (bubbleList.size > 3) bubbleList.removeAt(0)
        val b = Bubble()
        b.robot = r
        b.text = content
        b.max = life
        b.life = life
        bubbleList.add(b)
    }

    private fun updateBubbles(dt: Float) {
        var i = 0
        while (i < bubbleList.size) {
            val b = bubbleList[i]
            b.life -= dt
            if (b.life <= 0f) bubbleList.removeAt(i) else i++
        }
    }

    private fun drawBubbles(canvas: Canvas, s: Float) {
        var maleCount = 0
        var femaleCount = 0
        for (b in bubbleList) {
            val r = b.robot ?: continue
            val idx = if (r === male) maleCount.also { maleCount++ } else femaleCount.also { femaleCount++ }
            val pop = ((b.max - b.life) / 0.18f).coerceIn(0f, 1f)
            val fade = (b.life / 0.35f).coerceIn(0f, 1f)
            val sc = 0.7f + 0.3f * pop

            val fs = 15f * s
            text.textSize = fs
            val tw = text.measureText(b.text)
            val bw = tw + 26f * s
            val bh = 30f * s
            val bx = r.x
            val by = r.y - 48f * s - idx * 34f * s

            canvas.save()
            canvas.translate(bx, by)
            canvas.scale(sc, sc)
            fill.color = withAlpha(Color.WHITE, (250 * fade).toInt())
            rect.set(-bw / 2f, -bh, bw / 2f, 0f)
            canvas.drawRoundRect(rect, 9f * s, 9f * s, fill)
            // 小尾巴
            path.reset()
            path.moveTo(-5f * s, -1f)
            path.lineTo(1f * s, 7f * s)
            path.lineTo(6f * s, -1f)
            path.close()
            canvas.drawPath(path, fill)

            text.color = withAlpha(0xFF12211C.toInt(), (255 * fade).toInt())
            canvas.drawText(b.text, 0f, -bh / 2f + fs * 0.36f, text)
            canvas.restore()
        }
    }

    // ---------------------------------------------------------------- 绘制小工具

    /** 三圈递减透明度的廉价发光，替代 setShadowLayer（后者在电视 GPU 上是软件绘制）。 */
    private fun glowDot(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float) {
        fill.color = withAlpha(color, (alpha * 0.10f * 255).toInt().coerceIn(0, 255))
        canvas.drawCircle(x, y, r * 2.1f, fill)
        fill.color = withAlpha(color, (alpha * 0.22f * 255).toInt().coerceIn(0, 255))
        canvas.drawCircle(x, y, r * 1.45f, fill)
        fill.color = withAlpha(color, (alpha * 255).toInt().coerceIn(0, 255))
        canvas.drawCircle(x, y, r, fill)
    }

    private fun glowOval(canvas: Canvas, l: Float, t: Float, w: Float, h: Float, color: Int, alpha: Float) {
        fill.color = withAlpha(color, (alpha * 0.14f * 255).toInt().coerceIn(0, 255))
        rect.set(l - w * 0.35f, t - h * 0.9f, l + w * 1.35f, t + h * 1.9f)
        canvas.drawOval(rect, fill)
        fill.color = withAlpha(color, (alpha * 255).toInt().coerceIn(0, 255))
        rect.set(l, t, l + w, t + h)
        canvas.drawOval(rect, fill)
    }

    private fun heartPath(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx, cy + s * 0.78f)
        path.cubicTo(cx - s * 1.15f, cy - s * 0.12f, cx - s * 0.52f, cy - s * 1.02f, cx, cy - s * 0.34f)
        path.cubicTo(cx + s * 0.52f, cy - s * 1.02f, cx + s * 1.15f, cy - s * 0.12f, cx, cy + s * 0.78f)
        path.close()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}
