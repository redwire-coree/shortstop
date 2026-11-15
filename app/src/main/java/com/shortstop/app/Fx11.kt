package com.shortstop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.LinearLayout

/**
 * react-bits Option Wheel as a picker: options sit on a curve, fade and shrink away from the middle, the wheel
 * eases toward its target with frame-rate independent smoothing, drags freely, flings, and snaps to an option with
 * a tick. Tap any visible option to jump to it.
 */
class WheelPicker(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    private val items: List<String>,
    start: Int,
    private val loop: Boolean,
    private val onChange: (Int) -> Unit
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val sd = ctx.resources.displayMetrics.scaledDensity
    private val n = items.size
    private val rowH = 44f * d
    private var pos = start.toFloat()
    private var target = start.toFloat()
    private var sel = start
    private var last = 0L
    private var dragY = 0f
    private var dragStart = 0f
    private var dragging = false
    private var moved = false
    private var vt: VelocityTracker? = null
    private val tilt = Math.toRadians(11.0).toFloat()
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = fonts.semi }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mixColor(pal.surface, pal.ink, if (pal.dark) 0.16f else 0.08f) }
    private val rect = RectF()

    val selected: Int get() = sel

    fun select(i: Int, animate: Boolean) {
        val idx = ((i % n) + n) % n
        if (loop) {
            // take the short way round
            var dd = idx - (((Math.round(target) % n) + n) % n)
            if (dd > n / 2) dd -= n else if (dd < -n / 2) dd += n
            target = Math.round(target) + dd.toFloat()
        } else target = idx.toFloat()
        sel = idx
        if (!animate) pos = target
        invalidate()
    }

    private fun apply(v: Float, snap: Boolean) {
        var t = v
        if (!loop) t = t.coerceIn(0f, (n - 1).toFloat())
        if (snap) t = Math.round(t).toFloat()
        target = t
        val idx = ((Math.round(t) % n) + n) % n
        if (idx != sel) {
            sel = idx
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange(idx)
        }
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(if (s <= 0) (80f * d).toInt() else s, (rowH * 5).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        val tau = if (dragging) 0.05f else 0.11f
        val k = 1f - Math.exp((-dt / tau).toDouble()).toFloat()
        pos += (target - pos) * k
        val settled = Math.abs(target - pos) < 0.002f
        if (settled) pos = target

        val cx = w / 2f
        val cy = h / 2f
        rect.set(d * 2f, cy - rowH / 2f, w - d * 2f, cy + rowH / 2f)
        c.drawRoundRect(rect, rowH / 2f, rowH / 2f, band)

        val R = rowH / tilt
        val base = Math.floor(pos.toDouble()).toInt()
        for (o in -3..4) {
            val raw = base + o
            val idx = ((raw % n) + n) % n
            if (!loop && (raw < 0 || raw >= n)) continue
            val dd = raw - pos
            val dist = Math.abs(dd)
            if (dist > 2.7f) continue
            val ang = (dd * tilt).coerceIn(-1.45f, 1.45f)
            val y = cy + R * Math.sin(ang.toDouble()).toFloat()
            val sy = Math.cos(ang.toDouble()).toFloat()
            val p = (1f - Math.min(dist, 1f))
            txt.color = mixColor(pal.muted, pal.ink, p)
            txt.alpha = (255 * Math.max(0.05f, 1f - dist * 0.34f)).toInt()
            txt.textSize = 24f * sd * (1f + 0.08f * p)
            c.save()
            c.translate(cx, y)
            c.scale(1f, sy)
            c.drawText(items[idx], 0f, -(txt.ascent() + txt.descent()) / 2f, txt)
            c.restore()
        }
        if (!settled || dragging) postInvalidateOnAnimation() else last = 0L
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true; moved = false
                dragY = e.y; dragStart = target
                vt?.recycle(); vt = VelocityTracker.obtain(); vt!!.addMovement(e)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.y - dragY
                if (!moved && Math.abs(dy) > 6f * d) moved = true
                if (moved) apply(dragStart - dy / rowH, false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    vt?.addMovement(e)
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    apply(target - (vy / rowH) * 0.16f, true)
                } else if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    val off = Math.round((e.y - height / 2f) / rowH)
                    apply(Math.round(target) + off.toFloat(), true)
                } else apply(target, true)
                vt?.recycle(); vt = null
                invalidate()
            }
        }
        return true
    }
}

/** Hour, minute and AM/PM wheels side by side. Minutes move in 5s so a time takes a couple of flicks. */
class TimeWheel(
    ctx: Context,
    pal: Palette,
    fonts: Fonts,
    initial: Int,
    private val onChange: (Int) -> Unit
) : LinearLayout(ctx) {
    private val hour: WheelPicker
    private val minute: WheelPicker
    private val ampm: WheelPicker
    private var silent = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val hs = (1..12).map { it.toString() }
        val ms = (0 until 60 step 5).map { String.format("%02d", it) }
        hour = WheelPicker(ctx, pal, fonts, hs, 0, true) { fire() }
        minute = WheelPicker(ctx, pal, fonts, ms, 0, true) { fire() }
        ampm = WheelPicker(ctx, pal, fonts, listOf("AM", "PM"), 0, false) { fire() }
        addView(hour, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(minute, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(ampm, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        set(initial, false)
    }

    private fun value(): Int {
        val h12 = (hour.selected + 1) % 12
        return ((if (ampm.selected == 1) 12 else 0) + h12) * 60 + minute.selected * 5
    }

    private fun fire() { if (!silent) onChange(value()) }

    fun set(minutes: Int, animate: Boolean) {
        silent = true
        val h24 = (minutes / 60) % 24
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        hour.select(h12 - 1, animate)
        minute.select(Math.round((minutes % 60) / 5f) % 12, animate)
        ampm.select(if (h24 >= 12) 1 else 0, animate)
        silent = false
    }
}
