package com.shortstop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import java.util.Calendar

private fun Context.dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Progress ring with big and small text in the middle. */
class RingView(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    private val sizeDp: Int = 220,
    private val strokeDp: Int = 14,
    private val showText: Boolean = true
) : View(ctx) {
    private val roll = RollPainter(this)
    var big: String = ""
        set(value) {
            if (field != value) {
                roll.set(value, field.isNotEmpty() && isAttachedToWindow && width > 0)
                field = value
                invalidate()
            }
        }
    var small: String = ""
    private var progress = 0f
    private var anim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = pal.track
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = pal.ink; strokeCap = Paint.Cap.ROUND
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.ink; typeface = fonts.bold; textAlign = Paint.Align.CENTER
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.muted; typeface = fonts.medium; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setProgress(p: Float, animate: Boolean, ms: Long = 800) {
        val target = p.coerceIn(0f, 1f)
        anim?.cancel()
        if (!animate) {
            progress = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val limit = context.dpi(sizeDp)
        val s = minOf(MeasureSpec.getSize(w).takeIf { it > 0 } ?: limit, limit)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val stroke = context.dpi(strokeDp).toFloat()
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val inset = stroke / 2f
        rect.set(inset, inset, width - inset, height - inset)
        c.drawOval(rect, track)
        if (progress > 0.001f) c.drawArc(rect, -90f, 360f * progress, false, arc)
        if (showText) {
            bigPaint.textSize = width * 0.2f
            smallPaint.textSize = width * 0.062f
            val cy = height / 2f
            roll.draw(c, bigPaint, width / 2f, cy + bigPaint.textSize * 0.2f)
            if (small.isNotEmpty()) c.drawText(small, width / 2f, cy + bigPaint.textSize * 0.2f + smallPaint.textSize * 2.0f, smallPaint)
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

/** A 24-hour strip with the focus sessions drawn as black blocks. */
class TimelineView(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    private val segments: List<Pair<Float, Float>> // start/end as 0..1 of the day
) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.track }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.muted; typeface = fonts.medium }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(50))
    }

    override fun onDraw(c: Canvas) {
        val barH = context.dpi(14).toFloat()
        val r = barH / 2f
        rect.set(0f, 0f, width.toFloat(), barH)
        c.drawRoundRect(rect, r, r, bg)
        for ((a, b) in segments) {
            val l = width * a.coerceIn(0f, 1f)
            val rr = maxOf(l + context.dpi(6), width * b.coerceIn(0f, 1f))
            rect.set(l, 0f, rr, barH)
            c.drawRoundRect(rect, r, r, fg)
        }
        txt.textSize = context.dpi(11).toFloat()
        val labels = listOf("12am", "6am", "12pm", "6pm", "12am")
        labels.forEachIndexed { i, s ->
            txt.textAlign = when (i) { 0 -> Paint.Align.LEFT; 4 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            val x = when (i) { 0 -> 0f; 4 -> width.toFloat(); else -> width * i / 4f }
            c.drawText(s, x, barH + context.dpi(22), txt)
        }
    }
}

/** Month calendar: each day gets a small ring that fills toward the daily goal. */
class MonthView(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    private val year: Int,
    private val month: Int, // 0-based
    private val minutes: IntArray, // index 1..31
    private val goal: Int
) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = pal.track }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = pal.ink; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.semi; textAlign = Paint.Align.CENTER }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.medium; textAlign = Paint.Align.CENTER; color = pal.muted }
    private val rect = RectF()
    private val first = Calendar.getInstance().apply { set(year, month, 1, 12, 0, 0) }
    private val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    private val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon = 0
    private val rows = (offset + days + 6) / 7
    private val todayKey: Int = Calendar.getInstance().let {
        if (it.get(Calendar.YEAR) == year && it.get(Calendar.MONTH) == month) it.get(Calendar.DAY_OF_MONTH) else -1
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, context.dpi(28) + rows * (width / 7))
    }

    override fun onDraw(c: Canvas) {
        val cell = width / 7f
        head.textSize = context.dpi(12).toFloat()
        "MTWTFSS".forEachIndexed { i, ch -> c.drawText(ch.toString(), cell * i + cell / 2f, context.dpi(16).toFloat(), head) }
        val stroke = context.dpi(3).toFloat()
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        num.textSize = context.dpi(12).toFloat()
        for (d in 1..days) {
            val idx = offset + d - 1
            val col = idx % 7
            val row = idx / 7
            val cx = cell * col + cell / 2f
            val cy = context.dpi(28) + cell * row + cell / 2f
            val r = cell * 0.36f
            rect.set(cx - r, cy - r, cx + r, cy + r)
            val m = if (d < minutes.size) minutes[d] else 0
            val frac = if (goal > 0) (m.toFloat() / goal).coerceIn(0f, 1f) else 0f
            if (frac >= 1f) {
                c.drawCircle(cx, cy, r, fill)
                num.color = pal.canvas
            } else {
                c.drawOval(rect, track)
                if (frac > 0f) c.drawArc(rect, -90f, 360f * frac, false, arc)
                num.color = if (d == todayKey) pal.ink else pal.muted
            }
            c.drawText(d.toString(), cx, cy + num.textSize * 0.35f, num)
        }
    }
}

/** ScrollView that never grows past [maxPx]; used inside bottom sheets. */
class CapScroll(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
    }
}
