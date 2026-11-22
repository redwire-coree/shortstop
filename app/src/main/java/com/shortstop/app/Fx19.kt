package com.shortstop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.random.Random

enum class Move { ENTER, EXIT, IDLE, HOP, NOD, WAVE, POINT, CHEER, THINK, OOPS, FOCUS, BREAK, TASK, SURPRISE, TAP, GIGGLE, LISTEN, SLEEP, PROUD, ENCOURAGE, CELEBRATE }

/**
 * Orange's artwork as separate, aligned layers (body, leaf, eye whites, pupils, highlights, mouth, sparks).
 * They were cut from the 4K source and the gaps behind them were filled in, so the pieces recombine into the
 * original picture. Decoded once and shared.
 */
object OrangeArt {
    class L(val bmp: Bitmap, val x: Float, val y: Float)
    val layers = HashMap<String, L>()
    var leafPx = 315f; var leafPy = 136f
    val eye = arrayOf(FloatArray(6), FloatArray(6))
    var mouthCx = 339f; var mouthCy = 385f
    var ok = false
    private var tried = false

    fun ensure(c: Context): Boolean {
        if (tried) return ok
        tried = true
        try {
            val names = setOf("base", "leaf", "white0", "white1", "pupil0", "pupil1", "hl0", "hl1", "mouth", "sparks")
            c.assets.open("orange_layers.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = line.trim().split(" ")
                    if (p.size < 3) continue
                    when {
                        p[0] in names && p.size >= 5 -> {
                            val bmp = c.assets.open("mx_" + p[0] + ".png").use { BitmapFactory.decodeStream(it) }
                            layers[p[0]] = L(bmp, p[1].toFloat(), p[2].toFloat())
                        }
                        p[0] == "leafpivot" -> { leafPx = p[1].toFloat(); leafPy = p[2].toFloat() }
                        p[0] == "eye0" || p[0] == "eye1" -> { val e = eye[p[0].last() - '0']; for (i in 0..5) e[i] = p[i + 1].toFloat() }
                        p[0] == "mouthc" -> { mouthCx = p[1].toFloat(); mouthCy = p[2].toFloat() }
                    }
                }
            }
            ok = names.all { layers.containsKey(it) }
        } catch (e: Exception) { ok = false }
        return ok
    }
}

/**
 * Orange, the mascot, with a small animation controller.
 *  - States are [Move]s. One-shot moves return to IDLE by themselves; IDLE, FOCUS, BREAK, THINK and POINT are
 *    sustained until another move replaces them. A running one-shot ignores lower-priority requests.
 *  - The body is a mesh, so it squashes, stretches and wobbles; the leaf follows with its own spring; the eyes,
 *    pupils and mouth are separate layers, which allows blinking, gaze and expressions.
 *  - Everything is driven from onDraw with the wall clock, so nothing here can affect the focus timer.
 *  - Reduced motion shrinks every move to a quiet version, and he never goes fully still.
 *  - Drawing stops while the window is hidden or the view is detached.
 */
class OrangeView(
    ctx: Context,
    private val fonts: Fonts,
    private val dark: Boolean,
    private val sizeDp: Float = 96f
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val s = sizeDp * d
    private val k = s / 640f
    private val calm = FocusTheme.reducedMotion(ctx)
    private val amp = if (calm) 0.6f else 1f
    private val spd = if (calm) 0.75f else 1f
    private val art = OrangeArt.ensure(ctx)
    private val L = OrangeArt.layers

    var interactive = true

    private val vw = s * 2.7f
    private val vh = s * 1.75f
    private val mLeft = s * 0.12f
    private val mTop = vh - s * 0.07f - s
    private val feet = mTop + s * 634f / 640f
    private val cx = mLeft + s / 2f

    // ---- controller state
    private var move = Move.ENTER
    private var t0 = SystemClock.uptimeMillis()
    private var busy = true
    private var pointDir = 1f
    private var loopMoves: List<Move>? = null
    private var loopIdx = 0
    private var loopGap = 1600L
    private var nextAt = 0L
    private var lastTap = 0L
    private var tapRun = 0
    private var longFired = false
    private var attached = false
    private var winVisible = true

    // ---- pose (targets set by pose(), then smoothed)
    private var dy = 0f; private var sx = 1f; private var sy = 1f; private var rot = 0f; private var lag = 0f
    private var armL = 0f; private var armR = 0f
    private var eyeOpenT = 1f; private var happyT = 0f; private var pupilT = 1f
    private var lookXT = 0f; private var lookYT = 0f
    private var mouthMode = 0; private var mouthYT = 1f
    private var leafBiasT = 0f; private var leafKick = 0f
    private var spark = 0f; private var stars = 0f; private var thinking = false
    private var blinkWanted = false
    private var blinkQuiet = 1f

    // smoothed values actually drawn
    private var eyeOpen = 1f; private var happy = 0f; private var pupil = 1f
    private var lookX = 0f; private var lookY = 0f; private var mouthY = 1f
    private var leafBias = 0f

    // leaf spring
    private var leafA = 0f; private var leafV = 0f
    private var prevDy = 0f; private var prevRot = 0f; private var lastFrame = 0L

    // blink + glance
    private var blinkStart = 0L
    private var nextBlink = SystemClock.uptimeMillis() + 1400L
    private var doubleBlink = false
    private var nextGlance = SystemClock.uptimeMillis() + 2500L
    private var glX = 0f; private var glY = 0f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FocusCardColors.ORANGE }
    private val lid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5C2D14.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 11f
    }
    private val lipP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB82E08.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 9f
    }
    private val starP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Path().apply {
        moveTo(0f, -1f); quadTo(0.18f, -0.18f, 1f, 0f); quadTo(0.18f, 0.18f, 0f, 1f)
        quadTo(-0.18f, 0.18f, -1f, 0f); quadTo(-0.18f, -0.18f, 0f, -1f); close()
    }
    private val rect = RectF()
    private val gridC = 14; private val gridR = 12
    private val verts = FloatArray((gridC + 1) * (gridR + 1) * 2)
    private val enterSpring = SpringInterpolator(14f, 0.5f, 0.9f)
    private var tx = 0f; private var ty = 0f

    // Pose-to-pose blending: body, face, arms and effects all travel together.
    private val blendFrom = FloatArray(16)
    private var blendStarted = 0L
    private var blendDuration = 240L
    private var blendActive = false
    private var entranceStarted = t0

    // speech bubble
    private val textP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.semi; color = FocusTheme.title(dark); textSize = 14f * ctx.resources.displayMetrics.scaledDensity
    }
    private val bubbleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF3A2517.toInt() else 0xFFFFFFFF.toInt() }
    private val bubbleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) 0xFF4D3322.toInt() else 0xFFF6E2C8.toInt(); style = Paint.Style.STROKE; strokeWidth = d
    }
    private var text: String? = null
    private var layout: StaticLayout? = null
    private var bubbleT0 = 0L
    private val bubbleSpring = SpringInterpolator(18f, 0.55f, 0.6f)
    private val tail = Path()

    private val longPress = Runnable {
        if (interactive && move != Move.FOCUS && !busy) { longFired = true; play(Move.SURPRISE) }
    }

    // ---- public API
    fun say(t: String?) {
        text = t
        layout = null
        if (t != null) {
            val maxW = (vw - s * 1.12f - 4f * d - 24f * d).toInt()
            layout = StaticLayout(t, textP, maxW, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
            bubbleT0 = SystemClock.uptimeMillis()
        }
        invalidate()
    }

    private fun prio(m: Move) = when (m) {
        Move.EXIT -> 9; Move.CHEER -> 8; Move.ENTER -> 7; Move.OOPS -> 6; Move.TASK, Move.SURPRISE -> 5
        Move.FOCUS, Move.BREAK -> 4; Move.NOD, Move.WAVE, Move.POINT -> 3; Move.HOP, Move.THINK -> 2
        Move.CELEBRATE -> 8; Move.GIGGLE, Move.PROUD -> 6; Move.ENCOURAGE -> 5
        Move.TAP -> 1; Move.LISTEN, Move.SLEEP -> 2; Move.IDLE -> 0
    }

    private fun capturePose() {
        val values = floatArrayOf(dy, sx, sy, rot, lag, armL, armR, eyeOpenT, happyT,
            pupilT, lookXT, lookYT, mouthYT, leafBiasT, spark, stars)
        values.copyInto(blendFrom)
    }

    private fun blendPose(now: Long) {
        if (!blendActive) return
        val f = ((now - blendStarted).toFloat() / blendDuration).coerceIn(0f, 1f)
        // A critically damped-looking ease, with no overshoot at the end of a pose change.
        val e = f * f * (3f - 2f * f)
        val v = floatArrayOf(dy, sx, sy, rot, lag, armL, armR, eyeOpenT, happyT,
            pupilT, lookXT, lookYT, mouthYT, leafBiasT, spark, stars)
        for (i in v.indices) v[i] = blendFrom[i] + (v[i] - blendFrom[i]) * e
        dy=v[0]; sx=v[1]; sy=v[2]; rot=v[3]; lag=v[4]; armL=v[5]; armR=v[6]
        eyeOpenT=v[7]; happyT=v[8]; pupilT=v[9]; lookXT=v[10]; lookYT=v[11]
        mouthYT=v[12]; leafBiasT=v[13]; spark=v[14]; stars=v[15]
        if (f >= 1f) blendActive = false
    }

    fun play(m: Move, dir: Float = 1f, force: Boolean = false) {
        // One-shot priority prevents low-value events from interrupting an important reaction.
        if (!force && busy && m != Move.EXIT && prio(m) < prio(move)) return
        val now = SystemClock.uptimeMillis()
        capturePose()
        move = m; pointDir = if (dir >= 0f) 1f else -1f
        t0 = now; busy = true
        blendStarted = now
        blendDuration = when (m) { Move.ENTER -> 360L; Move.EXIT -> 210L; Move.CHEER, Move.CELEBRATE -> 190L; else -> 260L }
        blendActive = m != Move.ENTER && m != Move.EXIT
        if (m == Move.ENTER) entranceStarted = now
        if (m == Move.EXIT) loopMoves = null
        invalidate()
    }

    /** Plays these moves one after another with a pause between. */
    fun loop(moves: List<Move>, gapMs: Long) {
        loopMoves = moves; loopGap = gapMs; loopIdx = 0
        nextAt = SystemClock.uptimeMillis() + 700L
    }

    // ---- lifecycle
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; lastFrame = 0L; invalidate() }
    override fun onDetachedFromWindow() { attached = false; removeCallbacks(longPress); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        winVisible = v == VISIBLE
        if (winVisible) { lastFrame = 0L; invalidate() }
    }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(vw.toInt(), vh.toInt()) }

    // ---- animation helpers
    private fun bump(t: Float, a: Float, b: Float): Float =
        if (t <= a || t >= b) 0f else sin(Math.PI * (t - a) / (b - a)).toFloat()

    private fun land(kk: Float, a: Float): Float = a * exp(-6.0 * kk).toFloat() * cos(15.0 * kk).toFloat()

    private fun sm(x: Float) = x * x * (3f - 2f * x)

    /** Sets the pose for move time t (seconds). Returns true while a finite move is still playing. */
    private fun pose(t: Float): Boolean {
        dy = 0f; sx = 1f; sy = 1f; rot = 0f; lag = 0f; armL = 0f; armR = 0f
        eyeOpenT = 1f; happyT = 0f; pupilT = 1f; lookXT = 0f; lookYT = 0f
        mouthMode = 0; mouthYT = 1f; leafBiasT = 0f; leafKick = 0f
        spark = 0f; stars = 0f; thinking = false; blinkWanted = false; blinkQuiet = 1f
        when (move) {
            Move.ENTER -> {
                val p = (t / 0.9f).coerceIn(0f, 1f)
                dy = -(1f - enterSpring.getInterpolation(p)) * s * 1.3f
                if (t > 0.35f) { val kk = t - 0.35f; sy = 1f - land(kk, 0.10f) * amp; sx = 1f + (1f - sy) * 0.7f }
                // a small friendly bounce, then a gentle wave and a blink
                if (t in 0.9f..1.25f) { val u = (t - 0.9f) / 0.35f; dy = s * 0.06f * amp * 4f * u * (1f - u) }
                if (t > 1.0f) {
                    val w = (t - 1.0f)
                    val env = sm((w / 0.2f).coerceIn(0f, 1f)) * (1f - sm(((w - 0.8f) / 0.3f).coerceIn(0f, 1f)))
                    armR = env * (0.55f + 0.2f * sin(2.0 * Math.PI * 2.4 * w).toFloat()) * amp
                    rot = 3f * sin(2.0 * Math.PI * 2.4 * w - 0.6).toFloat() * env * amp
                }
                if (t in 1.5f..1.6f) blinkWanted = true
                spark = bump(t, 0.5f, 1.0f) * 0.7f
                return t < 2.1f
            }
            Move.EXIT -> {
                val p = (t / 0.35f).coerceIn(0f, 1f)
                dy = -p * p * s * 1.5f
                return t < 0.4f
            }
            Move.HOP -> {
                val h = s * 0.26f * amp
                when {
                    t < 0.12f -> { val u = t / 0.12f; sy = 1f - 0.14f * u * amp; sx = 1f + 0.09f * u * amp }
                    t < 0.52f -> {
                        val u = (t - 0.12f) / 0.40f
                        dy = h * 4f * u * (1f - u)
                        sy = 1f + (0.12f * (1f - u) * (1f - u) + 0.05f * u * u) * amp
                        sx = 1f - (sy - 1f) * 0.7f
                        rot = 3f * (u - 0.5f) * amp * pointDir
                    }
                    else -> {
                        val kk = t - 0.52f
                        sy = 1f - land(kk, 0.16f) * amp; sx = 1f + (1f - sy) * 0.7f
                        lag = 0.03f * exp(-6.0 * kk).toFloat() * sin(18.0 * kk).toFloat() * amp
                    }
                }
                spark = bump(t, 0.15f, 0.75f) * 0.8f
                return t < 1.0f
            }
            Move.NOD -> {
                if (t < 0.5f) {
                    val b = abs(sin(4.0 * Math.PI * t)).toFloat()
                    sy = 1f - 0.11f * b * amp; sx = 1f + 0.05f * b * amp; lag = 0.03f * b * amp
                    rot = 2.5f * sin(4.0 * Math.PI * t).toFloat() * amp
                    leafKick = 1f
                } else {
                    val kk = t - 0.5f
                    sy = 1f + 0.03f * exp(-7.0 * kk).toFloat() * sin(20.0 * kk).toFloat() * amp
                }
                if (t in 0.55f..0.65f) blinkWanted = true
                spark = bump(t, 0.0f, 0.55f) * 0.7f
                return t < 0.8f
            }
            Move.WAVE -> {
                val env = exp(-1.2 * t).toFloat()
                armR = (0.6f + 0.3f * sin(2.0 * Math.PI * 2.6 * t).toFloat()) * sm((t / 0.15f).coerceIn(0f, 1f)) * (1f - sm(((t - 1.0f) / 0.3f).coerceIn(0f, 1f))) * amp
                rot = 5f * sin(2.0 * Math.PI * 2.6 * t).toFloat() * env * amp
                lag = 0.05f * sin(2.0 * Math.PI * 2.6 * t - 0.9).toFloat() * env * amp
                dy = abs(sin(2.0 * Math.PI * 2.6 * t)).toFloat() * s * 0.025f * env * amp
                return t < 1.3f
            }
            Move.POINT -> {
                val a = 1f - exp(-12.0 * t).toFloat()
                rot = pointDir * 8f * a * amp + sin(t * 8f) * 1.2f * amp
                lag = pointDir * 0.05f * a * amp
                lookXT = pointDir * 0.8f * a
                armR = (if (pointDir > 0f) 0.5f else 0f) * a * amp
                armL = (if (pointDir < 0f) 0.5f else 0f) * a * amp
                sy = 1f + 0.010f * sin(t * 2.6f) * amp
                return true
            }
            Move.CHEER -> {
                val h = s * 0.46f * amp
                happyT = if (t in 0.14f..1.25f) 1f else 0f
                eyeOpenT = 1.08f
                when {
                    t < 0.16f -> { val u = sm(t / 0.16f); sy = 1f - 0.16f * u * amp; sx = 1f + 0.10f * u * amp }
                    t < 0.80f -> {
                        val u = (t - 0.16f) / 0.64f
                        dy = h * 4f * u * (1f - u)
                        rot = 5f * sin(Math.PI * u * 2.0).toFloat() * amp
                        sy = 1f + 0.12f * (1f - u) * amp; sx = 1f - (sy - 1f) * 0.6f
                    }
                    else -> { val kk = t - 0.80f; sy = 1f - land(kk, 0.2f) * amp; sx = 1f + (1f - sy) * 0.7f }
                }
                val arm = sm(((t - 0.12f) / 0.3f).coerceIn(0f, 1f)) * (1f - sm(((t - 1.15f) / 0.35f).coerceIn(0f, 1f))) * amp
                armL = arm; armR = arm
                leafKick = if (t in 0.16f..0.9f) 1f else 0f
                spark = bump(t, 0.12f, 1.3f)
                stars = if (t in 0.3f..1.5f) (t - 0.3f) / 1.2f else 0f
                return t < 1.7f
            }
            Move.TASK -> {
                val h = s * 0.14f * amp
                happyT = if (t in 0.12f..0.62f) 1f else 0f
                if (t < 0.5f) { val u = t / 0.5f; dy = h * 4f * u * (1f - u); sy = 1f + 0.06f * (1f - u) * amp }
                else { val kk = t - 0.5f; sy = 1f - land(kk, 0.10f) * amp; sx = 1f + (1f - sy) * 0.7f }
                armR = bump(t, 0.05f, 0.6f) * 0.4f * amp
                stars = if (t in 0.1f..0.8f) (t - 0.1f) / 0.7f * 0.6f else 0f
                spark = bump(t, 0.1f, 0.6f) * 0.6f
                return t < 0.9f
            }
            Move.THINK -> {
                thinking = true
                rot = (6f + 1.5f * sin(t * 2.0f)) * amp
                lookXT = 0.55f + 0.2f * sin(t * 0.9f); lookYT = -0.7f
                eyeOpenT = 0.94f; mouthMode = 1
                sy = 1f + 0.012f * sin(t * 2.2f) * amp
                leafKick = 0.4f + 0.2f * sin(t * 1.7f)
                return true
            }
            Move.FOCUS -> {
                // confident nod, then settle: slow breathing, focused eyes, calm closed mouth, no particles
                if (t < 0.8f) {
                    val b = abs(sin(2.0 * Math.PI * t / 0.8)).toFloat() * (1f - t / 0.8f)
                    sy = 1f - 0.09f * b * amp; sx = 1f + 0.04f * b * amp; lag = 0.025f * b * amp
                    leafKick = 0.6f
                }
                val b = sin(t * 1.25f)
                sy += 0.010f * b; sx -= 0.006f * b
                rot = 0.3f * sin(t * 0.7f)
                eyeOpenT = 0.84f; mouthMode = 1; lookYT = 0.05f
                blinkQuiet = 0.45f
                return true
            }
            Move.BREAK -> {
                val b = sin(t * 1.15f)
                sy = 1f + 0.016f * b; sx = 1f - 0.009f * b
                rot = 0.8f * sin(t * 0.6f)
                eyeOpenT = 0.62f; mouthMode = 1; leafBiasT = -7f
                blinkQuiet = 0.6f
                return true
            }
            Move.OOPS -> {
                // a surprised little squash and a gentle recovery
                val e = exp(-2.6 * t).toFloat()
                sy = 1f - 0.07f * e * amp; sx = 1f + 0.04f * e * amp
                rot = 2.2f * sin(2.0 * Math.PI * 7.0 * t).toFloat() * exp(-5.0 * t).toFloat() * amp
                eyeOpenT = 1f + 0.12f * e; pupilT = 1f - 0.16f * e
                mouthMode = if (t < 0.9f) 2 else 0
                leafKick = if (t < 0.4f) 1f else 0f
                return t < 1.5f
            }
            Move.SURPRISE -> {
                val u = (t / 0.35f).coerceIn(0f, 1f)
                dy = s * 0.07f * amp * 4f * u * (1f - u)
                val e = exp(-3.0 * t).toFloat()
                sy = 1f + 0.05f * e * amp
                eyeOpenT = 1.12f; pupilT = 0.82f
                mouthMode = if (t < 0.7f) 2 else 0
                leafKick = if (t < 0.3f) 1f else 0f
                return t < 1.0f
            }
            Move.TAP -> {
                val e = exp(-6.0 * t).toFloat()
                sy = 1f - 0.08f * e * cos(18.0 * t).toFloat() * amp; sx = 1f + (1f - sy) * 0.7f
                rot = 4f * sin(2.0 * Math.PI * 4.0 * t).toFloat() * e * amp
                happyT = if (t in 0.05f..0.3f) 1f else 0f
                leafKick = 0.5f
                return t < 0.5f
            }
            Move.GIGGLE -> {
                // A contained laugh: two soft pulses, eyes close naturally, then settle.
                val env = exp(-1.55f * t)
                val pulse = abs(sin(t * 10.5f)) * env
                sy = 1f - 0.045f * pulse * amp; sx = 1f + 0.032f * pulse * amp
                rot = 2.8f * sin(t * 10.5f) * env * amp
                happyT = if (t < 0.72f) 1f else 0f
                armL = pulse * 0.16f * amp; armR = pulse * 0.16f * amp
                leafKick = pulse * 0.7f
                return t < 1.15f
            }
            Move.LISTEN -> {
                // Attentive, calm posture; gaze shifts toward the person speaking.
                val settle = 1f - exp(-4.5f * t)
                rot = -2.2f * settle * amp
                lookXT = 0.18f * pointDir; lookYT = -0.12f
                eyeOpenT = 0.96f; mouthMode = 1
                sy = 1f + 0.008f * sin(t * 1.4f)
                leafBiasT = -2f
                return true
            }
            Move.SLEEP -> {
                // Slow breathing with a soft head dip and deliberately slow blinks.
                val breath = sin(t * 1.05f)
                sy = 1f + 0.018f * breath; sx = 1f - 0.010f * breath
                rot = 1.2f + 0.7f * sin(t * 0.48f)
                eyeOpenT = 0.34f; mouthMode = 1; lookYT = 0.28f
                leafBiasT = -10f; blinkQuiet = 0.42f
                return true
            }
            Move.PROUD -> {
                val settle = 1f - exp(-5.0f * t)
                rot = -2.8f * settle * amp
                sy = 1f + 0.014f * sin(t * 1.3f)
                eyeOpenT = 0.96f; happyT = 0.72f; mouthMode = 1
                armR = 0.12f * settle * amp; leafBiasT = 4f
                return t < 1.25f
            }
            Move.ENCOURAGE -> {
                // Warm reassuring nod, not a jump or a burst of particles.
                val nod = exp(-2.1f * t) * sin(t * 7.0f)
                sy = 1f - 0.055f * abs(nod) * amp
                sx = 1f + 0.026f * abs(nod) * amp
                rot = 1.5f * nod * amp
                happyT = 0.65f; eyeOpenT = 0.94f
                armR = 0.18f * exp(-1.4f * t) * amp
                leafKick = abs(nod) * 0.45f
                return t < 1.1f
            }
            Move.CELEBRATE -> {
                // A two-beat celebration with anticipation, lift, and a soft landing.
                happyT = if (t < 1.25f) 1f else 0f
                eyeOpenT = 1.06f
                when {
                    t < 0.18f -> { val u = sm(t / 0.18f); sy = 1f - 0.13f * u * amp; sx = 1f + 0.08f * u * amp }
                    t < 0.72f -> { val u = (t - 0.18f) / 0.54f; dy = s * 0.34f * amp * 4f * u * (1f-u); rot = 4f * sin(Math.PI * 2f * u).toFloat() * amp; sy = 1f + 0.10f * (1f-u) * amp; sx = 1f - 0.06f * (1f-u) * amp }
                    t < 1.12f -> { val u = (t - 0.72f) / 0.4f; dy = s * 0.10f * amp * 4f * u * (1f-u); rot = -2f * sin(Math.PI * u).toFloat() * amp; sy = 1f + 0.04f * (1f-u) * amp }
                    else -> { val q=t-1.12f; sy=1f-land(q,0.12f)*amp; sx=1f+(1f-sy)*0.65f }
                }
                armL = (0.72f * sm((t/0.2f).coerceIn(0f,1f)) * (1f-sm(((t-1.0f)/0.25f).coerceIn(0f,1f))) * amp)
                armR = armL; leafKick = if (t < 0.9f) 0.8f else 0f
                stars = if (t in 0.24f..1.35f) ((t-0.24f)/1.11f).coerceIn(0f,1f) else 0f
                spark = bump(t,0.16f,1.2f)*0.65f
                return t < 1.55f
            }
            Move.IDLE -> {
                val b = sin(t * 1.85f)   // ~3.4 s breath
                sy = 1f + 0.014f * b; sx = 1f - 0.008f * b
                rot = 0.5f * sin(t * 0.95f)
                return true
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return true
    }

    /** Eases the face parameters, steps the leaf spring, and runs the blink and glance timers. */
    private fun update(now: Long, dt: Float) {
        val a = 1f - exp(-14.0 * dt).toFloat()
        eyeOpen += (eyeOpenT - eyeOpen) * a
        happy += (happyT - happy) * (1f - exp(-22.0 * dt).toFloat())
        pupil += (pupilT - pupil) * a
        mouthY += (mouthYT - mouthY) * a
        leafBias += (leafBiasT - leafBias) * (1f - exp(-5.0 * dt).toFloat())

        // glances: an occasional small look-around while idle
        if (move == Move.IDLE || move == Move.BREAK) {
            if (now >= nextGlance) {
                glX = (Random.nextFloat() - 0.5f) * 1.2f; glY = (Random.nextFloat() - 0.5f) * 0.6f
                nextGlance = now + Random.nextLong(2200L, 5200L)
                if (Random.nextFloat() < 0.4f) { glX = 0f; glY = 0f }
            }
        } else { glX = 0f; glY = 0f }
        val tx2 = if (move == Move.IDLE || move == Move.BREAK) glX else lookXT
        val ty2 = if (move == Move.IDLE || move == Move.BREAK) glY else lookYT
        val g = 1f - exp(-11.0 * dt).toFloat()
        lookX += (tx2 - lookX) * g; lookY += (ty2 - lookY) * g

        // blinking
        if (blinkWanted && blinkStart == 0L) blinkStart = now
        if (blinkStart == 0L && now >= nextBlink) {
            blinkStart = now
            doubleBlink = Random.nextFloat() < 0.16f
            nextBlink = now + (Random.nextLong(2600L, 5600L) / blinkQuiet).toLong()
        }

        // leaf: a spring that is pushed by the body's vertical speed and sway, so it follows through
        val vy = if (dt > 0f) (dy - prevDy) / dt / s else 0f          // body heights per second
        val vr = if (dt > 0f) (rot - prevRot) / dt else 0f            // degrees per second
        prevDy = dy; prevRot = rot
        val drive = (-vy * 5.5f - vr * 0.055f + leafKick * 3.0f * sin(now / 90.0).toFloat()) * amp
        val target = leafBias + drive.coerceIn(-14f, 14f) + 0.9f * sin(now / 1400.0).toFloat() * (if (calm) 0.5f else 1f)
        val w = 16f; val z = 0.38f
        val acc = w * w * (target - leafA) - 2f * z * w * leafV
        leafV += acc * dt; leafA += leafV * dt
    }

    private fun blinkFactor(now: Long): Float {
        if (blinkStart == 0L) return 1f
        val dur = if (calm) 240f else 190f
        val p = (now - blinkStart) / dur
        if (p >= 1f) {
            if (doubleBlink) { doubleBlink = false; blinkStart = now + 60L } else blinkStart = 0L
            return 1f
        }
        if (p < 0f) return 1f
        val e = if (p < 0.38f) sm(p / 0.38f) else 1f - sm((p - 0.38f) / 0.62f)
        return 1f - e
    }

    // maps a point in layer space (640x640) to view pixels, using the current pose
    private fun map(px: Float, py: Float, withArms: Boolean) {
        val x = mLeft + px * k; val y = mTop + py * k
        val v = py / 640f
        val w = (1f - v) * (1f - v)
        var ox = cx + (x - cx) * sx + lag * s * w
        var oy = feet - (feet - y) * sy
        if (withArms && (armL > 0.002f || armR > 0.002f)) {
            val gl = armW(px, py, 100f, 470f) * armL
            val gr = armW(px, py, 560f, 448f) * armR
            oy -= s * 0.10f * (gl + gr)
            ox += s * 0.05f * (gr - gl)
        }
        tx = ox; ty = oy
    }

    private fun armW(px: Float, py: Float, ax: Float, ay: Float): Float {
        val a = px - ax; val b = py - ay
        return exp(-(a * a + b * b) / (2f * 78f * 78f))
    }

    private fun drawEye(c: Canvas, i: Int, bl: Float) {
        val e = OrangeArt.eye[i]
        val white = L["white$i"] ?: return
        val pu = L["pupil$i"] ?: return
        val hl = L["hl$i"] ?: return
        map(e[0], e[1], false)
        c.save()
        c.translate(tx, ty)
        if (happy > 0.5f || eyeOpen * bl < 0.2f) {
            // closed eye: a soft arc (happy = upward, blink/sleepy = downward)
            c.scale(k * sx, k * sy)
            val w = white.bmp.width * 0.55f; val h = white.bmp.height * 0.2f
            if (happy > 0.5f) { rect.set(-w / 2f, -h * 0.2f, w / 2f, h * 1.8f); c.drawArc(rect, 200f, 140f, false, lid) }
            else { rect.set(-w / 2f, -h * 1.5f, w / 2f, h * 0.5f); c.drawArc(rect, 20f, 140f, false, lid) }
        } else {
            c.scale(k * sx, k * sy * (eyeOpen * bl).coerceIn(0.05f, 1.15f))
            c.drawBitmap(white.bmp, white.x - e[0], white.y - e[1], paint)
            val gx = lookX * 20f; val gy = lookY * 13f
            // pupil
            val pcx = e[2] - e[0] + gx; val pcy = e[3] - e[1] + gy
            c.save()
            c.translate(pcx, pcy)
            c.scale(pupil, pupil)
            c.drawBitmap(pu.bmp, pu.x - e[2], pu.y - e[3], paint)
            c.restore()
            // highlight sits a little ahead of the pupil, so the gaze feels round
            val hcx = pcx + (e[4] - e[2]) * pupil + gx * 0.12f
            val hcy = pcy + (e[5] - e[3]) * pupil + gy * 0.12f
            c.drawBitmap(hl.bmp, hl.x - e[4] + hcx, hl.y - e[5] + hcy, paint)
        }
        c.restore()
    }

    private fun drawMouth(c: Canvas) {
        val m = L["mouth"] ?: return
        map(OrangeArt.mouthCx, OrangeArt.mouthCy, false)
        c.save()
        c.translate(tx, ty)
        c.scale(k * sx, k * sy)
        if (mouthMode == 1) {
            rect.set(-34f, -30f, 34f, 14f)
            c.drawArc(rect, 25f, 130f, false, lipP)
        } else {
            val o = if (mouthMode == 2) 0.55f else 1f
            c.scale(o, if (mouthMode == 2) 0.85f else 1f)
            c.drawBitmap(m.bmp, m.x - OrangeArt.mouthCx, m.y - OrangeArt.mouthCy, paint)
        }
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val base = L["base"]
        if (!art || base == null) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.05f)
        lastFrame = now

        var t = (now - t0) / 1000f * spd
        val running = pose(t)
        blendPose(now)
        if (!running && move == Move.EXIT) {
            busy = false
        } else if (!running) {
            // finished: back to idle (picked up by the loop, if any)
            move = Move.IDLE; t0 = now; t = 0f; pose(0f); busy = false
        } else {
            busy = move == Move.EXIT || (running && move != Move.IDLE && move != Move.FOCUS &&
                move != Move.BREAK && move != Move.THINK && move != Move.POINT &&
                move != Move.LISTEN && move != Move.SLEEP)
        }
        update(now, dt)

        val lm = loopMoves
        if (lm != null && move == Move.IDLE && now >= nextAt && lm.isNotEmpty()) {
            val m = lm[loopIdx % lm.size]; loopIdx++
            play(m, if (loopIdx % 2 == 0) 1f else -1f)
            nextAt = now + (loopGap / spd).toLong() + 900L
        }

        // Whole-character entrance/exit choreography: fade + restrained scale + vertical travel.
        val appear = when (move) {
            Move.ENTER -> sm(((now - t0) / 520f).coerceIn(0f, 1f))
            Move.EXIT -> 1f - sm(((now - t0) / 390f).coerceIn(0f, 1f))
            else -> 1f
        }
        val overallScale = when (move) {
            Move.ENTER -> 0.90f + 0.10f * appear
            Move.EXIT -> 1f - 0.07f * (1f - appear)
            else -> 1f
        }
        val overallLift = when (move) {
            Move.ENTER -> s * 0.10f * (1f - appear)
            Move.EXIT -> s * 0.16f * (1f - appear)
            else -> 0f
        }
        c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255f * appear).toInt().coerceIn(0,255))
        c.save()
        c.translate(cx, feet)
        c.scale(overallScale, overallScale)
        c.translate(-cx, -feet - overallLift)

        // ground shadow, smaller when he is in the air
        val air = (dy / (s * 0.5f)).coerceIn(0f, 1f)
        shadow.color = Color.argb((46 * (1f - 0.6f * air)).toInt(), 90, 40, 10)
        val sw = s * 0.36f * (1f - 0.25f * air)
        rect.set(cx - sw, feet - s * 0.03f, cx + sw, feet + s * 0.05f)
        if (move != Move.ENTER && move != Move.EXIT) c.drawOval(rect, shadow)

        c.save()
        c.translate(0f, -dy)
        c.rotate(rot, cx, feet)

        // body mesh
        var i = 0
        for (r in 0..gridR) {
            for (col in 0..gridC) {
                map(base.x + base.bmp.width * col / gridC.toFloat(), base.y + base.bmp.height * r / gridR.toFloat(), true)
                verts[i++] = tx; verts[i++] = ty
            }
        }
        c.drawBitmapMesh(base.bmp, gridC, gridR, verts, 0, null, 0, paint)

        drawMouth(c)
        val bl = blinkFactor(now)
        drawEye(c, 0, bl); drawEye(c, 1, bl)

        // leaf, hinged at the stem
        val leaf = L["leaf"]
        if (leaf != null) {
            map(OrangeArt.leafPx, OrangeArt.leafPy, false)
            c.save()
            c.translate(tx, ty)
            c.rotate(leafA)
            c.scale(k * sx, k * sy)
            c.drawBitmap(leaf.bmp, leaf.x - OrangeArt.leafPx, leaf.y - OrangeArt.leafPy, paint)
            c.restore()
        }

        // the two spark dashes
        val sp = L["sparks"]
        if (sp != null && spark > 0.02f) {
            val kk = spark.coerceIn(0f, 1f)
            val sc = (0.55f + 0.55f * kk) * (if (calm) 0.7f else 1f)
            c.save()
            c.translate(mLeft + sp.x * k, mTop + (sp.y + sp.bmp.height) * k)
            c.scale(sc * k, sc * k)
            paint.alpha = (255 * kk).toInt()
            c.drawBitmap(sp.bmp, 0f, -sp.bmp.height.toFloat(), paint)
            paint.alpha = 255
            c.restore()
        }
        // thinking dots
        if (thinking) {
            val tt = (now - t0) / 1000f
            for (n in 0..2) {
                val ph = ((tt * 1.8f - n * 0.3f) % 1f + 1f) % 1f
                val kk = sin(Math.PI * ph).toFloat().coerceIn(0f, 1f)
                dot.alpha = (60 + 195 * kk).toInt()
                c.drawCircle(mLeft + s * (0.86f + n * 0.13f), mTop + s * (0.14f - n * 0.07f), s * (0.025f + 0.02f * kk), dot)
            }
        }
        // a few celebration sparkles (only on wins; none while focusing)
        if (stars > 0.01f && !calm) {
            for (n in 0 until 6) {
                val ang = (n * 60f + 20f) * Math.PI.toFloat() / 180f
                val rr = s * (0.35f + 0.45f * stars)
                val px = cx + cos(ang) * rr * 1.05f
                val py = mTop + s * 0.45f + sin(ang) * rr * 0.8f - s * 0.1f
                val sz = s * 0.07f * (1f - stars) * (0.7f + 0.3f * ((n % 3) / 2f)) + s * 0.012f
                starP.color = if (n % 2 == 0) 0xFFFFB27A.toInt() else 0xFFFFD9A0.toInt()
                starP.alpha = (255 * (1f - stars)).toInt()
                c.save(); c.translate(px, py); c.scale(sz, sz); c.drawPath(star, starP); c.restore()
            }
        }
        c.restore()

        // speech bubble
        val lay = layout
        if (lay != null) {
            val bp = ((now - bubbleT0) / 600f).coerceIn(0f, 1f)
            val sc = bubbleSpring.getInterpolation(bp).coerceAtLeast(0.01f)
            val padH = 12f * d; val padV = 9f * d
            val bw = lay.width + padH * 2; val bh = lay.height + padV * 2
            val bL = s * 1.12f; val bT = mTop + s * 0.14f
            val tipX = bL - 7f * d; val tipY = bT + bh * 0.7f
            c.save()
            c.scale(sc, sc, tipX, tipY)
            rect.set(bL, bT, bL + bw, bT + bh)
            tail.reset()
            tail.moveTo(bL + 2f * d, tipY - 7f * d); tail.lineTo(tipX, tipY); tail.lineTo(bL + 2f * d, tipY + 5f * d); tail.close()
            c.drawRoundRect(rect, 16f * d, 16f * d, bubbleP)
            c.drawRoundRect(rect, 16f * d, 16f * d, bubbleEdge)
            c.drawPath(tail, bubbleP)
            c.save()
            c.translate(bL + padH, bT + padV)
            lay.draw(c)
            c.restore()
            c.restore()
        }

        c.restore()
        c.restore()

        // schedule the next frame: full speed only while something is actually moving
        if (attached && winVisible && isShown) {
            val moving = busy || blinkStart != 0L || abs(leafV) > 0.4f || abs(leafA - leafBias) > 0.15f || lm != null
            if (moving) postInvalidateOnAnimation() else postInvalidateDelayed(40)
        }
    }

    // ---- touch: a tap is a small playful reaction, a long press is a surprised one
    private fun hit(e: MotionEvent) = e.x >= mLeft && e.x <= mLeft + s && e.y >= mTop && e.y <= feet

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive || !hit(e)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longFired = false
                removeCallbacks(longPress); postDelayed(longPress, 480L)
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!longFired) {
                    val now = SystemClock.uptimeMillis()
                    // Count deliberate taps even while the short TAP reaction is settling.
                    // The old `!busy` guard made the three-tap GIGGLE effectively unreachable:
                    // TAP lasts 500 ms, longer than the old 450 ms tap cooldown.
                    // Never interrupt a focus/break pose or a higher-priority celebration.
                    if (move != Move.FOCUS && move != Move.BREAK &&
                        (!busy || prio(move) <= prio(Move.TAP))) {
                        if (now - lastTap >= 220L) {
                            tapRun = if (now - lastTap < 2500L) tapRun + 1 else 1
                            lastTap = now
                            if (tapRun >= 3) {
                                tapRun = 0
                                play(Move.GIGGLE, force = true)
                            } else {
                                play(Move.TAP, force = true)
                            }
                        }
                    }
                    if (hasOnClickListeners()) performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); return true }
        }
        return true
    }
}

/** Dimmed screen with a soft, glowing hole around the thing Orange is pointing at. */
class TourDim(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val calm = FocusTheme.reducedMotion(ctx)
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(176, 42, 22, 10) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FocusCardColors.ORANGE_A; style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FocusCardColors.ORANGE_B; style = Paint.Style.STROKE; strokeWidth = 10f * d; alpha = 70 }
    private val path = Path()
    private val cur = RectF()
    private var has = false
    private var anim: ValueAnimator? = null

    fun setTarget(r: RectF?) {
        anim?.cancel()
        if (r == null) { has = false; invalidate(); return }
        val from = RectF(cur)
        val first = !has
        has = true
        if (first || calm) { cur.set(r); invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460
            interpolator = SpringInterpolator(15f, 0.8f, 0.5f)
            addUpdateListener {
                val f = it.animatedValue as Float
                cur.set(from.left + (r.left - from.left) * f, from.top + (r.top - from.top) * f,
                    from.right + (r.right - from.right) * f, from.bottom + (r.bottom - from.bottom) * f)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (!has) { c.drawRect(0f, 0f, w, h, dim); return }
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        val rad = min(cur.height() / 2f, 22f * d)
        path.addRoundRect(cur, rad, rad, Path.Direction.CW)
        c.drawPath(path, dim)
        c.drawRoundRect(cur, rad, rad, glow)
        c.drawRoundRect(cur, rad, rad, ring)
    }
}
