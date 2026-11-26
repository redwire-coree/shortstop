package com.shortstop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View

/**
 * Backdrop for the Focus page: two big soft glows in the timer's ember tones that drift very slowly, under a fine
 * film grain (react-bits Noise) so the colour has texture instead of looking like a flat gradient. In dark mode
 * the same glows sit on black and carry the mood. Draws only while on screen.
 */
class AmbientBackdrop(ctx: Context, private val pal: Palette, private val rich: Boolean = false) : View(ctx) {
    private val glowA = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowB = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint()
    private var shA: RadialGradient? = null
    private var shB: RadialGradient? = null
    private var sw = 0f
    private val m = Matrix()
    private val cloud = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clouds = ArrayList<Path>()
    private val calm = FocusTheme.reducedMotion(ctx)
    private val d = ctx.resources.displayMetrics.density

    /** Soft puffy shapes: three overlapping circles merged into one path so the edges never double up. */
    private fun puff(r: Float): Path {
        val a = Path().apply { addCircle(0f, 0f, r, Path.Direction.CW) }
        val b = Path().apply { addCircle(r * 0.95f, r * 0.22f, r * 0.72f, Path.Direction.CW) }
        val c = Path().apply { addCircle(-r * 0.8f, r * 0.34f, r * 0.62f, Path.Direction.CW) }
        a.op(b, Path.Op.UNION)
        a.op(c, Path.Op.UNION)
        return a
    }

    init {
        val n = 128
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(7)
        val px = IntArray(n * n)
        // The same film grain as before, but as plain light and dark specks (alpha = how far from mid grey) drawn with
        // normal blending. The old OVERLAY blend forced the GPU to read the whole screen back every frame.
        val gain = if (pal.dark) 0.16f else 0.11f
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            val a = (Math.abs(v - 128) * 2 * gain).toInt().coerceIn(0, 255)
            px[i] = if (v > 128) Color.argb(a, 255, 255, 255) else Color.argb(a, 0, 0, 0)
        }
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        grain.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun build(w: Float) {
        sw = w
        val a = if (pal.dark) 70 else 58
        shA = RadialGradient(
            0f, 0f, w * 0.95f,
            intArrayOf(Color.argb(a, 255, 138, 61), Color.argb(a * 4 / 10, 255, 150, 90), Color.argb(0, 255, 138, 61)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        val b = if (pal.dark) 46 else 34
        shB = RadialGradient(
            0f, 0f, w * 0.75f,
            intArrayOf(Color.argb(b, 255, 190, 130), Color.argb(0, 255, 190, 130)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        glowA.shader = shA
        glowB.shader = shB
        for (i in ORBS.indices) {
            val rr = w * ORBS[i][4]
            orbPaints[i].shader = RadialGradient(0f, 0f, rr,
                Color.argb(if (pal.dark) 70 else 62, 255, 122, 43), Color.argb(0, 255, 122, 43), Shader.TileMode.CLAMP)
        }
    }

    private val orbPaints = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG) }

    private companion object {
        // constant tables: nothing here is allocated per frame any more
        val RADII_RICH = floatArrayOf(46f, 92f, 38f, 70f, 64f, 84f, 44f, 76f, 58f, 96f)
        val RADII_LITE = floatArrayOf(46f, 92f, 38f, 70f)
        val SPOTS_RICH = arrayOf(
            floatArrayOf(0.06f, 0.08f, 9.0f, 0f), floatArrayOf(-0.02f, 0.50f, 11.0f, 1.7f),
            floatArrayOf(0.98f, 0.24f, 8.0f, 3.1f), floatArrayOf(0.96f, 0.78f, 12.0f, 4.4f),
            floatArrayOf(0.52f, 0.04f, 10.0f, 2.2f), floatArrayOf(0.18f, 0.88f, 13.0f, 0.9f),
            floatArrayOf(0.74f, 0.52f, 9.5f, 5.0f), floatArrayOf(0.30f, 0.30f, 11.5f, 3.6f),
            floatArrayOf(0.64f, 0.94f, 10.5f, 1.2f), floatArrayOf(0.02f, 0.70f, 12.5f, 2.8f)
        )
        val SPOTS_LITE = arrayOf(
            floatArrayOf(0.06f, 0.10f, 9.0f, 0f), floatArrayOf(-0.02f, 0.52f, 11.0f, 1.7f),
            floatArrayOf(0.98f, 0.30f, 8.0f, 3.1f), floatArrayOf(0.96f, 0.80f, 12.0f, 4.4f)
        )
        val ORBS = arrayOf(
            floatArrayOf(0.22f, 0.20f, 14f, 0.3f, 0.34f), floatArrayOf(0.80f, 0.42f, 17f, 2.4f, 0.28f),
            floatArrayOf(0.34f, 0.66f, 16f, 4.1f, 0.30f), floatArrayOf(0.86f, 0.86f, 15f, 1.1f, 0.26f)
        )
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (shA == null || sw != w) build(w)
        val t = if (calm) 0f else SystemClock.uptimeMillis() / 1000f
        c.drawColor(FocusTheme.bg(pal.dark))
        if (clouds.isEmpty()) {
            val radii = if (rich) RADII_RICH else RADII_LITE
            for (r in radii) clouds.add(puff(r * d))
        }
        cloud.color = Color.argb(if (pal.dark) 40 else 52, 255, 154, 61)
        // x, y (fraction of the page), drift period, phase
        val spots = if (rich) SPOTS_RICH else SPOTS_LITE
        for (i in spots.indices) {
            val sp = spots[i]
            val k = t * 6.2832f / sp[2] + sp[3]
            // eased bob: a sine of a sine gives a soft, springy float rather than a mechanical sway
            val bob = Math.sin(k.toDouble() + 0.6 * Math.sin(k * 1.7)).toFloat()
            c.save()
            c.translate(w * sp[0] + bob * 7f * d, h * sp[1] + Math.cos(k.toDouble()).toFloat() * 9f * d)
            val sc = 1f + 0.025f * bob
            c.scale(sc, sc)
            if (rich) cloud.color = if (i % 2 == 0) Color.argb(if (pal.dark) 40 else 52, 255, 154, 61) else Color.argb(if (pal.dark) 34 else 44, 255, 106, 43)
            c.drawPath(clouds[i], cloud)
            c.restore()
        }
        if (rich) {
            // ember orbs: soft orange glows that rise and sway on their own slow loops
            for (i in ORBS.indices) {
                val o = ORBS[i]
                val k = t * 6.2832f / o[2] + o[3]
                val ox = w * o[0] + Math.sin(k.toDouble()).toFloat() * 14f * d
                val oy = h * o[1] + Math.cos(k * 0.8).toFloat() * 18f * d
                c.save()
                c.translate(ox, oy)
                c.drawCircle(0f, 0f, w * o[4], orbPaints[i])
                c.restore()
            }
        }
        m.setTranslate(w * (0.5f + 0.12f * Math.sin(t * 0.22).toFloat()), h * (0.26f + 0.03f * Math.cos(t * 0.19).toFloat()))
        shA!!.setLocalMatrix(m)
        c.drawRect(0f, 0f, w, h, glowA)
        m.setTranslate(w * (0.92f + 0.06f * Math.cos(t * 0.17).toFloat()), h * (0.64f + 0.04f * Math.sin(t * 0.21).toFloat()))
        shB!!.setLocalMatrix(m)
        c.drawRect(0f, 0f, w, h, glowB)
        c.drawRect(0f, 0f, w, h, grain)
        if (isShown && !calm) postInvalidateDelayed(40)
    }
}
