package com.shortstop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import java.util.Calendar

/** A 24-hour strip with the schedule's window on it. The window glides when the times change. */
class DayBar(ctx: Context, private val pal: Palette, private val fonts: Fonts, private val labels: Boolean) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val sd = ctx.resources.displayMetrics.scaledDensity
    private var s = 0f
    private var e = 0f
    private var on = true
    private var onT = 1f
    private var anim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mixColor(pal.surface, pal.ink, if (pal.dark) 0.14f else 0.09f) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.muted; textSize = 10.5f * sd; typeface = fonts.medium; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val barH get() = 10f * d

    fun set(start: Int, end: Int, enabled: Boolean, animate: Boolean) {
        val ts = start.toFloat()
        val te = (if (end > start) end else end + 1440).toFloat()
        val fs = s; val fe = e; val fo = onT
        val to = if (enabled) 1f else 0f
        on = enabled
        anim?.cancel()
        if (!animate || !isAttachedToWindow) { s = ts; e = te; onT = to; invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = SpringInterpolator(14f, 0.85f, 0.7f)
            addUpdateListener {
                val k = it.animatedValue as Float
                s = fs + (ts - fs) * k; e = fe + (te - fe) * k; onT = fo + (to - fo) * k
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), ((if (labels) 30f else 10f) * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val bh = barH
        rect.set(0f, 0f, w, bh)
        c.drawRoundRect(rect, bh / 2f, bh / 2f, track)
        bar.color = mixColor(mixColor(pal.border, pal.muted, 0.6f), pal.ink, onT)
        fun seg(a: Float, b: Float) {
            if (b <= a) return
            rect.set(w * a / 1440f, 0f, w * b / 1440f, bh)
            c.drawRoundRect(rect, bh / 2f, bh / 2f, bar)
        }
        seg(s.coerceIn(0f, 1440f), e.coerceAtMost(1440f))
        if (e > 1440f) seg(0f, e - 1440f)
        if (labels) {
            val names = arrayOf("12a", "6a", "12p", "6p", "12a")
            for (i in 0..4) {
                tick.textAlign = if (i == 0) Paint.Align.LEFT else if (i == 4) Paint.Align.RIGHT else Paint.Align.CENTER
                c.drawText(names[i], w * i / 4f, bh + 16f * d, tick)
            }
        }
    }
}

/** The whole week at a glance: one row per day, each schedule drawn as a bar, with a pulse at the current time. */
class WeekTimeline(
    ctx: Context, private val pal: Palette, private val fonts: Fonts,
    var items: List<Store.FocusSchedule>
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val sd = ctx.resources.displayMetrics.scaledDensity
    private var reveal = 0f
    private val rowH get() = 24f * d
    private val left get() = 26f * d
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mixColor(pal.surface, pal.ink, if (pal.dark) 0.12f else 0.07f) }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.border; strokeWidth = d }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val day = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * sd; textAlign = Paint.Align.CENTER }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.muted; textSize = 10.5f * sd; typeface = fonts.medium }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.canvas }
    private val dotIn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
    private val rect = RectF()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            interpolator = SpringInterpolator(9f, 0.9f, 1.1f)
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (rowH * 7 + 22f * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val tw = w - left
        val bh = 12f * d
        val cal = Calendar.getInstance()
        val today = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        val letters = "MTWTFSS"
        for (r in 0 until 7) {
            val cy = r * rowH + rowH / 2f
            day.typeface = if (r == today) fonts.bold else fonts.medium
            day.color = if (r == today) pal.ink else pal.muted
            c.drawText(letters[r].toString(), left / 2f - 2f * d, cy - (day.ascent() + day.descent()) / 2f, day)
            rect.set(left, cy - bh / 2f, w, cy + bh / 2f)
            c.drawRoundRect(rect, bh / 2f, bh / 2f, track)
        }
        for (g in intArrayOf(6, 12, 18)) {
            val x = left + tw * g / 24f
            c.drawLine(x, 0f, x, rowH * 7, guide)
        }
        fun seg(row: Int, a: Float, b: Float, s: Store.FocusSchedule) {
            if (b <= a) return
            val cy = row * rowH + rowH / 2f
            val grow = ((reveal * 1.35f - row * 0.05f)).coerceIn(0f, 1f)
            val x0 = left + tw * a / 1440f
            val x1 = x0 + (tw * b / 1440f - x0) * grow
            bar.color = if (s.on) pal.ink else mixColor(pal.border, pal.muted, 0.6f)
            rect.set(x0, cy - bh / 2f, x1, cy + bh / 2f)
            c.drawRoundRect(rect, bh / 2f, bh / 2f, bar)
        }
        for (s in items) for (r in 0 until 7) {
            if (((s.days shr r) and 1) == 0) continue
            if (s.end > s.start) seg(r, s.start.toFloat(), s.end.toFloat(), s)
            else {
                seg(r, s.start.toFloat(), 1440f, s)
                seg((r + 1) % 7, 0f, s.end.toFloat(), s)
            }
        }
        // now
        val nx = left + tw * nowMin / 1440f
        val ny = today * rowH + rowH / 2f
        val p = 0.5f + 0.5f * Math.sin(SystemClock.uptimeMillis() / 480.0).toFloat()
        halo.color = Color.argb((50 + 50 * p).toInt(), Color.red(pal.ink), Color.green(pal.ink), Color.blue(pal.ink))
        c.drawCircle(nx, ny, (7f + 3f * p) * d, halo)
        c.drawCircle(nx, ny, 4.6f * d, dot)
        c.drawCircle(nx, ny, 3.2f * d, dotIn)
        val names = arrayOf("12a", "6a", "12p", "6p")
        for (i in 0..3) {
            axis.textAlign = if (i == 0) Paint.Align.LEFT else Paint.Align.CENTER
            c.drawText(names[i], left + tw * i / 4f + (if (i == 0) 0f else 0f), rowH * 7 + 16f * d, axis)
        }
        if (isShown) postInvalidateDelayed(50)
    }
}

/** A small dot with a ring that breathes outward, for "live" states. */
class LiveDot(ctx: Context, private val color: Int) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var live = false
        set(v) { field = v; invalidate() }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension((22f * d).toInt(), (22f * d).toInt()) }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (live) {
            val t = (SystemClock.uptimeMillis() % 1600L) / 1600f
            p.color = Color.argb((90 * (1f - t)).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            c.drawCircle(cx, cy, (4f + 7f * t) * d, p)
        }
        p.color = if (live) color else Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))
        c.drawCircle(cx, cy, 4f * d, p)
        if (live && isShown) postInvalidateOnAnimation()
    }
}
