package com.shortstop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings

/** Colours and helpers for the warm Focus look (cream in light mode, espresso in dark). Used on the Focus page only. */
object FocusTheme {
    fun bg(dark: Boolean): Int = Color.parseColor(if (dark) "#2A1A10" else "#FFF3E3")
    fun title(dark: Boolean): Int = Color.parseColor(if (dark) "#FFEFD9" else "#4A2A12")
    fun cardFill(dark: Boolean): Int = Color.parseColor(if (dark) "#3A2517" else "#FFFBF6")
    fun cardEdge(dark: Boolean): Int = Color.parseColor(if (dark) "#4D3322" else "#F6E2C8")
    val orange: Int = Color.parseColor("#FF9A3D")

    fun reducedMotion(c: Context): Boolean =
        try { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: Exception) { false }
}

/**
 * The little green leaf: a soft two-tone leaf with a curved vein, drawn from a unit shape (base at 0,0, tip at 0,-1).
 * Always the same leaf, in light and dark mode.
 */
object Leaf {
    private val body = Path()
    private val shade = Path()
    private val vein = Path()
    private val stem = Path()
    private val clipR = RectF()
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    init {
        body.moveTo(0f, 0f)
        body.cubicTo(-0.60f, -0.14f, -0.60f, -0.66f, 0.10f, -1f)
        body.cubicTo(0.64f, -0.60f, 0.56f, -0.12f, 0f, 0f)
        body.close()
        shade.moveTo(0f, 0f)
        shade.quadTo(0.02f, -0.55f, 0.10f, -1f)
        shade.lineTo(1f, -1.1f)
        shade.lineTo(1f, 0.1f)
        shade.close()
        vein.moveTo(0f, 0f)
        vein.quadTo(0.01f, -0.48f, 0.07f, -0.84f)
        stem.moveTo(0f, 0f)
        stem.quadTo(-0.02f, 0.08f, -0.07f, 0.15f)
    }

    /** [size] is the leaf's height in pixels; (x, y) is where the stem meets the face. */
    fun draw(c: Canvas, x: Float, y: Float, size: Float, rotation: Float, scale: Float) {
        if (scale <= 0.01f) return
        c.save()
        c.translate(x, y)
        c.rotate(rotation)
        c.scale(size * scale, size * scale)
        fillP.shader = LinearGradient(-0.5f, -1f, 0.5f, 0f, Color.parseColor("#A4DE7E"), Color.parseColor("#62B44C"), Shader.TileMode.CLAMP)
        c.drawPath(body, fillP)
        c.save()
        c.clipPath(body)
        fillP.shader = null
        fillP.color = Color.parseColor("#4B9E44")
        fillP.alpha = 150
        c.drawPath(shade, fillP)
        fillP.alpha = 255
        c.restore()
        strokeP.color = Color.parseColor("#3F8A3C")
        strokeP.strokeWidth = 0.07f
        c.drawPath(stem, strokeP)
        strokeP.color = Color.argb(210, 214, 245, 188)
        strokeP.strokeWidth = 0.055f
        c.drawPath(vein, strokeP)
        c.restore()
    }
}
