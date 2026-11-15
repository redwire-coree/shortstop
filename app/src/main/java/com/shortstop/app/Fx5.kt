package com.shortstop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * The running-session timer. A thin ring whose arc advances continuously (it is driven by the clock every frame,
 * not stepped once a second), a soft glowing head, and a ring of 60 dots that fill as the seconds pass. The digits
 * roll over like an odometer. Pure black and white, no extras.
 */
class TimerDial(ctx: Context, private val pal: Palette, private val fonts: Fonts) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
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
        set(value) { if (field != value) { field = value; invalidate() } }

    private var startMs = 0L
    private var endMs = 0L
    private var live = false
    private var calm = false

    /** Arc follows real time between start and end. */
    fun setSpan(start: Long, end: Long, paused: Boolean) {
        startMs = start
        endMs = end
        calm = paused
        live = true
        postInvalidateOnAnimation()
    }

    fun stop() { live = false }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = pal.track
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = pal.ink
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surface }
    private val bigP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.ink; typeface = fonts.bold; textAlign = Paint.Align.CENTER; letterSpacing = -0.02f
    }
    private val smallP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.muted; typeface = fonts.medium; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        val limit = (250f * d).toInt()
        val s = minOf(MeasureSpec.getSize(w).takeIf { it > 0 } ?: limit, limit)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val size = width.toFloat()
        val cx = size / 2f
        val cy = size / 2f
        val sw = 5f * d
        val r = size / 2f - 10f * d
        val now = System.currentTimeMillis()
        val frac = if (endMs > startMs) ((now - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f) else 0f

        track.strokeWidth = sw
        track.alpha = 120
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawOval(rect, track)

        arc.strokeWidth = sw
        arc.alpha = if (calm) 110 else 255
        if (frac > 0.0005f) c.drawArc(rect, -90f, 360f * frac, false, arc)

        // glowing head at the end of the arc
        val a = Math.toRadians((-90f + 360f * frac).toDouble())
        val hx = cx + r * Math.cos(a).toFloat()
        val hy = cy + r * Math.sin(a).toFloat()
        if (!calm) {
            val pulse = 0.5f + 0.5f * Math.sin(now / 520.0).toFloat()
            val hr = (15f + 5f * pulse) * d
            halo.shader = RadialGradient(
                hx, hy, hr,
                Color.argb((60 + 30 * pulse).toInt(), Color.red(pal.ink), Color.green(pal.ink), Color.blue(pal.ink)),
                Color.argb(0, Color.red(pal.ink), Color.green(pal.ink), Color.blue(pal.ink)),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(hx, hy, hr, halo)
        }
        c.drawCircle(hx, hy, sw * 1.05f, ring)
        c.drawCircle(hx, hy, sw * 0.62f, head)

        // seconds: 60 dots that fill one by one, the current one swelling smoothly
        val sec = ((now / 1000L) % 60L).toInt()
        val within = (now % 1000L) / 1000f
        val dr = r - 22f * d
        for (i in 0 until 60) {
            val ang = Math.toRadians((-90.0 + i * 6.0))
            val px = cx + dr * Math.cos(ang).toFloat()
            val py = cy + dr * Math.sin(ang).toFloat()
            val major = i % 5 == 0
            val base = if (major) 1.7f * d else 1.2f * d
            val lit = when {
                i < sec -> 1f
                i == sec -> within
                else -> 0f
            }
            dot.color = pal.ink
            dot.alpha = (if (calm) 40 + 40 * lit else 38 + 190 * lit).toInt().coerceIn(0, 255)
            c.drawCircle(px, py, base * (1f + 0.5f * lit), dot)
        }

        // digits, sized to sit inside the dots
        var ts = size * 0.215f
        bigP.textSize = ts
        val inner = dr * 2f - 40f * d
        // the roll painter draws digit by digit, so measure the same way
        while (bigP.measureText(big) > inner && ts > 20f * d) {
            ts -= 1f * d
            bigP.textSize = ts
        }
        val base = cy + ts * 0.2f
        roll.draw(c, bigP, cx, base)
        if (small.isNotEmpty()) {
            smallP.textSize = size * 0.058f
            c.drawText(small, cx, base + smallP.textSize * 2.1f, smallP)
        }

        if (live && isShown) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        live = false
        super.onDetachedFromWindow()
    }
}
