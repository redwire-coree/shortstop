package com.shortstop.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator

/**
 * A short, restrained confetti burst for a finished focus session. Deterministic (no random per play), about forty
 * small pieces in Orange's own palette, launched from just above the card, drifting down and fading out over
 * [DURATION] ms. It never takes touches, so Share and Done underneath keep working, and it removes itself.
 */
class ConfettiBurst(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f
    private var anim: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())

    init { tag = "confetti"; isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun h01(i: Int, salt: Int): Float {
        var x = i * 374761393 + salt * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
    }

    fun start() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { dismiss() }
            })
            start()
        }
    }

    /** Removal is deferred so it can never run while the parent is still iterating its children. */
    private fun dismiss() {
        handler.post { (parent as? ViewGroup)?.removeView(this) }
    }

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        val w = width.toFloat(); val hgt = height.toFloat(); val d = resources.displayMetrics.density
        for (i in 0 until COUNT) {
            // two launch points left and right of the card centre, angled outwards, then gravity and a slow sway
            val side = if (i % 2 == 0) -1f else 1f
            val delay = h01(i, 1) * 0.18f
            val p = ((t - delay) / (1f - delay)).coerceIn(0f, 1f)
            if (p <= 0f) continue
            val speed = (0.45f + h01(i, 2) * 0.55f) * hgt * 0.62f
            val ang = (0.25f + h01(i, 3) * 0.55f)                       // 0 = straight up, 1 = flat out
            val vx = side * ang * speed * 0.9f
            val vy = -(1f - ang * 0.55f) * speed
            val g = hgt * 1.15f
            val x0 = w * 0.5f + side * w * 0.10f
            val y0 = hgt * 0.42f
            val tt = p * 1.15f
            val x = x0 + vx * tt + Math.sin((tt * 5f + h01(i, 4) * 6f).toDouble()).toFloat() * 6f * d
            val y = y0 + vy * tt + 0.5f * g * tt * tt
            val fade = if (p < 0.7f) 1f else (1f - (p - 0.7f) / 0.3f)
            paint.color = COLORS[i % COLORS.size]
            paint.alpha = (235 * fade).toInt().coerceIn(0, 255)
            val s = (3.5f + h01(i, 5) * 3.5f) * d
            c.save()
            c.rotate(h01(i, 6) * 360f + p * (120f + h01(i, 7) * 240f), x, y)
            if (i % 3 == 0) c.drawCircle(x, y, s * 0.55f, paint)
            else { rect.set(x - s, y - s * 0.45f, x + s, y + s * 0.45f); c.drawRoundRect(rect, s * 0.2f, s * 0.2f, paint) }
            c.restore()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    companion object {
        const val DURATION = 2300L
        private const val COUNT = 44
        private val COLORS = intArrayOf(0xFFFFA23A.toInt(), 0xFF8ED8A6.toInt(), 0xFFFFE08A.toInt(), 0xFFFF7A66.toInt())
    }
}
