package com.shortstop.app

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Gradual blur exit (react-bits GradualBlur, upward only): the whole snapshot of a screen blurs in a front that rises
 * from the bottom to the top, lifting and fading as it goes. Pictures AND text take part because the snapshot is the
 * real rendered content. Blurred copies are made on a worker thread so the tap never stutters.
 */
class UpwardBlurView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var snap: Bitmap? = null
    private var p = 0f
    private val maskP = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcR = Rect()
    private val dstR = RectF()
    private var imgs: List<Bitmap> = emptyList()   // 0 = sharp, 1..4 = progressively blurrier (just smaller; GPU upscales)
    private val N = 28

    fun start(snapshot: Bitmap, onDone: () -> Unit) {
        snap = snapshot
        p = 0f
        invalidate()
        pool.execute {
            val list = ArrayList<Bitmap>()
            list.add(snapshot)
            var cur = snapshot
            var k = 1
            for (i in 1..5) {
                cur = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
                k *= 2
                if (k >= 4) list.add(cur)
            }
            ui.post {
                imgs = list
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 760
                    interpolator = android.view.animation.PathInterpolator(0.45f, 0f, 0.15f, 1f)
                    addUpdateListener { p = it.animatedValue as Float; invalidate() }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(an: android.animation.Animator) { onDone() }
                    })
                    start()
                }
            }
        }
    }

    /** blur level 0..1 at screen position s (0 top, 1 bottom): the bottom blurs first, the wave climbs to the top */
    private fun level(s: Float): Float {
        val k = 0.65f
        return ((p * (1f + k) - (1f - s)) / k).coerceIn(0f, 1f)
    }

    override fun onDraw(c: Canvas) {
        val sn = snap ?: return
        val w = width.toFloat(); val h = height.toFloat()
        if (imgs.isEmpty()) { dstR.set(0f, 0f, w, h); paint.alpha = 255; c.drawBitmap(sn, null, dstR, paint); return }
        val top = imgs.size - 1
        val fadeT = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val a255 = ((1f - fadeT * fadeT * (3f - 2f * fadeT)) * 255).toInt()
        if (a255 <= 0) return
        // continuous strips: each strip is drawn from the blur level for its height and lifted by a smooth amount,
        // neighbouring strips share their edge so the picture warps upward without gaps
        val shift = FloatArray(N + 1)
        val lvl = FloatArray(N + 1)
        for (i in 0..N) {
            val s = i / N.toFloat()
            val a = level(s)
            lvl[i] = a * top
            shift[i] = -(a * 30f + p * 12f) * d
        }
        for (i in 0 until N) {
            val s0 = i / N.toFloat(); val s1 = (i + 1) / N.toFloat()
            val lc = (lvl[i] + lvl[i + 1]) / 2f
            val lo = lc.toInt().coerceIn(0, top)
            val hi = min(lo + 1, top)
            val fr = lc - lo
            dstR.set(0f, s0 * h + shift[i], w, s1 * h + shift[i + 1] + 1f)
            for (pass in 0..1) {
                val img = if (pass == 0) imgs[lo] else imgs[hi]
                if (pass == 1 && (fr < 0.02f || hi == lo)) break
                srcR.set(0, (s0 * img.height).toInt(), img.width, ((s1 * img.height).toInt() + 1).coerceAtMost(img.height))
                paint.alpha = if (pass == 0) a255 else (a255 * fr).toInt()
                c.drawBitmap(img, srcR, dstR, paint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        snap = null; imgs = emptyList()
    }

    companion object {
        private val pool = Executors.newSingleThreadExecutor()
        private val ui = Handler(Looper.getMainLooper())
    }
}
