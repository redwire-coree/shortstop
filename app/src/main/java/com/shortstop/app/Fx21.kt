package com.shortstop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * The round profile button in the Focus header. Today it shows a generic guest silhouette; nothing here signs anybody
 * in or knows an account. When a real avatar exists, call [setAvatar] with a bitmap: it is centre-cropped into the same
 * circle, so the layout does not change.
 *
 * The view is 48 dp square (the touch target) and draws a 44 dp circle, with a clear border so it never melts into the
 * cream background (light) or the dark brown (dark).
 */
class ProfileButton(ctx: Context, private val dark: Boolean) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF4A2E1B.toInt() else 0xFFFFEAD4.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        color = if (dark) 0xFFB8834F.toInt() else 0xFFE8AE78.toInt()
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFFF0CFAA.toInt() else 0xFFB5733A.toInt() }
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()
    private var avatar: Bitmap? = null
    private var pressedT = 0f

    init {
        isClickable = true
        // a soft lift instead of a drawn shadow: the system renders it, in both themes, without a software layer
        elevation = 2f * d
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                val c = v.width / 2; val r = (22.5f * d).toInt()
                o.setOval(c - r, c - r, c + r, c + r); o.alpha = if (dark) 0.5f else 0.35f
            }
        }
        contentDescription = "Profile, guest"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Future avatar image. Pass null to go back to the guest silhouette. */
    fun setAvatar(b: Bitmap?) {
        avatar = b
        photo.shader = null
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = (48 * d).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = 22f * d * (1f - 0.05f * pressedT)
        c.drawCircle(cx, cy, r, fill)
        val a = avatar
        if (a != null) {
            val sh = BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val sc = (2f * r) / minOf(a.width, a.height)
            val m = Matrix(); m.setScale(sc, sc)
            m.postTranslate(cx - a.width * sc / 2f, cy - a.height * sc / 2f)
            sh.setLocalMatrix(m); photo.shader = sh
            c.drawCircle(cx, cy, r - edge.strokeWidth / 2f, photo)
        } else {
            // generic silhouette: head and shoulders, clipped to the circle
            c.save()
            clip.reset(); clip.addCircle(cx, cy, r - edge.strokeWidth / 2f, Path.Direction.CW)
            c.clipPath(clip)
            c.drawCircle(cx, cy - r * 0.20f, r * 0.31f, ink)
            rect.set(cx - r * 0.62f, cy + r * 0.20f, cx + r * 0.62f, cy + r * 1.35f)
            c.drawOval(rect, ink)
            c.restore()
        }
        c.drawCircle(cx, cy, r - edge.strokeWidth / 2f, edge)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedT = 1f; invalidate() }
            MotionEvent.ACTION_UP -> { pressedT = 0f; invalidate(); if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) performClick() }
            MotionEvent.ACTION_CANCEL -> { pressedT = 0f; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
