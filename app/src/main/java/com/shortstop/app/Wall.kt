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

    private fun build(): FrameLayout {
        val frame = FrameLayout(ctx).apply { setBackgroundColor(pal.canvas) }
        frame.addView(
            SilkView(ctx, pal.canvas, pal.dark),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }

        if (cover) return buildCover(frame, col)

        val tile = tv(title.take(1).uppercase(), 24f, fonts.bold, pal.canvas).apply {
            background = GradientDrawable().apply { setColor(pal.ink); cornerRadius = dp(22).toFloat() }
        }
        val head = ShinyTextView(ctx, pal.ink, mixColor(pal.ink, pal.canvas, 0.72f)).apply {
            text = "Pause."; textSize = 48f; typeface = fonts.bold; setTextColor(pal.ink)
            gravity = Gravity.CENTER; letterSpacing = -0.02f
        }
        val sub = tv(
            if (message.isNotBlank()) message else "$title is blocked right now.",
            17f, fonts.regular, pal.ink
        )
        val hint = tv("Hold the button to open it for $passMin min.", 14f, fonts.regular, pal.muted)
        val hold = HoldPill(ctx, pal, fonts, holdSec) { onPass() }
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
        val head = ShinyTextView(ctx, pal.ink, mixColor(pal.ink, pal.canvas, 0.72f)).apply {
            text = "Focus."; textSize = 48f; typeface = fonts.bold; setTextColor(pal.ink)
            gravity = Gravity.CENTER; letterSpacing = -0.02f
        }
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
