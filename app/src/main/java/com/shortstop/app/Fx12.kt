package com.shortstop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

private val EMBER_HI = Color.parseColor("#FFB27A")
private val EMBER_LO = Color.parseColor("#FF6A2B")

/** Last two weeks as a smooth line with a soft ember wash underneath. It draws itself in once. */
class BlockSpark(ctx: Context, private val pal: Palette, private val vals: List<Int>) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var reveal = 0f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f * d; color = pal.ink; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val area = Path()
    private val pts = FloatArray(vals.size * 2)
    private var wash: LinearGradient? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            interpolator = SpringInterpolator(8f, 1f, 1.2f)
            addUpdateListener { reveal = (it.animatedValue as Float).coerceIn(0f, 1f); invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (78f * d).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val n = vals.size
        val mx = Math.max(vals.maxOrNull() ?: 1, 1).toFloat()
        val top = 8f * d
        val bot = h - 8f * d
        for (i in 0 until n) {
            pts[i * 2] = if (n == 1) w / 2f else w * i / (n - 1f)
            pts[i * 2 + 1] = bot - (bot - top) * (vals[i] / mx)
        }
        path.reset()
        path.moveTo(pts[0], pts[1])
        for (i in 0 until n - 1) {
            val x0 = pts[i * 2]; val y0 = pts[i * 2 + 1]
            val x1 = pts[i * 2 + 2]; val y1 = pts[i * 2 + 3]
            val mid = (x0 + x1) / 2f
            path.cubicTo(mid, y0, mid, y1, x1, y1)
        }
        area.set(path)
        area.lineTo(pts[(n - 1) * 2], h.toFloat())
        area.lineTo(pts[0], h.toFloat())
        area.close()
        wash = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.argb(if (pal.dark) 110 else 120, 255, 138, 61), Color.argb(0, 255, 138, 61), Shader.TileMode.CLAMP)
        fill.shader = wash
    }

    override fun onDraw(c: Canvas) {
        val n = vals.size
        if (n < 2) return
        c.save()
        c.clipRect(0f, 0f, width * reveal, height.toFloat())
        c.drawPath(area, fill)
        c.drawPath(path, line)
        c.restore()
        if (reveal > 0.95f) {
            val x = pts[(n - 1) * 2]; val y = pts[(n - 1) * 2 + 1]
            val p = 0.5f + 0.5f * Math.sin(SystemClock.uptimeMillis() / 500.0).toFloat()
            dot.color = Color.argb((50 + 50 * p).toInt(), 255, 138, 61)
            c.drawCircle(x - 4f * d, y, (6f + 3f * p) * d, dot)
            dot.color = pal.surface
            c.drawCircle(x - 4f * d, y, 4.6f * d, dot)
            dot.color = pal.ink
            c.drawCircle(x - 4f * d, y, 3f * d, dot)
            if (isShown) postInvalidateDelayed(50)
        }
    }
}

/** Seven days of blocks. Touch or drag across the bars to read any day; the picked bar glows ember. */
class BlockBars(
    ctx: Context, private val pal: Palette, private val fonts: Fonts,
    private val vals: List<Int>, private val labels: List<String>, today: Int
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val sd = ctx.resources.displayMetrics.scaledDensity
    private var sel = today
    private val today = today
    private var shown = FloatArray(vals.size)
    private var reveal = 0f
    private var selAnim = FloatArray(vals.size).also { if (today in it.indices) it[today] = 1f }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.border; strokeWidth = d }
    private val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 12f * sd }
    private val tip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tipTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = fonts.semi; textSize = 12.5f * sd }
    private val rect = RectF()
    private val chartH get() = 132f * d
    private val tipH get() = 30f * d
    private val labH get() = 26f * d
    private val mx = Math.max(vals.maxOrNull() ?: 1, 1)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (tipH + chartH + labH).toInt())
    }

    private fun spring(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (Math.exp((-7.5 * x).toDouble()) * Math.cos(x * 9.0)).toFloat()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val n = vals.size
        val slot = w / n
        val bw = slot * 0.46f
        val top = tipH
        val base = tipH + chartH
        for (g in 0..2) {
            val y = base - chartH * g / 2f
            c.drawLine(0f, y, w, y, grid)
        }
        var again = false
        for (i in 0 until n) {
            val target = if (i == sel) 1f else 0f
            val cur = selAnim[i]
            val nx = cur + (target - cur) * 0.22f
            selAnim[i] = if (Math.abs(nx - target) < 0.01f) target else nx
            if (selAnim[i] != target) again = true
            val rv = spring((reveal * 1.4f - i * 0.07f))
            val full = if (vals[i] == 0) 7f * d else Math.max(10f * d, chartH * 0.94f * vals[i] / mx)
            val h = full * rv
            val cx = slot * i + slot / 2f
            val s = selAnim[i]
            val x0 = cx - bw / 2f - 2f * d * s
            val x1 = cx + bw / 2f + 2f * d * s
            val y0 = base - h
            rect.set(x0, y0, x1, base)
            val rr = (x1 - x0) / 2f
            if (s > 0.01f) {
                bar.shader = LinearGradient(0f, y0, 0f, base, EMBER_HI, EMBER_LO, Shader.TileMode.CLAMP)
                bar.alpha = (255 * s).toInt()
                c.drawRoundRect(rect, rr, rr, bar)
                bar.shader = null
            }
            if (s < 0.99f) {
                bar.color = if (pal.dark) Color.WHITE else Color.BLACK
                bar.alpha = (255 * (1f - s)).toInt()
                c.drawRoundRect(rect, rr, rr, bar)
            }
            bar.alpha = 255
            lab.typeface = if (i == sel) fonts.bold else fonts.medium
            lab.color = if (i == sel) pal.ink else pal.muted
            c.drawText(labels[i], cx, base + 18f * d, lab)
        }
        // value bubble over the picked bar
        if (sel in 0 until n) {
            val cx = slot * sel + slot / 2f
            val txt = vals[sel].toString()
            val tw = tipTxt.measureText(txt) + 22f * d
            val bx0 = (cx - tw / 2f).coerceIn(0f, w - tw)
            val rv = spring((reveal * 1.4f - sel * 0.07f))
            val barTop = base - (if (vals[sel] == 0) 7f * d else Math.max(10f * d, chartH * 0.94f * vals[sel] / mx)) * rv
            val by = (barTop - tipH + 2f * d).coerceAtLeast(0f)
            rect.set(bx0, by, bx0 + tw, by + 24f * d)
            tip.color = Color.parseColor(if (pal.dark) "#F0A15F" else "#F47A24")
            c.drawRoundRect(rect, 12f * d, 12f * d, tip)
            tipTxt.color = pal.canvas
            c.drawText(txt, bx0 + tw / 2f, by + 12f * d - (tipTxt.ascent() + tipTxt.descent()) / 2f, tipTxt)
        }
        if (again || reveal < 1f) postInvalidateOnAnimation()
    }

    private fun pick(x: Float) {
        val i = (x / (width / vals.size.toFloat())).toInt().coerceIn(0, vals.size - 1)
        if (i != sel) {
            sel = i
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); pick(e.x) }
            MotionEvent.ACTION_MOVE -> pick(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}

/** The 24 hours of the day as a strip: the more you were pulled toward blocked things, the warmer the cell. */
class HeatStrip(ctx: Context, private val pal: Palette, private val fonts: Fonts, private val byHour: List<Int>) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val sd = ctx.resources.displayMetrics.scaledDensity
    private var reveal = 0f
    private val cell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.muted; textSize = 11f * sd; typeface = fonts.medium }
    private val rect = RectF()
    private val mx = Math.max(byHour.maxOrNull() ?: 1, 1)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (66f * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val gap = 3f * d
        val cw = (w - gap * 23) / 24f
        val ch = 40f * d
        for (i in 0 until 24) {
            val a = ((reveal * 1.5f - i * 0.025f)).coerceIn(0f, 1f)
            val v = byHour.getOrElse(i) { 0 } / mx.toFloat()
            val warm = if (byHour.getOrElse(i) { 0 } == 0) 0f else (0.25f + 0.75f * v)
            cell.color = mixColor(mixColor(pal.surface, pal.ink, if (pal.dark) 0.18f else 0.08f), mixColor(EMBER_HI, EMBER_LO, v), warm)
            cell.alpha = (255 * a).toInt()
            val x = i * (cw + gap)
            rect.set(x, 0f, x + cw, ch)
            c.drawRoundRect(rect, cw / 2.2f, cw / 2.2f, cell)
        }
        val names = arrayOf("12a", "6a", "12p", "6p")
        for (k in 0..3) {
            val x = k * 6 * (cw + gap)
            c.drawText(names[k], x, ch + 20f * d, lab)
        }
    }
}
