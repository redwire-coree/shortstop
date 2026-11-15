package com.shortstop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First-run setup as a stepper (react-bits Stepper): a rail whose raised pill glides between the steps on a spring,
 * numbers that turn into checks the moment Android confirms a step, content that slides across, and Back / Continue.
 * It watches the real state, so coming back from Settings advances it by itself.
 */
class SetupStepper(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    private val steps: List<Step>,
    private val onAck: (Int) -> Unit,
    private val onAllDone: () -> Unit
) : LinearLayout(ctx) {

    class Step(
        val tab: String,
        val title: String,
        val body: String,
        val action: String,
        val open: () -> Unit,
        /** Android can't tell us this one; Continue confirms it. */
        val manual: Boolean
    )

    private val d = ctx.resources.displayMetrics.density
    private val sd = ctx.resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = (v * d).toInt()
    private val n = steps.size
    private val done = BooleanArray(n)
    private var index = 0
    private var started = false
    private var allDone = false
    private val handler = Handler(Looper.getMainLooper())

    private val count: TextView
    private val rail: Rail
    private val content: LinearLayout
    private val heading: TextView
    private val bodyTv: TextView
    private val actionBtn: TextView
    private val back: TextView
    private val next: TextView
    private var glide: ValueAnimator? = null

    private fun text(t: String, sp: Float, face: android.graphics.Typeface, color: Int) = TextView(ctx()).apply {
        text = t; textSize = sp; typeface = face; setTextColor(color); includeFontPadding = false
        setLineSpacing(0f, 1.2f)
    }

    private fun ctx(): Context = context

    private fun pillBg(fill: Int, stroke: Int, strokeW: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(40f).toFloat(); if (strokeW > 0) setStroke(strokeW, stroke)
    }

    private fun springPress(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80)
                    .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(SpringInterpolator(18f, 0.5f, 0.8f)).start()
            }
            false
        }
    }

    init {
        orientation = VERTICAL
        setPadding(0, dp(14f), 0, dp(14f))

        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = text("Set up Shortstop", 20f, fonts.bold, pal.ink).apply { letterSpacing = -0.02f }
        head.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        count = text("", 13f, fonts.medium, pal.muted)
        head.addView(count, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(head, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14f) })

        rail = Rail(context)
        addView(rail, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)))

        content = LinearLayout(context).apply {
            orientation = VERTICAL
            minimumHeight = dp(178f)
            setPadding(dp(2f), dp(20f), dp(2f), 0)
        }
        heading = text("", 18f, fonts.bold, pal.ink).apply { letterSpacing = -0.01f }
        bodyTv = text("", 14f, fonts.regular, pal.muted)
        actionBtn = TextView(context).apply {
            textSize = 14f
            typeface = fonts.semi
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(18f), 0, dp(18f), 0)
            isClickable = true
        }
        springPress(actionBtn)
        content.addView(heading, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(bodyTv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        content.addView(actionBtn, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42f)).apply { topMargin = dp(16f) })
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val foot = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        back = text("Back", 14f, fonts.medium, pal.muted).apply {
            setPadding(dp(4f), dp(12f), dp(16f), dp(12f))
            isClickable = true
            setOnClickListener { if (index > 0) goTo(index - 1) }
        }
        next = TextView(context).apply {
            textSize = 14f
            typeface = fonts.semi
            setTextColor(pal.canvas)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = pillBg(pal.ink, 0, 0)
            setPadding(dp(22f), 0, dp(22f), 0)
            isClickable = true
            setOnClickListener { onNext() }
        }
        springPress(next)
        foot.addView(back, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        foot.addView(View(context), LayoutParams(0, 1, 1f))
        foot.addView(next, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))
        addView(foot, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

        rail.onSelect = { i -> if (i != index) goTo(i) }
        bind()
    }

    // ---------------------------------------------------------------- state

    /** states[i] is true once step i is really done. Safe to call every second. */
    fun sync(states: BooleanArray) {
        var firstUndone = -1
        val wasCurrentDone = done[index]
        for (i in 0 until n) {
            val s = states.getOrElse(i) { false }
            if (s != done[i]) {
                done[i] = s
                rail.setCheck(i, s, started)
            }
            if (!s && firstUndone < 0) firstUndone = i
        }
        val all = firstUndone < 0
        if (!started) {
            started = true
            index = if (all) n - 1 else firstUndone
            rail.jump(index)
            bind()
            return
        }
        if (all) {
            if (!allDone) {
                allDone = true
                bind()
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ onAllDone() }, 1100)
            }
            return
        }
        if (allDone) {
            allDone = false
            goTo(firstUndone)
            return
        }
        if (!wasCurrentDone && done[index] && firstUndone != index) {
            handler.postDelayed({ if (done[index] && !allDone) goTo(firstUndone) }, 650)
        }
        refreshState()
    }

    private fun goTo(i: Int) {
        if (i == index || i !in 0 until n) { refreshState(); return }
        val dir = if (i > index) 1f else -1f
        index = i
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        rail.glideTo(i)
        content.animate().cancel()
        content.animate().alpha(0f).translationX(-dir * dp(22f)).setDuration(110)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                bind()
                content.translationX = dir * dp(22f)
                content.animate().alpha(1f).translationX(0f).setDuration(520)
                    .setInterpolator(SpringInterpolator(15f, 0.75f, 0.9f)).start()
            }.start()
    }

    private fun bind() {
        count.text = if (allDone) "Done" else "${index + 1} / $n"
        if (allDone) {
            heading.text = "You're all set"
            bodyTv.text = "Shortstop is on and will keep running. Start a focus session whenever you're ready."
            actionBtn.visibility = View.GONE
            refreshState()
            return
        }
        val s = steps[index]
        heading.text = s.title
        bodyTv.text = s.body
        actionBtn.visibility = View.VISIBLE
        refreshState()
    }

    private fun refreshState() {
        count.text = if (allDone) "Done" else "${index + 1} / $n"
        val s = steps[index]
        if (!allDone) {
            if (done[index] && !s.manual) {
                actionBtn.text = "✓  Done"
                actionBtn.setTextColor(pal.muted)
                actionBtn.background = pillBg(Color.TRANSPARENT, pal.border, dp(1f))
                actionBtn.isClickable = false
                actionBtn.setOnClickListener(null)
            } else {
                actionBtn.text = s.action
                actionBtn.setTextColor(pal.ink)
                actionBtn.background = pillBg(pal.canvas, pal.border, dp(1f))
                actionBtn.isClickable = true
                actionBtn.setOnClickListener { s.open() }
            }
        }
        val last = index == n - 1
        next.text = if (allDone) "Close" else if (last) "Finish" else "Continue  ›"
        val enabled = allDone || (if (last) done[index] else (s.manual || done[index]))
        next.animate().cancel()
        next.animate().alpha(if (enabled) 1f else 0.38f).setDuration(160).start()
        val canBack = index > 0 && !allDone
        back.animate().cancel()
        back.animate().alpha(if (canBack) 1f else 0f).setDuration(160).start()
        back.isClickable = canBack
    }

    private fun onNext() {
        if (allDone) { onAllDone(); return }
        val s = steps[index]
        val last = index == n - 1
        val ok = if (last) done[index] else (s.manual || done[index])
        if (!ok) { shake(next); return }
        if (s.manual && !done[index]) {
            done[index] = true
            rail.setCheck(index, true, true)
            onAck(index)
        }
        if (last) {
            if (done.all { it }) onAllDone()
        } else goTo(index + 1)
    }

    private fun shake(v: View) {
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 340
        a.addUpdateListener {
            val f = it.animatedValue as Float
            v.translationX = Math.sin(f * Math.PI * 4).toFloat() * dp(6f) * (1f - f)
        }
        a.start()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        glide?.cancel()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- the rail

    private inner class Rail(ctx: Context) : View(ctx) {
        var onSelect: (Int) -> Unit = {}
        private var pos = 0f
        private val chk = FloatArray(n)
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mixColor(pal.surface, pal.ink, 0.075f) }
        private val pillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.canvas }
        private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = pal.border; strokeWidth = d
        }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = fonts.semi }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d }
        private val fillC = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.7f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val r = RectF()
        private val p = Path()
        private val overshoot = OvershootInterpolator(2.2f)

        init { isClickable = true }

        fun jump(i: Int) { glide?.cancel(); pos = i.toFloat(); invalidate() }

        fun glideTo(i: Int) {
            glide?.cancel()
            val from = pos
            glide = ValueAnimator.ofFloat(from, i.toFloat()).apply {
                duration = 560
                interpolator = SpringInterpolator(14f, 0.72f, 0.95f)
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun setCheck(i: Int, on: Boolean, animate: Boolean) {
            val to = if (on) 1f else 0f
            if (!animate) { chk[i] = to; invalidate(); return }
            ValueAnimator.ofFloat(chk[i], to).apply {
                duration = 420
                addUpdateListener { chk[i] = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(46f))
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val pad = 4f * d
                val iw = (width - 2 * pad) / n
                onSelect(((e.x - pad) / iw).toInt().coerceIn(0, n - 1))
                performClick()
            }
            return super.onTouchEvent(e)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            r.set(0f, 0f, w, h)
            c.drawRoundRect(r, h / 2f, h / 2f, bg)
            val pad = 4f * d
            val iw = (w - 2f * pad) / n
            val left = pad + pos * iw
            val ph = h - 2f * pad
            // the raised pill that glides
            for (k in 3 downTo 1) {
                shadowP.color = Color.argb(if (pal.dark) 18 else 10, 0, 0, 0)
                r.set(left - k * d * 0.6f, pad + k * d * 0.5f, left + iw + k * d * 0.6f, h - pad + k * d * 1.2f)
                c.drawRoundRect(r, ph / 2f + k * d, ph / 2f + k * d, shadowP)
            }
            r.set(left, pad, left + iw, h - pad)
            c.drawRoundRect(r, ph / 2f, ph / 2f, pillP)
            r.inset(d * 0.5f, d * 0.5f)
            c.drawRoundRect(r, ph / 2f, ph / 2f, pillEdge)

            for (i in 0 until n) {
                val cx = pad + i * iw + iw / 2f
                val act = (1f - Math.abs(pos - i)).coerceIn(0f, 1f)
                txt.typeface = if (act > 0.5f) fonts.semi else fonts.medium
                txt.textSize = 12.5f * sd
                txt.color = mixColor(pal.muted, pal.ink, act)
                val label = steps[i].tab
                val lw = txt.measureText(label)
                val circ = 18f * d
                val gap = 6f * d
                val sx = cx - (circ + gap + lw) / 2f
                val ccx = sx + circ / 2f
                val ccy = h / 2f
                val t = chk[i]
                if (t > 0.01f) {
                    val sc = overshoot.getInterpolation(t.coerceIn(0f, 1f))
                    fillC.color = mixColor(pal.muted, pal.ink, 1f)
                    c.drawCircle(ccx, ccy, circ / 2f * sc, fillC)
                    tick.color = pal.canvas
                    tick.alpha = (255 * t.coerceIn(0f, 1f)).toInt()
                    val u = circ / 18f * sc
                    p.reset()
                    p.moveTo(ccx - 4f * u, ccy + 0.3f * u)
                    p.lineTo(ccx - 1.2f * u, ccy + 3f * u)
                    p.lineTo(ccx + 4.2f * u, ccy - 3f * u)
                    c.drawPath(p, tick)
                } else {
                    ring.color = mixColor(pal.muted, pal.ink, act)
                    c.drawCircle(ccx, ccy, circ / 2f - 0.7f * d, ring)
                    num.color = ring.color
                    num.textSize = 10.5f * sd
                    c.drawText((i + 1).toString(), ccx, ccy - (num.ascent() + num.descent()) / 2f, num)
                }
                c.drawText(label, sx + circ + gap, ccy - (txt.ascent() + txt.descent()) / 2f, txt)
            }
        }
    }
}

/** Collapses a card to nothing, then hides it. */
fun collapseAway(v: View) {
    if (v.getTag(0x7f0a0002) == true || v.visibility != View.VISIBLE) return
    v.setTag(0x7f0a0002, true)
    val h = v.height.coerceAtLeast(1)
    val lp = v.layoutParams
    ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 460
        interpolator = DecelerateInterpolator(1.5f)
        addUpdateListener {
            val f = it.animatedValue as Float
            lp.height = (h * f).toInt().coerceAtLeast(1)
            v.alpha = f
            v.requestLayout()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                v.visibility = View.GONE
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                v.alpha = 1f
                v.setTag(0x7f0a0002, null)
                v.requestLayout()
            }
        })
        start()
    }
}
