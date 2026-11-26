package com.shortstop.app

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What kind of thing a line is about. Used to pick lines and to drop a line whose moment has passed. */
enum class LineCat { IDLE, FOCUS, BREAK, SLEEP, SCHEDULE, ENDING, BEGGING, DEAD, PLAYFUL }

/** Orange's lines. Existing wording is kept; the extra lines only add variety for tap-to-cycle. */
object OrangeLines {
    private val pools: Map<LineCat, List<String>> = mapOf(
        LineCat.IDLE to listOf("Ready when you are.", "Ready for a focused session?", "I'm here if you need me.", "Just hanging out.", "Whenever you're ready."),
        LineCat.FOCUS to listOf("Nice and steady.", "You're doing great.", "One thing at a time.", "Keep going. I'm right here.", "You've got this!"),
        LineCat.BREAK to listOf("Phew. Break time.", "Stretch your shoulders.", "Maybe some water?", "Look at something far away."),
        LineCat.SLEEP to listOf("Taking a little rest.", "Zzz... five more minutes.", "Shh. Resting."),
        LineCat.SCHEDULE to listOf("Your next focus session is ready.", "Planning ahead. I like it.", "Pick one and we'll go."),
        LineCat.ENDING to listOf("Please... one more minute?", "Are you sure?", "I can still help. Really."),
        LineCat.BEGGING to listOf("Please... one more minute?", "Don't go yet...", "I'll be good. Promise."),
        LineCat.DEAD to listOf("Well. That happened.", "Tell my timer I loved it.", "I'm fine. Totally fine."),
        LineCat.PLAYFUL to listOf("You've got this!", "Hehe, that tickles.", "Boop.")
    )
    private val recent = ArrayDeque<String>()
    private var lastAuto = 0L

    /** A random line from [cat] that is not [avoid] and not among the last few shown. */
    fun pick(cat: LineCat, avoid: String? = null): String {
        val pool = pools[cat] ?: pools.getValue(LineCat.IDLE)
        var cands = pool.filter { it != avoid && it !in recent }
        if (cands.isEmpty()) cands = pool.filter { it != avoid }
        if (cands.isEmpty()) cands = pool
        val line = cands.random()
        recent.addLast(line)
        while (recent.size > 3) recent.removeFirst()
        return line
    }

    /** Cooldown gate for dialogue Orange starts on his own (never for user-driven or session events). */
    fun autoAllowed(cooldownMs: Long = 45_000L): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastAuto < cooldownMs) return false
        lastAuto = now
        return true
    }
}

/**
 * Orange's speech box, drawn as a small overlay in the window so it can never be clipped by Orange's own view.
 * Text is measured at the real width, the box grows vertically, and it is kept on screen. It sits beside the body
 * (never on the face), enters and leaves softly, morphs between messages and can be tapped for another line.
 */
class OrangeBubble(
    ctx: Context,
    private val anchor: OrangeView,
    private val dark: Boolean,
    tf: Typeface?,
    textColor: Int
) : View(ctx) {
    private enum class Side { RIGHT, LEFT, ABOVE, BELOW }

    private val d = ctx.resources.displayMetrics.density
    private val padH = 12f * d
    private val padV = 9f * d
    private val tailLen = 8f * d
    private val corner = 16f * d

    private val textP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf; color = textColor; textSize = 14f * ctx.resources.displayMetrics.scaledDensity
    }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF3A2517.toInt() else 0xFFFFFFFF.toInt() }
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) 0xFF4D3322.toInt() else 0xFFF6E2C8.toInt(); style = Paint.Style.STROKE; strokeWidth = d
    }
    private val rect = RectF()
    private val tail = Path()
    private val loc = IntArray(2)
    private val hostLoc = IntArray(2)
    private val body = RectF()

    private var lay: StaticLayout? = null
    private var oldLay: StaticLayout? = null
    private var side = Side.RIGHT
    private var boxW = 0f; private var boxH = 0f      // target box size
    private var fromW = 0f; private var fromH = 0f    // size the morph starts from
    private var morph = 1f
    private var appear = 0f
    private var tipX = 0f; private var tipY = 0f      // tail tip in view coordinates (after tail geometry is known)
    private var tipRelA = 0f                          // wanted tip offset along the pinned edge
    private var lastX = Int.MIN_VALUE; private var lastY = Int.MIN_VALUE

    private var appearAnim: ValueAnimator? = null
    private var morphAnim: ValueAnimator? = null
    private var leaving = false
    private var lastAnchorX = Float.NaN; private var lastAnchorY = Float.NaN

    var cat: LineCat = LineCat.IDLE; private set
    var current: String = ""; private set
    var onTapBubble: (() -> Unit)? = null
    var preferBelow = false

    private val autoHide = Runnable { hide() }
    private val pre = ViewTreeObserver.OnPreDrawListener {
        if (!leaving) follow()
        true
    }

    init {
        isClickable = true
        elevation = 0f
    }

    val isShowing: Boolean get() = parent != null && !leaving

    fun show(text: String, category: LineCat, durationMs: Long) {
        removeCallbacks(autoHide)
        val host = hostView() ?: return
        if (removalQueued) { mainHandler.removeCallbacks(removal); removalQueued = false; detach(); visibility = VISIBLE }
        val firstTime = parent == null || leaving
        cat = category
        current = text
        if (host.width == 0) return
        if (parent == null) {
            host.addView(this, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
            visibility = View.INVISIBLE
        }
        val wasLeaving = leaving
        leaving = false

        // measure for the new text at the real available width
        val oldW = boxW; val oldH = boxH
        val prevLay = lay
        val choice = chooseSide(host, text)
        side = choice.first
        val nl = choice.second
        boxW = nl.width + padH * 2; boxH = nl.height + padV * 2
        lay = nl

        if (firstTime || oldW == 0f) {
            morphAnim?.cancel(); morph = 1f; oldLay = null
            fromW = boxW; fromH = boxH
            applyBounds(host, boxW, boxH, true)
            startAppear(true)
        } else {
            // morph from what is currently drawn to the new size; old text fades out, new text fades in
            fromW = currentW(); fromH = currentH()
            oldLay = prevLay
            applyBounds(host, max(fromW, boxW), max(fromH, boxH), false)
            if (wasLeaving) startAppear(true)
            startMorph(host)
        }
        if (durationMs > 0L) postDelayed(autoHide, durationMs)
    }

    fun hide() {
        removeCallbacks(autoHide)
        if (parent == null || leaving) return
        leaving = true
        appearAnim?.cancel()
        val from = appear
        appearAnim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = (170L * from).toLong().coerceAtLeast(60L)
            interpolator = AccelerateInterpolator(1.4f)
            addUpdateListener { appear = it.animatedValue as Float; alpha = appear; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) detach() }
            })
            start()
        }
    }

    /** Removes the box at once (used when Orange is detached or hidden). */
    fun dismissNow() {
        removeCallbacks(autoHide)
        appearAnim?.cancel(); morphAnim?.cancel()
        leaving = false
        detach()
    }

    /**
     * For lifecycle callbacks (window hidden, Orange detached or hidden). Those run while the window's root view is
     * looping over its children, and removing a child there shrinks the list under the loop, which is what crashed
     * the app when it went to the background. So here the box is only hidden; the real removal happens afterwards.
     */
    fun dismissSafely() {
        removeCallbacks(autoHide)
        appearAnim?.cancel(); morphAnim?.cancel()
        leaving = false; appear = 0f
        visibility = INVISIBLE
        if (parent != null && !removalQueued) {
            removalQueued = true
            mainHandler.post(removal)
        }
    }
    private var removalQueued = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val removal = Runnable { removalQueued = false; detach(); visibility = VISIBLE }

    private fun detach() {
        (parent as? ViewGroup)?.removeView(this)
        leaving = false; appear = 0f; boxW = 0f; boxH = 0f; lay = null; oldLay = null
        lastX = Int.MIN_VALUE; lastY = Int.MIN_VALUE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(pre)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(pre)
        removeCallbacks(autoHide)
        appearAnim?.cancel(); morphAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun hostView(): ViewGroup? {
        var c: Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c.window.decorView as? ViewGroup
            c = c.baseContext
        }
        return null
    }

    private fun currentW() = fromW + (boxW - fromW) * morph
    private fun currentH() = fromH + (boxH - fromH) * morph

    private fun startAppear(fresh: Boolean) {
        appearAnim?.cancel()
        val from = if (fresh) appear else appear
        appearAnim = ValueAnimator.ofFloat(from, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { appear = it.animatedValue as Float; alpha = appear.coerceIn(0f, 1f); invalidate() }
            start()
        }
    }

    private fun startMorph(host: ViewGroup) {
        morphAnim?.cancel()
        morph = 0f
        morphAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { morph = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (cancelled) return
                    oldLay = null; fromW = boxW; fromH = boxH
                    hostView()?.let { applyBounds(it, boxW, boxH, true) }
                }
            })
            start()
        }
    }

    /** Picks where the box goes and builds a layout for [text] at the width that place allows. */
    private fun chooseSide(host: ViewGroup, text: String): Pair<Side, StaticLayout> {
        anchor.getLocationInWindow(loc); host.getLocationInWindow(hostLoc)
        anchor.bodyBounds(body)
        val bl = loc[0] - hostLoc[0] + body.left; val br = loc[0] - hostLoc[0] + body.right
        val sw = host.width.toFloat()
        val margin = 12f * d
        val gap = 6f * d
        val maxBox = min(260f * d, sw - margin * 2)
        val minBox = 120f * d
        val availR = sw - margin - (br + gap) - tailLen
        val availL = bl - gap - margin - tailLen
        val side = when {
            preferBelow -> Side.BELOW
            availR >= 200f * d -> Side.RIGHT
            availL >= 200f * d -> Side.LEFT
            max(availR, availL) >= minBox -> if (availR >= availL) Side.RIGHT else Side.LEFT
            else -> Side.ABOVE
        }
        val avail = when (side) { Side.RIGHT -> availR; Side.LEFT -> availL; else -> sw - margin * 2 }
        val box = min(maxBox, avail).coerceAtLeast(minBox.coerceAtMost(sw - margin * 2))
        val first = layoutFor(text, (box - padH * 2).toInt().coerceAtLeast(40))
        var widest = 0f
        for (i in 0 until first.lineCount) widest = max(widest, first.getLineWidth(i))
        val tight = if (first.lineCount > 1) layoutFor(text, ceil(widest).toInt() + 2) else first
        val finalLay = if (tight.lineCount == first.lineCount) tight else first
        return Pair(side, finalLay)
    }

    private fun layoutFor(text: String, w: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, textP, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

    /** Sizes and places the view for a box of [bw] x [bh]; the pinned edge next to Orange never moves. */
    private fun applyBounds(host: ViewGroup, bw: Float, bh: Float, snap: Boolean) {
        anchor.getLocationInWindow(loc); host.getLocationInWindow(hostLoc)
        anchor.bodyBounds(body)
        val bl = loc[0] - hostLoc[0] + body.left; val br = loc[0] - hostLoc[0] + body.right
        val bt = loc[1] - hostLoc[1] + body.top; val bb = loc[1] - hostLoc[1] + body.bottom
        val sw = host.width.toFloat(); val sh = host.height.toFloat()
        val margin = 12f * d
        val gap = 6f * d
        var topLimit = margin; var botLimit = margin
        host.rootWindowInsets?.let {
            @Suppress("DEPRECATION")
            topLimit = max(margin, it.systemWindowInsetTop + 8f * d)
            @Suppress("DEPRECATION")
            botLimit = max(margin, it.systemWindowInsetBottom + 8f * d)
        }
        val vw: Float; val vh: Float
        var vx: Float; var vy: Float
        when (side) {
            Side.RIGHT, Side.LEFT -> {
                vw = bw + tailLen; vh = bh
                vx = if (side == Side.RIGHT) br + gap else bl - gap - vw
                // sit beside the upper part of the body, away from the face centre
                vy = bt + (bb - bt) * 0.08f
                val wantTip = bt + (bb - bt) * 0.34f
                vy = vy.coerceIn(topLimit, max(topLimit, sh - botLimit - vh))
                tipRelA = (wantTip - vy).coerceIn(16f * d, max(16f * d, bh - 16f * d))
            }
            else -> {
                vw = bw; vh = bh + tailLen
                val cx = (bl + br) / 2f
                vx = (cx - vw / 2f)
                tipRelA = (cx - vx).coerceIn(18f * d, max(18f * d, bw - 18f * d))
                val above = bt - gap - vh
                if (above >= topLimit && !preferBelow) { side = Side.ABOVE; vy = above } else { side = Side.BELOW; vy = bb + gap }
            }
        }
        vx = vx.coerceIn(margin, max(margin, sw - margin - vw))
        if (side == Side.ABOVE || side == Side.BELOW) vy = vy.coerceIn(topLimit, max(topLimit, sh - botLimit - vh))
        val ix = vx.toInt(); val iy = vy.toInt()
        val lp = layoutParams as FrameLayout.LayoutParams
        val newW = ceil(vw).toInt(); val newH = ceil(vh).toInt()
        val changed = lp.width != newW || lp.height != newH || lp.leftMargin != ix || lp.topMargin != iy
        if (changed) {
            val ox = lastX; val oy = lastY
            lp.width = newW; lp.height = newH; lp.leftMargin = ix; lp.topMargin = iy
            layoutParams = lp
            if (!snap && ox != Int.MIN_VALUE && (ox != ix || oy != iy)) {
                animate().cancel()
                translationX = (ox - ix).toFloat(); translationY = (oy - iy).toFloat()
                animate().translationX(0f).translationY(0f).setDuration(240L)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            }
            lastX = ix; lastY = iy
        }
        visibility = if (anchor.isShown) View.VISIBLE else View.INVISIBLE
    }

    /** Keeps the box beside Orange if his view moves (slide-ins, layout changes) and hides it with him. */
    private fun follow() {
        if (parent == null || boxW == 0f) return
        val host = parent as ViewGroup
        anchor.getLocationInWindow(loc)
        val ax = loc[0].toFloat(); val ay = loc[1].toFloat()
        val shown = anchor.isShown && anchor.alpha > 0.02f
        val want = if (shown) View.VISIBLE else View.INVISIBLE
        if (visibility != want) visibility = want
        if (ax != lastAnchorX || ay != lastAnchorY) {
            lastAnchorX = ax; lastAnchorY = ay
            val w = if (morph < 1f) max(fromW, boxW) else boxW
            val h = if (morph < 1f) max(fromH, boxH) else boxH
            applyBounds(host, w, h, true)
        }
    }

    override fun onDraw(c: Canvas) {
        val l = lay ?: return
        val w = if (morph < 1f) currentW() else boxW
        val h = if (morph < 1f) currentH() else boxH
        // box position inside the view; the edge next to Orange stays pinned
        val bx: Float; val by: Float
        when (side) {
            Side.RIGHT -> { bx = tailLen; by = 0f; tipX = 0f; tipY = tipRelA }
            Side.LEFT -> { bx = width - tailLen - w; by = 0f; tipX = width.toFloat(); tipY = tipRelA }
            Side.ABOVE -> { bx = 0f; by = height - tailLen - h; tipX = tipRelA; tipY = height.toFloat() }
            Side.BELOW -> { bx = 0f; by = tailLen; tipX = tipRelA; tipY = 0f }
        }
        val a = appear.coerceIn(0f, 1f)
        val sc = 0.92f + 0.08f * a
        c.save()
        c.translate(0f, (1f - a) * 5f * d)
        c.scale(sc, sc, tipX, tipY)
        rect.set(bx, by, bx + w, by + h)
        tail.reset()
        val base = 6f * d
        when (side) {
            Side.RIGHT -> {
                val ty = tipY.coerceIn(by + corner * 0.6f + base, by + h - corner * 0.6f - base)
                tail.moveTo(bx + 1f, ty - base); tail.lineTo(tipX, tipY.coerceIn(by + 8f * d, by + h - 8f * d)); tail.lineTo(bx + 1f, ty + base * 0.8f)
            }
            Side.LEFT -> {
                val ty = tipY.coerceIn(by + corner * 0.6f + base, by + h - corner * 0.6f - base)
                tail.moveTo(bx + w - 1f, ty - base); tail.lineTo(tipX, tipY.coerceIn(by + 8f * d, by + h - 8f * d)); tail.lineTo(bx + w - 1f, ty + base * 0.8f)
            }
            Side.ABOVE -> {
                val tx = tipX.coerceIn(bx + corner + base, bx + w - corner - base)
                tail.moveTo(tx - base, by + h - 1f); tail.lineTo(tx, tipY); tail.lineTo(tx + base, by + h - 1f)
            }
            Side.BELOW -> {
                val tx = tipX.coerceIn(bx + corner + base, bx + w - corner - base)
                tail.moveTo(tx - base, by + 1f); tail.lineTo(tx, tipY); tail.lineTo(tx + base, by + 1f)
            }
        }
        tail.close()
        c.drawRoundRect(rect, corner, corner, fillP)
        c.drawRoundRect(rect, corner, corner, edgeP)
        c.drawPath(tail, fillP)

        // text: old fades out first, new fades in once the box has mostly settled
        val ol = oldLay
        if (ol != null && morph < 1f) {
            val ao = (1f - morph / 0.3f).coerceIn(0f, 1f)
            if (ao > 0f) drawText(c, ol, bx, by, w, h, ao)
        }
        val an = if (ol != null && morph < 1f) ((morph - 0.35f) / 0.5f).coerceIn(0f, 1f) else 1f
        if (an > 0f) drawText(c, l, bx, by, w, h, an)
        c.restore()
    }

    private fun drawText(c: Canvas, l: StaticLayout, bx: Float, by: Float, w: Float, h: Float, a: Float) {
        val keep = textP.alpha
        textP.alpha = (255 * a).toInt()
        c.save()
        c.translate(bx + (w - l.width) / 2f, by + (h - l.height) / 2f)
        l.draw(c)
        c.restore()
        textP.alpha = keep
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { return !leaving }
            MotionEvent.ACTION_UP -> {
                if (!leaving && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    onTapBubble?.invoke()
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
