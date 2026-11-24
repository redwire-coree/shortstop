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

enum class Move { ENTER, EXIT, IDLE, HOP, NOD, WAVE, POINT, CHEER, THINK, OOPS, FOCUS, BREAK, TASK, SURPRISE, TAP, GIGGLE, LISTEN, SLEEP, PROUD, ENCOURAGE, CELEBRATE, BEGGING, DEAD, PANIC, CRYING, FINAL_BOSS, OVERLOAD, VICTORY, WAKE, STRETCH }

/**
 * Orange's artwork as separate, aligned layers (body, leaf, eye whites, pupils, highlights, mouth, sparks).
 * They were cut from the 4K source and the gaps behind them were filled in, so the pieces recombine into the
 * original picture. Decoded once and shared.
 */
private const val NECK_X = 330f
private const val NECK_Y = 430f
private const val GROUND_Y = 642f

/** Temporary diagnostics: every opacity/scale/visibility anomaly and every move change is recorded here. */
object OrangeDiag {
    private val lines = ArrayDeque<String>()
    @Synchronized fun log(tag: String, msg: String) {
        val l = String.format(java.util.Locale.US, "%6.1f %-6s %s", (SystemClock.uptimeMillis() % 1_000_000L) / 1000f, tag, msg)
        android.util.Log.d("OrangeDiag", l)
        lines.addLast(l); while (lines.size > 60) lines.removeFirst()
    }
    @Synchronized fun last(n: Int): String = lines.toList().takeLast(n).joinToString("\n")
    fun caller(): String {
        for (e in Throwable().stackTrace) {
            val c = e.className
            if (c.startsWith("com.shortstop") && !c.contains("OrangeDiag") && !c.contains("OrangeView")) return "${(e.fileName ?: "?").removeSuffix(".kt")}:${e.lineNumber}"
        }
        return "?"
    }
}

object OrangeArt {
    class L(val bmp: Bitmap, val x: Float, val y: Float)
    val layers = HashMap<String, L>()
    var leafPx = 315f; var leafPy = 136f
    val eye = arrayOf(FloatArray(6), FloatArray(6))
    var mouthCx = 339f; var mouthCy = 385f
    var ok = false
    /** Shoulder, elbow, wrist and fingertip of each arm in layer space (x, y per joint): left, right. */
    val joints = arrayOf(floatArrayOf(150f, 448f, 105f, 462f, 62f, 478f, 18f, 490f), floatArrayOf(506f, 448f, 553f, 445f, 595f, 440f, 640f, 435f))
    var rigArt = false
    private var tried = false

    fun ensure(c: Context): Boolean {
        if (tried) return ok
        tried = true
        try {
            val rigNames = setOf("armL", "armR", "patchL", "patchR")
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
                        p[0] in rigNames && p.size >= 5 -> {
                            val bmp = c.assets.open("mx_" + p[0] + ".png").use { BitmapFactory.decodeStream(it) }
                            layers[p[0]] = L(bmp, p[1].toFloat(), p[2].toFloat())
                        }
                        (p[0] == "jointL" || p[0] == "jointR") && p.size >= 9 -> {
                            val j = joints[if (p[0] == "jointL") 0 else 1]; for (i in 0..7) j[i] = p[i + 1].toFloat()
                        }
                        p[0] == "leafpivot" -> { leafPx = p[1].toFloat(); leafPy = p[2].toFloat() }
                        p[0] == "eye0" || p[0] == "eye1" -> { val e = eye[p[0].last() - '0']; for (i in 0..5) e[i] = p[i + 1].toFloat() }
                        p[0] == "mouthc" -> { mouthCx = p[1].toFloat(); mouthCy = p[2].toFloat() }
                    }
                }
            }
            ok = names.all { layers.containsKey(it) }
            rigArt = ok && rigNames.all { layers.containsKey(it) }
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
    // A deliberate 4.5-second hold unlocks a quiet, theme-aware magic flourish.
    private var magicAt = 0L
    private var holdStartedAt = 0L
    // Petting is an overlay reaction: it never replaces the active focus/break animation.
    private var petAt = 0L
    private var petSide = 1f
    private var petStrength = 0f
    // A tactile press is a separate, reversible squash overlay; it never changes the active move.
    private var touchHeld = false
    private var dragged = false
    private var xEyes = 0f
    private var slideX = 0f
    // watchdog for the view's own opacity / scale (set by animators in the host screens)
    private var fxSince = 0L
    private var fxLastA = 1f; private var fxLastS = 1f
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF4A2410.toInt() }
    private val tonguePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7C8E.toInt() }
    // hold ring: a rounded silhouette that hugs the whole character, built once
    private val ringPath = Path(); private val ringSeg = Path()
    private val ringMeasure = PathMeasure()
    private val ringPos = FloatArray(2)
    private var ringLen = 0f
    private var ringBuilt = false
    private var downAt = 0L
    /** Ring progress that is actually drawn: follows the hold exactly while pressed, eases back to 0 on release. */
    private var holdShown = 0f
    /** Zzz layer: fades in only while the eyes are really shut, restarts its sequence on every new sleep. */
    private var zzzLevel = 0f
    private var zzzT = 0f
    /** Where a tap-wake goes afterwards (the app sets BREAK during a break so Orange rests instead of hopping about). */
    var wakeTo: Move = Move.IDLE
    var onWoke: (() -> Unit)? = null
    private var touchSquash = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastDragX = 0f
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
    private val scenarioFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val magicGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val magicDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val magicRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    // Articulated foreground limbs: independent shoulder/elbow/wrist motion layered over the
    // original orange body. The base illustration remains untouched.
    private val limbOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = 0xFFCE5B12.toInt()
    }
    private val limbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = 0xFFFFA13A.toInt()
    }
    private val handFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB04B.toInt() }
    private val scenarioStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val sleepText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val gridC = 14; private val gridR = 12
    private val verts = FloatArray((gridC + 1) * (gridR + 1) * 2)
    private val enterSpring = SpringInterpolator(14f, 0.5f, 0.9f)
    private var tx = 0f; private var ty = 0f

    // ---- articulated rig: channels live in OrangeRig.kt, geometry is drawn here
    private val rig = OrangeRig()
    private var armMeshL: RigArm? = null
    private var armMeshR: RigArm? = null
    private val armVerts = FloatArray(11 * 11 * 2)
    private val patchVerts = FloatArray(7 * 5 * 2)
    private val legL = RigLeg(234f, 600f, -1f)
    private val legR = RigLeg(374f, 600f, 1f)
    private val legPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val footPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var legShader: Shader? = null
    private val lidPath = Path()
    // head: everything above the neck follows the head channels
    private var neckX = 0f; private var neckY = 0f
    private var headRotRad = 0f
    private var afterMove: Move? = null
    private var gazeX = 0f; private var gazeY = 0f

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
        // fires at most once per press: longFired is set first and every later path checks it
        if (interactive && touchHeld && !longFired && !dragged && holdStartedAt != 0L && move != Move.EXIT) {
            longFired = true
            val now = SystemClock.uptimeMillis()
            magicAt = now
            petAt = now
            petStrength = 1f
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            // a small happy hop when Orange is free; focus / break / sleep poses are only touched by the pet overlay
            if (move == Move.IDLE || move == Move.TAP) play(Move.HOP, 1f, true)
            invalidate()
        }
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
        Move.BEGGING, Move.DEAD, Move.PANIC, Move.CRYING, Move.FINAL_BOSS, Move.OVERLOAD, Move.VICTORY -> 9
        Move.WAKE -> 7; Move.STRETCH -> 3
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
        OrangeDiag.log("move", "$move -> $m force=$force by ${OrangeDiag.caller()}")
        afterMove = null
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

    /** Plays [m] and, when it finishes by itself, continues straight into [next] (for example WAKE then FOCUS). */
    fun playThen(m: Move, next: Move) {
        play(m, 1f, true)
        afterMove = next
    }

    /** Plays these moves one after another with a pause between. */
    fun loop(moves: List<Move>, gapMs: Long) {
        loopMoves = moves; loopGap = gapMs; loopIdx = 0
        nextAt = SystemClock.uptimeMillis() + 700L
    }

    // ---- showcase / test hooks (they only drive this view; nothing touches the timer or saved data)
    fun blinkNow() { if (blinkStart == 0L) blinkStart = SystemClock.uptimeMillis() }
    fun petNow() { petAt = SystemClock.uptimeMillis(); petSide = 1f; petStrength = 1f; invalidate() }
    /** Simulates a finger held on Orange: the ring fills over 4.5 s and the completion fires. */
    fun previewHold() {
        removeCallbacks(longPress)
        val now = SystemClock.uptimeMillis()
        touchHeld = true; longFired = false; dragged = false; downAt = now; holdStartedAt = now
        postDelayed(longPress, 4500L)
        postDelayed({ if (touchHeld && downAt == now) { touchHeld = false; holdStartedAt = 0L; longFired = false; invalidate() } }, 4900L)
        invalidate()
    }
    /** Back to a clean idle: cancels every pending interaction and restores view opacity and scale. */
    fun resetToIdle() {
        removeCallbacks(longPress)
        touchHeld = false; longFired = false; dragged = false; holdStartedAt = 0L; magicAt = 0L; petAt = 0L
        loopMoves = null
        animate().cancel(); alpha = 1f; scaleX = 1f; scaleY = 1f
        say(null)
        play(Move.IDLE, 1f, true)
    }

    override fun setVisibility(v: Int) {
        if (v != visibility) OrangeDiag.log("vis", "$visibility -> $v from ${OrangeDiag.caller()}")
        super.setVisibility(v)
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
                // Tiny asymmetric arm settling keeps the companion alive without distracting
                // from the timer; wrists continue their own delayed micro-motion in the rig.
                armL = 0.075f + 0.018f * sin(t * 1.05f)
                armR = 0.082f + 0.016f * sin(t * 0.92f + 1.1f)
                blinkQuiet = 0.45f
                return true
            }
            Move.BREAK -> {
                val b = sin(t * 1.15f)
                sy = 1f + 0.016f * b; sx = 1f - 0.009f * b
                rot = 0.8f * sin(t * 0.6f)
                eyeOpenT = 0.62f; mouthMode = 1; leafBiasT = -7f
                armL = 0.10f + 0.025f * sin(t * 0.8f)
                armR = 0.11f + 0.022f * sin(t * 0.72f + 0.8f)
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
            Move.WAKE, Move.STRETCH -> {
                // Wake-up performance. WAKE starts asleep; STRETCH joins at the same stretch.
                // 0-1.0 eyes open slowly while the head lifts; 1.0-2.5 yawn and a big stretch;
                // 2.5-3.4 shake it off; then an attentive blink and nod (WAKE ends at 4.0 s).
                val tt = if (move == Move.WAKE) t else t + 1.0f
                val rise = sm((tt / 1.0f).coerceIn(0f, 1f))
                eyeOpenT = if (tt < 1.0f) 0.07f + 0.5f * rise else 1f
                val st = bump(tt, 1.0f, 2.5f)
                sy = 1f + 0.075f * st * amp; sx = 1f - 0.04f * st * amp
                dy = s * 0.03f * st * amp
                mouthMode = 0; mouthYT = 1f + 0.85f * bump(tt, 1.1f, 2.2f)
                happyT = if (tt in 1.25f..2.05f) 1f else 0f
                leafBiasT = -9f * (1f - rise) + 7f * st
                val sh = tt - 2.5f
                if (tt > 2.5f && tt < 3.5f) {
                    sy = 1f - 0.04f * exp(-5.0 * sh).toFloat() * cos(14.0 * sh).toFloat() * amp
                    rot = 3f * sin(2.0 * Math.PI * 3.4 * sh).toFloat() * exp(-3.4 * sh).toFloat() * amp
                    leafKick = 0.9f * exp(-3.0 * sh).toFloat()
                }
                if (tt in 3.4f..3.52f) blinkWanted = true
                if (tt > 3.5f) { val nn = sm(((tt - 3.5f) / 0.35f).coerceIn(0f, 1f)); sy = 1f - 0.02f * sin(Math.PI * nn).toFloat() }
                blinkQuiet = 0.2f
                return tt < (if (move == Move.WAKE) 4.0f else 3.6f)
            }
            Move.SLEEP -> {
                // Slow breathing with a soft head dip and deliberately slow blinks.
                val breath = sin(t * 1.05f)
                sy = 1f + 0.018f * breath; sx = 1f - 0.010f * breath
                rot = 1.2f + 0.7f * sin(t * 0.48f)
                eyeOpenT = 0.07f; mouthMode = 1; lookYT = 0.28f
                leafBiasT = -10f; blinkQuiet = 0.12f
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
            Move.BEGGING -> {
                // Sustained pleading: a soft tremble, forward lean, wide eyes and reaching arms.
                val pulse = sin(t * 9.0f)
                rot = -5f + 1.2f * pulse * amp; dy = -s * (0.018f + 0.012f * abs(pulse))
                sx = 1f + 0.012f * pulse; sy = 1f - 0.018f * abs(pulse)
                armL = 0.72f + 0.08f * pulse; armR = 0.78f - 0.08f * pulse
                eyeOpenT = 1.18f; pupilT = 0.72f; lookYT = -0.12f; mouthMode = 2
                leafKick = 0.35f * abs(pulse); blinkQuiet = 0.35f
                return true
            }
            Move.DEAD -> {
                // Five beats: realise -> energy drains -> topples like a skittle -> lies there -> sits back up.
                val total = 6.2f
                fun eob(v: Float): Float { val u = v - 1f; return 1f + 2.4f * u * u * u + 1.4f * u * u }
                val rotV: Float = when {
                    t < 0.55f -> 3f * sin(t * 38f) * (1f - t / 0.55f)
                    t < 1.45f -> { val u = (t - 0.55f) / 0.9f; 5f * u * sin(u * 6.9f) + 2f * u }
                    t < 2.2f -> { val u = (t - 1.45f) / 0.75f; 5f + 79f * u.pow(2.2f) }
                    t < 2.9f -> { val q = t - 2.2f; 84f - 7f * exp(-6f * q) * abs(sin(q * 11f)) }
                    t < 4.9f -> 84f + 0.5f * sin(t * 1.3f)
                    t < 6.2f -> { val u = (t - 4.9f) / 1.3f; if (u < 0.2f) 84f + 4f * sm(u / 0.2f) else 88f * (1f - eob(((u - 0.2f) / 0.8f).coerceIn(0f, 1f))) }
                    else -> 0f
                }
                rot = rotV
                val rr = Math.toRadians(rotV.toDouble()).toFloat()
                slideX = -0.45f * s * sin(rr)
                dy = s * 0.40f * sin(rr).coerceAtLeast(0f) + (if (t < 0.5f) s * 0.03f * bump(t, 0f, 0.5f) else 0f)
                val landQ = (t - 2.2f).coerceAtLeast(0f)
                if (t in 2.2f..3.4f) { val e = exp(-8f * landQ); sx = 1f + 0.10f * e; sy = 1f - 0.08f * e }
                else if (t < 1.45f) { sy = 1f + 0.04f * bump(t, 0f, 0.55f) - 0.06f * sm(((t - 0.55f) / 0.9f).coerceIn(0f, 1f)) }
                xEyes = if (t in 2.2f..4.95f) 1f else 0f
                val drain = sm(((t - 0.55f) / 0.9f).coerceIn(0f, 1f))
                val wake = sm(((t - 4.95f) / 0.5f).coerceIn(0f, 1f))
                eyeOpenT = when {
                    t < 0.55f -> 1.22f
                    t < 2.2f -> 1.22f - 0.7f * drain
                    t < 4.95f -> 1f
                    else -> 0.12f + 0.88f * wake
                }
                pupilT = if (t < 0.55f) 0.5f else if (t < 2.2f) 0.5f - 0.2f * drain else 1f
                mouthMode = if (t < 0.55f) 2 else if (t < 2.2f) 1 else if (t < 4.95f) 2 else 0
                leafBiasT = -4f - 22f * drain
                leafKick = if (t in 2.2f..3.0f) 1f else 0f
                blinkQuiet = 0.02f
                return t < total
            }
            Move.PANIC -> {
                val pulse = sin(t * 15f); val fade = exp(-0.7f * t)
                rot = 5.5f * pulse * fade * amp; dy = s * 0.025f * abs(pulse) * fade
                sx = 1f + 0.025f * abs(pulse); sy = 1f - 0.035f * abs(pulse)
                eyeOpenT = 1.24f; pupilT = 0.48f; mouthMode = 2
                armL = 0.42f + 0.2f * pulse; armR = 0.42f - 0.2f * pulse
                leafKick = 0.8f; blinkQuiet = 0.2f
                return t < 2.4f
            }
            Move.CRYING -> {
                val sob = abs(sin(t * 4.2f))
                rot = 4f + 1.2f * sin(t * 2.1f); dy = s * 0.025f * sob
                sx = 1f - 0.018f * sob; sy = 1f + 0.012f * sob
                eyeOpenT = 0.55f + 0.08f * sob; happyT = 0f; mouthMode = 2
                armL = 0.22f + 0.04f * sob; armR = 0.22f + 0.04f * sob
                leafBiasT = -9f; blinkQuiet = 0.28f
                return true
            }
            Move.FINAL_BOSS -> {
                val pulse = sin(t * 2.7f)
                rot = -3f + pulse * 1.1f; sy = 1.02f + 0.012f * pulse; sx = 0.99f
                eyeOpenT = 0.78f; pupilT = 1.2f; lookYT = 0.12f; mouthMode = 1
                armL = 0.68f; armR = 0.68f; leafBiasT = 8f; spark = 0.35f + 0.25f * abs(pulse)
                return true
            }
            Move.OVERLOAD -> {
                val wobble = sin(t * 7.5f)
                rot = wobble * 8f * amp; sx = 1f + 0.045f * abs(wobble); sy = 1f - 0.055f * abs(wobble)
                eyeOpenT = 1.18f; pupilT = 0.22f; mouthMode = 2
                armL = 0.58f + 0.16f * wobble; armR = 0.58f - 0.16f * wobble
                leafKick = 0.9f; thinking = true
                return true
            }
            Move.VICTORY -> {
                val pulse = sin(t * 5f)
                dy = s * (0.035f + 0.025f * (0.5f + 0.5f * pulse))
                sx = 1f - 0.018f * abs(pulse); sy = 1f + 0.025f * abs(pulse)
                rot = 2.5f * sin(t * 2.5f); happyT = 1f; eyeOpenT = 1.05f
                armL = 0.78f; armR = 0.78f; leafKick = 0.6f; stars = 0.85f
                return true
            }
            Move.IDLE -> {
                val b = sin(t * 1.85f)   // ~3.4 s breath
                sy = 1f + 0.014f * b; sx = 1f - 0.008f * b
                rot = 0.5f * sin(t * 0.95f)
                armL = 0.055f + 0.012f * sin(t * 0.9f)
                armR = 0.060f + 0.010f * sin(t * 0.82f + 1.0f)
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
        // Semi-implicit spring integration in bounded substeps. This keeps the leaf stable
        // after dropped frames / app resume instead of injecting a visible velocity spike.
        val frameDt = dt.coerceIn(0.001f, 0.05f)
        val steps = ceil(frameDt / (1f / 120f)).toInt().coerceIn(1, 6)
        val h = frameDt / steps
        repeat(steps) {
            val acc = w * w * (target - leafA) - 2f * z * w * leafV
            leafV = (leafV + acc * h).coerceIn(-180f, 180f)
            leafA = (leafA + leafV * h).coerceIn(-38f, 38f)
        }
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
    private fun headWeight(py: Float): Float {
        val u = ((NECK_Y - py) / 220f).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    private fun prepareHead() {
        val x = mLeft + NECK_X * k; val y = mTop + NECK_Y * k
        val w = (1f - NECK_Y / 640f) * (1f - NECK_Y / 640f)
        neckX = cx + (x - cx) * sx + lag * s * w
        neckY = feet - (feet - y) * sy
        headRotRad = Math.toRadians(rig[RigCh.HEAD_ROT].toDouble()).toFloat()
    }

    private fun map(px: Float, py: Float, @Suppress("UNUSED_PARAMETER") withArms: Boolean) {
        val x = mLeft + px * k; val y = mTop + py * k
        val v = py / 640f
        val w = (1f - v) * (1f - v)
        var ox = cx + (x - cx) * sx + lag * s * w
        var oy = feet - (feet - y) * sy
        val hw = headWeight(py)
        if (hw > 0f) {
            val ang = headRotRad * hw
            val ca = cos(ang); val sa = sin(ang)
            val dx = ox - neckX; val dyy = oy - neckY
            ox = neckX + dx * ca - dyy * sa + rig[RigCh.HEAD_X] * k * hw
            oy = neckY + dx * sa + dyy * ca + rig[RigCh.HEAD_Y] * k * hw
        }
        tx = ox; ty = oy
    }

    // ---- rig choreography: writes targets only; OrangeRig springs do the moving -----------------------------------------

    private fun rigPose(@Suppress("UNUSED_PARAMETER") t: Float, now: Long) {
        rig.begin()
        val b = rig.base; val a = rig.add
        val clock = now / 1000f
        val tt = (now - t0) / 1000f * spd
        val m = amp
        // 1) legacy arm lift from the move table becomes shoulder angle, with the forearm and wrist following
        val lL = (armL - 0.05f).coerceAtLeast(0f) * 118f
        val lR = (armR - 0.05f).coerceAtLeast(0f) * 118f
        b[RigCh.ARM_L_SH] = lL; b[RigCh.ARM_R_SH] = lR
        b[RigCh.ARM_L_EL] = lL * 0.26f; b[RigCh.ARM_R_EL] = lR * 0.26f
        b[RigCh.ARM_L_WR] = lL * 0.10f; b[RigCh.ARM_R_WR] = lR * 0.10f
        // 2) every move can add its own body language on top
        when (move) {
            Move.IDLE, Move.ENTER, Move.EXIT, Move.LISTEN, Move.ENCOURAGE -> {
                val br = sin(clock * 1.5f)
                b[RigCh.HEAD_ROT] = 0.9f * sin(clock * 0.55f) * m
                b[RigCh.HEAD_Y] = 0.9f * br * m
                b[RigCh.ARM_L_SH] += 1.4f * br * m; b[RigCh.ARM_R_SH] += 1.4f * br * m
                b[RigCh.ARM_L_WR] += 2.0f * sin(clock * 1.1f) * m; b[RigCh.ARM_R_WR] += 2.0f * sin(clock * 1.0f + 1.2f) * m
                b[RigCh.FOOT_L_X] = 1.2f * sin(clock * 0.4f) * m; b[RigCh.FOOT_R_X] = -1.2f * sin(clock * 0.4f) * m
                b[RigCh.LEG_L_ANKLE] = 1.5f * sin(clock * 0.8f) * m; b[RigCh.LEG_R_ANKLE] = -1.5f * sin(clock * 0.8f + 0.7f) * m
                if (move == Move.LISTEN) { b[RigCh.HEAD_ROT] -= 4f; b[RigCh.PUPIL_X] = 0.25f * pointDir }
                if (move == Move.IDLE) {
                    // one small, quiet variation at a time with long pauses between them
                    val cyc = clock / 7.3f
                    val slot = cyc.toInt(); val f = cyc - slot
                    val bumpV = if (f in 0.25f..0.60f) sin(Math.PI * (f - 0.25f) / 0.35f).toFloat() else 0f
                    val side = if (slot % 2 == 0) 1f else -1f
                    when (slot % 4) {
                        0 -> { b[RigCh.PUPIL_X] += 0.38f * side * bumpV; b[RigCh.HEAD_ROT] += 1.6f * side * bumpV }
                        1 -> { b[RigCh.HEAD_X] += 1.8f * side * bumpV; b[RigCh.FOOT_L_X] += 2f * side * bumpV; b[RigCh.FOOT_R_X] += 2f * side * bumpV }
                        2 -> { b[RigCh.MOUTH_SMILE] += 0.45f * bumpV; b[RigCh.LEAF_BEND] += 4f * side * bumpV }
                        else -> Unit
                    }
                }
            }
            Move.FOCUS -> {
                // settled and attentive: head a touch forward, brows set, arms tucked, feet planted
                val br = sin(clock * 0.95f)
                b[RigCh.HEAD_Y] = 2.4f + 0.7f * br * m; b[RigCh.HEAD_ROT] = -1.1f
                b[RigCh.ARM_L_SH] = lL - 4f + 0.9f * br * m; b[RigCh.ARM_R_SH] = lR - 4f + 0.9f * br * m
                b[RigCh.LID_L_COVER] = 0.10f; b[RigCh.LID_R_COVER] = 0.10f
                b[RigCh.LID_L_TILT] = 0.20f; b[RigCh.LID_R_TILT] = 0.20f
                b[RigCh.PUPIL_Y] = 0.04f
                b[RigCh.FOOT_L_X] = -1f; b[RigCh.FOOT_R_X] = 1f
            }
            Move.BREAK -> {
                // relaxed: head tilted, shoulders dropped, soft half-lidded eyes, feet apart
                val br = sin(clock * 0.8f)
                b[RigCh.HEAD_ROT] = 3.6f + 0.8f * sin(clock * 0.37f) * m; b[RigCh.HEAD_Y] = 1.5f + 0.8f * br * m
                b[RigCh.ARM_L_SH] = lL - 8f + 1.0f * br * m; b[RigCh.ARM_R_SH] = lR - 8f + 1.0f * br * m
                b[RigCh.LID_L_COVER] = 0.22f; b[RigCh.LID_R_COVER] = 0.22f
                b[RigCh.LID_L_TILT] = -0.12f; b[RigCh.LID_R_TILT] = -0.12f
                b[RigCh.MOUTH_SMILE] = 0.5f
                b[RigCh.FOOT_L_X] = -5f; b[RigCh.FOOT_R_X] = 5f
                b[RigCh.LEG_L_ANKLE] = -3f; b[RigCh.LEG_R_ANKLE] = -3f
            }
            Move.SLEEP -> {
                // dozing: head drooped to one side, arms limp, eyes shut, feet drawn in
                val br = sin(clock * 1.05f)
                b[RigCh.HEAD_ROT] = 8f + 0.9f * sin(clock * 0.48f) * m; b[RigCh.HEAD_Y] = 7f + 0.8f * br * m
                b[RigCh.ARM_L_SH] = -15f + 1.0f * br * m; b[RigCh.ARM_R_SH] = -15f + 1.0f * br * m
                b[RigCh.ARM_L_WR] = -6f; b[RigCh.ARM_R_WR] = -6f
                b[RigCh.LID_L_COVER] = 0.95f; b[RigCh.LID_R_COVER] = 0.95f
                b[RigCh.MOUTH_OPEN] = 0.7f; b[RigCh.MOUTH_SMILE] = 0.2f
                b[RigCh.FOOT_L_X] = -3f; b[RigCh.FOOT_R_X] = 3f
                b[RigCh.LEG_L_ANKLE] = -6f; b[RigCh.LEG_R_ANKLE] = -6f
            }
            Move.DEAD -> {
                val drain = sm(((tt - 0.55f) / 0.9f).coerceIn(0f, 1f))
                val lying = sm(((tt - 2.0f) / 0.4f).coerceIn(0f, 1f)) * (1f - sm(((tt - 4.9f) / 0.3f).coerceIn(0f, 1f)))
                val jolt = bump(tt, 0f, 0.55f)
                val flail = bump(tt, 1.45f, 2.3f)
                b[RigCh.HEAD_Y] = -3f * jolt + 5f * drain
                b[RigCh.HEAD_ROT] = 6f * drain + 8f * lying
                // arms: flung up in shock, droop as the energy goes, flail on the way down, then flop
                b[RigCh.ARM_L_SH] = 38f * jolt - 12f * drain + 30f * flail * sin(tt * 20f) - 20f * lying
                b[RigCh.ARM_R_SH] = 38f * jolt - 12f * drain - 30f * flail * sin(tt * 20f + 1f) - 20f * lying
                b[RigCh.ARM_L_WR] = -8f * lying; b[RigCh.ARM_R_WR] = -8f * lying
                b[RigCh.LID_L_COVER] = 0.35f * drain * (1f - lying); b[RigCh.LID_R_COVER] = 0.35f * drain * (1f - lying)
                b[RigCh.LID_L_TILT] = -0.7f * drain; b[RigCh.LID_R_TILT] = -0.7f * drain
                b[RigCh.MOUTH_SMILE] = -0.9f * drain
                b[RigCh.FOOT_L_LIFT] = 5f * flail; b[RigCh.FOOT_R_LIFT] = 3f * flail
                b[RigCh.FOOT_L_X] = -4f * lying; b[RigCh.FOOT_R_X] = 4f * lying
                b[RigCh.LEG_L_ANKLE] = -12f * lying; b[RigCh.LEG_R_ANKLE] = 14f * lying
            }
            Move.WAKE, Move.STRETCH -> {
                val w2 = if (move == Move.WAKE) tt else tt + 1.0f
                // starts as the sleeping pose and releases it
                val sleepy = 1f - sm((w2 / 1.0f).coerceIn(0f, 1f))
                b[RigCh.HEAD_ROT] = 8f * sleepy; b[RigCh.HEAD_Y] = 7f * sleepy
                b[RigCh.ARM_L_SH] = -15f * sleepy; b[RigCh.ARM_R_SH] = -15f * sleepy
                b[RigCh.LID_L_COVER] = 0.95f * sleepy; b[RigCh.LID_R_COVER] = 0.95f * sleepy
                // 0.25-1.0: one hand rubs the eye
                val rub = bump(w2, 0.2f, 1.05f)
                b[RigCh.ARM_L_SH] += 88f * rub; b[RigCh.ARM_L_EL] += 26f * rub
                b[RigCh.ARM_L_WR] += 10f * rub * sin(w2 * 22f)
                // 1.0-2.5: big two-armed stretch with open hands, head back, up on tiptoe
                val st = sm(((w2 - 0.95f) / 0.45f).coerceIn(0f, 1f)) * (1f - sm(((w2 - 2.1f) / 0.5f).coerceIn(0f, 1f)))
                b[RigCh.ARM_L_SH] += 104f * st; b[RigCh.ARM_R_SH] += 104f * st
                b[RigCh.ARM_L_EL] += 6f * st; b[RigCh.ARM_R_EL] += 6f * st
                b[RigCh.ARM_L_WR] += 16f * st; b[RigCh.ARM_R_WR] += 16f * st
                b[RigCh.ARM_L_HAND] = 1f + 0.18f * st; b[RigCh.ARM_R_HAND] = 1f + 0.18f * st
                b[RigCh.HEAD_ROT] += -6f * st; b[RigCh.HEAD_Y] += -5f * st
                b[RigCh.FOOT_L_LIFT] = 5f * st; b[RigCh.FOOT_R_LIFT] = 5f * st
                b[RigCh.LEG_L_ANKLE] += -14f * st; b[RigCh.LEG_R_ANKLE] += -14f * st
                b[RigCh.MOUTH_OPEN] = 1f + 0.5f * st
                // 2.5-3.4: shake it off, then eyes wander once and lock forward
                val sh = (w2 - 2.5f).coerceAtLeast(0f)
                if (w2 > 2.5f) {
                    b[RigCh.HEAD_ROT] += 6f * sin(2.0 * Math.PI * 3.4 * sh).toFloat() * exp(-3.4 * sh).toFloat()
                    b[RigCh.PUPIL_X] = 0.55f * sin(sh * 4.2f) * (1f - sm(((w2 - 3.2f) / 0.4f).coerceIn(0f, 1f)))
                }
                if (w2 > 3.55f) { val nod = bump(w2, 3.55f, 3.95f); b[RigCh.HEAD_Y] += 2.5f * nod; b[RigCh.HEAD_ROT] += -1.1f * sm(((w2 - 3.55f) / 0.3f).coerceIn(0f, 1f)) }
            }
            Move.TAP -> {
                val e = exp(-6.0 * tt).toFloat()
                b[RigCh.ARM_L_SH] += 12f * e * m; b[RigCh.ARM_R_SH] += 12f * e * m
                b[RigCh.ARM_L_WR] += 8f * sin(22f * tt) * e * m; b[RigCh.ARM_R_WR] += 8f * sin(22f * tt + 1f) * e * m
                b[RigCh.HEAD_ROT] = 3f * sin(24f * tt) * e * m
                b[RigCh.FOOT_L_LIFT] = 3f * bump(tt, 0f, 0.22f) * m; b[RigCh.FOOT_R_LIFT] = 3f * bump(tt, 0.05f, 0.27f) * m
            }
            Move.THINK -> {
                // one hand comes up toward the chin, head tilted, eyes drift up and away
                val u = sm((tt / 0.45f).coerceIn(0f, 1f))
                b[RigCh.ARM_R_SH] = 62f * u; b[RigCh.ARM_R_EL] = 34f * u; b[RigCh.ARM_R_WR] = 12f * u
                b[RigCh.HEAD_ROT] = -5f * u + 1.2f * sin(clock * 0.9f) * m
                b[RigCh.PUPIL_X] = -0.45f * u; b[RigCh.PUPIL_Y] = -0.40f * u
                b[RigCh.LID_L_COVER] = 0.08f * u; b[RigCh.LID_R_COVER] = 0.08f * u
            }
            Move.WAVE -> {
                // the raised arm waves from the forearm and wrist; the shoulder barely moves
                val w = 2.0f * Math.PI.toFloat() * 2.35f
                val ph = sin(w * tt); val lagged = sin(w * tt - 0.9f)
                b[RigCh.ARM_R_EL] += 15f * ph * (lR / 70f).coerceIn(0f, 1f); b[RigCh.ARM_R_WR] += 22f * lagged * (lR / 70f).coerceIn(0f, 1f)
                b[RigCh.ARM_L_EL] += 15f * ph * (lL / 70f).coerceIn(0f, 1f); b[RigCh.ARM_L_WR] += 22f * lagged * (lL / 70f).coerceIn(0f, 1f)
                b[RigCh.HEAD_ROT] = 2.5f * sin(w * tt - 0.4f) * (lR / 70f).coerceIn(0f, 1f)
            }
            Move.CHEER, Move.CELEBRATE, Move.VICTORY, Move.HOP -> {
                val up = ((lL + lR) / 160f).coerceIn(0f, 1f)
                b[RigCh.ARM_L_WR] += 14f * sin(tt * 9f) * up; b[RigCh.ARM_R_WR] += 14f * sin(tt * 9f + 1.3f) * up
                b[RigCh.ARM_L_EL] += 8f * sin(tt * 9f - 0.8f) * up; b[RigCh.ARM_R_EL] += 8f * sin(tt * 9f + 0.5f) * up
                b[RigCh.HEAD_ROT] = 3f * sin(tt * 7f) * up
            }
            else -> {
                b[RigCh.HEAD_ROT] = 0.8f * sin(clock * 0.6f) * m
            }
        }
        // 3) legs react to the body: feet leave the ground in the air, knees bend on landing
        val lift = (dy / k) * 0.5f
        a[RigCh.FOOT_L_LIFT] += lift; a[RigCh.FOOT_R_LIFT] += lift * 0.85f
        val land = ((1f - sy) * 160f).coerceIn(0f, 18f)
        a[RigCh.LEG_L_KNEE] += land; a[RigCh.LEG_R_KNEE] += land
        a[RigCh.FOOT_L_X] += -land * 0.25f; a[RigCh.FOOT_R_X] += land * 0.25f
        // 4) overlays that sit on top of whatever the move is doing
        if (touchSquash > 0.01f) {
            a[RigCh.ARM_L_SH] += 6f * touchSquash; a[RigCh.ARM_R_SH] += 6f * touchSquash
            a[RigCh.HEAD_Y] += 2.2f * touchSquash
        }
        val petAge = if (petAt == 0L) 2f else ((now - petAt) / 1000f).coerceAtLeast(0f)
        val petPulse = if (petAge < 0.82f) sin(Math.PI * (petAge / 0.82f)).toFloat() else 0f
        if (petPulse > 0.001f) {
            a[RigCh.HEAD_ROT] += petSide * 5f * petPulse
            if (petSide < 0f) { a[RigCh.ARM_L_SH] += 18f * petPulse; a[RigCh.ARM_L_WR] += 8f * petPulse }
            else { a[RigCh.ARM_R_SH] += 18f * petPulse; a[RigCh.ARM_R_WR] += 8f * petPulse }
            a[RigCh.MOUTH_SMILE] += 0.6f * petPulse
        }
        if (holdShown > 0f) {
            val hs = holdShown
            a[RigCh.ARM_L_SH] += 10f * hs; a[RigCh.ARM_R_SH] += 10f * hs
            a[RigCh.LID_L_COVER] += 0.10f * hs; a[RigCh.LID_R_COVER] += 0.10f * hs
            a[RigCh.HEAD_Y] += 1.5f * hs
        }
        if (magicAt != 0L) {
            val ca = (now - magicAt) / 1000f
            val up = bump(ca, 0.1f, 0.9f)
            a[RigCh.ARM_L_SH] += 96f * up; a[RigCh.ARM_R_SH] += 96f * up
            a[RigCh.ARM_L_EL] += 10f * up; a[RigCh.ARM_R_EL] += 10f * up
            a[RigCh.HEAD_ROT] += -5f * up
            a[RigCh.FOOT_L_LIFT] += 4f * up; a[RigCh.FOOT_R_LIFT] += 4f * up
        }
        if (touchHeld) {
            // the finger is on him: eyes and head follow it
            a[RigCh.PUPIL_X] += gazeX * 0.85f; a[RigCh.PUPIL_Y] += gazeY * 0.6f
            a[RigCh.HEAD_ROT] += gazeX * 4f
            // holding for the long-press: he charges up (arms lift, leaf quivers, eyes go wide)
            if (!longFired && holdStartedAt != 0L) {
                val p = (((now - holdStartedAt) - 500L) / 4000f).coerceIn(0f, 1f)
                if (p > 0f) {
                    a[RigCh.ARM_L_SH] += 34f * p; a[RigCh.ARM_R_SH] += 34f * p
                    a[RigCh.LEAF_BEND] += 5f * p * sin(now / 55f)
                    a[RigCh.EYE_L_OPEN] += 0.08f * p; a[RigCh.EYE_R_OPEN] += 0.08f * p
                    a[RigCh.MOUTH_SMILE] += 0.7f * p
                }
            }
        }
    }

    // ---- rig drawing ----------------------------------------------------------------------------------------

    private fun ensureRigGeometry() {
        if (armMeshL != null || !OrangeArt.rigArt) return
        val l = L["armL"]; val r = L["armR"]
        if (l != null && r != null) {
            armMeshL = RigArm(l.bmp, l.x, l.y, OrangeArt.joints[0], 1f)
            armMeshR = RigArm(r.bmp, r.x, r.y, OrangeArt.joints[1], -1f)
        }
        val g = LinearGradient(0f, feet - s * 0.02f, 0f, feet + s * 0.09f, 0xFFFF9B2A.toInt(), 0xFFFB6208.toInt(), Shader.TileMode.CLAMP)
        legShader = g
        legPaint.shader = g; footPaint.shader = g
    }

    /** Legs sit behind the body, so only the feet and a little shin ever show; they bend with the body. */
    private fun drawRigLegs(c: Canvas) {
        ensureRigGeometry()
        val r = rig.cur
        legL.solve(-6f + r[RigCh.FOOT_L_X], r[RigCh.FOOT_L_LIFT], r[RigCh.LEG_L_HIP], r[RigCh.LEG_L_KNEE], r[RigCh.LEG_L_ANKLE], GROUND_Y)
        legR.solve(6f + r[RigCh.FOOT_R_X], r[RigCh.FOOT_R_LIFT], r[RigCh.LEG_R_HIP], r[RigCh.LEG_R_KNEE], r[RigCh.LEG_R_ANKLE], GROUND_Y)
        for (n in 0..1) {
            val lg = if (n == 0) legL else legR
            val hx = if (n == 0) 234f else 374f
            legPaint.strokeWidth = 25f * k
            map(hx, 600f, false); val ax = tx; val ay = ty
            map(lg.kneeX, lg.kneeY, false); val bx = tx; val by = ty
            map(lg.ankleX, lg.ankleY, false); val cx2 = tx; val cy2 = ty
            c.drawLine(ax, ay, bx, by, legPaint)
            c.drawLine(bx, by, cx2, cy2, legPaint)
            c.save()
            c.translate(cx2 + (if (n == 0) -3f else 3f) * k, cy2 + 4f * k)
            c.rotate(lg.footAngle)
            rect.set(-27f * k, -14f * k, 27f * k, 14f * k)
            c.drawOval(rect, footPaint)
            c.restore()
        }
    }

    /** The corner slivers of the original silhouette: full strength at rest, gone as soon as an arm moves. */
    private fun drawRigPatches(c: Canvas) {
        val r = rig.cur
        for (n in 0..1) {
            val p = L[if (n == 0) "patchL" else "patchR"] ?: continue
            val sh = if (n == 0) r[RigCh.ARM_L_SH] else r[RigCh.ARM_R_SH]
            val el = if (n == 0) r[RigCh.ARM_L_EL] else r[RigCh.ARM_R_EL]
            val a = (1f - (abs(sh) / 9f + abs(el) / 22f)).coerceIn(0f, 1f)
            if (a < 0.02f) continue
            var i = 0
            for (rr in 0..4) for (col in 0..6) {
                map(p.x + p.bmp.width * col / 6f, p.y + p.bmp.height * rr / 4f, false)
                patchVerts[i++] = tx; patchVerts[i++] = ty
            }
            paint.alpha = (255 * a).toInt()
            c.drawBitmapMesh(p.bmp, 6, 4, patchVerts, 0, null, 0, paint)
            paint.alpha = 255
        }
    }

    /** Both arms: each is a three-bone skin of the original arm artwork, in front of the body and the face. */
    private fun drawRigArms(c: Canvas) {
        val r = rig.cur
        for (n in 0..1) {
            val arm = (if (n == 0) armMeshL else armMeshR) ?: continue
            val o = if (n == 0) RigCh.ARM_L_SH else RigCh.ARM_R_SH
            arm.deform(r[o], r[o + 1], r[o + 2], r[o + 3])
            var i = 0
            for (v in 0 until arm.count) {
                map(arm.pos[v * 2], arm.pos[v * 2 + 1], false)
                armVerts[i++] = tx; armVerts[i++] = ty
            }
            c.drawBitmapMesh(arm.bmp, arm.cols, arm.rows, armVerts, 0, null, 0, paint)
        }
    }

    private fun drawEye(c: Canvas, i: Int, bl: Float) {
        val e = OrangeArt.eye[i]
        val white = L["white$i"] ?: return
        val pu = L["pupil$i"] ?: return
        val hl = L["hl$i"] ?: return
        map(e[0], e[1], false)
        val rg = rig.cur
        val op = (eyeOpen * bl * rg[RigCh.EYE_L_OPEN + i]).coerceAtLeast(0f)
        c.save()
        c.translate(tx, ty)
        c.rotate(rg[RigCh.HEAD_ROT] * headWeight(e[1]))
        if (xEyes > 0.5f) {
            c.scale(k * sx, k * sy)
            xPaint.strokeWidth = 11f
            c.drawLine(-27f, -27f, 27f, 27f, xPaint); c.drawLine(27f, -27f, -27f, 27f, xPaint)
        } else if (happy > 0.5f || op < 0.2f) {
            // closed eye: a soft arc (happy = upward, blink/sleepy = downward)
            c.scale(k * sx, k * sy)
            val w = white.bmp.width * 0.55f; val h = white.bmp.height * 0.2f
            if (happy > 0.5f) { rect.set(-w / 2f, -h * 0.2f, w / 2f, h * 1.8f); c.drawArc(rect, 200f, 140f, false, lid) }
            else { rect.set(-w / 2f, -h * 1.5f, w / 2f, h * 0.5f); c.drawArc(rect, 20f, 140f, false, lid) }
        } else {
            c.scale(k * sx, k * sy * op.coerceIn(0.05f, 1.15f))
            // lids: how much of the eye is covered from above, and how slanted the lid line is
            val cover = rg[RigCh.LID_L_COVER + i].coerceIn(0f, 1f); val tilt = rg[RigCh.LID_L_TILT + i].coerceIn(-1f, 1f)
            if (cover > 0.01f || abs(tilt) > 0.02f) {
                val wl = white.x - e[0]; val wt = white.y - e[1]; val ww = white.bmp.width.toFloat(); val wh = white.bmp.height.toFloat()
                val yc = wt + wh * cover
                val dyt = tilt * wh * 0.30f
                val yIn = yc + dyt; val yOut = yc - dyt
                // eye 0 is the left eye: its inner corner is on the right; eye 1 is mirrored
                val yl = if (i == 0) yOut else yIn; val yr = if (i == 0) yIn else yOut
                lidPath.reset()
                lidPath.moveTo(wl - 6f, yl); lidPath.lineTo(wl + ww + 6f, yr)
                lidPath.lineTo(wl + ww + 6f, wt + wh + 6f); lidPath.lineTo(wl - 6f, wt + wh + 6f); lidPath.close()
                c.clipPath(lidPath)
            }
            c.drawBitmap(white.bmp, white.x - e[0], white.y - e[1], paint)
            val gx = (lookX + rg[RigCh.PUPIL_X]).coerceIn(-1.2f, 1.2f) * 20f; val gy = (lookY + rg[RigCh.PUPIL_Y]).coerceIn(-1.2f, 1.2f) * 13f
            val pupilS = pupil * rg[RigCh.PUPIL_SCALE]
            // pupil
            val pcx = e[2] - e[0] + gx; val pcy = e[3] - e[1] + gy
            c.save()
            c.translate(pcx, pcy)
            c.scale(pupilS, pupilS)
            c.drawBitmap(pu.bmp, pu.x - e[2], pu.y - e[3], paint)
            c.restore()
            // highlight sits a little ahead of the pupil, so the gaze feels round
            val hcx = pcx + (e[4] - e[2]) * pupilS + gx * 0.12f
            val hcy = pcy + (e[5] - e[3]) * pupilS + gy * 0.12f
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
        val smile = rig[RigCh.MOUTH_SMILE]
        if (mouthMode == 1 || (abs(smile) > 0.25f && mouthMode == 0 && rig[RigCh.MOUTH_OPEN] < 1.05f)) {
            if (smile < -0.25f) { rect.set(-34f, 2f, 34f, 46f); c.drawArc(rect, 205f, 130f, false, lipP) }
            else { rect.set(-34f, -30f, 34f, 14f); c.drawArc(rect, 25f, 130f, false, lipP) }
        } else {
            val o = if (mouthMode == 2) 0.55f else 1f
            c.scale(o, (if (mouthMode == 2) 0.85f else 1f) * mouthY * rig[RigCh.MOUTH_OPEN])
            c.drawBitmap(m.bmp, m.x - OrangeArt.mouthCx, m.y - OrangeArt.mouthCy, paint)
        }
        if (xEyes > 0.5f) {
            // tongue lolling out of the open mouth, swaying with the slow breathing
            val sway = 3f * sin(SystemClock.uptimeMillis() / 700.0).toFloat()
            c.drawRoundRect(-13f + sway * 0.4f, 6f, 13f + sway * 0.4f, 40f + sway, 12f, 12f, tonguePaint)
            xPaint.strokeWidth = 2.5f; xPaint.color = 0xFFD9566A.toInt()
            c.drawLine(sway * 0.4f, 12f, sway * 0.4f, 30f + sway * 0.6f, xPaint)
            xPaint.color = 0xFF4A2410.toInt()
        }
        c.restore()
    }

    /** Scenario-specific hand-drawn effects. These are time-based and deterministic, so loops stay smooth
     * without allocating particle objects or relying on frame-count timing. Coordinates are mascot-local. */
    private fun drawScenarioEffects(c: Canvas, now: Long, t: Float) {
        val phase = t * (if (calm) 0.65f else 1f)
        val cx0 = mLeft + s * 0.50f
        val top = mTop + s * 0.08f
        val bottom = feet - s * 0.02f
        val fx = scenarioFill
        val stroke = scenarioStroke
        fun path(points: List<Pair<Float, Float>>, p: Paint) {
            if (points.size < 2) return
            val q = Path(); q.moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) q.lineTo(points[i].first, points[i].second)
            c.drawPath(q, p)
        }
        when (move) {
            Move.BEGGING -> {
                // Little trembling hands and a pleading sweat drop.
                val pulse = sin(phase * 13f) * s * 0.012f
                stroke.color = 0xFFFFA45C.toInt(); stroke.strokeWidth = s * 0.025f
                path(listOf(Pair(cx0-s*.37f, bottom-s*.38f+pulse), Pair(cx0-s*.44f, bottom-s*.32f+pulse), Pair(cx0-s*.39f,bottom-s*.27f+pulse)), stroke)
                path(listOf(Pair(cx0+s*.36f,bottom-s*.39f-pulse), Pair(cx0+s*.44f,bottom-s*.33f-pulse), Pair(cx0+s*.39f,bottom-s*.28f-pulse)), stroke)
                val dropY = top + s * (0.22f + 0.035f * sin(phase * 5f))
                fx.color = 0xFF70C9F5.toInt(); c.drawOval(cx0+s*.32f, dropY, cx0+s*.39f, dropY+s*.13f, fx)
            }
            Move.DEAD -> {
                // A small spirit wisp leaves the head while Orange lies there; it stays upright whatever the body does.
                if (t > 2.4f && t < 5.0f) {
                    val env = sm(((t - 2.4f) / 0.5f).coerceIn(0f, 1f)) * (1f - sm(((t - 4.5f) / 0.5f).coerceIn(0f, 1f)))
                    c.save(); c.rotate(-rot, cx0, feet)
                    val p = (((t - 2.4f) / 2.1f) % 1f + 1f) % 1f
                    val wx = cx0 + s * 0.36f + sin(p * 6.0f) * s * 0.035f
                    val wy = feet - s * (0.50f + 0.40f * p)
                    val a = (sin(Math.PI * p).toFloat() * 0.85f * env)
                    fx.color = 0xFFEAF4FF.toInt(); fx.alpha = (230f * a).toInt().coerceIn(0, 255)
                    c.drawOval(wx - s * 0.055f, wy - s * 0.075f, wx + s * 0.055f, wy + s * 0.07f, fx)
                    val tail = Path().apply { moveTo(wx - s * 0.04f, wy + s * 0.05f); quadTo(wx - s * 0.02f, wy + s * 0.14f, wx + s * 0.03f, wy + s * 0.17f); quadTo(wx + s * 0.02f, wy + s * 0.11f, wx + s * 0.045f, wy + s * 0.05f); close() }
                    c.drawPath(tail, fx)
                    fx.color = 0xFF7D8FA3.toInt(); fx.alpha = (200f * a).toInt().coerceIn(0, 255)
                    c.drawCircle(wx - s * 0.02f, wy - s * 0.01f, s * 0.009f, fx); c.drawCircle(wx + s * 0.022f, wy - s * 0.01f, s * 0.009f, fx)
                    fx.alpha = 255
                    c.restore()
                }
            }
            Move.PANIC -> {
                stroke.color = 0xFF73BFEF.toInt(); stroke.strokeWidth = s*.025f
                for (side in -1..1 step 2) {
                    val x = cx0 + side*s*.40f
                    for (i in 0..2) { val yy = top+s*(.34f+i*.12f); c.drawLine(x,yy,x+side*s*.06f,yy-s*.055f,stroke) }
                }
            }
            Move.CRYING -> {
                fx.color = 0xFF56B8F4.toInt()
                val wob = sin(phase*4.2f)
                for (side in -1..1 step 2) {
                    val x = cx0 + side*s*.22f
                    // Each tear falls, fades, and respawns on a staggered phase for a seamless loop.
                    val fall = ((phase * 0.72f + (if (side < 0) 0f else 0.48f)) % 1f + 1f) % 1f
                    val y = top+s*(.37f + .16f * fall + .012f*wob)
                    fx.alpha = (235f * (1f - 0.12f * fall)).toInt().coerceIn(0,255)
                    val tear = Path().apply { moveTo(x,y); cubicTo(x-side*s*.10f,y+s*.12f,x+side*s*.10f,y+s*.17f,x,y+s*.22f); cubicTo(x-side*s*.08f,y+s*.17f,x+side*s*.08f,y+s*.12f,x,y); close() }
                    c.drawPath(tear,fx)
                    fx.alpha = 255
                }
            }
            Move.FINAL_BOSS -> {
                // Stylized flame tongues, pulsing at different phases for continuous motion.
                for (i in 0..6) {
                    val x = cx0 - s*.43f + i*s*.14f
                    val h = s*(.13f + .07f*(.5f+.5f*sin(phase*5f+i*1.7f)))
                    val y = bottom - s*.05f
                    val flame = Path().apply { moveTo(x-s*.045f,y); cubicTo(x-s*.10f,y-h*.35f,x+s*.03f,y-h*.65f,x,y-h); cubicTo(x+s*.10f,y-h*.58f,x+s*.10f,y-h*.22f,x+s*.045f,y); close() }
                    fx.color = if (i%2==0) 0xFFFF8B35.toInt() else 0xFFFFB34F.toInt(); c.drawPath(flame,fx)
                    fx.color = 0xFFFFD27A.toInt(); c.drawOval(x-s*.018f,y-h*.42f,x+s*.018f,y-s*.015f,fx)
                }
                // Rising embers use fixed phase offsets: smooth, repeatable, allocation-free particles.
                for (i in 0..8) {
                    val p = ((phase * 0.42f + i * 0.137f) % 1f + 1f) % 1f
                    val x = cx0 + sin(phase * 2.1f + i * 2.4f) * s * (.25f + .025f * i)
                    val y = bottom - s * (.08f + p * .56f)
                    fx.color = if (i % 2 == 0) 0xFFFFD27A.toInt() else 0xFFFF9B43.toInt()
                    fx.alpha = (220f * (1f - p)).toInt().coerceIn(0,255)
                    c.drawCircle(x,y,s*(.009f + .004f*(i%3)),fx)
                }
                fx.alpha = 255
            }
            Move.OVERLOAD -> {
                fx.color = 0xFF9AA3AD.toInt()
                for (i in 0..3) {
                    val ph = phase*1.4f+i*1.55f
                    val x = cx0 + sin(ph)*s*.31f
                    val y = top + s*(.08f + .035f*cos(ph*1.3f))
                    val rr = s*(.045f+.012f*(i%2))
                    c.drawCircle(x,y,rr,fx)
                }
                stroke.color = 0xFF56616C.toInt(); stroke.strokeWidth = s*.025f
                for (side in -1..1 step 2) {
                    val x = cx0+side*s*.20f; val y=top+s*.40f
                    val spiral=Path().apply { moveTo(x,y); for (j in 1..18) { val a=j*.48f; val rr=s*.006f*j; lineTo(x+cos(a)*rr,y+sin(a)*rr) } }
                    c.drawPath(spiral,stroke)
                }
            }
            Move.VICTORY -> {
                val crownY = top - s*.015f + s*.018f*sin(phase*3f)
                val crown = Path().apply { moveTo(cx0-s*.25f,crownY+s*.12f); lineTo(cx0-s*.30f,crownY-s*.05f); lineTo(cx0-s*.13f,crownY+s*.02f); lineTo(cx0,crownY-s*.14f); lineTo(cx0+s*.13f,crownY+s*.02f); lineTo(cx0+s*.30f,crownY-s*.05f); lineTo(cx0+s*.25f,crownY+s*.12f); close() }
                fx.color = 0xFFFFC34F.toInt(); c.drawPath(crown,fx)
                fx.color = 0xFFFFF0B3.toInt()
                for (i in 0..4) { val a=phase*1.3f+i*1.256f; val x=cx0+cos(a)*s*.58f; val y=top+s*(.32f+sin(a*1.7f)*.18f); c.drawCircle(x,y,s*.018f,fx) }
                // Confetti drifts down on offset loops instead of flashing in place.
                for (i in 0..11) {
                    val p = ((phase * .30f + i * .083f) % 1f + 1f) % 1f
                    val x = cx0 + sin(i * 4.1f + phase * .7f) * s * (.30f + (i % 4) * .07f)
                    val y = top + s * (.10f + p * .62f)
                    fx.alpha = (210f * (1f - p)).toInt().coerceIn(0,255)
                    fx.color = if (i % 2 == 0) 0xFFFFB34F.toInt() else 0xFF8ED8A6.toInt()
                    c.drawCircle(x,y,s*.012f,fx)
                }
                fx.alpha = 255
            }
            else -> Unit
        }
    }

    override fun onDraw(c: Canvas) {
        val base = L["base"]
        if (!art || base == null) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.05f)
        lastFrame = now

        // opacity / scale watchdog: log every change, and repair a value that has been stuck for 1.5 s
        run {
            val va = alpha; val vs = scaleX
            if (abs(va - fxLastA) > 0.001f || abs(vs - fxLastS) > 0.001f) {
                if (va < 0.999f || vs < 0.999f || fxLastA < 0.999f || fxLastS < 0.999f)
                    OrangeDiag.log("viewfx", String.format(java.util.Locale.US, "alpha %.2f->%.2f scale %.2f->%.2f move=%s", fxLastA, va, fxLastS, vs, move))
                fxLastA = va; fxLastS = vs; fxSince = now
            } else if ((va < 0.995f || abs(vs - 1f) > 0.005f) && fxSince != 0L && now - fxSince > 1500L && move != Move.ENTER && move != Move.EXIT) {
                OrangeDiag.log("HEAL", String.format(java.util.Locale.US, "stuck alpha=%.2f scale=%.2f move=%s", va, vs, move))
                alpha = 1f; scaleX = 1f; scaleY = 1f
                fxLastA = 1f; fxLastS = 1f; fxSince = now
            }
        }
        xEyes = 0f; slideX = 0f
        var t = (now - t0) / 1000f * spd
        val running = pose(t)
        // Smooth press-in / release-out response. The critically damped approach is frame-rate
        // independent and is layered over the current pose, so a tap cannot cancel focus or a scenario.
        val pressTarget = if (touchHeld) 1f else 0f
        touchSquash += (pressTarget - touchSquash) * (1f - exp(-24.0 * dt).toFloat())
        val pressPulse = touchSquash.coerceIn(0f, 1f)
        sx += 0.035f * pressPulse
        sy -= 0.055f * pressPulse
        dy += s * 0.012f * pressPulse
        blendPose(now)
        // The hold builds anticipation: Orange crouches a little as the ring fills.
        run {
            val tgt = if (touchHeld && !longFired && holdStartedAt != 0L) ((now - holdStartedAt) / 4500f).coerceIn(0f, 1f) else 0f
            holdShown = if (tgt >= holdShown) tgt else { val v = holdShown * exp(-14.0 * dt).toFloat(); if (v < 0.003f) 0f else v }
            sy -= 0.045f * holdShown; sx += 0.028f * holdShown
        }
        // Completion: a visible little celebration on top of whatever Orange is doing (works in every state).
        if (magicAt != 0L) {
            val ca = (now - magicAt) / 1000f
            if (ca < 1.0f) {
                when {
                    ca < 0.16f -> { val u = sm(ca / 0.16f); sy -= 0.12f * u; sx += 0.07f * u }
                    ca < 0.66f -> { val u = (ca - 0.16f) / 0.5f; dy += s * 0.24f * 4f * u * (1f - u); sy += 0.07f * sin(Math.PI * u).toFloat(); sx -= 0.04f * sin(Math.PI * u).toFloat() }
                    else -> { val q = ca - 0.66f; val e = exp(-7.0 * q).toFloat() * cos(18.0 * q).toFloat(); sy -= 0.10f * e; sx += 0.06f * e }
                }
                happyT = max(happyT, bump(ca, 0.05f, 0.85f))
                leafKick = max(leafKick, 0.9f * bump(ca, 0.1f, 0.8f))
            }
        }
        // A pet is a cross-state additive reaction: it remains visible during idle, focus,
        // break and scenario performances without replacing their choreography.
        val petAge = if (petAt == 0L) 2f else ((now - petAt) / 1000f).coerceAtLeast(0f)
        val petPulse = if (petAge < 0.82f) sin(Math.PI * (petAge / 0.82f)).toFloat() else 0f
        if (petPulse > 0.001f) {
            dy -= s * 0.025f * petPulse
            sx += 0.022f * petPulse
            sy -= 0.032f * petPulse
            rot += petSide * 1.8f * petPulse
            happyT = max(happyT, 0.72f * petPulse)
            lookXT = petSide * 0.30f * petPulse
            leafKick = max(leafKick, 0.75f * petPulse)
        }
        if (!running && move == Move.EXIT) {
            busy = false
            if (now - t0 > 2500L) { OrangeDiag.log("EXIT", "finished exit still on screen -> idle by ${OrangeDiag.caller()}"); play(Move.IDLE, 1f, true) }
        } else if (!running) {
            // finished: back to idle (picked up by the loop, if any)
            val nm = afterMove ?: Move.IDLE
            afterMove = null
            capturePose()
            move = nm; t0 = now; t = 0f
            blendStarted = now; blendDuration = 320L; blendActive = nm != Move.IDLE
            pose(0f); busy = false
        } else {
            busy = move == Move.EXIT || (running && move != Move.IDLE && move != Move.FOCUS &&
                move != Move.BREAK && move != Move.THINK && move != Move.POINT &&
                move != Move.LISTEN && move != Move.SLEEP)
        }
        update(now, dt)
        ensureRigGeometry()
        rigPose(t, now)
        rig.step(dt)
        prepareHead()

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
        c.translate(slideX, -dy)
        c.rotate(rot, cx, feet)

        // body mesh
        var i = 0
        for (r in 0..gridR) {
            for (col in 0..gridC) {
                map(base.x + base.bmp.width * col / gridC.toFloat(), base.y + base.bmp.height * r / gridR.toFloat(), true)
                verts[i++] = tx; verts[i++] = ty
            }
        }
        if (armMeshL != null) drawRigLegs(c)
        c.drawBitmapMesh(base.bmp, gridC, gridR, verts, 0, null, 0, paint)
        if (armMeshL != null) drawRigPatches(c)

        drawMouth(c)
        val bl = blinkFactor(now)
        drawEye(c, 0, bl); drawEye(c, 1, bl)
        if (armMeshL != null) drawRigArms(c)

        // leaf, hinged at the stem
        val leaf = L["leaf"]
        if (leaf != null) {
            map(OrangeArt.leafPx, OrangeArt.leafPy, false)
            c.save()
            c.translate(tx, ty)
            c.rotate(leafA + rig[RigCh.HEAD_ROT] * headWeight(OrangeArt.leafPy) + rig[RigCh.LEAF_BEND])
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

        // Character-attached scenario effects use the same entrance/exit transform and frame clock.
        if (move == Move.BEGGING || move == Move.DEAD || move == Move.PANIC || move == Move.CRYING || move == Move.FINAL_BOSS || move == Move.OVERLOAD || move == Move.VICTORY) {
            c.save()
            c.translate(cx, feet)
            c.scale(overallScale, overallScale)
            c.translate(-cx, -feet - overallLift - dy)
            c.translate(slideX, 0f)
            c.rotate(rot, cx, feet)
            drawScenarioEffects(c, now, t)
            c.restore()
        }

        // Long-press magic: a small, soft aurora halo with drifting sprinkles. It is deliberately
        // restrained and follows the app theme (sage in light mode, warm orange in dark mode).
        drawZzz(c, dt)
        drawLongPressMagic(c, now)

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
            val petAnimating = petAt != 0L && now - petAt < 950L
            val magicAnimating = magicAt != 0L && now - magicAt < 2600L
            val ambientLoop = move == Move.FOCUS || move == Move.BREAK || move == Move.SLEEP || move == Move.IDLE ||
                move == Move.BEGGING || move == Move.DEAD || move == Move.PANIC || move == Move.CRYING ||
                move == Move.FINAL_BOSS || move == Move.OVERLOAD || move == Move.VICTORY
            val moving = busy || holdShown > 0f || zzzLevel > 0.01f || petAnimating || magicAnimating || (touchHeld && !longFired) || blinkStart != 0L || abs(leafV) > 0.4f ||
                abs(leafA - leafBias) > 0.15f || lm != null || ambientLoop
            // Keep character motion on the display frame clock; only static/offscreen states may idle.
            if (moving) postInvalidateOnAnimation() else postInvalidateDelayed(40)
        }
    }

    /** One rounded silhouette that hugs the whole character (leaf to feet, arm tip to arm tip) with even spacing. */
    private fun buildRing() {
        val ex = 2.0 / 2.6
        val cX = mLeft + 329f * k; val cY = mTop + 321f * k
        val a = 345f * k; val b = 357f * k
        ringPath.reset()
        val n = 144
        for (i in 0..n) {
            val th = 2.0 * Math.PI * i / n
            val sn = sin(th); val cs = cos(th)
            val x = cX + a * (if (sn < 0) -1 else 1) * Math.pow(abs(sn), ex).toFloat()
            val y = cY - b * (if (cs < 0) -1 else 1) * Math.pow(abs(cs), ex).toFloat()
            if (i == 0) ringPath.moveTo(x, y) else ringPath.lineTo(x, y)
        }
        ringPath.close()
        ringMeasure.setPath(ringPath, false)
        ringLen = ringMeasure.length
        ringBuilt = true
    }

    private fun ringColor() = if (dark) Color.rgb(255, 170, 84) else Color.rgb(232, 118, 26)

    /** The hold ring: a thin warm-orange arc around the whole silhouette that fills continuously over exactly 4.5 s. */
    private fun drawHoldProgress(c: Canvas, now: Long) {
        if (holdShown <= 0.003f) return
        if (!ringBuilt) buildRing()
        val holding = touchHeld && !longFired && holdStartedAt != 0L
        val fade = if (holding) ((now - holdStartedAt) / 120f).coerceIn(0f, 1f) else (holdShown * 8f).coerceIn(0f, 1f)
        if (fade <= 0f) return
        val color = ringColor()
        magicRing.color = color; magicRing.strokeWidth = 2.0f * d
        magicRing.alpha = (44 * fade).toInt()
        c.drawPath(ringPath, magicRing)
        ringSeg.rewind()
        ringMeasure.getSegment(0f, ringLen * holdShown, ringSeg, true)
        magicRing.alpha = (245 * fade).toInt()
        c.drawPath(ringSeg, magicRing)
        // a bright bead with a soft halo rides the end of the arc
        ringMeasure.getPosTan(ringLen * holdShown, ringPos, null)
        magicDot.color = color; magicDot.alpha = (70 * fade).toInt()
        c.drawCircle(ringPos[0], ringPos[1], 5.2f * d, magicDot)
        magicDot.color = Color.rgb(255, 232, 200); magicDot.alpha = (255 * fade).toInt()
        c.drawCircle(ringPos[0], ringPos[1], 2.3f * d, magicDot)
    }

    /** Completion: the ring flashes full, breathes outward and fades; sparkles lift off it. Nothing leaves the view. */
    private fun drawLongPressMagic(c: Canvas, now: Long) {
        drawHoldProgress(c, now)
        if (magicAt == 0L) return
        val age = (now - magicAt) / 1000f
        if (age >= 1.3f) { magicAt = 0L; return }
        if (!ringBuilt) buildRing()
        val color = ringColor()
        val e = sm((age / 0.6f).coerceIn(0f, 1f))
        val envelope = (1f - ((age - 0.3f) / 1.0f).coerceIn(0f, 1f))
        val cX = mLeft + 329f * k; val cY = mTop + 321f * k
        c.save()
        val sc = 1f + 0.022f * e
        c.scale(sc, sc, cX, cY)
        magicRing.color = color; magicRing.strokeWidth = (2.6f - 1.2f * e) * d
        magicRing.alpha = (240 * envelope).toInt()
        c.drawPath(ringPath, magicRing)
        c.restore()
        val limit = mTop + s * 1.06f
        for (i in 0 until 12) {
            val travel = sm(((age - 0.04f - (i % 3) * 0.05f) / 0.85f).coerceIn(0f, 1f))
            ringMeasure.getPosTan(ringLen * (i / 12f), ringPos, null)
            var dx = ringPos[0] - cX; var dy2 = ringPos[1] - cY
            val len = hypot(dx, dy2).coerceAtLeast(1f); dx /= len; dy2 /= len
            val x = ringPos[0] + dx * s * 0.07f * travel
            val y = ringPos[1] + dy2 * s * 0.07f * travel - s * 0.025f * travel
            if (y > limit) continue
            val tw = 0.65f + 0.35f * sin(age * 18f + i)
            magicDot.color = color
            magicDot.alpha = (240f * envelope * (1f - travel * 0.55f) * tw).toInt().coerceIn(0, 255)
            val r = d * (1.1f + (i % 3) * 0.55f)
            if (i % 2 == 0) {
                c.save(); c.translate(x, y); c.rotate(45f)
                c.drawRoundRect(-r, -r, r, r, r * 0.3f, r * 0.3f, magicDot); c.restore()
            } else c.drawCircle(x, y, r, magicDot)
        }
    }

    /** Zzz: its own layer. Three letters rise in turn, grow a little, sway and fade, then the cycle repeats seamlessly. */
    private fun drawZzz(c: Canvas, dt: Float) {
        val asleep = move == Move.SLEEP && rig[RigCh.LID_L_COVER] > 0.55f
        zzzLevel = if (asleep) min(1f, zzzLevel + dt * 1.1f) else max(0f, zzzLevel - dt * 3.2f)
        if (zzzLevel <= 0.01f) { zzzT = 0f; return }
        zzzT += dt
        val ox = cx + s * 0.27f; val oy = mTop + s * 0.20f
        sleepText.color = if (dark) 0xFFFFC58E.toInt() else 0xFF8A6A4F.toInt()
        for (i in 0..2) {
            val local = zzzT - 0.5f - i * 1.15f
            if (local < 0f) continue
            val u = (local % 3.45f) / 3.45f
            val inA = sm((u / 0.16f).coerceIn(0f, 1f))
            val outA = 1f - sm(((u - 0.55f) / 0.45f).coerceIn(0f, 1f))
            val a = inA * outA * zzzLevel
            if (a <= 0.01f) continue
            sleepText.textSize = s * (0.085f + 0.05f * u)
            sleepText.alpha = (235f * a).toInt()
            val x = ox + s * 0.11f * u + sin(u * 5.2f + i * 1.7f) * s * 0.014f
            val y = oy - s * 0.34f * u
            c.save(); c.rotate(-10f + 6f * sin(u * 4f + i), x, y)
            c.drawText("Z", x, y, sleepText)
            c.restore()
        }
        sleepText.alpha = 255
    }

    // ---- touch: a tap is a small playful reaction, a long press is a surprised one
    private fun hit(e: MotionEvent) = e.x >= mLeft && e.x <= mLeft + s && e.y >= mTop && e.y <= feet

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive || !hit(e)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = e.x; touchDownY = e.y; lastDragX = e.x
                gazeX = ((e.x - cx) / (s * 0.5f)).coerceIn(-1f, 1f); gazeY = ((e.y - (mTop + s * 0.45f)) / (s * 0.5f)).coerceIn(-1f, 1f)
                touchHeld = true
                longFired = false
                dragged = false
                downAt = SystemClock.uptimeMillis()
                invalidate()
                holdStartedAt = downAt
                removeCallbacks(longPress); postDelayed(longPress, 4500L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastDragX
                gazeX = ((e.x - cx) / (s * 0.5f)).coerceIn(-1f, 1f); gazeY = ((e.y - (mTop + s * 0.45f)) / (s * 0.5f)).coerceIn(-1f, 1f)
                if (abs(e.x - touchDownX) + abs(e.y - touchDownY) > 10f * d) {
                    // moving the finger away cancels the hold: the ring eases back and nothing fires
                    if (!dragged && !longFired) { dragged = true; removeCallbacks(longPress); holdStartedAt = 0L }
                    // A finger glide is interpreted as petting, not as a click. Gaze and leaf
                    // follow the stroke while the underlying focus/scenario state continues.
                    if (move != Move.EXIT) {
                        petAt = SystemClock.uptimeMillis()
                        petSide = if (dx < 0f) -1f else 1f
                        petStrength = 1f
                    }
                    lastDragX = e.x
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                touchHeld = false; gazeX = 0f; gazeY = 0f
                val heldMs = SystemClock.uptimeMillis() - downAt
                holdStartedAt = 0L
                removeCallbacks(longPress)
                invalidate()
                if (!longFired && !dragged && heldMs <= 600L) {
                    val now = SystemClock.uptimeMillis()
                    // Count deliberate taps even while the short TAP reaction is settling.
                    // The old `!busy` guard made the three-tap GIGGLE effectively unreachable:
                    // TAP lasts 500 ms, longer than the old 450 ms tap cooldown.
                    // Never interrupt a focus/break pose or a higher-priority celebration.
                    if (now - lastTap >= 220L) {
                        if (move == Move.FOCUS || move == Move.BREAK) {
                            // Interactable while working: a tap gives feedback but never interrupts focus.
                            petAt = now
                            petSide = if (e.x < width / 2f) -1f else 1f
                            petStrength = 1f
                            tapRun = if (now - lastTap < 2500L) tapRun + 1 else 1
                            if (tapRun >= 3) { tapRun = 0; say("You've got this!") ; postDelayed({ if (isAttachedToWindow) say(null) }, 1500L) }
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            invalidate()
                        } else if (move == Move.SLEEP) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            playThen(Move.WAKE, wakeTo)
                            onWoke?.invoke()
                        } else if (move != Move.EXIT) {
                            petAt = now
                            petSide = if (e.x < width / 2f) -1f else 1f
                            petStrength = 1f
                            tapRun = if (now - lastTap < 2500L) tapRun + 1 else 1
                            if (tapRun >= 3 && !busy) { tapRun = 0; play(Move.GIGGLE, force = true) }
                            else if (!busy || prio(move) <= prio(Move.TAP)) play(Move.TAP, force = true)
                        }
                        lastTap = now
                    }
                    performClick()
                } else if (!longFired && !dragged && move != Move.EXIT) {
                    // a longer press that was let go early is a gentle pet, not a tap
                    petAt = SystemClock.uptimeMillis(); petSide = if (e.x < width / 2f) -1f else 1f; petStrength = 1f
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { touchHeld = false; gazeX = 0f; gazeY = 0f; holdStartedAt = 0L; removeCallbacks(longPress); invalidate(); return true }
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
