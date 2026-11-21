package com.shortstop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * One collectible card in the grid. Locked: the same card artwork in grey with a hand-drawn question mark.
 * Unlocked: the original colour artwork. A freshly earned card plays a short reveal (colour floods in, a soft light
 * sweeps across, a small spring pop).
 */
class CardArtView(ctx: Context, private val art: Bitmap, private var locked: Boolean) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val calm = FocusTheme.reducedMotion(ctx)
    private val grayP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        val m = ColorMatrix().apply { setSaturation(0f) }
        m.postConcat(ColorMatrix(floatArrayOf(0.88f, 0f, 0f, 0f, 16f, 0f, 0.88f, 0f, 0f, 16f, 0f, 0f, 0.88f, 0f, 16f, 0f, 0f, 0f, 1f, 0f)))
        colorFilter = ColorMatrixColorFilter(m)
    }
    private val colorP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val q = Path()
    private val dot = Path()
    private var reveal = 1f
    private var pop = 0f
    private var anim: ValueAnimator? = null

    init {
        // the question mark, drawn like a marker doodle: one loose hook and a slightly lopsided dot
        q.moveTo(-0.30f, -0.30f)
        q.cubicTo(-0.34f, -0.70f, 0.34f, -0.76f, 0.36f, -0.30f)
        q.cubicTo(0.38f, 0.02f, 0.02f, 0.02f, 0.0f, 0.34f)
        dot.moveTo(-0.07f, 0.56f)
        dot.cubicTo(-0.10f, 0.46f, 0.08f, 0.44f, 0.10f, 0.55f)
        dot.cubicTo(0.12f, 0.66f, -0.05f, 0.69f, -0.07f, 0.56f)
        dot.close()
    }

    /** For a card that was just earned: start looking locked, then play the reveal. */
    fun playReveal() {
        if (calm) { locked = false; reveal = 1f; invalidate(); return }
        locked = false
        reveal = 0f
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1250
            interpolator = DecelerateInterpolator(1.3f)
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    /** Shows the locked look first (used just before [playReveal]). */
    fun holdLocked() { locked = false; reveal = 0f; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val avail = MeasureSpec.getSize(w).takeIf { it > 0 } ?: (150f * d).toInt()
        setMeasuredDimension(avail, (avail * 1.3f).toInt())
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun doodle(c: Canvas, cx: Float, cy: Float, s: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        val t = if (calm) 0f else SystemClock.uptimeMillis() / 1000f
        val sway = Math.sin(t * 1.7).toFloat() * 3.2f
        val bob = Math.sin(t * 2.2).toFloat() * 0.012f * s
        c.save()
        c.translate(cx, cy + bob)
        c.rotate(sway)
        c.scale(s, s)
        val a = (255 * alpha).toInt()
        halo.color = Color.argb((210 * alpha).toInt(), 255, 255, 255)
        halo.strokeWidth = 0.215f
        c.drawPath(q, halo)
        dotP.color = Color.argb((210 * alpha).toInt(), 255, 255, 255)
        halo.style = Paint.Style.FILL_AND_STROKE
        halo.strokeWidth = 0.09f
        c.drawPath(dot, halo)
        halo.style = Paint.Style.STROKE
        // loose second pass, a touch off, like a pen going over it twice
        ink.color = Color.argb((120 * alpha).toInt(), 58, 58, 58)
        ink.strokeWidth = 0.028f
        c.save(); c.translate(0.026f, 0.02f); c.rotate(1.6f); c.drawPath(q, ink); c.restore()
        ink.color = Color.argb(a, 52, 52, 54)
        ink.strokeWidth = 0.125f
        c.drawPath(q, ink)
        dotP.color = Color.argb(a, 52, 52, 54)
        c.drawPath(dot, dotP)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = 1f + 0.055f * Math.sin(Math.PI * (reveal * 1.15f).coerceAtMost(1f)).toFloat() * (if (reveal in 0.001f..0.999f) 1f else 0f)
        c.save()
        c.scale(s, s, w / 2f, h / 2f)
        dst.set(0f, 0f, w, h)
        if (locked) {
            c.drawBitmap(art, null, dst, grayP)
            doodle(c, w / 2f, h * 0.47f, h * 0.30f, 1f)
        } else if (reveal >= 1f) {
            c.drawBitmap(art, null, dst, colorP)
        } else {
            c.drawBitmap(art, null, dst, grayP)
            colorP.alpha = (255 * reveal).toInt()
            c.drawBitmap(art, null, dst, colorP)
            colorP.alpha = 255
            doodle(c, w / 2f, h * 0.47f, h * 0.30f, (1f - reveal * 1.6f).coerceIn(0f, 1f))
            // a soft light that sweeps across once
            val x = -0.3f * w + 1.6f * w * reveal
            shine.shader = LinearGradient(x - 0.22f * w, 0f, x + 0.22f * w, h * 0.35f,
                intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(150, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(dst, 22f * w / 300f, 22f * w / 300f, shine)
        }
        c.restore()
        if ((locked && !calm) || (reveal < 1f && !calm)) postInvalidateDelayed(45)
    }
}

/** Thin orange progress bar (track plus a soft gradient fill). */
class OrangeBar(ctx: Context, private val trackColor: Int) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var frac = 0f
    private var shW = 0f

    fun setProgress(f: Float, animate: Boolean) {
        val to = f.coerceIn(0f, 1f)
        if (!animate || FocusTheme.reducedMotion(context)) { frac = to; invalidate(); return }
        ValueAnimator.ofFloat(frac, to).apply {
            duration = 800
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { frac = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, h / 2f, h / 2f, track)
        if (frac <= 0.001f) return
        if (shW != w) {
            shW = w
            fill.shader = LinearGradient(0f, 0f, w, 0f, Color.parseColor("#FFB27A"), Color.parseColor("#FF6A2B"), Shader.TileMode.CLAMP)
        }
        rect.set(0f, 0f, maxOf(h, w * frac), h)
        c.drawRoundRect(rect, h / 2f, h / 2f, fill)
    }
}
