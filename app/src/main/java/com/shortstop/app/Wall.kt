package com.shortstop.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen wall drawn over a blocked app (accessibility overlay: needs no extra permission).
 * Hold the button for [holdSec] seconds to open it for a while, or tap "Not now" to go Home.
 */
class Wall(
    private val ctx: Context,
    private val title: String,
    private val message: String,
    private val holdSec: Int,
    private val passMin: Int,
    private val onPass: () -> Unit,
    private val onLeave: () -> Unit,
    private val cover: Boolean = false,
    private val coverText: () -> String = { "" }
) {
    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private val pal = Palette(ctx)
    private val fonts = Fonts(ctx)

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun tv(t: String, sp: Float, face: android.graphics.Typeface, color: Int) = TextView(ctx).apply {
        text = t; textSize = sp; typeface = face; setTextColor(color); gravity = Gravity.CENTER
    }

    /** Press-and-hold pill that fills while held. Releasing early drains it back. */
    private inner class HoldButton(context: Context) : View(context) {
        private var progress = 0f
        private var anim: ValueAnimator? = null
        private var done = false
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surface }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.border; style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.ink; typeface = fonts.semi; textAlign = Paint.Align.CENTER
        }
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.canvas; typeface = fonts.semi; textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()
        private val clip = Path()

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(60))
        }

        private fun caption(): String {
            if (done) return "Opening…"
            if (progress <= 0f) return "Hold to open · ${holdSec}s"
            val left = Math.ceil(((1f - progress) * holdSec).toDouble()).toInt()
            return "Keep holding · ${maxOf(1, left)}s"
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, r, r, bg)

            val cx = w / 2f
            dark.textSize = dp(15).toFloat()
            light.textSize = dp(15).toFloat()
            val cy = h / 2f - (dark.ascent() + dark.descent()) / 2f
            c.drawText(caption(), cx, cy, dark)

            if (progress > 0f) {
                c.save()
                clip.reset()
                clip.addRoundRect(rect, r, r, Path.Direction.CW)
                c.clipPath(clip)
                c.drawRect(0f, 0f, w * progress, h, fill)
                c.clipRect(0f, 0f, w * progress, h)
                c.drawText(caption(), cx, cy, light)
                c.restore()
            }
            rect.inset(dp(1) / 2f, dp(1) / 2f)
            c.drawRoundRect(rect, r, r, edge)
        }

        private fun animateTo(target: Float, ms: Long, onEnd: (() -> Unit)? = null) {
            anim?.cancel()
            val a = ValueAnimator.ofFloat(progress, target)
            a.duration = ms
            a.interpolator = if (target >= 1f) LinearInterpolator() else DecelerateInterpolator()
            a.addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            var cancelled = false
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
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
                        postDelayed({ onPass() }, 200)
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
        val frame = FrameLayout(ctx).apply { setBackgroundColor(pal.canvas) }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }

        if (cover) return buildCover(frame, col)

        val tile = tv(title.take(1).uppercase(), 24f, fonts.bold, pal.canvas).apply {
            background = GradientDrawable().apply { setColor(pal.ink); cornerRadius = dp(22).toFloat() }
        }
        val head = tv("Pause.", 48f, fonts.bold, pal.ink).apply { letterSpacing = -0.02f }
        val sub = tv(
            if (message.isNotBlank()) message else "$title is blocked right now.",
            17f, fonts.regular, pal.ink
        )
        val hint = tv("Hold the button to open it for $passMin min.", 14f, fonts.regular, pal.muted)
        val hold = HoldButton(ctx)
        val leave = tv("Not now", 15f, fonts.semi, pal.ink).apply {
            background = GradientDrawable().apply {
                setColor(pal.canvas); setStroke(dp(1), pal.border); cornerRadius = dp(40).toFloat()
            }
            setPadding(dp(28), dp(14), dp(28), dp(14))
            setOnClickListener { onLeave() }
        }

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        col.addView(tile, LinearLayout.LayoutParams(dp(64), dp(64)))
        col.addView(head, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(24); bottomMargin = dp(10) })
        col.addView(sub, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(8) })
        col.addView(hint, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(32) })
        col.addView(hold, LinearLayout.LayoutParams(match, wrap))
        col.addView(leave, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(14) })

        frame.addView(col, FrameLayout.LayoutParams(match, wrap, Gravity.CENTER))

        col.alpha = 0f
        col.translationY = dp(20).toFloat()
        col.animate().alpha(1f).translationY(0f).setDuration(300)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        return frame
    }

    /** Focus cover: shown over the home screen while a session runs. No hold button, just the timer. */
    private fun buildCover(frame: FrameLayout, col: LinearLayout): FrameLayout {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val tile = tv("F", 24f, fonts.bold, pal.canvas).apply {
            background = GradientDrawable().apply { setColor(pal.ink); cornerRadius = dp(22).toFloat() }
        }
        val head = tv("Focus.", 48f, fonts.bold, pal.ink).apply { letterSpacing = -0.02f }
        val sub = tv(
            if (message.isNotBlank()) message else "Your blocked apps are closed. Stay with it.",
            17f, fonts.regular, pal.ink
        )
        val time = tv(coverText(), 28f, fonts.bold, pal.ink)
        val open = tv("Open Shortstop", 15f, fonts.semi, pal.canvas).apply {
            background = GradientDrawable().apply { setColor(pal.ink); cornerRadius = dp(40).toFloat() }
            setPadding(dp(28), dp(14), dp(28), dp(14))
            setOnClickListener { onLeave() }
        }
        col.addView(tile, LinearLayout.LayoutParams(dp(64), dp(64)))
        col.addView(head, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(24); bottomMargin = dp(10) })
        col.addView(sub, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(20) })
        col.addView(time, LinearLayout.LayoutParams(wrap, wrap).apply { bottomMargin = dp(28) })
        col.addView(open, LinearLayout.LayoutParams(wrap, wrap))
        frame.addView(col, FrameLayout.LayoutParams(match, wrap, Gravity.CENTER))
        val tick = object : Runnable {
            override fun run() {
                time.text = coverText()
                tickHandler.postDelayed(this, 1000)
            }
        }
        tickHandler.postDelayed(tick, 1000)
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
        tickHandler.removeCallbacksAndMessages(null)
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
