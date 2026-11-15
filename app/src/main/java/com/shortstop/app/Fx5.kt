package com.shortstop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Matrix
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

    // Goal mode: the same dial, but the arc shows progress toward a goal instead of a running clock.
    private var goalMode = false
    private var goalFrac = 0f
    private var goalAnim: android.animation.ValueAnimator? = null

    fun showGoal(frac: Float, animate: Boolean) {
        goalMode = true
        live = true
        calm = false
        val to = frac.coerceIn(0f, 1f)
        goalAnim?.cancel()
        if (!animate) { goalFrac = to; postInvalidateOnAnimation(); return }
        goalAnim = android.animation.ValueAnimator.ofFloat(goalFrac, to).apply {
            duration = 1100
            interpolator = SpringInterpolator(9f, 0.9f, 1.1f)
            addUpdateListener { goalFrac = it.animatedValue as Float; postInvalidateOnAnimation() }
            start()
        }
        postInvalidateOnAnimation()
    }

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
        color = Color.parseColor("#1A1410"); typeface = fonts.bold; textAlign = Paint.Align.CENTER; letterSpacing = -0.02f
    }
    private val smallP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 26, 20, 16); typeface = fonts.medium; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val grain = GrainientEngine(this)
    private val faceInk = Color.parseColor("#1A1410")
    private var faceCx = 0f
    private var faceCy = 0f
    private var faceR = 0f
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faceEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sweepM = Matrix()
    private var shadowShader: RadialGradient? = null
    private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowM = Matrix()
    private var haloShader: RadialGradient? = null
    private val haloM = Matrix()

    override fun onMeasure(w: Int, h: Int) {
        val limit = (280f * d).toInt()
        val s = minOf(MeasureSpec.getSize(w).takeIf { it > 0 } ?: limit, limit)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val size = width.toFloat()
        val cx = size / 2f
        val cy = size / 2f
        val sw = 5f * d
        val r = size / 2f - 30f * d
        val now = System.currentTimeMillis()
        val frac = if (goalMode) goalFrac.coerceIn(0f, 1f) else if (endMs > startMs) ((now - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f) else 0f

        // the dial face: a soft raised disc inside the ring
        faceR = r - 14f * d
        faceCx = cx
        faceCy = cy
        if (shadowShader == null) {
            val a = if (pal.dark) 150 else 46
            shadowShader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(Color.argb(a, 0, 0, 0), Color.argb(a * 6 / 10, 0, 0, 0), Color.argb(0, 0, 0, 0)),
                floatArrayOf(0.74f, 0.88f, 1f), Shader.TileMode.CLAMP
            )
            shadowP.shader = shadowShader
        }
        // wide soft drop shadow, then a tight contact shadow right under the edge
        shadowM.setScale(faceR * 1.42f, faceR * 1.42f); shadowM.postTranslate(cx, cy + 12f * d)
        shadowShader!!.setLocalMatrix(shadowM)
        shadowP.alpha = 255
        c.drawCircle(cx, cy + 12f * d, faceR * 1.42f, shadowP)
        shadowM.setScale(faceR * 1.1f, faceR * 1.1f); shadowM.postTranslate(cx, cy + 3f * d)
        shadowShader!!.setLocalMatrix(shadowM)
        shadowP.alpha = 140
        c.drawCircle(cx, cy + 3f * d, faceR * 1.1f, shadowP)
        // react-bits Grainient, Ember preset: flowing grainy gradient you can stir with a finger
        grain.step()
        grain.drawDisc(c, cx, cy, faceR, d)
        faceEdge.color = Color.argb(70, 255, 255, 255)
        faceEdge.strokeWidth = d
        c.drawCircle(cx, cy, faceR - 0.5f * d, faceEdge)

        track.strokeWidth = sw
        track.alpha = 120
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawOval(rect, track)

        // arc with a comet tail: faint where it starts, solid at the head
        arc.strokeWidth = sw
        if (frac > 0.0005f) {
            val ink = pal.ink
            val tail = Color.argb(if (calm) 30 else 46, Color.red(ink), Color.green(ink), Color.blue(ink))
            val headC = Color.argb(if (calm) 110 else 255, Color.red(ink), Color.green(ink), Color.blue(ink))
            val sg = SweepGradient(cx, cy, intArrayOf(tail, headC), floatArrayOf(0f, maxOf(frac, 0.01f)))
            sweepM.setRotate(-90f, cx, cy)
            sg.setLocalMatrix(sweepM)
            arc.shader = sg
            c.drawArc(rect, -90f, 360f * frac, false, arc)
            arc.shader = null
        }

        // glowing head at the end of the arc
        val a = Math.toRadians((-90f + 360f * frac).toDouble())
        val hx = cx + r * Math.cos(a).toFloat()
        val hy = cy + r * Math.sin(a).toFloat()
        if (!calm) {
            val pulse = 0.5f + 0.5f * Math.sin(now / 520.0).toFloat()
            val hr = (13f + 4f * pulse) * d
            if (haloShader == null) {
                val ik = pal.ink
                haloShader = RadialGradient(
                    0f, 0f, 1f, Color.argb(90, Color.red(ik), Color.green(ik), Color.blue(ik)),
                    Color.argb(0, Color.red(ik), Color.green(ik), Color.blue(ik)), Shader.TileMode.CLAMP
                )
                halo.shader = haloShader
            }
            haloM.setScale(hr, hr)
            haloM.postTranslate(hx, hy)
            haloShader!!.setLocalMatrix(haloM)
            halo.alpha = (255 * (0.66f + 0.34f * pulse)).toInt()
            c.drawCircle(hx, hy, hr, halo)
        }
        c.drawCircle(hx, hy, sw * 1.05f, ring)
        c.drawCircle(hx, hy, sw * 0.62f, head)

        // seconds: 60 dots that fill one by one, the current one swelling smoothly
        val sec = ((now / 1000L) % 60L).toInt()
        val within = (now % 1000L) / 1000f
        val dr = r - 30f * d
        for (i in 0 until (if (goalMode) 0 else 60)) {
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
            dot.color = faceInk
            dot.alpha = (if (calm) 40 + 40 * lit else 38 + 190 * lit).toInt().coerceIn(0, 255)
            c.drawCircle(px, py, base * (1f + 0.5f * lit), dot)
        }

        // digits, sized to sit inside the dots
        // one size for the whole session: fitted against the widest digits of the same length, so nothing
        // changes size as the numbers tick and nothing can leave the circle
        var ts = size * (if (goalMode) 0.2f else 0.215f)
        val inner = (if (goalMode) faceR * 2f * 0.8f else dr * 2f - 28f * d)
        val template = big.map { if (it.isDigit()) '8' else it }.joinToString("")
        bigP.textSize = ts
        while (bigP.measureText(template) > inner && ts > 18f * d) {
            ts -= 0.5f * d
            bigP.textSize = ts
        }
        val base = cy + ts * 0.2f
        roll.draw(c, bigP, cx, base)
        if (small.isNotEmpty()) {
            smallP.textSize = size * 0.058f
            c.drawText(small, cx, base + smallP.textSize * 2.1f, smallP)
        }

        // the gradient flows slowly, so 30fps is plenty until a finger stirs it
        if (live && isShown) {
            if (grain.touching()) postInvalidateOnAnimation() else postInvalidateDelayed(30)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        grain.restartIntro()
    }

    private fun gx(x: Float) = (x - (faceCx - faceR)) / (2f * faceR)
    private fun gy(y: Float) = 1f - (y - (faceCy - faceR)) / (2f * faceR)

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (faceR <= 0f || Math.hypot((e.x - faceCx).toDouble(), (e.y - faceCy).toDouble()) > faceR) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                grain.pointerDown(gx(e.x), gy(e.y))
                postInvalidateOnAnimation()
            }
            android.view.MotionEvent.ACTION_MOVE -> { grain.pointerMove(gx(e.x), gy(e.y)); postInvalidateOnAnimation() }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> grain.pointerUp()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        live = false
        super.onDetachedFromWindow()
    }
}
