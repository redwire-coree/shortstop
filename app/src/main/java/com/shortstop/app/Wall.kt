package com.shortstop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout

/**
 * Full-screen "wall" drawn over a blocked app (accessibility overlay, needs no extra permission).
 * The person has to press and hold the button for [holdSec] seconds to open the section for a while,
 * or tap "Not now" to go Home.
 */
class Wall(
    private val ctx: Context,
    private val title: String,
    private val holdSec: Int,
    private val passMin: Int,
    private val onPass: () -> Unit,
    private val onLeave: () -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null

    private val blue = Color.parseColor("#3B3BFF")
    private val indigo = Color.parseColor("#1A1A8C")

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun font(file: String, fallback: Typeface): Typeface =
        try { Typeface.createFromAsset(ctx.assets, "fonts/$file") } catch (e: Exception) { fallback }

    private val display = font("Nunito-Black.ttf", Typeface.create("sans-serif-black", Typeface.NORMAL))
    private val body = font("Inter-Regular.otf", Typeface.DEFAULT)
    private val mono = font("IBMPlexMono-Bold.ttf", Typeface.create("monospace", Typeface.BOLD))

    private fun grain(): Drawable {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val r = java.util.Random(7)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, Color.argb(r.nextInt(46), 255, 255, 255))
        val tile = BitmapDrawable(ctx.resources, bmp).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        return LayerDrawable(arrayOf<Drawable>(ColorDrawable(blue), tile))
    }

    private fun tv(t: String, sp: Float, face: Typeface, gravity: Int = Gravity.CENTER) = TextView(ctx).apply {
        text = t; textSize = sp; typeface = face; setTextColor(Color.WHITE); this.gravity = gravity
    }

    /** Press-and-hold button that fills while held. Releasing early drains it back. */
    private inner class HoldButton(context: Context) : View(context) {
        private var progress = 0f
        private var anim: ValueAnimator? = null
        private var done = false
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = indigo }
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = indigo; typeface = mono; textAlign = Paint.Align.CENTER; letterSpacing = 0.06f
        }
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = mono; textAlign = Paint.Align.CENTER; letterSpacing = 0.06f
        }
        private val rect = RectF()
        private val clip = Path()

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(68))
        }

        private fun caption(): String {
            if (done) return "OPEN"
            if (progress <= 0f) return "HOLD TO OPEN · ${holdSec}s"
            val left = Math.ceil(((1f - progress) * holdSec).toDouble()).toInt()
            return "KEEP HOLDING · ${maxOf(1, left)}s"
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = dp(18).toFloat()
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, r, r, bg)

            val cx = w / 2f
            val cy = h / 2f + dp(5)
            dark.textSize = dp(14).toFloat()
            light.textSize = dp(14).toFloat()
            c.drawText(caption(), cx, cy, dark)

            if (progress > 0f) {
                c.save()
                clip.reset()
                clip.addRoundRect(rect, r, r, Path.Direction.CW)
                c.clipPath(clip)
                c.drawRect(0f, 0f, w * progress, h, fill)
                c.save()
                c.clipRect(0f, 0f, w * progress, h)
                c.drawText(caption(), cx, cy, light)
                c.restore()
                c.restore()
            }
        }

        private fun animateTo(target: Float, ms: Long, onEnd: (() -> Unit)? = null) {
            anim?.cancel()
            val a = ValueAnimator.ofFloat(progress, target)
            a.duration = ms
            a.interpolator = if (target >= 1f) LinearInterpolator() else DecelerateInterpolator()
            a.addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            var cancelled = false
            a.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) onEnd?.invoke()
                }
            })
            anim = a
            a.start()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (done) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    animateTo(1f, maxOf(200L, (holdSec * 1000L * (1f - progress)).toLong())) {
                        done = true
                        progress = 1f
                        invalidate()
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        postDelayed({ onPass() }, 220)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator()).start()
                    animateTo(0f, 320)
                }
            }
            return true
        }

        override fun onDetachedFromWindow() {
            anim?.cancel()
            super.onDetachedFromWindow()
        }
    }

    private fun build(): FrameLayout {
        val frame = FrameLayout(ctx).apply { background = grain() }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }

        val tag = tv("SHORTSTOP", 12f, mono).apply { letterSpacing = 0.12f; alpha = 0.85f }
        val head = tv("Pause.", 52f, display)
        val sub = tv(
            "$title is blocked. Hold the button to open it for $passMin min, or let it go.",
            16f, body
        ).apply { alpha = 0.92f }
        val hold = HoldButton(ctx)
        val leave = tv("NOT NOW", 13f, mono).apply {
            letterSpacing = 0.1f
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setOnClickListener { onLeave() }
        }

        col.addView(tag)
        col.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(18); bottomMargin = dp(10) })
        col.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(36) })
        col.addView(hold, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(leave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(14) })

        frame.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Entrance: fade and rise.
        col.alpha = 0f
        col.translationY = dp(24).toFloat()
        col.animate().alpha(1f).translationY(0f).setDuration(320)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        return frame
    }

    fun show(): Boolean {
        if (root != null) return true
        return try {
            val v = build()
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            wm.addView(v, lp)
            root = v
            true
        } catch (e: Exception) {
            root = null
            false
        }
    }

    fun dismiss() {
        val v = root ?: return
        root = null
        try {
            v.animate().alpha(0f).setDuration(160).withEndAction {
                try { wm.removeViewImmediate(v) } catch (e: Exception) { }
            }.start()
        } catch (e: Exception) {
            try { wm.removeViewImmediate(v) } catch (e2: Exception) { }
        }
    }

    fun showing() = root != null
}
