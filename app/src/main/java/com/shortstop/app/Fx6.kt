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
 * First-run setup as a stepper (react-bits Stepper). The rail is a row of numbered circles joined by lines that fill
 * as you go; the current step swells into a black pill that carries its name, so labels never collide. Below it a
 * panel slides across with the step, and exactly one button is the main one at any moment: the step's action until
 * Android confirms it, then Continue. It watches the real state, so coming back from Settings advances it by itself.
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
    private val panel: Panel
    private val eyebrow: TextView
    private val heading: TextView
    private val bodyTv: TextView
    private val actionBtn: Btn
    private val back: TextView
    private val next: Btn
    private var glide: ValueAnimator? = null

    private fun text(t: String, sp: Float, face: android.graphics.Typeface, color: Int) = TextView(context).apply {
        text = t; textSize = sp; typeface = face; setTextColor(color); includeFontPadding = false
        setLineSpacing(0f, 1.25f)
    }

    private fun springPress(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                    .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(SpringInterpolator(18f, 0.5f, 0.8f)).start()
            }
            false
        }
    }

    /** A pill button whose fill, outline and text colours cross-fade between styles. */
    private inner class Btn(ctx: Context) : TextView(ctx) {
        private var fill = pal.canvas
        private var line = pal.border
        private var ink = pal.ink
        private var anim: ValueAnimator? = null
        private val bgd = GradientDrawable().apply { cornerRadius = dp(28f).toFloat() }

        init {
            background = bgd
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = fonts.semi
            textSize = 15f
            isClickable = true
            paintTo(fill, line, ink)
            springPress(this)
        }

        private fun paintTo(f: Int, s: Int, t: Int) {
            fill = f; line = s; ink = t
            bgd.setColor(f)
            bgd.setStroke(dp(1f), s)
            setTextColor(t)
        }

        fun style(f: Int, s: Int, t: Int) {
            if (f == fill && s == line && t == ink) return
            anim?.cancel()
            if (!isAttachedToWindow) { paintTo(f, s, t); return }
            val f0 = fill; val s0 = line; val t0 = ink
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240
                addUpdateListener {
                    val k = it.animatedFraction
                    paintTo(mixColor(f0, f, k), mixColor(s0, s, k), mixColor(t0, t, k))
                }
                start()
            }
        }

        fun primary() = style(pal.ink, pal.ink, pal.canvas)
        fun quiet(muted: Boolean = false) = style(pal.canvas, pal.border, if (muted) pal.muted else pal.ink)
    }

    /** The step card: a bordered panel with the step number as a faint watermark. */
    private inner class Panel(ctx: Context) : LinearLayout(ctx) {
        var mark = ""
        private val wm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.bold; color = pal.ink; alpha = 14; textAlign = Paint.Align.RIGHT; letterSpacing = -0.04f
        }
        init {
            setWillNotDraw(false)
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.canvas); cornerRadius = dp(22f).toFloat(); setStroke(dp(1f), pal.border)
            }
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            minimumHeight = dp(196f)
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (mark.isEmpty()) return
            wm.textSize = 84f * sd
            c.drawText(mark, width - dp(14f).toFloat(), dp(14f) + wm.textSize * 0.78f, wm)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(0, dp(16f), 0, dp(16f))

        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = text("Set up Shortstop", 20f, fonts.bold, pal.ink).apply { letterSpacing = -0.02f }
        head.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        count = text("", 13f, fonts.medium, pal.muted)
        head.addView(count, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(head, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16f) })

        rail = Rail(context)
        addView(rail, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40f)))

        panel = Panel(context)
        eyebrow = text("", 11f, fonts.semi, pal.muted).apply { letterSpacing = 0.14f }
        heading = text("", 20f, fonts.bold, pal.ink).apply { letterSpacing = -0.015f }
        bodyTv = text("", 14.5f, fonts.regular, pal.muted)
        actionBtn = Btn(context)
        panel.addView(eyebrow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(heading, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        panel.addView(bodyTv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        panel.addView(View(context), LayoutParams(1, 0, 1f))
        panel.addView(actionBtn, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)).apply { topMargin = dp(20f) })
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) })

        val foot = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        back = text("‹  Back", 14.5f, fonts.medium, pal.muted).apply {
            setPadding(dp(4f), dp(14f), dp(20f), dp(14f))
            isClickable = true
            setOnClickListener { if (index > 0) goTo(index - 1) }
        }
        next = Btn(context).apply {
            setPadding(dp(28f), 0, dp(28f), 0)
            minWidth = dp(140f)
            setOnClickListener { onNext() }
        }
        foot.addView(back, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        foot.addView(View(context), LayoutParams(0, 1, 1f))
        foot.addView(next, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48f)))
        addView(foot, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) })

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
        panel.animate().cancel()
        panel.animate().alpha(0f).translationX(-dir * dp(20f)).setDuration(110)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                bind()
                panel.translationX = dir * dp(20f)
                panel.animate().alpha(1f).translationX(0f).setDuration(520)
                    .setInterpolator(SpringInterpolator(15f, 0.75f, 0.9f)).start()
            }.start()
    }

    private fun bind() {
        if (allDone) {
            eyebrow.text = "ALL DONE"
            panel.mark = "✓"
            heading.text = "You're all set"
            bodyTv.text = "Shortstop is on and will keep running. Start a focus session whenever you're ready."
            actionBtn.visibility = View.GONE
            panel.invalidate()
            refreshState()
            return
        }
        val s = steps[index]
        eyebrow.text = "STEP ${index + 1} OF $n"
        panel.mark = "${index + 1}"
        heading.text = s.title
        bodyTv.text = s.body
        actionBtn.visibility = View.VISIBLE
        panel.invalidate()
        refreshState()
    }

    private fun refreshState() {
        val tally = done.count { it }
        count.text = if (allDone) "Done" else "$tally of $n done"
        val last = index == n - 1
        val enabled = allDone || (if (last) done[index] else (steps[index].manual || done[index]))
        if (!allDone) {
            val s = steps[index]
            if (done[index] && !s.manual) {
                actionBtn.text = "✓  Done"
                actionBtn.quiet(true)
                actionBtn.isClickable = false
                actionBtn.setOnClickListener(null)
            } else {
                actionBtn.text = s.action
                if (enabled) actionBtn.quiet() else actionBtn.primary()
                actionBtn.isClickable = true
                actionBtn.setOnClickListener { s.open() }
            }
        }
        next.text = if (allDone) "Close" else if (last) "Finish" else "Continue  ›"
        if (enabled) next.primary() else next.quiet(true)
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
        private val xs = FloatArray(n)
        private val ws = FloatArray(n)
        private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND; color = pal.border
        }
        private val fillLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND; color = pal.ink
        }
        private val pillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
        private val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.semi }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = fonts.semi }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.8f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val r = RectF()
        private val p = Path()
        private val overshoot = OvershootInterpolator(2.2f)
        private val itemH get() = 34f * d

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
            setMeasuredDimension(MeasureSpec.getSize(w), dp(40f))
        }

        private fun layoutItems(w: Float) {
            val h = itemH
            lab.textSize = 13.5f * sd
            var total = 0f
            for (i in 0 until n) {
                val act = (1f - Math.abs(pos - i)).coerceIn(0f, 1f)
                ws[i] = h + act * (8f * d + lab.measureText(steps[i].tab) + 16f * d)
                total += ws[i]
            }
            val gap = if (n > 1) ((w - total) / (n - 1)).coerceAtLeast(14f * d) else 0f
            var x = 0f
            for (i in 0 until n) { xs[i] = x; x += ws[i] + gap }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                layoutItems(width.toFloat())
                var best = 0
                var bd = Float.MAX_VALUE
                for (i in 0 until n) {
                    val dist = if (e.x < xs[i]) xs[i] - e.x else if (e.x > xs[i] + ws[i]) e.x - xs[i] - ws[i] else 0f
                    if (dist < bd) { bd = dist; best = i }
                }
                onSelect(best)
                performClick()
            }
            return super.onTouchEvent(e)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = itemH
            val top = (height - h) / 2f
            val cy = height / 2f
            layoutItems(w)

            // lines between the steps, filling as you move on
            for (i in 0 until n - 1) {
                val a = xs[i] + ws[i] + 6f * d
                val b = xs[i + 1] - 6f * d
                if (b <= a) continue
                c.drawLine(a, cy, b, cy, trackP)
                val f = Math.max((pos - i).coerceIn(0f, 1f), chk[i])
                if (f > 0.01f) c.drawLine(a, cy, a + (b - a) * f, cy, fillLine)
            }

            for (i in 0 until n) {
                val act = (1f - Math.abs(pos - i)).coerceIn(0f, 1f)
                val t = chk[i].coerceIn(0f, 1f)
                val ccx = xs[i] + h / 2f
                // the black pill of the current step
                if (act > 0.01f) {
                    pillP.alpha = (255 * act).toInt()
                    r.set(xs[i], top, xs[i] + ws[i], top + h)
                    c.drawRoundRect(r, h / 2f, h / 2f, pillP)
                }
                // outline: muted while waiting, ink once done, hidden inside the pill
                ring.color = mixColor(pal.border, pal.ink, Math.max(t, 0.0f))
                if (t < 0.01f) ring.color = mixColor(pal.muted, pal.border, 0.4f)
                ring.alpha = (255 * (1f - act)).toInt()
                if (ring.alpha > 0) c.drawCircle(ccx, cy, h / 2f - 0.75f * d, ring)

                val onInk = mixColor(pal.ink, pal.canvas, act)
                if (t > 0.01f) {
                    val sc = overshoot.getInterpolation(t)
                    tick.color = onInk
                    tick.alpha = (255 * t).toInt()
                    val u = (h / 34f) * sc
                    p.reset()
                    p.moveTo(ccx - 5f * d * u, cy + 0.4f * d * u)
                    p.lineTo(ccx - 1.5f * d * u, cy + 3.6f * d * u)
                    p.lineTo(ccx + 5.2f * d * u, cy - 3.6f * d * u)
                    c.drawPath(p, tick)
                } else {
                    num.color = mixColor(pal.muted, pal.canvas, act)
                    num.textSize = 12.5f * sd
                    c.drawText((i + 1).toString(), ccx, cy - (num.ascent() + num.descent()) / 2f, num)
                }
                if (act > 0.35f) {
                    val la = ((act - 0.35f) / 0.65f)
                    lab.color = pal.canvas
                    lab.alpha = (255 * la * la).toInt()
                    c.drawText(steps[i].tab, xs[i] + h + 2f * d, cy - (lab.ascent() + lab.descent()) / 2f, lab)
                }
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
