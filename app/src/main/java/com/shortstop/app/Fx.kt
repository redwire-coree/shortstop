package com.shortstop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Effects ported from react-bits to native Android, in the black and white palette only:
 *  Counter     -> RollPainter (rolling digits)
 *  ShinyText   -> ShinyTextView (light sweep over a headline)
 *  ClickSpark  -> SparkLayer (sparks on every tap)
 *  DotGrid     -> DotGridView (dots that bulge away from your finger)
 *  SpotlightCard -> GlowCard (soft light that follows your finger)
 */

private fun Context.px(v: Float) = v * resources.displayMetrics.density

fun mixColor(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    return Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
    )
}

/** Rolling-digit text. Changed characters slide out upward and the new ones slide in from below. */
class RollPainter(private val host: View) {
    private var cur = ""
    private val prevC = arrayOfNulls<Char>(24)
    private val curC = arrayOfNulls<Char>(24)
    private val start = LongArray(24)
    private val dur = 520L
    private var active = false

    fun set(text: String, animate: Boolean) {
        val old = cur
        cur = text
        val now = SystemClock.uptimeMillis()
        val n = maxOf(old.length, text.length).coerceAtMost(24)
        for (k in 0 until n) {
            val o = old.getOrNull(old.length - 1 - k)
            val c = text.getOrNull(text.length - 1 - k)
            if (o != c) {
                prevC[k] = o
                curC[k] = c
                start[k] = if (animate) now + k * 35L else now - dur
            } else if (!(animate && start[k] + dur > now)) {
                prevC[k] = null
                curC[k] = c
                start[k] = now - dur
            }
        }
        active = animate
        host.invalidate()
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.2f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    fun draw(c: Canvas, paint: Paint, cx: Float, baseline: Float) {
        val n = cur.length.coerceAtMost(24)
        val digitW = paint.measureText("0")
        val widths = FloatArray(n)
        var total = 0f
        for (i in 0 until n) {
            val ch = cur[cur.length - n + i]
            widths[i] = if (ch.isDigit()) digitW else paint.measureText(ch.toString())
            total += widths[i]
        }
        val now = SystemClock.uptimeMillis()
        val travel = paint.textSize * 0.55f
        val baseAlpha = paint.alpha
        var x = cx - total / 2f
        var still = true
        paint.textAlign = Paint.Align.CENTER
        for (i in 0 until n) {
            val k = n - 1 - i
            val mid = x + widths[i] / 2f
            val t = ((now - start[k]).toFloat() / dur).coerceIn(0f, 1f)
            val p = prevC[k]
            val q = curC[k] ?: cur[cur.length - n + i]
            if (t < 1f) {
                still = false
                val e = easeOutBack(t)
                val o = t * t
                if (p != null) {
                    paint.alpha = (baseAlpha * (1f - o)).toInt()
                    c.drawText(p.toString(), mid, baseline - o * travel, paint)
                }
                paint.alpha = (baseAlpha * t.coerceIn(0f, 1f)).toInt()
                c.drawText(q.toString(), mid, baseline + (1f - e) * travel, paint)
                paint.alpha = baseAlpha
            } else {
                c.drawText(q.toString(), mid, baseline, paint)
            }
            x += widths[i]
        }
        if (!still) host.postInvalidateOnAnimation() else active = false
    }
}

/** A headline with a soft light sweeping across it now and then. */
class ShinyTextView(ctx: Context, private val base: Int, private val dip: Int) : TextView(ctx) {
    private var phase = 0f
    private var anim: ValueAnimator? = null
    private var shader: LinearGradient? = null
    private var band = 0f
    private val m = Matrix()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f) { super.onDraw(canvas); return }
        if (shader == null || band != w * 0.45f) {
            band = w * 0.45f
            shader = LinearGradient(
                0f, 0f, band, 0f, intArrayOf(base, dip, base), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
        val s = (phase / 0.55f).coerceAtMost(1f)
        val e = 0.5f - Math.cos(Math.PI * s).toFloat() / 2f
        m.setTranslate(-band + (w + band) * e, 0f)
        shader!!.setLocalMatrix(m)
        paint.shader = shader
        super.onDraw(canvas)
        paint.shader = null
    }
}

/** Sparks that burst out of every tap. Never takes touches itself. */
class SparkLayer(ctx: Context, private val color: Int) : View(ctx) {
    private class Burst(val x: Float, val y: Float, val t0: Long, val jit: FloatArray)

    private val bursts = ArrayList<Burst>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val life = 480f

    fun burst(x: Float, y: Float) {
        if (bursts.size >= 12) bursts.removeAt(0)
        val j = FloatArray(8) { (Math.random().toFloat() - 0.5f) * 0.5f }
        bursts.add(Burst(x, y, SystemClock.uptimeMillis(), j))
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (bursts.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val radius = context.px(30f)
        val size = context.px(12f)
        p.strokeWidth = context.px(2f)
        val it = bursts.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val t = (now - b.t0) / life
            if (t >= 1f) { it.remove(); continue }
            val e = 1f - Math.pow((1f - t).toDouble(), 3.0).toFloat()
            val len = size * Math.pow((1f - t).toDouble(), 1.6).toFloat()
            val fade = if (t < 0.55f) 1f else 1f - (t - 0.55f) / 0.45f
            p.color = color
            p.alpha = (255 * fade).toInt().coerceIn(0, 255)
            for (i in 0 until 8) {
                val a = i * (Math.PI * 2 / 8).toFloat() + b.jit[i]
                val d1 = radius * e
                val d2 = d1 + len
                val cs = Math.cos(a.toDouble()).toFloat()
                val sn = Math.sin(a.toDouble()).toFloat()
                c.drawLine(b.x + cs * d1, b.y + sn * d1, b.x + cs * d2, b.y + sn * d2, p)
            }
        }
        if (bursts.isNotEmpty()) postInvalidateOnAnimation()
    }
}

/** A grid of dots. Touch it and the dots near your finger swell and push away. */
class DotGridView(ctx: Context, private val dot: Int, private val hot: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gap = ctx.px(26f)
    private val r0 = ctx.px(1.6f)
    private val reach = ctx.px(120f)
    private val push = ctx.px(16f)
    private var tx = 0f
    private var ty = 0f
    private var sx = 0f
    private var sy = 0f
    private var infl = 0f
    private var target = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tx = e.x; ty = e.y
                if (infl < 0.02f) { sx = tx; sy = ty }
                target = 1f
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> { tx = e.x; ty = e.y; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { target = 0f; invalidate() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        infl += (target - infl) * 0.14f
        sx += (tx - sx) * 0.3f
        sy += (ty - sy) * 0.3f
        val cols = (width / gap).toInt() + 1
        val rows = (height / gap).toInt() + 1
        val ox = (width - (cols - 1) * gap) / 2f
        val oy = (height - (rows - 1) * gap) / 2f
        val live = infl > 0.01f
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                var x = ox + i * gap
                var y = oy + j * gap
                var rad = r0
                paint.color = dot
                if (live) {
                    val dx = x - sx
                    val dy = y - sy
                    val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (d < reach) {
                        val f = (1f - d / reach)
                        val g = f * f * infl
                        if (d > 0.5f) { x += dx / d * g * push; y += dy / d * g * push }
                        rad = r0 * (1f + g * 2.4f)
                        paint.color = mixColor(dot, hot, g * 1.4f)
                    }
                }
                c.drawCircle(x, y, rad, paint)
            }
        }
        if (live || Math.abs(target - infl) > 0.01f) postInvalidateOnAnimation()
    }
}

/**
 * Bento card (react-bits MagicBento): soft light that follows your finger, an edge light on the border,
 * and a ring that ripples out on tap.
 */
class GlowCard(ctx: Context, private val glow: Int, private val corner: Float, private val size: Float) :
    LinearLayout(ctx) {
    private var gx = 0f
    private var gy = 0f
    private var amt = 0f
    private var wave = 1f
    private var wx = 0f
    private var wy = 0f
    private var anim: ValueAnimator? = null
    private var waveAnim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.px(1.5f) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.px(2f) }
    private val clip = Path()
    private val rect = RectF()
    private val er = RectF()

    init { setWillNotDraw(false) }

    /** Dark mode: a soft shine falling from the top edge, always on. Touch glow stays finger-only. */
    var ambient = false
    private var shineW = 0
    private var shineH = 0
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.px(1f) }

    private fun drawShine(c: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (w != shineW || h != shineH) {
            shineW = w; shineH = h
            val reach = minOf(h.toFloat(), size * 0.9f)
            shinePaint.shader = LinearGradient(0f, 0f, 0f, reach,
                Color.argb(30, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            shineEdge.shader = LinearGradient(0f, 0f, 0f, h * 0.6f,
                Color.argb(80, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        }
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(rect, corner, corner, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        c.drawRect(rect, shinePaint)
        c.restore()
        er.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        c.drawRoundRect(er, corner, corner, shineEdge)
    }

    private fun fade(to: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(amt, to).apply {
            duration = if (to > amt) 160 else 380
            addUpdateListener { amt = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun ripple(x: Float, y: Float) {
        waveAnim?.cancel()
        wx = x; wy = y; wave = 0f
        waveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            addUpdateListener { wave = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { gx = e.x; gy = e.y; fade(1f); ripple(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> { gx = e.x; gy = e.y; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fade(0f)
        }
        return super.dispatchTouchEvent(e)
    }

    private fun tint(alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(glow), Color.green(glow), Color.blue(glow))

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (ambient) drawShine(c)
        val live = amt >= 0.01f
        val waving = wave < 1f
        if (!live && !waving) return
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset()
        clip.addRoundRect(rect, corner, corner, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        if (live) {
            val a = (Color.alpha(glow) * amt).toInt()
            paint.shader = RadialGradient(gx, gy, size, tint(a), tint(0), Shader.TileMode.CLAMP)
            c.drawRect(rect, paint)
        }
        if (waving) {
            ring.color = tint((Color.alpha(glow) * 5 * (1f - wave)).toInt())
            c.drawCircle(wx, wy, size * 0.9f * wave, ring)
        }
        c.restore()
        if (live) {
            er.set(0.75f, 0.75f, width - 0.75f, height - 0.75f)
            edge.shader = RadialGradient(
                gx, gy, size * 0.8f, tint((Color.alpha(glow) * 7 * amt).toInt()), tint(0), Shader.TileMode.CLAMP
            )
            c.drawRoundRect(er, corner, corner, edge)
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        waveAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
