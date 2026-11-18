package com.shortstop.app

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Gradual blur exit (react-bits GradualBlur, upward only): the whole snapshot of a screen blurs in a front that rises
 * from the bottom to the top, lifting and fading as it goes. Pictures AND text take part because the snapshot is the
 * real rendered content. Blurred copies are made on a worker thread so the tap never stutters.
 */
class UpwardBlurView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var snap: Bitmap? = null
    private var b1: Bitmap? = null
    private var b2: Bitmap? = null
    private var p = 0f
    private val maskP = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    private fun blurred(src: Bitmap, f: Int): Bitmap {
        val w = src.width; val h = src.height
        var cur = Bitmap.createScaledBitmap(src, max(1, w / 2), max(1, h / 2), true)
        var k = 2
        while (k < f) { k *= 2; cur = Bitmap.createScaledBitmap(cur, max(1, w / k), max(1, h / k), true) }
        while (k > 1) { k /= 2; cur = Bitmap.createScaledBitmap(cur, max(1, w / k), max(1, h / k), true) }
        return cur
    }

    fun start(snapshot: Bitmap, onDone: () -> Unit) {
        snap = snapshot
        p = 0f
        invalidate()
        pool.execute {
            val a = blurred(snapshot, 8)
            val b = blurred(snapshot, 32)
            ui.post {
                b1 = a; b2 = b
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 820
                    interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
                    addUpdateListener { p = it.animatedValue as Float; invalidate() }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(an: android.animation.Animator) { onDone() }
                    })
                    start()
                }
            }
        }
    }

    private fun layerMasked(c: Canvas, b: Bitmap, front: Float, soft: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        c.saveLayer(0f, 0f, w, h, null)
        dst.set(0f, 0f, w, h)
        c.drawBitmap(b, null, dst, bmpP)
        maskP.shader = LinearGradient(0f, front - soft, 0f, front + soft, 0x00000000, -0x1000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, maskP)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val sn = snap ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val soft = h * 0.28f
        val front = h + soft - (h + 2f * soft) * p
        val t = ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
        val alpha = ((1f - t * t * (3f - 2f * t)) * 255).toInt()
        c.save()
        c.translate(0f, -34f * d * p)
        c.saveLayerAlpha(0f, 0f, w, h, alpha)
        dst.set(0f, 0f, w, h)
        c.drawBitmap(sn, null, dst, bmpP)
        b1?.let { layerMasked(c, it, front, soft) }
        b2?.let { layerMasked(c, it, front + 0.12f * h, soft) }
        c.restore()
        c.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        snap = null; b1 = null; b2 = null
    }

    companion object {
        private val pool = Executors.newSingleThreadExecutor()
        private val ui = Handler(Looper.getMainLooper())
    }
}
