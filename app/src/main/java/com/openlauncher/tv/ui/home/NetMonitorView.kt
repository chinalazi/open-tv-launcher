package com.openlauncher.tv.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 左上的联网监视器：实时滚动"谁在联网"。
 *
 * 两个视图（遥控器 OK 或点右上角按钮切换）：
 * - **日志流**：一次一条连接，时间 / 上下行 / 进程 / 速率条 / 远端地址，逐条向上滚；
 * - **排行**：按进程聚合速率排序，条形宽度按开方压缩，小流量也看得见。
 *
 * 全部 Canvas 手绘：这台电视的 GPU 很弱，多一层 View 就多一次合成，
 * 整块自绘反而更稳（和 [DesktopPetView] 同样的思路）。数据由 [NetTrafficMonitor] 喂。
 */
class NetMonitorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode(val label: String) {
        LOG("连接日志"),
        RANK("进程排行")
    }

    private data class Row(
        val time: String,
        val label: String,
        val pkg: String,
        val rate: Long,
        val isUp: Boolean,
        val remote: String
    )

    var onModeChanged: ((Mode) -> Unit)? = null
    var onFocusTip: ((Boolean) -> Unit)? = null

    private var mode = Mode.LOG

    // ---- 度量（dp 换算一次，避免每次绘制都乘） -----------------------------------
    private val padH = dp(17f)
    private val headerH = dp(40f)
    private val footerH = dp(22f)
    private val rowH = dp(28.5f)
    private val radius = dp(11f)

    private val colTime = dp(72f)
    private val colLed = dp(5f)
    private val colDir = dp(11f)
    private val colName = dp(193f)
    private val colBar = dp(85f)
    private val colVal = dp(75f)
    private val colGap = dp(6f)

    private val textRow = dp(13f)
    private val textSmall = dp(10f)
    private val textTitle = dp(15f)

    // ---- 画笔 -------------------------------------------------------------------
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x57FFFFFF
    }
    private val innerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
        color = 0x2412B57F
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0xBF2BE79F.toInt()
    }
    private val scanPaint = Paint().apply { shader = scanShader() }

    private val mono = Typeface.MONOSPACE
    private val sans = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val pTitle = textPaint(textTitle, 0xFFB9F5DE.toInt(), mono, Typeface.BOLD)
    private val pSub = textPaint(textSmall, 0xFF4E7C6D.toInt(), mono)
    private val pLive = textPaint(textSmall, 0xFF7FE0BC.toInt(), mono)
    private val pCount = textPaint(dp(15f), 0xFFDFFFF3.toInt(), mono, Typeface.BOLD)
    private val pCountUnit = textPaint(textSmall, 0xFF4E7C6D.toInt(), mono)
    private val pTime = textPaint(textRow, 0xFF3F6B5E.toInt(), mono)
    private val pName = textPaint(textRow, 0xFFE8FFF8.toInt(), sans)
    private val pPkg = textPaint(textSmall, 0xFF4C7D6D.toInt(), mono)
    private val pValDown = textPaint(textRow, 0xFF8CF3CB.toInt(), mono)
    private val pValUp = textPaint(textRow, 0xFFFFD98A.toInt(), mono)
    private val pPeer = textPaint(textSmall, 0xFF3F6B5E.toInt(), mono)
    private val pFoot = textPaint(dp(11f), 0xFF6FA594.toInt(), mono)
    private val pFootDim = textPaint(dp(10f), 0xFF3F6B5E.toInt(), mono)
    private val pBtn = textPaint(dp(11f), 0xFF7FE0BC.toInt(), sans)
    private val pRank = textPaint(textSmall, 0xFF3F6B5E.toInt(), mono)

    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2BE79F.toInt() }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0EFFFFFF }
    private val barDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2BE79F.toInt() }
    private val barUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2B33D.toInt() }
    private val btnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1F12B57F }
    private val btnBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
        color = 0x8C12B57F.toInt()
    }
    private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF2BE79F.toInt()
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        color = 0xFF2BE79F.toInt()
    }
    private val sparkFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val tmpRect = RectF()
    private val clipPath = Path()
    private val sparkPath = Path()
    private val sparkFillPath = Path()

    // ---- 数据 -------------------------------------------------------------------
    private var snapshot: NetTrafficMonitor.Snapshot? = null
    private val rows = ArrayDeque<Row>()
    private val recentPkgs = ArrayDeque<String>()
    private var rollOffset = 0f
    private var rollAnimator: ValueAnimator? = null
    private var tickCounter = 0

    private val spark = ArrayDeque<Long>()
    private var peak = 0L
    private var totalDown = 0L
    private var totalUp = 0L
    private var procCount = 0
    private var connCount = 0

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private var listTop = 0f
    private var listBottom = 0f
    private var btnRect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setWillNotDraw(false)
    }

    // ---- 对外 -------------------------------------------------------------------

    fun currentMode(): Mode = mode

    fun setMode(value: Mode) {
        if (mode == value) return
        mode = value
        cancelRoll()
        rows.clear()
        invalidate()
        onModeChanged?.invoke(mode)
    }

    fun toggleMode() = setMode(if (mode == Mode.LOG) Mode.RANK else Mode.LOG)

    /** 新的一份采样（主线程回调）。 */
    fun submit(value: NetTrafficMonitor.Snapshot) {
        snapshot = value
        totalDown = value.totalDown
        totalUp = value.totalUp
        procCount = value.procs.size
        connCount = value.conns.size
        peak = max(peak, value.totalDown)

        spark.addLast(value.totalDown)
        while (spark.size > 60) spark.removeFirst()

        if (rows.isEmpty() && value.conns.isNotEmpty()) seedRows(value)

        if (mode == Mode.LOG) {
            // 一秒一条太密，两秒往列表里推一条，读得过来
            if (++tickCounter >= 2) {
                tickCounter = 0
                pushRow(value)
            }
        }
        if (rollAnimator?.isRunning != true) invalidate()
    }

    // ---- 行数据 -----------------------------------------------------------------

    /** 开场用当前连接清单铺满一屏，别让用户面对一块空屏。 */
    private fun seedRows(value: NetTrafficMonitor.Snapshot) {
        val seen = HashSet<String>()
        for (c in value.conns) {
            if (rows.size >= VISIBLE_ROWS) break
            if (!seen.add(c.pkg)) continue
            rows.addLast(
                Row(
                    time = timeFormat.format(Date(TimeSync.now())),
                    label = c.label,
                    pkg = shortPkg(c.pkg),
                    rate = c.rate,
                    isUp = c.isUp,
                    remote = c.remote
                )
            )
        }
        // 进程种类不够 7 个就重复补，保证一屏是满的
        var i = 0
        while (rows.size < VISIBLE_ROWS && value.conns.isNotEmpty()) {
            val c = value.conns[i % value.conns.size]
            rows.addLast(
                Row(
                    time = timeFormat.format(Date(TimeSync.now())),
                    label = c.label,
                    pkg = shortPkg(c.pkg),
                    rate = c.rate,
                    isUp = c.isUp,
                    remote = c.remote
                )
            )
            i++
        }
    }

    private fun pushRow(value: NetTrafficMonitor.Snapshot) {
        val picked = pickConn(value) ?: return

        // 同一个进程连着来（长连接下载就是这样）：就地刷新数值，别把一屏刷成同一个包名。
        // 时间保留首次出现的那次，行就变成"某进程从 xx:xx:xx 起一直在联网"，比一串重复时间有用。
        val last = rows.lastOrNull()
        if (last != null && last.label == picked.label && last.pkg == picked.pkg) {
            rows.removeLast()
            rows.addLast(picked.copy(time = last.time))
            invalidate()
            return
        }

        rows.addLast(picked)
        while (rows.size > VISIBLE_ROWS) rows.removeFirst()
        startRoll()
    }

    /**
     * 优先显示这一轮真的有流量的连接（速率高的在前），并避开最近出现过的进程；
     * 全都没流量时才退而显示新建连接，再不济就跳过这轮 —— 免得刷一屏 0 B/s。
     */
    private fun pickConn(value: NetTrafficMonitor.Snapshot): Row? {
        val flowing = value.conns.filter { it.rate > 0 }
        val conn = flowing.firstOrNull { it.pkg !in recentPkgs }
            ?: flowing.firstOrNull()
            ?: value.conns.firstOrNull { it.pkg !in recentPkgs }
            ?: value.conns.firstOrNull()
            ?: value.freshConns.firstOrNull()
            ?: return null

        recentPkgs.addLast(conn.pkg)
        while (recentPkgs.size > 3) recentPkgs.removeFirst()

        return Row(
            time = timeFormat.format(Date(TimeSync.now())),
            label = conn.label,
            pkg = shortPkg(conn.pkg),
            rate = conn.rate,
            isUp = conn.isUp,
            remote = conn.remote
        )
    }

    private fun shortPkg(pkg: String): String =
        if (pkg.isEmpty()) "" else pkg.substringAfterLast('.')

    // ---- 滚动动画 ---------------------------------------------------------------

    private fun startRoll() {
        cancelRoll()
        rollOffset = rowH
        rollAnimator = ValueAnimator.ofFloat(rowH, 0f).apply {
            duration = 280L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                rollOffset = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    private fun cancelRoll() {
        rollAnimator?.cancel()
        rollAnimator = null
        rollOffset = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelRoll()
    }

    // ---- 尺寸 -------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        listTop = headerH
        listBottom = h - footerH
        bgPaint.shader = LinearGradient(
            0f, 0f, w * 0.4f, h.toFloat(),
            intArrayOf(0xFF0A211B.toInt(), 0xFF071612.toInt(), 0xFF061210.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        sparkFillPaint.shader = LinearGradient(
            0f, listTop, 0f, 0f,
            intArrayOf(0x572BE79F, 0x002BE79F),
            null,
            Shader.TileMode.CLAMP
        )
        clipPath.reset()
        clipPath.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW)
    }

    // ---- 绘制 -------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        drawHeader(canvas, w)
        drawBody(canvas, w)
        drawFooter(canvas, w, h)
        drawFrame(canvas, w, h)

        canvas.restore()
        if (isFocused) {
            canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, focusRingPaint)
        }
    }

    private fun drawHeader(canvas: Canvas, w: Float) {
        val centerY = headerH / 2f
        canvas.drawLine(0f, headerH, w, headerH, innerLinePaint)

        // 标题
        canvas.drawText("NET MONITOR", padH, centerY - dp(4f), pTitle)
        canvas.drawText("网络活动监视", padH, centerY + dp(11f), pSub)

        // LIVE 指示灯
        var x = padH + dp(104f)
        val dotR = dp(2.4f)
        canvas.drawCircle(x + dotR, centerY - dp(5f), dotR, ledPaint)
        canvas.drawText("LIVE", x + dotR * 2 + dp(4f), centerY - dp(1.5f), pLive)

        // 切换按钮（贴右上角）
        val btnW = dp(52f)
        val btnH = dp(18f)
        btnRect.set(w - padH - btnW, centerY - btnH / 2f, w - padH, centerY + btnH / 2f)
        canvas.drawRoundRect(btnRect, btnH / 2f, btnH / 2f, btnBgPaint)
        canvas.drawRoundRect(btnRect, btnH / 2f, btnH / 2f, btnBorderPaint)
        pBtn.textAlign = Paint.Align.CENTER
        canvas.drawText("切换", btnRect.centerX(), btnRect.centerY() + dp(4.2f), pBtn)
        pBtn.textAlign = Paint.Align.LEFT

        // 进程 / 连接数，贴在按钮左边
        val countRight = btnRect.left - dp(10f)
        val unitW = pCountUnit.measureText("进程")
        pCount.textAlign = Paint.Align.RIGHT
        pCountUnit.textAlign = Paint.Align.RIGHT
        canvas.drawText("$procCount", countRight - unitW - dp(3f), centerY - dp(1f), pCount)
        canvas.drawText("进程", countRight, centerY - dp(1f), pCountUnit)
        canvas.drawText("$connCount 条连接", countRight, centerY + dp(11f), pCountUnit)
        pCount.textAlign = Paint.Align.LEFT
        pCountUnit.textAlign = Paint.Align.LEFT

        // sparkline 占据标题和计数之间的空白
        val sx = padH + dp(150f)
        val sw = countRight - dp(46f) - sx
        if (sw > dp(30f)) drawSpark(canvas, sx, centerY - dp(9f), sw, dp(18f))
    }

    private fun drawSpark(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        if (spark.size < 2) return
        val maxValue = max(1L, spark.maxOrNull() ?: 1L)
        val stepX = width / (spark.size - 1)
        sparkPath.reset()
        sparkFillPath.reset()
        var i = 0
        for (v in spark) {
            val x = left + i * stepX
            val y = top + height - (v.toFloat() / maxValue) * height
            if (i == 0) {
                sparkPath.moveTo(x, y)
                sparkFillPath.moveTo(x, top + height)
                sparkFillPath.lineTo(x, y)
            } else {
                sparkPath.lineTo(x, y)
                sparkFillPath.lineTo(x, y)
            }
            i++
        }
        sparkFillPath.lineTo(left + (spark.size - 1) * stepX, top + height)
        sparkFillPath.close()
        canvas.drawPath(sparkFillPath, sparkFillPaint)
        canvas.drawPath(sparkPath, sparkPaint)
    }

    private fun drawBody(canvas: Canvas, w: Float) {
        canvas.save()
        canvas.clipRect(0f, listTop, w, listBottom)
        if (mode == Mode.LOG) drawLog(canvas, w) else drawRank(canvas, w)
        canvas.restore()
    }

    private fun drawLog(canvas: Canvas, w: Float) {
        val count = rows.size
        if (count == 0) {
            canvas.drawText("正在采样…", padH, listTop + rowH, pFootDim)
            return
        }
        val maxRowRate = max(1L, rows.maxOf { it.rate }).toFloat()
        // 最新的一行在最下面；插入时整体上移 rowH，形成"滚上去"的观感
        val firstBaseline = listBottom - (count - 1) * rowH - rowH / 2f - rollOffset + textRow * 0.36f
        var i = 0
        for (row in rows) {
            val baseY = firstBaseline + i * rowH
            if (baseY > listTop - rowH && baseY < listBottom + rowH) {
                drawLogRow(canvas, row, baseY, w, maxRowRate)
            }
            i++
        }
    }

    private fun drawLogRow(canvas: Canvas, row: Row, baseY: Float, w: Float, maxRowRate: Float) {
        var x = padH
        val midY = baseY - textRow * 0.36f

        canvas.drawText(row.time, x, baseY, pTime)
        x += colTime

        canvas.drawCircle(x + colLed / 2f, midY, dp(2.2f), ledPaint)
        x += colLed + colGap

        pPeer.textAlign = Paint.Align.CENTER
        canvas.drawText(if (row.isUp) "▲" else "▼", x + colDir / 2f, baseY, if (row.isUp) pValUp else pValDown)
        pPeer.textAlign = Paint.Align.LEFT
        x += colDir + colGap

        // 中文名 + 包名末段；宽度不够时截断中文名
        val nameMax = colName - dp(8f)
        var label = row.label
        while (label.isNotEmpty() && pName.measureText(label) > nameMax - pPkg.measureText(row.pkg) - dp(6f)) {
            label = label.dropLast(1)
        }
        if (label != row.label) label = label.dropLast(1) + "…"
        canvas.drawText(label, x, baseY, pName)
        if (row.pkg.isNotEmpty()) {
            canvas.drawText(row.pkg, x + pName.measureText(label) + dp(5f), baseY, pPkg)
        }
        x += colName + colGap

        // 速率条：长度是「相对当前最活跃那条连接」的比例（开方压缩），
        // 绝对值看右边的数字。这样即使整机只有几十 KB/s，条也不会是一排小点。
        if (row.rate > 0 && maxRowRate > 0) {
            val ratio = sqrt(min(1f, row.rate.toFloat() / maxRowRate)).coerceIn(0.08f, 1f)
            val barTop = midY - dp(2.5f)
            tmpRect.set(x, barTop, x + colBar, barTop + dp(5f))
            canvas.drawRoundRect(tmpRect, dp(2.5f), dp(2.5f), barBgPaint)
            tmpRect.right = x + colBar * ratio
            canvas.drawRoundRect(tmpRect, dp(2.5f), dp(2.5f), if (row.isUp) barUpPaint else barDownPaint)
        }
        x += colBar + colGap

        val value = formatRate(row.rate)
        pValDown.textAlign = Paint.Align.RIGHT
        pValUp.textAlign = Paint.Align.RIGHT
        canvas.drawText(value, x + colVal, baseY, if (row.isUp) pValUp else pValDown)
        pValDown.textAlign = Paint.Align.LEFT
        pValUp.textAlign = Paint.Align.LEFT
        x += colVal + colGap

        val peerRight = w - padH
        if (peerRight - x > dp(28f) && row.remote.isNotEmpty()) {
            val text = ellipsize(row.remote, pPeer, peerRight - x)
            pPeer.textAlign = Paint.Align.RIGHT
            canvas.drawText(text, peerRight, baseY, pPeer)
            pPeer.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawRank(canvas: Canvas, w: Float) {
        val list = snapshot?.procs.orEmpty().take(VISIBLE_ROWS)
        if (list.isEmpty()) {
            canvas.drawText("正在采样…", padH, listTop + rowH, pFootDim)
            return
        }
        val maxRate = max(1L, list.first().total)
        var i = 0
        for (proc in list) {
            val top = listTop + i * rowH
            val baseY = top + rowH / 2f + textRow * 0.36f
            val midY = top + rowH / 2f
            val hot = i < 3

            var x = padH
            canvas.drawText(String.format(Locale.US, "%02d", i + 1), x, baseY, if (hot) pValDown else pRank)
            x += dp(20f)

            canvas.drawCircle(x + colLed / 2f, midY, dp(2.2f), ledPaint)
            x += colLed + colGap

            val nameMax = dp(178f)
            var label = proc.label
            while (label.isNotEmpty() && pName.measureText(label) > nameMax - pPkg.measureText(shortPkg(proc.pkg)) - dp(6f)) {
                label = label.dropLast(1)
            }
            if (label != proc.label) label = label.dropLast(1) + "…"
            canvas.drawText(label, x, baseY, pName)
            val pkg = shortPkg(proc.pkg)
            if (pkg.isNotEmpty()) {
                canvas.drawText(pkg, x + pName.measureText(label) + dp(5f), baseY, pPkg)
            }
            x += dp(196f) + colGap

            val ratio = sqrt(proc.total.toFloat() / maxRate).coerceIn(0.04f, 1f)
            val barTop = midY - dp(2.5f)
            tmpRect.set(x, barTop, x + colBar + dp(24f), barTop + dp(5f))
            canvas.drawRoundRect(tmpRect, dp(2.5f), dp(2.5f), barBgPaint)
            tmpRect.right = x + (colBar + dp(24f)) * ratio
            canvas.drawRoundRect(tmpRect, dp(2.5f), dp(2.5f), barDownPaint)
            x += colBar + dp(24f) + colGap

            pValDown.textAlign = Paint.Align.RIGHT
            canvas.drawText(formatRate(proc.total), x + colVal, baseY, pValDown)
            pValDown.textAlign = Paint.Align.LEFT
            x += colVal + colGap

            val tag = when {
                proc.total > 1.2 * 1024 * 1024 -> "大流量"
                proc.total > 200 * 1024 -> "活跃"
                else -> "空闲"
            }
            pPeer.textAlign = Paint.Align.RIGHT
            canvas.drawText(tag, w - padH, baseY, pPeer)
            pPeer.textAlign = Paint.Align.LEFT
            i++
        }

        // 进程凑不满一屏时补几条空槽：像一台真在扫的监视器，而不是半屏留白
        while (i < VISIBLE_ROWS) {
            val midY = listTop + i * rowH + rowH / 2f
            val baseY = midY + textRow * 0.36f
            canvas.drawText(String.format(Locale.US, "%02d", i + 1), padH, baseY, pRank)
            tmpRect.set(padH + dp(20f) + colLed + colGap, midY - dp(0.75f), w - padH, midY + dp(0.75f))
            canvas.drawRoundRect(tmpRect, dp(0.75f), dp(0.75f), barBgPaint)
            i++
        }
    }

    private fun drawFooter(canvas: Canvas, w: Float, h: Float) {
        val y = h - footerH / 2f + dp(4f)
        canvas.drawLine(0f, h - footerH, w, h - footerH, innerLinePaint)

        var x = padH
        canvas.drawText("下行", x, y, pFoot)
        x += pFoot.measureText("下行") + dp(4f)
        canvas.drawText(formatRate(totalDown), x, y, pValDown)
        x += pValDown.measureText(formatRate(totalDown)) + dp(14f)

        canvas.drawText("上行", x, y, pFoot)
        x += pFoot.measureText("上行") + dp(4f)
        canvas.drawText(formatRate(totalUp), x, y, pValUp)
        x += pValUp.measureText(formatRate(totalUp)) + dp(14f)

        canvas.drawText("峰值", x, y, pFoot)
        x += pFoot.measureText("峰值") + dp(4f)
        canvas.drawText(formatRate(peak), x, y, pValDown)

        if (snapshot?.hasRate == false) {
            pFootDim.textAlign = Paint.Align.RIGHT
            canvas.drawText("速率不可用（内核未开 qtaguid）", w - padH, y, pFootDim)
            pFootDim.textAlign = Paint.Align.LEFT
        } else {
            pFootDim.textAlign = Paint.Align.RIGHT
            canvas.drawText("每 2 秒一条 · 只统计已建立连接", w - padH, y, pFootDim)
            pFootDim.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawFrame(canvas: Canvas, w: Float, h: Float) {
        canvas.drawRect(0f, 0f, w, h, scanPaint)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, borderPaint)
        canvas.drawRoundRect(
            RectF(dp(5f), dp(5f), w - dp(5f), h - dp(5f)),
            radius - dp(3f), radius - dp(3f), innerLinePaint
        )
        // 四角取景标记
        val c = dp(13f)
        val inset = dp(9f)
        drawCorner(canvas, inset, inset, c, 1f, 1f)
        drawCorner(canvas, w - inset, inset, c, -1f, 1f)
        drawCorner(canvas, inset, h - inset, c, 1f, -1f)
        drawCorner(canvas, w - inset, h - inset, c, -1f, -1f)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, len: Float, dx: Float, dy: Float) {
        canvas.drawLine(x, y, x + len * dx, y, cornerPaint)
        canvas.drawLine(x, y, x, y + len * dy, cornerPaint)
    }

    // ---- 交互 -------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            toggleMode()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        onFocusTip?.invoke(gainFocus)
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ---- 小工具 -----------------------------------------------------------------

    private fun scanShader(): Shader {
        val tileH = 3
        val bmp = Bitmap.createBitmap(1, tileH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0x00000000)
        c.drawLine(0f, 0f, 1f, 0f, Paint().apply { color = 0x0F78FFD7 })
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun textPaint(size: Float, color: Int, face: Typeface, style: Int = Typeface.NORMAL): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(face, style)
        }

    /**
     * 地址放不下时保留**尾部**：IP 的末段比开头有信息量（IPv6 尤其明显，
     * 前面的 ::ffff: 千篇一律，真正区分的是后面）。
     */
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 3 && paint.measureText("…$t") > maxWidth) t = t.drop(1)
        return "…$t"
    }

    private fun formatRate(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val mb = bytes / 1024.0 / 1024.0
        return when {
            mb >= 0.95 -> String.format(Locale.US, "%.2f MB/s", mb)
            bytes >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytes / 1024.0)
            else -> "$bytes B/s"
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        /** 一屏 7 行：40(头) + 7*28.5(行) + 22(脚) = 261.5dp，正好是画框原来的 262dp */
        private const val VISIBLE_ROWS = 7
    }
}
