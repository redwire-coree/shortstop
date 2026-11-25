package com.shortstop.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.max

/**
 * A page leaving the screen by softening into blur and fading away, over the real background.
 *
 * The page is captured with a transparent background, so the cloudy backdrop behind it is never part of the picture
 * and is never flattened, stretched or replaced; the page simply dissolves into it. One uniform blur is applied to the
 * whole picture (no strips, no shifting, no scaling), its radius easing up from zero so nothing pops in.
 *  - Android 12+: RenderEffect blur with DECAL edges (edges fade out instead of smearing).
 *  - Older phones: the picture is cross-faded through two pre-shrunk filtered copies, which blur evenly.
 * [onMid] fires while the page is still partly visible so the next content is already underneath; [onDone] always
 * fires exactly once, also when the view is detached early or the animation is cancelled.
 */
class PageBlurFade(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var full: Bitmap? = null
    private var soft: Bitmap? = null    // 1/4 size
    private var softer: Bitmap? = null  // 1/12 size
    private var p = 0f
    private var lastR = -1f
    private var anim: ValueAnimator? = null
    private var onMid: (() -> Unit)? = null
    private var onDone: (() -> Unit)? = null
    private var midFired = false
    private var doneFired = false
    private val live = Build.VERSION.SDK_INT >= 31
    private val watchdog = Runnable { finish() }

    fun start(snapshot: Bitmap, mid: () -> Unit, done: () -> Unit) {
        full = snapshot
        onMid = mid; onDone = done
        if (!live) {
            try {
                val a = Bitmap.createScaledBitmap(snapshot, max(1, snapshot.width / 4), max(1, snapshot.height / 4), true)
                soft = a
                softer = Bitmap.createScaledBitmap(a, max(1, a.width / 3), max(1, a.height / 3), true)
            } catch (e: Throwable) { soft = null; softer = null }
        }
        if (FocusTheme.reducedMotion(context)) { fireMid(); finish(); return }
        postDelayed(watchdog, 2000L)
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 640L
            interpolator = PathInterpolator(0.33f, 0f, 0.2f, 1f)
            addUpdateListener { set(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { fireMid(); finish() }
            })
            start()
        }
    }

    private fun fireMid() { if (!midFired) { midFired = true; onMid?.invoke() } }

    private fun set(v: Float) {
        p = v
        if (p >= 0.42f) fireMid()
        if (live) {
            // radius grows from exactly zero with an ease-in, so the first frames are identical to the real page
            val e = (p / 0.85f).coerceIn(0f, 1f)
            val r = 26f * d * e * e * (3f - 2f * e)
            if (kotlin.math.abs(r - lastR) >= 0.4f || (r == 0f && lastR != 0f)) {
                lastR = r
                setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL))
            }
        }
        invalidate()
    }

    /** Fade: stays fully visible for the first part so the blur is seen, then dissolves smoothly. */
    private fun alphaNow(): Float {
        val t = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    override fun onDraw(c: Canvas) {
        val f = full ?: return
        val a = alphaNow()
        if (a <= 0f) return
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        if (live || soft == null || softer == null) {
            paint.alpha = (255 * a).toInt()
            c.drawBitmap(f, null, dst, paint)
            return
        }
        // older phones: sharp -> soft -> softer as one even cross-fade over the whole page
        val b1 = (p / 0.45f).coerceIn(0f, 1f)          // 0..1 sharp to soft
        val b2 = ((p - 0.3f) / 0.5f).coerceIn(0f, 1f)  // 0..1 soft to softer
        val layer = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * a).toInt())
        paint.alpha = 255
        c.drawBitmap(f, null, dst, paint)
        paint.alpha = (255 * b1).toInt(); c.drawBitmap(soft!!, null, dst, paint)
        paint.alpha = (255 * b2).toInt(); c.drawBitmap(softer!!, null, dst, paint)
        c.restoreToCount(layer)
    }

    private fun finish() {
        if (doneFired) return
        doneFired = true
        removeCallbacks(watchdog)
        anim?.cancel()
        if (live) setRenderEffect(null)
        full = null; soft = null; softer = null
        onDone?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fireMid(); finish()
    }
}
