package com.shortstop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View

/**
 * Quiet backdrop for the Focus page: a grid of dots that a slow ripple travels through (react-bits Dot Grid / Ripple
 * Grid, in the app's own ink), fading out toward the edges, with one very soft warm glow that echoes the timer face.
 * Draws only while it is on screen, in three alpha batches, so it costs next to nothing.
 */
class AmbientBackdrop(ctx: Context, private val pal: Palette) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val gap = 24f * d
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.ink; strokeCap = Paint.Cap.ROUND; strokeWidth = 2.2f * d
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pts = FloatArray(0)
    private var fade = FloatArray(0)
    private var dist = FloatArray(0)
    private val buckets = Array(4) { FloatArray(0) }
    private val counts = IntArray(4)
    private var count = 0
    private val ember = Color.parseColor("#FF8A3D")
    private val base = if (pal.dark) 70 else 54

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val cols = (w / gap).toInt() + 1
        val rows = (h / gap).toInt() + 1
        count = cols * rows
        pts = FloatArray(count * 2)
        fade = FloatArray(count)
        dist = FloatArray(count)
        for (b in buckets.indices) buckets[b] = FloatArray(count * 2)
        val ox = (w - (cols - 1) * gap) / 2f
        val cx = w / 2f
        val cy = h * 0.3f
        val reach = Math.max(w * 0.95f, h * 0.45f)
        var k = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = ox + c * gap
            val y = r * gap + 6f * d
            pts[k * 2] = x; pts[k * 2 + 1] = y
            val dd = Math.hypot((x - cx).toDouble(), ((y - cy) * 1.15f).toDouble()).toFloat()
            dist[k] = dd
            val f = (1f - dd / reach).coerceIn(0f, 1f)
            fade[k] = f * f * (3f - 2f * f)
            k++
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = SystemClock.uptimeMillis() / 1000f
        // the warm glow breathes and drifts a little
        val gx = w * (0.5f + 0.1f * Math.sin(t * 0.25).toFloat())
        val gy = h * (0.27f + 0.03f * Math.cos(t * 0.21).toFloat())
        val rad = w * 0.8f
        val a = if (pal.dark) 40 else 52
        glow.shader = RadialGradient(
            gx, gy, rad, intArrayOf(Color.argb(a, 255, 138, 61), Color.argb(a / 3, 255, 138, 61), Color.argb(0, 255, 138, 61)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, glow)

        // dots: alpha follows the distance from the centre, plus a ring that slowly travels outward
        java.util.Arrays.fill(counts, 0)
        val wave = t * 70f * d
        val period = 210f * d
        for (i in 0 until count) {
            val f = fade[i]
            if (f <= 0.01f) continue
            val ph = ((dist[i] - wave) % period + period) % period / period
            val ring = Math.max(0f, 1f - Math.abs(ph - 0.5f) * 6f)
            val v = f * (0.55f + 0.9f * ring)
            val b = (v * 3.4f).toInt().coerceIn(0, 3)
            val n = counts[b]
            buckets[b][n * 2] = pts[i * 2]
            buckets[b][n * 2 + 1] = pts[i * 2 + 1]
            counts[b] = n + 1
        }
        for (b in 0 until 4) {
            if (counts[b] == 0) continue
            dot.alpha = (base * (b + 1) / 4f).toInt()
            c.drawPoints(buckets[b], 0, counts[b] * 2, dot)
        }
        if (isShown) postInvalidateDelayed(40)
    }
}
