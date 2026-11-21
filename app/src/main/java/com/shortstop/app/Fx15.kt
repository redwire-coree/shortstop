package com.shortstop.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.PathInterpolator
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Gradual blur exit (react-bits GradualBlur, upward only). The snapshot of a screen blurs in a front that rises from
 * the bottom to the top, lifting and fading as it goes. Pictures and text both take part because the snapshot is the
 * real rendered content. Seven real blur levels (box blur on shrunken copies, made on a worker thread while the
 * animation already runs) are cross-faded strip by strip, so the blur grows continuously instead of in steps.
 * [onMid] fires while the blur is still half visible, so the next screen is already there underneath: no blank gap.
 */
class UpwardBlurView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val calm = FocusTheme.reducedMotion(ctx)
    private var snap: Bitmap? = null
    private var p = 0f
    @Volatile private var imgs: Array<Bitmap?> = arrayOfNulls(LEVELS + 1)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcR = Rect()
    private val dstR = RectF()
    private val N = 44
    private val shift = FloatArray(N + 1)
    private val lvl = FloatArray(N + 1)
    private var midFired = false
    private var anim: ValueAnimator? = null

    fun start(snapshot: Bitmap, onMid: () -> Unit, onDone: () -> Unit) {
        snap = snapshot
        imgs[0] = snapshot
        p = 0f
        midFired = false
        invalidate()
        if (calm) { onMid(); onDone(); return }
        pool.execute { buildLevels(snapshot) }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 980
            interpolator = PathInterpolator(0.40f, 0f, 0.18f, 1f)
            addUpdateListener {
                p = it.animatedValue as Float
                if (!midFired && p >= 0.52f) { midFired = true; onMid() }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: Animator) { if (!midFired) { midFired = true; onMid() }; onDone() }
            })
            start()
        }
    }

    private fun buildLevels(snapshot: Bitmap) {
        // chained halving keeps shrunken copies clean; each level then gets its own blur strength
        val s2 = Bitmap.createScaledBitmap(snapshot, max(1, snapshot.width / 2), max(1, snapshot.height / 2), true)
        val s4 = Bitmap.createScaledBitmap(s2, max(1, s2.width / 2), max(1, s2.height / 2), true)
        val s8 = Bitmap.createScaledBitmap(s4, max(1, s4.width / 2), max(1, s4.height / 2), true)
        val s16 = Bitmap.createScaledBitmap(s8, max(1, s8.width / 2), max(1, s8.height / 2), true)
        val plan = arrayOf(
            Triple(s2, 2, 1), Triple(s2, 4, 2), Triple(s4, 3, 3), Triple(s4, 5, 4),
            Triple(s8, 4, 5), Triple(s8, 7, 6), Triple(s16, 5, 7)
        )
        for ((base, r, level) in plan) {
            val out = boxBlur(base, r)
            imgs[level] = out
            postInvalidateOnAnimation()
        }
    }

    /** blur level 0..1 at screen position s (0 top, 1 bottom): the bottom blurs first, the wave climbs to the top */
    private fun level(s: Float): Float {
        val k = 0.7f
        val raw = ((p * (1f + k) - (1f - s)) / k).coerceIn(0f, 1f)
        return raw * raw * (3f - 2f * raw)   // smoothstep: no kink where the blur begins
    }

    private fun pick(i: Int): Bitmap? {
        var k = i.coerceIn(0, LEVELS)
        while (k > 0 && imgs[k] == null) k--
        return imgs[k]
    }

    override fun onDraw(c: Canvas) {
        val sn = snap ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val fadeT = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val a255 = ((1f - fadeT * fadeT * (3f - 2f * fadeT)) * 255).toInt()
        if (a255 <= 0) return
        for (i in 0..N) {
            val a = level(i / N.toFloat())
            lvl[i] = a * LEVELS
            shift[i] = -(a * 34f + p * 10f) * d
        }
        for (i in 0 until N) {
            val s0 = i / N.toFloat(); val s1 = (i + 1) / N.toFloat()
            val lc = (lvl[i] + lvl[i + 1]) / 2f
            val lo = lc.toInt().coerceIn(0, LEVELS)
            val hi = min(lo + 1, LEVELS)
            val fr = lc - lo
            dstR.set(0f, s0 * h + shift[i], w, s1 * h + shift[i + 1] + 1f)
            val a = pick(lo) ?: sn
            srcR.set(0, (s0 * a.height).toInt(), a.width, ((s1 * a.height).toInt() + 1).coerceAtMost(a.height))
            paint.alpha = a255
            c.drawBitmap(a, srcR, dstR, paint)
            if (fr > 0.02f && hi != lo) {
                val b = pick(hi) ?: continue
                if (b !== a) {
                    srcR.set(0, (s0 * b.height).toInt(), b.width, ((s1 * b.height).toInt() + 1).coerceAtMost(b.height))
                    paint.alpha = (a255 * fr).toInt()
                    c.drawBitmap(b, srcR, dstR, paint)
                }
            }
        }
        if (p < 1f) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim?.cancel()
        snap = null
        imgs = arrayOfNulls(LEVELS + 1)
    }

    companion object {
        private const val LEVELS = 7
        private val pool = Executors.newSingleThreadExecutor()

        /** Two box-blur passes in each direction (close to a Gaussian). The picture is opaque, so plain RGB sums are exact. */
        private fun boxBlur(src: Bitmap, r: Int): Bitmap {
            val w = src.width; val h = src.height
            var a = IntArray(w * h)
            src.getPixels(a, 0, w, 0, 0, w, h)
            var b = IntArray(w * h)
            repeat(2) {
                pass(a, b, w, h, r, true)
                pass(b, a, w, h, r, false)
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(a, 0, w, 0, 0, w, h)
            return out
        }

        private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val stride = if (horizontal) 1 else w
            val div = 2 * r + 1
            for (l in 0 until lines) {
                val base = if (horizontal) l * w else l
                var rs = 0; var gs = 0; var bs = 0
                for (k in -r..r) {
                    val c = src[base + k.coerceIn(0, len - 1) * stride]
                    rs += (c shr 16) and 255; gs += (c shr 8) and 255; bs += c and 255
                }
                for (i in 0 until len) {
                    dst[base + i * stride] = (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                    val add = src[base + min(i + r + 1, len - 1) * stride]
                    val sub = src[base + max(i - r, 0) * stride]
                    rs += ((add shr 16) and 255) - ((sub shr 16) and 255)
                    gs += ((add shr 8) and 255) - ((sub shr 8) and 255)
                    bs += (add and 255) - (sub and 255)
                }
            }
        }
    }
}
