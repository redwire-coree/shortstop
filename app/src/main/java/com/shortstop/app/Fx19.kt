package com.shortstop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

enum class Move { ENTER, EXIT, IDLE, HOP, NOD, WAVE, POINT, CHEER, THINK, OOPS }

/** The character artwork, cut out of the app icon so he looks exactly like the original. */
object OrangeArt {
    private var body: Bitmap? = null
    private var sparks: Bitmap? = null
    private fun load(c: Context, name: String): Bitmap? =
        try { c.assets.open(name).use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
    fun body(c: Context): Bitmap? { if (body == null) body = load(c, "orange_body.png"); return body }
    fun sparks(c: Context): Bitmap? { if (sparks == null) sparks = load(c, "orange_sparks.png"); return sparks }
}

/**
 * Orange, the mascot. One bitmap drawn through a small mesh so the body squashes, stretches and wobbles like jelly:
 * anticipation, stretch, squash on landing, and a top that lags behind the base. With reduced motion every move
 * shrinks to a quiet version, so he never goes fully still.
 */
class OrangeView(
    ctx: Context,
    private val fonts: Fonts,
    private val dark: Boolean,
    private val sizeDp: Float = 96f
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val s = sizeDp * d
    private val calm = FocusTheme.reducedMotion(ctx)
    private val amp = if (calm) 0.28f else 1f
    private val spd = if (calm) 0.75f else 1f
    private val body = OrangeArt.body(ctx)
    private val sparksBmp = OrangeArt.sparks(ctx)

    private val vw = s * 2.7f
    private val vh = s * 1.75f
    private val mLeft = s * 0.12f
    private val mBottom = vh - s * 0.07f
    private val mTop = mBottom - s
    private val cx = mLeft + s / 2f

    private var move = Move.ENTER
    private var t0 = SystemClock.uptimeMillis()
    private var pointDir = 1f
    private var loopMoves: List<Move>? = null
    private var loopIdx = 0
    private var loopGap = 1600L
    private var nextAt = 0L

    // pose
    private var dy = 0f; private var sx = 1f; private var sy = 1f; private var rot = 0f; private var lag = 0f
    private var spark = 0f; private var pivotCenter = false; private var thinking = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FocusCardColors.ORANGE }
    private val verts = FloatArray(11 * 11 * 2)
    private val rect = RectF()
    private val enterSpring = SpringInterpolator(14f, 0.5f, 0.9f)

    // speech bubble
    private val textP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.semi; color = FocusTheme.title(dark); textSize = 14f * ctx.resources.displayMetrics.scaledDensity
    }
    private val bubbleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF3A2517.toInt() else 0xFFFFFFFF.toInt() }
    private val bubbleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) 0xFF4D3322.toInt() else 0xFFF6E2C8.toInt(); style = Paint.Style.STROKE; strokeWidth = d
    }
    private var text: String? = null
    private var layout: StaticLayout? = null
    private var bubbleT0 = 0L
    private val bubbleSpring = SpringInterpolator(18f, 0.55f, 0.6f)
    private val tail = Path()

    fun say(t: String?) {
        text = t
        layout = null
        if (t != null) {
            val maxW = (vw - s * 1.12f - 4f * d - 24f * d).toInt()
            layout = StaticLayout(t, textP, maxW, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
            bubbleT0 = SystemClock.uptimeMillis()
        }
        invalidate()
    }

    fun play(m: Move, dir: Float = 1f) {
        move = m; pointDir = if (dir >= 0f) 1f else -1f
        t0 = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Plays these moves one after another with a pause between, forever. */
    fun loop(moves: List<Move>, gapMs: Long) {
        loopMoves = moves; loopGap = gapMs; loopIdx = 0
        nextAt = SystemClock.uptimeMillis() + 700L
    }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(vw.toInt(), vh.toInt()) }

    private fun bump(t: Float, a: Float, b: Float): Float =
        if (t <= a || t >= b) 0f else sin(Math.PI * (t - a) / (b - a)).toFloat()

    private fun land(k: Float, a: Float): Float = a * exp(-6.0 * k).toFloat() * cos(15.0 * k).toFloat()

    /** Returns true while the move is still running. */
    private fun pose(t: Float): Boolean {
        dy = 0f; sx = 1f; sy = 1f; rot = 0f; lag = 0f; spark = 0f; pivotCenter = false; thinking = false
        val breathe = sin(t * 2.6f)
        when (move) {
            Move.ENTER -> {
                val p = (t / 0.9f).coerceIn(0f, 1f)
                dy = -(1f - enterSpring.getInterpolation(p)) * s * 1.3f
                if (t > 0.35f) {
                    val k = t - 0.35f
                    sy = 1f - land(k, 0.10f) * amp; sx = 1f + (1f - sy) * 0.7f
                }
                spark = bump(t, 0.4f, 0.95f)
                return t < 1.0f
            }
            Move.EXIT -> {
                val p = (t / 0.35f).coerceIn(0f, 1f)
                dy = -p * p * s * 1.5f
                return t < 0.4f
            }
            Move.HOP -> {
                val h = s * 0.30f * amp
                when {
                    t < 0.12f -> { val k = t / 0.12f; sy = 1f - 0.15f * k * amp; sx = 1f + 0.10f * k * amp }
                    t < 0.52f -> {
                        val u = (t - 0.12f) / 0.40f
                        dy = h * 4f * u * (1f - u)
                        sy = 1f + (0.13f * (1f - u) * (1f - u) + 0.06f * u * u) * amp
                        sx = 1f - (sy - 1f) * 0.7f
                        rot = 3f * (u - 0.5f) * amp * pointDir
                    }
                    else -> { val k = t - 0.52f; sy = 1f - land(k, 0.17f) * amp; sx = 1f + (1f - sy) * 0.7f; lag = 0.03f * exp(-6.0 * k).toFloat() * sin(18.0 * k).toFloat() * amp }
                }
                spark = bump(t, 0.15f, 0.75f)
                return t < 1.0f
            }
            Move.NOD -> {
                if (t < 0.5f) {
                    val b = abs(sin(4.0 * Math.PI * t)).toFloat()
                    sy = 1f - 0.11f * b * amp; sx = 1f + 0.05f * b * amp; lag = 0.03f * b * amp
                    rot = 2.5f * sin(4.0 * Math.PI * t).toFloat() * amp
                } else {
                    val k = t - 0.5f
                    sy = 1f + 0.03f * exp(-7.0 * k).toFloat() * sin(20.0 * k).toFloat() * amp
                }
                spark = bump(t, 0.0f, 0.55f)
                return t < 0.8f
            }
            Move.WAVE -> {
                val env = exp(-1.4 * t).toFloat()
                rot = 7f * sin(2.0 * Math.PI * 2.6 * t).toFloat() * env * amp
                lag = 0.06f * sin(2.0 * Math.PI * 2.6 * t - 0.9).toFloat() * env * amp
                dy = abs(sin(2.0 * Math.PI * 2.6 * t)).toFloat() * s * 0.03f * env * amp
                spark = bump(t, 0.1f, 0.7f) * 0.8f
                return t < 1.3f
            }
            Move.POINT -> {
                val a = 1f - exp(-12.0 * t).toFloat()
                rot = pointDir * 9f * a * amp + sin(t * 10f) * 1.6f * amp
                lag = pointDir * 0.05f * a * amp
                sy = 1f + 0.012f * breathe * amp
                spark = 0.5f + 0.5f * sin(t * 6f)
                return true
            }
            Move.CHEER -> {
                val h = s * 0.5f * amp
                pivotCenter = true
                when {
                    t < 0.14f -> { val k = t / 0.14f; sy = 1f - 0.16f * k * amp; sx = 1f + 0.10f * k * amp }
                    t < 0.80f -> {
                        val u = (t - 0.14f) / 0.66f
                        dy = h * 4f * u * (1f - u)
                        val e = if (u < 0.5f) 2f * u * u else 1f - 2f * (1f - u) * (1f - u)
                        rot = 360f * e * amp
                        sy = 1f + 0.12f * (1f - u) * amp; sx = 1f - (sy - 1f) * 0.6f
                    }
                    else -> { val k = t - 0.80f; pivotCenter = false; sy = 1f - land(k, 0.2f) * amp; sx = 1f + (1f - sy) * 0.7f }
                }
                spark = bump(t, 0.12f, 1.3f)
                return t < 1.5f
            }
            Move.THINK -> {
                thinking = true
                rot = (6f + 2f * sin(t * 2.2f)) * amp
                sy = 1f + 0.015f * breathe * amp
                return true
            }
            Move.OOPS -> {
                sy = 0.88f + 0.12f * (1f - exp(-3.0 * t).toFloat()); sx = 1f + (1f - sy) * 0.6f
                sy = 1f - (1f - sy) * amp; sx = 1f + (sx - 1f) * amp
                rot = 2.5f * sin(2.0 * Math.PI * 9.0 * t).toFloat() * exp(-5.0 * t).toFloat() * amp
                lag = -0.02f * amp
                return t < 1.4f
            }
            Move.IDLE -> {
                sy = 1f + 0.016f * breathe * (if (calm) 1f else 1f)
                sx = 1f - 0.010f * breathe
                rot = 0.8f * sin(t * 1.9f) * (if (calm) 0.6f else 1f)
                return true
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return true
    }

    override fun onDraw(c: Canvas) {
        val b = body ?: return
        val now = SystemClock.uptimeMillis()
        var t = (now - t0) / 1000f * spd
        if (!pose(t)) {
            // the move is over: back to idle, and the loop (if any) picks the next one
            if (move != Move.EXIT) { move = Move.IDLE; t0 = now; t = 0f; pose(0f) }
        }
        val lm = loopMoves
        if (lm != null && move == Move.IDLE && now >= nextAt && lm.isNotEmpty()) {
            val m = lm[loopIdx % lm.size]; loopIdx++
            play(m, if (loopIdx % 2 == 0) 1f else -1f)
            nextAt = now + (loopGap / spd).toLong() + 900L
        }

        // soft ground shadow that shrinks as he jumps
        val air = (dy / (s * 0.5f)).coerceIn(0f, 1f)
        shadow.color = Color.argb((46 * (1f - 0.6f * air)).toInt(), 90, 40, 10)
        val sw = s * 0.36f * (1f - 0.25f * air)
        rect.set(cx - sw, mBottom - s * 0.035f, cx + sw, mBottom + s * 0.045f)
        if (move != Move.ENTER && move != Move.EXIT) c.drawOval(rect, shadow)

        c.save()
        c.translate(0f, -dy)
        val py = if (pivotCenter) mBottom - s / 2f else mBottom
        c.rotate(rot, cx, py)
        // body mesh
        var i = 0
        for (r in 0..10) {
            val v = r / 10f
            for (col in 0..10) {
                val u = col / 10f
                val x = mLeft + u * s
                val y = mTop + v * s
                val w = (1f - v) * (1f - v)
                verts[i++] = cx + (x - cx) * sx + lag * s * w
                verts[i++] = mBottom - (mBottom - y) * sy
            }
        }
        c.drawBitmapMesh(b, 10, 10, verts, 0, null, 0, paint)
        // the two spark dashes
        val sp = sparksBmp
        if (sp != null && spark > 0.02f) {
            val k = spark.coerceIn(0f, 1f)
            val sxw = s * 0.2f; val sxh = s * 0.197f
            val x0 = mLeft + s * 0.758f; val y0 = mTop + s * 0.068f
            c.save()
            val sc = (0.55f + 0.55f * k) * (if (calm) 0.7f else 1f)
            c.translate(x0 - s * 0.02f * (1f - k), y0 + sxh * (1f - sc))
            c.scale(sc, sc)
            paint.alpha = (255 * k).toInt()
            rect.set(0f, 0f, sxw, sxh)
            c.drawBitmap(sp, null, rect, paint)
            paint.alpha = 255
            c.restore()
        }
        // thinking dots
        if (thinking) {
            for (n in 0..2) {
                val ph = ((t * 1.8f - n * 0.3f) % 1f + 1f) % 1f
                val k = sin(Math.PI * ph).toFloat().coerceIn(0f, 1f)
                dot.alpha = (60 + 195 * k).toInt()
                c.drawCircle(mLeft + s * (0.86f + n * 0.13f), mTop + s * (0.14f - n * 0.07f), s * (0.025f + 0.02f * k), dot)
            }
        }
        c.restore()

        // speech bubble
        val lay = layout
        if (lay != null) {
            val bp = ((now - bubbleT0) / 600f).coerceIn(0f, 1f)
            val sc = bubbleSpring.getInterpolation(bp).coerceAtLeast(0.01f)
            val padH = 12f * d; val padV = 9f * d
            val bw = lay.width + padH * 2; val bh = lay.height + padV * 2
            val bl = s * 1.12f; val bt = mTop - s * 0.05f
            val tipX = bl - 7f * d; val tipY = bt + bh * 0.7f
            c.save()
            c.scale(sc, sc, tipX, tipY)
            rect.set(bl, bt, bl + bw, bt + bh)
            tail.reset()
            tail.moveTo(bl + 2f * d, tipY - 7f * d); tail.lineTo(tipX, tipY); tail.lineTo(bl + 2f * d, tipY + 5f * d); tail.close()
            c.drawRoundRect(rect, 16f * d, 16f * d, bubbleP)
            c.drawRoundRect(rect, 16f * d, 16f * d, bubbleEdge)
            c.drawPath(tail, bubbleP)
            c.save()
            c.translate(bl + padH, bt + padV)
            lay.draw(c)
            c.restore()
            c.restore()
        }
        if (isShown) {
            if (move == Move.IDLE && loopMoves == null || (move == Move.IDLE && now < nextAt - 200)) postInvalidateDelayed(33) else postInvalidateOnAnimation()
        }
    }

    private fun hit(e: MotionEvent) = e.x >= mLeft && e.x <= mLeft + s && e.y >= mTop - s * 0.3f && e.y <= mBottom

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!hasOnClickListeners() || !hit(e)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { play(Move.HOP); return true }
            MotionEvent.ACTION_UP -> { performClick(); return true }
        }
        return true
    }
}

/** Dimmed screen with a soft, glowing hole around the thing Orange is pointing at. */
class TourDim(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val calm = FocusTheme.reducedMotion(ctx)
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(176, 42, 22, 10) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FocusCardColors.ORANGE_A; style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FocusCardColors.ORANGE_B; style = Paint.Style.STROKE; strokeWidth = 10f * d; alpha = 70 }
    private val path = Path()
    private val cur = RectF()
    private var has = false
    private var anim: ValueAnimator? = null

    fun setTarget(r: RectF?) {
        anim?.cancel()
        if (r == null) { has = false; invalidate(); return }
        val from = RectF(cur)
        val first = !has
        has = true
        if (first || calm) { cur.set(r); invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460
            interpolator = SpringInterpolator(15f, 0.8f, 0.5f)
            addUpdateListener {
                val f = it.animatedValue as Float
                cur.set(from.left + (r.left - from.left) * f, from.top + (r.top - from.top) * f,
                    from.right + (r.right - from.right) * f, from.bottom + (r.bottom - from.bottom) * f)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (!has) { c.drawRect(0f, 0f, w, h, dim); return }
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        val rad = min(cur.height() / 2f, 22f * d)
        path.addRoundRect(cur, rad, rad, Path.Direction.CW)
        c.drawPath(path, dim)
        c.drawRoundRect(cur, rad, rad, glow)
        c.drawRoundRect(cur, rad, rad, ring)
    }
}
