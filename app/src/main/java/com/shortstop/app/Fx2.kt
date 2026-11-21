package com.shortstop.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/*
 * More react-bits ports, still black and white only. Settings follow the ones picked on reactbits.dev:
 *  HoldButton   -> HoldPill        (hold 2s, release 200ms, press scale 0.97, wave 6, glow, radius 14)
 *  SlideCommit  -> SlideCommit     (height 56, radius 28, return bounce 0.38, landing dip 0.026)
 *  FuseButton   -> FuseButton      (radius 22, undo window 4000ms, outline fuse 1.5, crossfade 200ms)
 *  SwipeToast   -> Toaster         (width 356, radius 12, slide 400ms, bounce 0.2, swipe 40, 4000ms, fuse bottom)
 *  SpringCheck  -> SpringCheck     (box 28, radius 9, font 18, bounce 0.2, strike lag 0.12, done opacity 0.42)
 *  GooeyNav     -> GooeyNav        (15 particles, spread 56, 600ms, variance 300ms, gooeyness 0.5, wobble 0.5)
 *  Counter/CountUp -> OdometerView (roll mode, spring, 2.4s)
 *  LatticeLoader -> LatticeLoader  (3x3 orbit, round, cell 6, gap 2, step 90ms, idle 0.15)
 *  StatusMark   -> StatusMark      (size 28, stroke 2, 8 dashes, spin 1100ms, arc 0.68, draw 240ms, fill 0.06)
 *  Silk         -> SilkView        (graphite, sheen 0.7, depth 0.7, glow 0.2, grain 1, ripple 1)
 */

private fun Context.dpf(v: Float) = v * resources.displayMetrics.density

fun ValueAnimator.onFinish(cb: () -> Unit) {
    addListener(object : AnimatorListenerAdapter() {
        private var cancelled = false
        override fun onAnimationCancel(animation: Animator) { cancelled = true }
        override fun onAnimationEnd(animation: Animator) { if (!cancelled) cb() }
    })
}

private fun withAlpha(color: Int, a: Float): Int =
    Color.argb((a * 255f).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

// ------------------------------------------------------------------ Hold button

/** Press and hold. A wavy liquid fill rises from the left; let go early and it drains back. */
class HoldPill(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    private val holdSecOf: () -> Int,
    private val onDone: () -> Unit
) : View(ctx) {
    private val holdSec get() = holdSecOf()
    private var progress = 0f
    private var anim: ValueAnimator? = null
    private var done = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surface }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.border; style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.ink; typeface = fonts.semi; textAlign = Paint.Align.CENTER
    }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A0F08"); typeface = fonts.semi; textAlign = Paint.Align.CENTER
    }
    private var fillW = 0f
    private val rect = RectF()
    private val er = RectF()
    private val clip = Path()
    private val wave = Path()
    private val gm = Matrix()
    private var glowShader: LinearGradient? = null

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpf(60f).toInt())
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
        val r = context.dpf(14f)
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, bg)
        val size = context.dpf(15f)
        dark.textSize = size
        light.textSize = size
        val cy = h / 2f - (dark.ascent() + dark.descent()) / 2f
        val text = caption()
        c.drawText(text, w / 2f, cy, dark)

        if (progress > 0f) {
            clip.reset()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            val edgeX = w * progress
            val amp = if (progress >= 1f) 0f else context.dpf(6f) * Math.sin(Math.PI * progress).toFloat()
            val t = SystemClock.uptimeMillis() * 0.006
            wave.reset()
            wave.moveTo(0f, 0f)
            val step = h / 12f
            var y = 0f
            while (y <= h + 0.5f) {
                val x = edgeX + amp * Math.sin((y / h).toDouble() * 2.0 * Math.PI * 1.3 + t).toFloat()
                wave.lineTo(x, y)
                y += step
            }
            wave.lineTo(0f, h)
            wave.close()
            if (progress < 0.99f) {
                if (glowShader == null) {
                    glowShader = LinearGradient(
                        0f, 0f, context.dpf(36f), 0f, withAlpha(Color.parseColor("#FF6A2B"), 0.30f), withAlpha(Color.parseColor("#FF6A2B"), 0f),
                        Shader.TileMode.CLAMP
                    )
                }
                gm.setTranslate(edgeX, 0f)
                glowShader!!.setLocalMatrix(gm)
                glow.shader = glowShader
                c.drawRect(edgeX, 0f, edgeX + context.dpf(36f), h, glow)
            }
            if (fillW != w) {
                fillW = w
                fill.shader = LinearGradient(0f, 0f, w, 0f, Color.parseColor("#FFB27A"), Color.parseColor("#FF6A2B"), Shader.TileMode.CLAMP)
            }
            c.drawPath(wave, fill)
            c.save()
            c.clipPath(wave)
            c.drawText(text, w / 2f, cy, light)
            c.restore()
            c.restore()
            if (progress < 1f) postInvalidateOnAnimation()
        }
        val inset = context.dpf(1f) / 2f
        er.set(inset, inset, w - inset, h - inset)
        c.drawRoundRect(er, r, r, edge)
    }

    private fun animateTo(target: Float, ms: Long, onEnd: (() -> Unit)? = null) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(progress, target)
        a.duration = ms
        a.interpolator = if (target >= 1f) LinearInterpolator() else android.view.animation.DecelerateInterpolator()
        a.addUpdateListener { progress = it.animatedValue as Float; invalidate() }
        if (onEnd != null) a.onFinish(onEnd)
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
                    postDelayed({ onDone() }, 200)
                    postDelayed({
                        if (isAttachedToWindow) { done = false; progress = 0f; invalidate() }
                    }, 1400)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator()).start()
                animateTo(0f, 200)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

private val SLIDE_GREEN = Color.parseColor("#86EFAC")
private val SLIDE_DEEP = Color.parseColor("#14532D")

// ------------------------------------------------------------------ Slide to confirm

/**
 * Slide the handle across to confirm. A light sweeps across the label and the handle gives a small nudge while it
 * waits; once you drag, the handle lifts and follows your finger with a little easing, ticks as it passes each
 * quarter, and turns into a check when it is far enough to let go. While the action runs it spins, then unfurls
 * into a done pill.
 */
class SlideCommit(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    private val label: String,
    private val doneLabel: String,
    private val validate: () -> Boolean,
    private val onDone: () -> Unit
) : View(ctx) {
    private var pos = 0f
    private var tpos = 0f
    private var drag = false
    private var lift = 0f
    private var armed = false
    private var armT = 0f
    private var tickBucket = 0
    private var phase = 0
    private var doneT = 0f
    private var spin = 0f
    private var checkT = 0f
    /** Colour the track turns when armed and the finish fills with (green by default). */
    var accent: Int = SLIDE_GREEN
        set(v) { field = v; greenP.color = v; invalidate() }
    var deep: Int = SLIDE_DEEP
        set(v) { field = v; checkP.color = v; invalidate() }
    private val greenP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLIDE_GREEN }
    private val checkP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SLIDE_DEEP; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = ctx.dpf(2.6f)
    }
    private val checkPath = Path()
    private val checkSeg = Path()
    private var anim: ValueAnimator? = null
    private var spinAnim: ValueAnimator? = null
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wipeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.border; style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1f)
    }
    private val goal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.ink; style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1.5f)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.canvas; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; strokeWidth = ctx.dpf(2.2f)
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.semi; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val er = RectF()
    private val clip = Path()
    private val arc = RectF()
    private var shim: LinearGradient? = null
    private val shimM = android.graphics.Matrix()

    private val hd get() = context.dpf(48f)
    private val pad get() = context.dpf(4f)

    override fun onMeasure(w: Int, h: Int) {
        val cap = context.dpf(340f).toInt()
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(if (s <= 0) cap else minOf(s, cap), context.dpf(56f).toInt())
    }

    private fun run(): Float = width - 2 * pad - hd

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val now = SystemClock.uptimeMillis()
        if (drag) pos += (tpos - pos) * 0.5f
        lift += ((if (drag) 1f else 0f) - lift) * 0.25f
        val armTarget = if (armed) 1f else 0f
        armT += (armTarget - armT) * 0.3f
        // a small nudge every few seconds while it waits
        var nudge = 0f
        if (phase == 0 && !drag && pos < 0.01f) {
            val t = (now % 3200L) / 3200f
            if (t < 0.2f) nudge = Math.sin(t / 0.2f * Math.PI).toFloat() * context.dpf(6f)
        }
        val hx = pad + pos * run() + nudge
        val cxh = hx + hd / 2f
        rect.set(0f, 0f, w, h)
        trackP.color = pal.surface
        c.drawRoundRect(rect, r, r, trackP)
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        if (phase < 2 && pos > 0f) {
            // the wash behind the handle turns light green once you are far enough to let go
            wipeP.color = mixColor(mixColor(pal.surface, pal.ink, 0.06f + 0.12f * pos.coerceIn(0f, 1f)), accent, armT)
            c.drawRect(0f, 0f, cxh, h, wipeP)
        }
        if (doneT > 0.005f) {
            val e = 1f - (1f - doneT) * (1f - doneT) * (1f - doneT)
            c.drawCircle(cxh, h / 2f, Math.hypot(w.toDouble(), h.toDouble()).toFloat() * e, greenP)
        }
        txt.textSize = context.dpf(15f)
        val cy = h / 2f - (txt.ascent() + txt.descent()) / 2f
        val fade = (1f - doneT) * (1f - minOf(1f, pos * 1.6f))
        if (fade > 0.01f) {
            val lx = w / 2f + hd / 4f
            if (shim == null) {
                shim = LinearGradient(
                    0f, 0f, context.dpf(110f), 0f, intArrayOf(pal.muted, pal.ink, pal.muted),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
                )
            }
            val tw = txt.measureText(label)
            val st = ((now % 2800L) / 2800f)
            val k = (st / 0.75f).coerceIn(0f, 1f)
            val e = k * k * (3f - 2f * k)
            val from = lx - tw / 2f - context.dpf(110f)
            val to = lx + tw / 2f
            shimM.setTranslate(from + (to - from) * e, 0f)
            shim!!.setLocalMatrix(shimM)
            txt.shader = shim
            txt.alpha = (255 * fade).toInt()
            c.drawText(label, lx, cy, txt)
            txt.shader = null
        }
        if (doneT > 0.01f) {
            val la = ((doneT - 0.35f) / 0.65f).coerceIn(0f, 1f)
            txt.color = deep
            txt.alpha = (255 * la).toInt()
            val lx = w / 2f + context.dpf(14f)
            c.drawText(doneLabel, lx, cy, txt)
            val tw = txt.measureText(doneLabel)
            val ix = lx - tw / 2f - context.dpf(20f)
            val s = context.dpf(7f)
            checkPath.reset()
            checkPath.moveTo(ix - s, h / 2f)
            checkPath.lineTo(ix - s * 0.3f, h / 2f + s * 0.75f)
            checkPath.lineTo(ix + s, h / 2f - s * 0.75f)
            val pm = android.graphics.PathMeasure(checkPath, false)
            checkSeg.reset()
            pm.getSegment(0f, pm.length * checkT, checkSeg, true)
            c.drawPath(checkSeg, checkP)
        }
        // the landing spot at the far end: it lights up once you are far enough to let go
        if (doneT < 0.5f) {
            goal.alpha = ((40 + 150 * armT) * (1f - doneT * 2f)).toInt().coerceIn(0, 255)
            c.drawCircle(w - pad - hd / 2f, h / 2f, hd / 2f - context.dpf(5f) - 3f * armT * context.dpf(1f), goal)
        }
        c.restore()
        if (doneT < 1f) {
            edge.alpha = (255 * (1f - doneT)).toInt()
            val i = context.dpf(1f) / 2f
            er.set(i, i, w - i, h - i)
            c.drawRoundRect(er, r, r, edge)
        }
        val hs = 1f - doneT
        if (hs > 0.01f) {
            val sc = hs * (1f + 0.06f * lift + 0.05f * armT)
            c.save()
            c.scale(sc, sc, cxh, h / 2f)
            if (lift > 0.02f) {
                shadow.color = Color.argb((60 * lift).toInt(), 0, 0, 0)
                c.drawCircle(cxh, h / 2f + context.dpf(2.5f), hd / 2f + context.dpf(1.5f), shadow)
            }
            c.drawCircle(cxh, h / 2f, hd / 2f, handleP)
            if (phase == 1) {
                val ar = hd * 0.2f
                arc.set(cxh - ar, h / 2f - ar, cxh + ar, h / 2f + ar)
                c.drawArc(arc, spin, 250f, false, icon)
            } else {
                val s = context.dpf(6f)
                icon.alpha = (255 * (1f - armT)).toInt()
                if (icon.alpha > 0) {
                    c.drawLine(cxh - s, h / 2f, cxh + s, h / 2f, icon)
                    c.drawLine(cxh + s * 0.2f, h / 2f - s * 0.8f, cxh + s, h / 2f, icon)
                    c.drawLine(cxh + s * 0.2f, h / 2f + s * 0.8f, cxh + s, h / 2f, icon)
                }
                icon.alpha = (255 * armT).toInt()
                if (icon.alpha > 0) {
                    c.drawLine(cxh - s * 0.9f, h / 2f, cxh - s * 0.2f, h / 2f + s * 0.75f, icon)
                    c.drawLine(cxh - s * 0.2f, h / 2f + s * 0.75f, cxh + s * 0.95f, h / 2f - s * 0.75f, icon)
                }
                icon.alpha = 255
            }
            c.restore()
        }
        if (phase == 0 && isShown) postInvalidateOnAnimation()
    }

    private fun springBack() {
        anim?.cancel()
        armed = false
        val from = pos
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            interpolator = SpringInterpolator(13f, 0.62f, 0.8f)
            addUpdateListener { pos = (from * (1f - it.animatedValue as Float)).coerceAtLeast(-0.02f); tpos = pos; invalidate() }
            start()
        }
    }

    private fun commit() {
        anim?.cancel()
        val from = pos
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140
            interpolator = DecelerateInterpolator()
            addUpdateListener { pos = from + (1f - from) * it.animatedValue as Float; tpos = pos; invalidate() }
            onFinish { land() }
            start()
        }
    }

    private fun land() {
        pos = 1f
        tpos = 1f
        invalidate()
        animate().scaleY(0.974f).setDuration(70).withEndAction { animate().scaleY(1f).setDuration(160).start() }.start()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (!validate()) { springBack(); return }
        phase = 2
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560
            interpolator = LinearInterpolator()
            addUpdateListener { doneT = it.animatedValue as Float; invalidate() }
            onFinish { postDelayed({ onDone() }, 480) }
            start()
        }
        spinAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460
            startDelay = 220
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { checkT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (phase != 0) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hx = pad + pos * run()
                if (e.x >= hx - context.dpf(16f) && e.x <= hx + hd + context.dpf(16f)) {
                    drag = true
                    anim?.cancel()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tickBucket = 0
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> if (drag) {
                tpos = ((e.x - pad - hd / 2f) / run()).coerceIn(0f, 1f)
                val b = (tpos * 4f).toInt().coerceAtMost(3)
                if (b != tickBucket) {
                    tickBucket = b
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                val nowArmed = tpos >= 0.9f
                if (nowArmed != armed) {
                    armed = nowArmed
                    if (armed) performHapticFeedback(
                        if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                    )
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drag) {
                drag = false
                if (e.actionMasked == MotionEvent.ACTION_UP && tpos >= 0.9f) commit() else springBack()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        spinAnim?.cancel()
        super.onDetachedFromWindow()
    }
}

// ------------------------------------------------------------------ Fuse button

/** Tap once and the button becomes Undo while a fuse burns around its edge. When the fuse ends the action commits. */
class FuseButton(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    label: String,
    private val undoLabel: String,
    private val doneLabel: String,
    private val windowMs: Long,
    private val guard: () -> Boolean,
    private val onCommit: () -> Unit
) : View(ctx) {
    var text: String = label
        set(v) {
            if (field != v) {
                field = v
                if (state == 0) { invalidate(); requestLayout() }
            }
        }
    private var state = 0
    private var fuse = 0f
    private var swap = 1f
    private var oldLabel = ""
    private var burn: ValueAnimator? = null
    private var swapAnim: ValueAnimator? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.canvas }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.border; style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1f)
    }
    private val fusePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.ink; style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1.5f); strokeCap = Paint.Cap.ROUND
    }
    private val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.semi; textAlign = Paint.Align.CENTER }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.ink; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = ctx.dpf(2f)
    }
    private val rect = RectF()
    private val outline = Path()
    private val seg = Path()
    private val pm = PathMeasure()
    private val pt = FloatArray(2)

    /** Soft fill and edge colors, for screens with their own palette. */
    fun tint(fill: Int, edge: Int, ink: Int = pal.ink, fuse: Int = pal.ink) {
        bg.color = fill; ring.color = edge; inkC = ink
        fusePaint.color = fuse; tip.color = fuse; tick.color = fuse; invalidate()
    }
    private var inkC = pal.ink

    private fun shown(): String = when (state) { 1 -> undoLabel; 2 -> doneLabel; else -> text }

    override fun onMeasure(w: Int, h: Int) {
        txt.textSize = 14f * context.resources.displayMetrics.scaledDensity
        val widest = listOf(text, undoLabel, doneLabel).maxByOrNull { it.length } ?: text
        val want = (txt.measureText(widest) + context.dpf(56f)).toInt()
        val mode = MeasureSpec.getMode(w)
        val size = MeasureSpec.getSize(w)
        val mw = when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> minOf(want, size)
            else -> want
        }
        val mh = if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else context.dpf(46f).toInt()
        setMeasuredDimension(mw, mh)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val i = context.dpf(0.75f)
        rect.set(i, i, w - i, h - i)
        outline.reset()
        outline.addRoundRect(rect, h / 2f, h / 2f, Path.Direction.CW)
        pm.setPath(outline, false)
    }

    private fun drawLabel(c: Canvas, s: String, a: Float, cx: Float, cy: Float, check: Boolean) {
        if (a <= 0.01f) return
        txt.color = inkC
        txt.alpha = (255 * a).toInt()
        if (check) {
            val tw = txt.measureText(s)
            val x = cx - tw / 2f - context.dpf(14f)
            val q = context.dpf(5f)
            tick.alpha = (255 * a).toInt()
            c.drawLine(x - q, height / 2f, x - q * 0.3f, height / 2f + q * 0.7f, tick)
            c.drawLine(x - q * 0.3f, height / 2f + q * 0.7f, x + q, height / 2f - q * 0.7f, tick)
            c.drawText(s, cx + context.dpf(8f), cy, txt)
        } else {
            c.drawText(s, cx, cy, txt)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRoundRect(rect, h / 2f, h / 2f, bg)
        c.drawRoundRect(rect, h / 2f, h / 2f, ring)
        if (state == 1 && pm.length > 0f) {
            val len = pm.length
            seg.reset()
            pm.getSegment(len * fuse, len, seg, true)
            c.drawPath(seg, fusePaint)
            pm.getPosTan(len * fuse, pt, null)
            tip.alpha = 70
            c.drawCircle(pt[0], pt[1], context.dpf(6f), tip)
            tip.alpha = 255
            c.drawCircle(pt[0], pt[1], context.dpf(2.2f), tip)
        }
        txt.textSize = 14f * context.resources.displayMetrics.scaledDensity
        val cy = h / 2f - (txt.ascent() + txt.descent()) / 2f
        if (swap < 1f) {
            drawLabel(c, oldLabel, 1f - swap * 2f, w / 2f, cy, false)
            drawLabel(c, shown(), swap * 2f - 1f, w / 2f, cy, state == 2)
        } else {
            drawLabel(c, shown(), 1f, w / 2f, cy, state == 2)
        }
    }

    private fun swapTo(newState: Int) {
        oldLabel = shown()
        state = newState
        swapAnim?.cancel()
        swap = 0f
        swapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            addUpdateListener { swap = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun tap() {
        if (state == 0) {
            if (!guard()) return
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            swapTo(1)
            burn?.cancel()
            burn = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = windowMs
                interpolator = LinearInterpolator()
                addUpdateListener { fuse = it.animatedValue as Float; invalidate() }
                onFinish {
                    swapTo(2)
                    onCommit()
                }
                start()
            }
        } else if (state == 1) {
            burn?.cancel()
            fuse = 0f
            swapTo(0)
        }
    }

    fun reset() {
        burn?.cancel()
        swapAnim?.cancel()
        state = 0
        fuse = 0f
        swap = 1f
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state == 2) return true
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
            }
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) tap()
            }
            MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        burn?.cancel()
        swapAnim?.cancel()
        super.onDetachedFromWindow()
    }
}

// ------------------------------------------------------------------ Toast

private class CheckIcon(ctx: Context, private val ink: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; strokeWidth = ctx.dpf(1.6f)
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val r = w / 2f - p.strokeWidth
        c.drawCircle(w / 2f, w / 2f, r, p)
        c.drawLine(w * 0.3f, w * 0.52f, w * 0.45f, w * 0.66f, p)
        c.drawLine(w * 0.45f, w * 0.66f, w * 0.72f, w * 0.36f, p)
    }
}

/** Toast that slides up, burns a fuse along its bottom edge and swipes down to dismiss. */
class Toaster(
    private val ctx: Context,
    private val host: FrameLayout,
    private val pal: Palette,
    private val fonts: Fonts,
    private val bottomPx: Int
) {
    private var cur: View? = null
    private var fuseAnim: ValueAnimator? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun front() { cur?.bringToFront() }

    private fun remove(v: View, animate: Boolean) {
        if (cur === v) {
            cur = null
            fuseAnim?.cancel()
            fuseAnim = null
        }
        if (!animate) { host.removeView(v); return }
        v.animate().translationY(dp(140).toFloat()).alpha(0f).setDuration(220).withEndAction {
            try { host.removeView(v) } catch (e: Exception) { }
        }.start()
    }

    fun show(title: String, desc: String = "", action: String? = null, onAction: (() -> Unit)? = null, ms: Long = 4000L) {
        cur?.let { remove(it, false) }
        val card = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(pal.canvas); setStroke(dp(1), pal.border); cornerRadius = dp(12).toFloat()
            }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(12), dp(15))
        }
        row.addView(CheckIcon(ctx, pal.ink), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = title; textSize = 14f; typeface = fonts.semi; setTextColor(pal.ink); includeFontPadding = false
        })
        if (desc.isNotBlank()) col.addView(TextView(ctx).apply {
            text = desc; textSize = 12f; typeface = fonts.regular; setTextColor(pal.muted)
            includeFontPadding = false; setPadding(0, dp(4), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) {
            row.addView(TextView(ctx).apply {
                text = action; textSize = 12f; typeface = fonts.semi; setTextColor(pal.canvas)
                includeFontPadding = false; gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(pal.ink); cornerRadius = dp(8).toFloat() }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { onAction?.invoke(); remove(card, true) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            })
        }
        card.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = View(ctx).apply { setBackgroundColor(pal.ink); pivotX = 0f }
        card.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM).apply {
            marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(5)
        })

        val screenW = ctx.resources.displayMetrics.widthPixels
        val lp = FrameLayout.LayoutParams(minOf(dp(356), screenW - dp(32)), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = bottomPx }
        host.addView(card, lp)
        cur = card

        var downY = 0f
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; fuseAnim?.pause(); true }
                MotionEvent.ACTION_MOVE -> { v.translationY = (e.rawY - downY).coerceAtLeast(0f); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    fuseAnim?.resume()
                    if (e.rawY - downY > dp(40)) remove(card, true)
                    else v.animate().translationY(0f).setDuration(220).setInterpolator(OvershootInterpolator(1.0f)).start()
                    true
                }
                else -> true
            }
        }

        card.translationY = dp(120).toFloat()
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(400).setInterpolator(OvershootInterpolator(1.0f)).start()
        val fa = ValueAnimator.ofFloat(1f, 0f)
        fa.duration = ms
        fa.interpolator = LinearInterpolator()
        fa.addUpdateListener { bar.scaleX = it.animatedValue as Float }
        fa.onFinish { remove(card, true) }
        fuseAnim = fa
        fa.start()
    }
}

// ------------------------------------------------------------------ Spring check

/** Checkbox row: one spring fills the box, draws the tick and strikes through the label. */
class SpringCheck(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    private val label: String,
    private val fontSp: Float = 18f
) : View(ctx) {
    var interactive = false
    var onChange: ((Boolean) -> Unit)? = null
    var checked = false
        private set
    private var t = 0f
    private var anim: ValueAnimator? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1.5f) }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
    private val tickP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.canvas; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; strokeWidth = ctx.dpf(2.4f)
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.semi }
    private val strike = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink; strokeWidth = ctx.dpf(1.6f); strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val tickPath = Path()
    private val seg = Path()
    private val pm = PathMeasure()

    private fun box() = context.dpf(28f)

    fun setChecked(v: Boolean, animate: Boolean) {
        val to = if (v) 1f else 0f
        if (v == checked && t == to) return
        checked = v
        anim?.cancel()
        if (!animate) { t = to; invalidate(); return }
        anim = ValueAnimator.ofFloat(t, to).apply {
            duration = 520
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        txt.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp, resources.displayMetrics)
        val tw = Math.ceil(txt.measureText(label).toDouble()).toFloat()
        setMeasuredDimension((box() + context.dpf(12f) + tw).toInt() + 2, context.dpf(34f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val b = box()
        val cy = height / 2f
        val k = t.coerceIn(0f, 1f)
        val top = cy - b / 2f
        ring.color = mixColor(pal.muted, pal.ink, k)
        rect.set(ring.strokeWidth / 2f, top + ring.strokeWidth / 2f, b - ring.strokeWidth / 2f, top + b - ring.strokeWidth / 2f)
        c.drawRoundRect(rect, context.dpf(9f), context.dpf(9f), ring)
        val s = t.coerceAtLeast(0f)
        if (s > 0.01f) {
            val ins = (1f - s) * b / 2f
            rect.set(ins, top + ins, b - ins, top + b - ins)
            val rad = minOf(context.dpf(9f), rect.width() / 2f)
            c.drawRoundRect(rect, rad, rad, fillP)
        }
        val tp = ((k - 0.35f) / 0.65f).coerceIn(0f, 1f)
        if (tp > 0f) {
            tickPath.reset()
            tickPath.moveTo(b * 0.27f, top + b * 0.52f)
            tickPath.lineTo(b * 0.43f, top + b * 0.68f)
            tickPath.lineTo(b * 0.74f, top + b * 0.34f)
            pm.setPath(tickPath, false)
            seg.reset()
            pm.getSegment(0f, pm.length * tp, seg, true)
            c.drawPath(seg, tickP)
        }
        txt.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp, resources.displayMetrics)
        val x = b + context.dpf(12f)
        val base = cy - (txt.ascent() + txt.descent()) / 2f
        txt.color = pal.ink
        txt.alpha = (255 * (1f - 0.58f * k)).toInt()
        c.drawText(label, x, base, txt)
        val sp = ((k - 0.12f) / 0.88f).coerceIn(0f, 1f)
        if (sp > 0f) {
            val tw = txt.measureText(label)
            strike.alpha = (255 * (1f - 0.58f * k)).toInt()
            c.drawLine(x, base - txt.textSize * 0.3f, x + tw * sp, base - txt.textSize * 0.3f, strike)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            setChecked(!checked, true)
            onChange?.invoke(checked)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

// ------------------------------------------------------------------ Odometer

/** Number whose digits spin like an odometer into place (react-bits CountUp, roll mode, spring). */
class OdometerView(
    ctx: Context,
    private val ink: Int,
    private val face: Typeface,
    private val sp: Float
) : View(ctx) {
    private var text = "0"
    private var t0 = 0L
    private var play = true
    private val dur = 2400L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = face; textAlign = Paint.Align.LEFT }

    fun setValue(v: Int, animate: Boolean = true) {
        text = v.toString()
        play = animate
        t0 = 0L
        requestLayout()
        invalidate()
    }

    private fun px() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    override fun onMeasure(w: Int, h: Int) {
        paint.textSize = px()
        val cw = paint.measureText("0")
        var total = 0f
        for (ch in text) total += if (ch.isDigit()) cw else paint.measureText(ch.toString())
        setMeasuredDimension(Math.ceil(total.toDouble()).toInt() + 2, Math.ceil((paint.descent() - paint.ascent()).toDouble()).toInt())
    }

    override fun onDraw(c: Canvas) {
        paint.textSize = px()
        val now = SystemClock.uptimeMillis()
        if (t0 == 0L) t0 = if (play) now else now - dur * 3
        val n = text.length
        val lh = paint.descent() - paint.ascent()
        val by = -paint.ascent()
        val cw = paint.measureText("0")
        val span = (dur - 90L * n).coerceAtLeast(600L)
        var x = 0f
        var live = false
        for (i in 0 until n) {
            val ch = text[i]
            if (!ch.isDigit()) {
                paint.alpha = 255
                c.drawText(ch.toString(), x, by, paint)
                x += paint.measureText(ch.toString())
                continue
            }
            val d = ch - '0'
            val u = ((now - t0 - i * 90L).toFloat() / span).coerceIn(0f, 1f)
            if (u < 1f) live = true
            val z = u - 1f
            val e = 1f + 1.25f * z * z * z + 0.25f * z * z
            val travel = (minOf(n - i, 4) * 10 + d).toFloat()
            val p = travel * e
            val k = Math.floor(p.toDouble()).toInt()
            val frac = p - k
            c.save()
            c.clipRect(x, 0f, x + cw, lh)
            paint.alpha = (255 * (1f - frac)).toInt()
            c.drawText(((k % 10 + 10) % 10).toString(), x, by - frac * lh, paint)
            if (frac > 0.001f) {
                paint.alpha = (255 * frac).toInt()
                c.drawText((((k + 1) % 10 + 10) % 10).toString(), x, by + (1f - frac) * lh, paint)
            }
            c.restore()
            paint.alpha = 255
            x += cw
        }
        if (live) postInvalidateOnAnimation()
    }
}

// ------------------------------------------------------------------ Lattice loader + status mark

/** 3x3 grid of cells; a light orbits the ring while a session runs. */
class LatticeLoader(ctx: Context, private val ink: Int) : View(ctx) {
    var running = true
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val rect = RectF()
    private val order = arrayOf(
        intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 2),
        intArrayOf(2, 2), intArrayOf(2, 1), intArrayOf(2, 0), intArrayOf(1, 0)
    )

    override fun onMeasure(w: Int, h: Int) {
        val s = (context.dpf(6f) * 3 + context.dpf(2f) * 2).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val cell = context.dpf(6f)
        val gap = context.dpf(2f)
        val head = ((SystemClock.uptimeMillis() / 90L) % 8L).toInt()
        for (idx in 0 until 8) {
            val dist = (head - idx + 8) % 8
            val a = if (running) 0.15f + 0.85f * maxOf(0f, 1f - dist / 4f) else 0.15f
            p.alpha = (255 * a).toInt()
            val r = order[idx][0]
            val col = order[idx][1]
            val x = col * (cell + gap)
            val y = r * (cell + gap)
            rect.set(x, y, x + cell, y + cell)
            c.drawRoundRect(rect, cell / 2f, cell / 2f, p)
        }
        p.alpha = (255 * (if (running) 0.35f else 0.15f)).toInt()
        val x = cell + gap
        rect.set(x, x, x + cell, x + cell)
        c.drawRoundRect(rect, cell / 2f, cell / 2f, p)
        if (running) postInvalidateDelayed(45)
    }
}

/** Small status glyph: dashed ring (waiting), spinning arc (running), check (done), cross (cancelled). */
class StatusMark(ctx: Context, private val pal: Palette, private val sizeDp: Float = 28f) : View(ctx) {
    companion object {
        const val PENDING = 0
        const val RUNNING = 1
        const val DONE = 2
        const val CANCELLED = 3
    }

    private var status = PENDING
    private var t = 1f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.ink; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink; alpha = 15 }
    private val rect = RectF()

    fun setStatus(s: Int, animate: Boolean, delayMs: Long = 0L) {
        status = s
        anim?.cancel()
        if (!animate || s == PENDING || s == RUNNING) { t = 1f; invalidate(); return }
        t = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delayMs
            duration = 240
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = context.dpf(sizeDp).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        val sw = context.dpf(2f) * sizeDp / 28f
        p.strokeWidth = sw
        val r = (s - sw) / 2f
        rect.set(sw / 2f, sw / 2f, s - sw / 2f, s - sw / 2f)
        val cx = s / 2f
        when (status) {
            PENDING -> {
                p.alpha = 140
                for (i in 0 until 8) c.drawArc(rect, i * 45f + 4f, 28f, false, p)
            }
            RUNNING -> {
                p.alpha = 255
                val a = (SystemClock.uptimeMillis() % 1100L) / 1100f * 360f
                c.drawArc(rect, a, 0.68f * 360f, false, p)
                postInvalidateOnAnimation()
            }
            DONE -> {
                p.alpha = 255
                c.drawCircle(cx, cx, r, wash)
                c.drawCircle(cx, cx, r, p)
                val k = t
                val x1 = s * 0.3f; val y1 = s * 0.52f
                val x2 = s * 0.45f; val y2 = s * 0.67f
                val x3 = s * 0.72f; val y3 = s * 0.36f
                if (k < 0.4f) {
                    val f = k / 0.4f
                    c.drawLine(x1, y1, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, p)
                } else {
                    c.drawLine(x1, y1, x2, y2, p)
                    val f = (k - 0.4f) / 0.6f
                    c.drawLine(x2, y2, x2 + (x3 - x2) * f, y2 + (y3 - y2) * f, p)
                }
            }
            else -> {
                p.alpha = 255
                c.drawCircle(cx, cx, r, p)
                val a = s * 0.32f
                val b = s * 0.68f
                c.drawLine(a, a, a + (b - a) * minOf(1f, t * 2f), a + (b - a) * minOf(1f, t * 2f), p)
                if (t > 0.5f) {
                    val f = (t - 0.5f) * 2f
                    c.drawLine(b, a, b - (b - a) * f, a + (b - a) * f, p)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

// ------------------------------------------------------------------ Silk

/** Flowing satin folds in graphite, with a sheen that leans toward your finger and a ripple on tap. */
class SilkView(ctx: Context, private val canvasColor: Int, private val dark: Boolean) : View(ctx) {
    private val graphite = 0xFF8D8A96.toInt()
    private val basePaint = Paint().apply {
        color = mixColor(canvasColor, graphite, if (dark) 0.14f else 0.2f)
    }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2f) }
    private val grain = Paint()
    private val sheen = if (dark) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()
    private val shade = if (dark) 0xFF000000.toInt() else graphite
    private var bandShader: LinearGradient? = null
    private var shadeShader: LinearGradient? = null
    private var bw = 0f
    private var tx = 0f
    private var ty = 0f
    private var sx = 0f
    private var sy = 0f
    private var infl = 0f
    private var target = 0f
    private var rx = 0f
    private var ry = 0f
    private var rt0 = 0L

    init {
        val n = 96
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val px = IntArray(n * n)
        val rnd = java.util.Random(7)
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            px[i] = Color.argb(if (dark) 16 else 14, v, v, v)
        }
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        grain.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tx = e.x; ty = e.y
                if (infl < 0.02f) { sx = tx; sy = ty }
                target = 1f
                rx = e.x; ry = e.y; rt0 = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_MOVE -> { tx = e.x; ty = e.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> target = 0f
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        c.drawRect(0f, 0f, w, h, basePaint)
        if (bandShader == null || bw != w * 0.9f) {
            bw = w * 0.9f
            bandShader = LinearGradient(
                0f, 0f, bw, 0f,
                intArrayOf(withAlpha(sheen, 0f), withAlpha(sheen, if (dark) 0.1f else 0.55f), withAlpha(sheen, 0f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            shadeShader = LinearGradient(
                0f, 0f, bw, 0f,
                intArrayOf(withAlpha(shade, 0f), withAlpha(shade, if (dark) 0.35f else 0.22f), withAlpha(shade, 0f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
        val time = now / 1000.0 * 0.5
        for (i in 0 until 4) {
            val drift = Math.sin(time + i * 1.7).toFloat()
            val sway = Math.cos(time * 0.8 + i * 2.3).toFloat()
            c.save()
            c.translate(w * (0.1f + 0.25f * i) + drift * w * 0.12f, -h * 0.4f)
            c.rotate(18f + sway * 7f, 0f, h)
            band.shader = if (i % 2 == 0) bandShader else shadeShader
            c.drawRect(0f, 0f, bw * (0.55f + 0.1f * i), h * 2.2f, band)
            c.restore()
        }
        infl += (target - infl) * 0.12f
        sx += (tx - sx) * 0.2f
        sy += (ty - sy) * 0.2f
        if (infl > 0.01f) {
            val rad = context.dpf(240f)
            spot.shader = RadialGradient(
                sx, sy, rad, withAlpha(sheen, (if (dark) 0.14f else 0.5f) * infl), withAlpha(sheen, 0f),
                Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, spot)
        }
        c.drawRect(0f, 0f, w, h, grain)
        if (isShown) postInvalidateDelayed(33)
    }

    private fun pal(c: Int) = c
}
