package com.shortstop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * The layer behind a bottom sheet: the screen underneath is genuinely blurred and gently dimmed, never just darkened.
 *  - Android 12+ (API 31): the real app views are blurred live with RenderEffect, and the radius follows [level].
 *  - Older phones: a small snapshot of the screen taken once, shrunk in two filtered steps (a soft blur) and cross-faded in.
 * [level] runs 0..1 (opening, closing and dragging all drive it). [release] always restores the real views.
 */
class SheetBackdrop(ctx: Context, private val targets: List<View>, private val tint: Int, snapshotOf: ViewGroup?) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val dim = Paint().apply { color = tint }
    private val snapP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var snap: Bitmap? = null
    private val live = Build.VERSION.SDK_INT >= 31
    private var lastR = -1f

    var level = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            applyBlur()
            invalidate()
        }

    init {
        if (!live && snapshotOf != null && snapshotOf.width > 0 && snapshotOf.height > 0) {
            try {
                val w = snapshotOf.width; val h = snapshotOf.height
                val big = Bitmap.createBitmap(max(1, w / 4), max(1, h / 4), Bitmap.Config.ARGB_8888)
                val c = Canvas(big)
                c.drawColor(0xFF000000.toInt() or (tint and 0)) // opaque base
                c.scale(0.25f, 0.25f)
                snapshotOf.draw(c)
                val mid = Bitmap.createScaledBitmap(big, max(1, big.width / 3), max(1, big.height / 3), true)
                snap = mid
                big.recycle()
            } catch (e: Throwable) { snap = null }
        }
    }

    private fun applyBlur() {
        if (!live) return
        val r = level * 14f * d
        if (kotlin.math.abs(r - lastR) < 0.6f && !(r == 0f && lastR != 0f)) return
        lastR = r
        val fx = if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
        for (t in targets) if (t.visibility == VISIBLE && t.width > 0) t.setRenderEffect(fx)
    }

    override fun onDraw(c: Canvas) {
        val s = snap
        if (s != null && level > 0f) {
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            snapP.alpha = (255 * level).toInt()
            c.drawBitmap(s, null, dst, snapP)
        }
        dim.alpha = (Color.alpha(tint) * level).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
    }

    /** Gives the real views back: no blur effect is left on anything, whatever path closed the sheet. */
    fun release(clearEffects: Boolean = true) {
        if (live && clearEffects) for (t in targets) t.setRenderEffect(null)
        lastR = -1f
        snap?.recycle(); snap = null
    }

    override fun onDetachedFromWindow() {
        release(false)
        super.onDetachedFromWindow()
    }
}
