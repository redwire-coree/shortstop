package com.shortstop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View

/**
 * Backdrop for the Focus page: two big soft glows in the timer's ember tones that drift very slowly, under a fine
 * film grain (react-bits Noise) so the colour has texture instead of looking like a flat gradient. In dark mode
 * the same glows sit on black and carry the mood. Draws only while on screen.
 */
class AmbientBackdrop(ctx: Context, private val pal: Palette) : View(ctx) {
    private val glowA = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowB = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint()
    private var shA: RadialGradient? = null
    private var shB: RadialGradient? = null
    private var sw = 0f
    private val m = Matrix()

    init {
        val n = 128
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(7)
        val px = IntArray(n * n)
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            px[i] = Color.argb(255, v, v, v)
        }
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        grain.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        grain.alpha = if (pal.dark) 16 else 11
        grain.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.OVERLAY)
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
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (shA == null || sw != w) build(w)
        val t = SystemClock.uptimeMillis() / 1000f
        m.setTranslate(w * (0.5f + 0.12f * Math.sin(t * 0.22).toFloat()), h * (0.26f + 0.03f * Math.cos(t * 0.19).toFloat()))
        shA!!.setLocalMatrix(m)
        c.drawRect(0f, 0f, w, h, glowA)
        m.setTranslate(w * (0.92f + 0.06f * Math.cos(t * 0.17).toFloat()), h * (0.64f + 0.04f * Math.sin(t * 0.21).toFloat()))
        shB!!.setLocalMatrix(m)
        c.drawRect(0f, 0f, w, h, glowB)
        c.drawRect(0f, 0f, w, h, grain)
        if (isShown) postInvalidateDelayed(50)
    }
}
