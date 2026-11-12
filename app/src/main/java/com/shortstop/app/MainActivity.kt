package com.shortstop.app

import android.animation.ArgbEvaluator
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Arc-style control panel built in code (no dependencies). Light and dark palettes, grain, scalloped dividers. */
class MainActivity : Activity() {

    private val blue = Color.parseColor("#3B3BFF")
    private val indigo = Color.parseColor("#1A1A8C")
    private val white = Color.WHITE
    private val matchP = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapC = ViewGroup.LayoutParams.WRAP_CONTENT

    // Palette, set by applyPalette()
    private var darkMode = false
    private var bandBg = 0
    private var accent = 0
    private var muted = 0
    private var trackOff = 0
    private var rule = 0
    private var chipText = 0

    private var scroll: ScrollView? = null
    private var entrance = true            // play the staggered entrance only once
    private var lockPick = 30              // minutes chosen for the next focus lock
    private var lockText: TextView? = null
    private var wasLocked = false
    private val ui = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (Store.locked(this@MainActivity)) {
                lockText?.text = "LOCKED · ${lockString()} LEFT"
                ui.postDelayed(this, 1000)
            } else if (wasLocked) {
                wasLocked = false
                render()
            }
        }
    }

    /** False (with a short message) while a focus lock is running: nothing may be switched off. */
    private fun free(): Boolean {
        if (!Store.locked(this)) return true
        Toast.makeText(this, "Focus lock: ${Store.lockLeftMin(this)} min left. Nothing can change until it ends.",
            Toast.LENGTH_SHORT).show()
        return false
    }

    private fun lockString(): String {
        val sec = Store.lockLeftMs(this) / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val x = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, x) else String.format("%02d:%02d", m, x)
    }

    // ---------- fonts ----------
    private fun font(file: String, fallback: Typeface): Typeface =
        try { Typeface.createFromAsset(assets, "fonts/$file") } catch (e: Exception) { fallback }

    // Display: Nunito Black, bundled in assets/fonts.
    private val fDisplay by lazy { font("Nunito-Black.ttf", Typeface.create("sans-serif-black", Typeface.NORMAL)) }
    private val body by lazy { font("Inter-Regular.otf", Typeface.DEFAULT) }
    private val mono by lazy { font("IBMPlexMono-Bold.ttf", Typeface.create("monospace", Typeface.BOLD)) }

    // ---------- grain ----------
    private val grainBmp: Bitmap by lazy {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val r = java.util.Random(7)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, Color.argb(r.nextInt(46), 255, 255, 255))
        bmp
    }

    private fun grainOn(base: Int): Drawable {
        val tile = BitmapDrawable(resources, grainBmp).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        return LayerDrawable(arrayOf<Drawable>(ColorDrawable(base), tile))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    // ---------- theme ----------
    private fun applyPalette() {
        darkMode = when (Store.theme(this)) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        bandBg = Color.parseColor(if (darkMode) "#0E0E24" else "#FFFEF0")
        accent = if (darkMode) Color.parseColor("#9A9AFF") else blue
        muted = Color.parseColor(if (darkMode) "#A4A4C8" else "#6B6B8C")
        trackOff = Color.parseColor(if (darkMode) "#3A3A5C" else "#CFCFE6")
        rule = if (darkMode) Color.parseColor("#33FFFFFF") else Color.parseColor("#2E3B3BFF")
        chipText = if (darkMode) white else indigo
        window.statusBarColor = indigo
        window.navigationBarColor = bandBg
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        render()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.split(':').any { it.startsWith("$packageName/") }
    }

    // ---------- custom views ----------
    /** Row of half-circles, drawn over a [bg] strip. Used for the Arc-style zone dividers. */
    private inner class ScallopView(
        ctx: Context,
        private val bg: Int,
        private val bgGrain: Boolean,
        private val fg: Int,
        private val fgGrain: Boolean
    ) : View(ctx) {
        private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
        private val noise = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(grainBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        private val tileW = dp(32).toFloat()
        private val tileH = dp(14).toFloat()
        private val oval = RectF()

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), tileH.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            solid.color = bg
            canvas.drawRect(0f, 0f, w, tileH, solid)
            if (bgGrain) canvas.drawRect(0f, 0f, w, tileH, noise)
            solid.color = fg
            var x = 0f
            while (x < w) {
                oval.set(x, -tileH, x + tileW, tileH)
                canvas.drawArc(oval, 0f, 180f, true, solid)
                if (fgGrain) canvas.drawArc(oval, 0f, 180f, true, noise)
                x += tileW
            }
        }
    }

    /** The circular "NO MORE SWIPING" sticker from the design file. */
    private inner class StampView(ctx: Context) : View(ctx) {
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = indigo }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; typeface = mono; letterSpacing = 0.12f }
        private val tri = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
        private val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blue; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(c: Canvas) {
            val s = width.toFloat()
            val mid = s / 2f
            c.drawCircle(mid, mid, mid, disc)

            val path = Path().apply { addCircle(mid, mid, s * 0.36f, Path.Direction.CW) }
            ring.textSize = s * 0.078f
            c.drawTextOnPath("NO MORE SWIPING · NO MORE SWIPING · ", path, 0f, 0f, ring)

            val t = Path().apply {
                moveTo(s * 0.42f, s * 0.34f); lineTo(s * 0.42f, s * 0.66f); lineTo(s * 0.68f, s * 0.5f); close()
            }
            c.drawPath(t, tri)
            slash.strokeWidth = s * 0.06f
            c.drawLine(s * 0.3f, s * 0.7f, s * 0.7f, s * 0.3f, slash)
        }
    }

    /** Hand-drawn on/off switch: 46x26 dp track, round knob that glides and always stays inside. */
    private inner class ToggleView(ctx: Context, private var on: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
        private val rect = RectF()
        private val eval = ArgbEvaluator()
        private var p = if (on) 1f else 0f

        init {
            isClickable = true
            isFocusable = true
            contentDescription = if (on) "On" else "Off"
            setOnClickListener {
                if (!free()) return@setOnClickListener
                on = !on
                contentDescription = if (on) "On" else "Off"
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                ValueAnimator.ofFloat(p, if (on) 1f else 0f).apply {
                    duration = 200
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { p = it.animatedValue as Float; invalidate() }
                    start()
                }
                onChange(on)
            }
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(dp(46), dp(26))
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            trackPaint.color = eval.evaluate(p, trackOff, blue) as Int
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)
            val pad = dp(3).toFloat()
            val r = h / 2f - pad
            val left = pad + r
            val right = w - pad - r
            c.drawCircle(left + (right - left) * p, h / 2f, r, knobPaint)
        }
    }

    /** Bar chart; bars grow in on first show. [highlight] is the bar drawn at full strength. */
    private inner class BarChart(
        ctx: Context,
        private val values: List<Int>,
        private val labels: List<String>,
        private val highlight: Int,
        private val animate: Boolean
    ) : View(ctx) {
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textAlign = Paint.Align.CENTER }
        private var grow = if (animate) 0f else 1f

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (animate) {
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 700
                    startDelay = 250
                    interpolator = DecelerateInterpolator(1.8f)
                    addUpdateListener { grow = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(130))
        }

        override fun onDraw(c: Canvas) {
            val n = values.size
            val top = maxOf(1, values.maxOrNull() ?: 1)
            val gap = dp(if (n > 8) 6 else 10).toFloat()
            val bw = (width - gap * (n - 1)) / n
            val labelH = dp(22).toFloat()
            val baseY = height - labelH
            val usable = baseY - dp(18)
            txt.textSize = dp(11).toFloat()
            for (i in 0 until n) {
                val left = i * (bw + gap)
                val h = maxOf(dp(3).toFloat(), values[i].toFloat() / top * usable * grow)
                bar.color = accent
                bar.alpha = if (i == highlight) 255 else 120
                c.drawRoundRect(RectF(left, baseY - h, left + bw, baseY), dp(5).toFloat(), dp(5).toFloat(), bar)
                txt.color = muted
                txt.alpha = (grow * 255).toInt()
                if (values[i] > 0) c.drawText(values[i].toString(), left + bw / 2f, baseY - h - dp(4), txt)
                txt.alpha = 255
                c.drawText(labels[i], left + bw / 2f, height - dp(6).toFloat(), txt)
            }
        }
    }

    /** Thin progress bar that fills in. */
    private inner class MeterView(ctx: Context, private val frac: Float, private val animate: Boolean) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var grow = if (animate) 0f else 1f

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (animate) {
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 700
                    startDelay = 300
                    interpolator = DecelerateInterpolator(1.8f)
                    addUpdateListener { grow = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(8))
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.color = trackOff
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, h / 2f, h / 2f, paint)
            paint.color = accent
            rect.set(0f, 0f, maxOf(h, w * frac.coerceIn(0f, 1f) * grow), h)
            c.drawRoundRect(rect, h / 2f, h / 2f, paint)
        }
    }

    // ---------- small builders ----------
    private fun text(t: String, sp: Float, color: Int, face: Typeface, gravity: Int = Gravity.START) =
        TextView(this).apply {
            text = t; textSize = sp; setTextColor(color); typeface = face; this.gravity = gravity
        }

    private fun label(t: String, color: Int) =
        text(t.uppercase(), 11f, color, mono).apply { letterSpacing = 0.08f }

    private fun sectionLabel(t: String) = label(t, muted).apply { setPadding(0, dp(28), 0, dp(8)) }

    private fun pillButton(t: String, fill: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        text = t.uppercase()
        typeface = mono
        textSize = 12f
        letterSpacing = 0.05f
        setTextColor(fg)
        stateListAnimator = null
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(12).toFloat() }
        setPadding(dp(24), dp(12), dp(24), dp(12))
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setOnClickListener { onClick() }
        // Springy press feedback; returns false so the click still fires.
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator()).start()
            }
            false
        }
    }

    private fun styleChip(b: Button, selected: Boolean) {
        (b.background as GradientDrawable).setColor(if (selected) blue else trackOff)
        b.setTextColor(if (selected) white else chipText)
    }

    private fun chip(t: String, selected: Boolean, onClick: () -> Unit) =
        pillButton(t, if (selected) blue else trackOff, if (selected) white else chipText, onClick)
            .apply { textSize = 11f; setPadding(dp(6), dp(10), dp(6), dp(10)) }

    private fun stepBtn(t: String, onClick: () -> Unit) =
        pillButton(t, indigo, white, onClick).apply { textSize = 14f; setPadding(dp(14), dp(6), dp(14), dp(6)) }

    private fun timeBtn(t: String, onClick: () -> Unit) =
        pillButton(t, trackOff, chipText, onClick).apply { textSize = 14f; setPadding(dp(14), dp(8), dp(14), dp(8)) }

    private fun hairline() = View(this).apply { setBackgroundColor(rule) }

    private class SwitchRow(val view: View, val sub: TextView)

    private fun switchRow(
        title: String, sub: String, checked: Boolean,
        rerender: Boolean = false, onChange: (Boolean) -> Unit
    ): SwitchRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(10))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val subTv = label(sub, muted)
        col.addView(text(title, 18f, accent, fDisplay))
        col.addView(subTv)
        row.addView(col, lp(0, wrapC, 1f))
        row.addView(ToggleView(this, checked) { v ->
            onChange(v)
            if (rerender) window.decorView.postDelayed({ render() }, 240)
        })
        return SwitchRow(row, subTv)
    }

    private fun fmt(minutes: Int) = String.format("%02d:%02d", minutes / 60, minutes % 60)

    private fun pickTime(initial: Int, onPick: (Int) -> Unit) {
        if (!free()) return
        TimePickerDialog(
            this,
            TimePickerDialog.OnTimeSetListener { _, h, m -> onPick(h * 60 + m) },
            initial / 60, initial % 60, DateFormat.is24HourFormat(this)
        ).show()
    }

    // ---------- sections ----------
    /** A "− / +" row. Updates its own caption in place, so nothing re-draws. */
    private fun stepper(
        caption: (Int) -> String, start: Int, min: Int, max: Int, step: Int, onSet: (Int) -> Unit
    ): View {
        var v = start
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val cap = label(caption(v), muted)
        fun change(d: Int) {
            if (!free()) return
            v = (v + d).coerceIn(min, max)
            onSet(v)
            cap.text = caption(v).uppercase()
        }
        row.addView(cap, lp(0, wrapC, 1f))
        row.addView(stepBtn("−") { change(-step) })
        row.addView(stepBtn("+") { change(step) }, lp(wrapC, wrapC).apply { marginStart = dp(8) })
        box.addView(row)
        box.addView(hairline(), lp(matchP, dp(1)))
        return box
    }

    private fun allowanceRow(key: String): View = stepper(
        { m -> if (m == 0) "Allowance: none" else "Allowance: $m min/day, used ${Store.usedSeconds(this, key) / 60}" },
        Store.allowMinutes(this, key), 0, 120, 5
    ) { Store.setAllowMinutes(this, key, it) }

    private fun scheduleEditor(refill: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ws = Store.windows(this)
        if (ws.isEmpty()) {
            box.addView(label("No windows yet. Add one below.", muted).apply { setPadding(0, dp(6), 0, dp(10)) })
        }
        ws.forEachIndexed { i, w ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); setStroke(dp(1), rule); cornerRadius = dp(12).toFloat()
                }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(timeBtn(fmt(w.start)) { pickTime(w.start) { m -> Store.updateWindow(this, i, w.copy(start = m)); refill() } })
            top.addView(label("to", muted).apply { setPadding(dp(10), 0, dp(10), 0) })
            top.addView(timeBtn(fmt(w.end)) { pickTime(w.end) { m -> Store.updateWindow(this, i, w.copy(end = m)); refill() } })
            top.addView(View(this), lp(0, 1, 1f))
            top.addView(stepBtn("✕") { if (free()) { Store.removeWindow(this, i); refill() } })
            card.addView(top)

            val days = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            "MTWTFSS".forEachIndexed { d, ch ->
                val on = ((w.days shr d) and 1) == 1
                days.addView(
                    chip(ch.toString(), on) {
                        if (free()) { Store.updateWindow(this, i, w.copy(days = w.days xor (1 shl d))); refill() }
                    },
                    lp(0, wrapC, 1f).apply { if (d < 6) marginEnd = dp(4) }
                )
            }
            card.addView(days)
            box.addView(card, lp(matchP, wrapC).apply { bottomMargin = dp(10) })
        }

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun preset(name: String, w: Window, last: Boolean = false) {
            presets.addView(chip("+ $name", false) { if (free()) { Store.addWindow(this, w); refill() } },
                lp(0, wrapC, 1f).apply { if (!last) marginEnd = dp(6) })
        }
        preset("Work", Window(9 * 60, 17 * 60, 31))
        preset("Study", Window(18 * 60, 22 * 60, 127))
        preset("Night", Window(0, 6 * 60, 127))
        preset("Custom", Window(9 * 60, 17 * 60, 127), true)
        box.addView(presets)
        box.addView(label("Work 9-5 weekdays · Study 6-10 pm · Night 12-6 am", muted).apply { setPadding(0, dp(8), 0, 0) })
        return box
    }

    private fun summaryText(): String {
        val week = (0..6).map { Store.blockedOn(this, it) }.sum()
        val prev = (7..13).map { Store.blockedOn(this, it) }.sum()
        val bestAgo = (0..6).maxByOrNull { Store.blockedOn(this, it) } ?: 0
        val bestN = Store.blockedOn(this, bestAgo)
        val saved = week * 40 / 60
        val trend = when {
            prev == 0 && week == 0 -> ""
            prev == 0 -> " First week of data."
            week < prev -> " Down ${(prev - week) * 100 / prev}% from last week."
            week > prev -> " Up ${(week - prev) * 100 / prev}% from last week."
            else -> " Same as last week."
        }
        val busiest = if (bestN > 0) " Busiest day: ${Store.dayName(bestAgo)} ($bestN)." else ""
        val used = Store.usedTodaySeconds(this) / 60
        val allowance = if (used > 0) " Allowance used today: $used min." else ""
        return "This week: $week blocked, about $saved min saved.$trend$busiest$allowance"
    }

    private fun hourName(h: Int): String {
        val x = h % 12
        return "${if (x == 0) 12 else x} ${if (h % 24 < 12) "am" else "pm"}"
    }

    private fun peakText(): String {
        val by = Store.blocksByHour(this)
        val b = (0..7).map { i -> by.subList(i * 3, i * 3 + 3).sum() }
        val out = StringBuilder()
        if (by.sum() == 0) {
            out.append("Your most tempting hours will show up after a few blocks.")
        } else {
            val best = b.indices.maxByOrNull { b[it] } ?: 0
            out.append("Most tempting: ${hourName(best * 3)} to ${hourName((best * 3 + 3) % 24)} (${b[best]} blocks).")
        }
        val today = Store.today(this)
        val before = (1..6).map { Store.blockedOn(this, it) }.sum() / 6
        if (today >= 5 && today > before * 2) out.append(" Heavier than usual today. A focus lock can help.")
        return out.toString()
    }

    private fun stat(n: Int, l: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text("$n", 36f, accent, fDisplay))
        addView(label(l, accent))
    }

    private fun rewardRow(title: String, value: Int, targets: List<Int>, unit: String, animate: Boolean): View {
        val next = targets.firstOrNull { it > value }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(10)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(title, 16f, accent, fDisplay), lp(0, wrapC, 1f))
        top.addView(label(if (next != null) "$value / $next $unit" else "$value $unit · done", muted))
        box.addView(top)
        val frac = if (next == null) 1f else value.toFloat() / next
        box.addView(MeterView(this, frac, animate), lp(matchP, wrapC).apply { topMargin = dp(6) })
        return box
    }

    private fun earnedText(streak: Int, total: Int, mins: Int): String {
        val e = mutableListOf<String>()
        listOf(3, 7, 14, 30, 60, 100).filter { it <= streak }.forEach { e.add("$it-day streak") }
        listOf(10, 50, 100, 500, 1000, 5000).filter { it <= total }.forEach { e.add("$it blocks") }
        listOf(60, 300, 1440, 6000).filter { it <= mins }.forEach { e.add("${it / 60} h reclaimed") }
        return if (e.isEmpty()) "No badges yet. The first one is a few blocks away." else "Earned: " + e.joinToString(" · ")
    }

    private fun animateIn(views: List<View>) {
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dp(18).toFloat()
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(minOf(i, 16) * 40L).setDuration(380)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    // ---------- screen ----------
    private fun render() {
        applyPalette()
        val first = entrance
        entrance = false
        val keepY = scroll?.scrollY ?: 0
        val snoozeLeft = Store.snoozeUntil(this) - System.currentTimeMillis()
        val serviceEnabled = serviceOn()
        val offSchedule = Store.scheduleOn(this) && !Store.inSchedule(this)
        val locked = Store.locked(this)
        wasLocked = locked
        lockText = null

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        column.addView(
            text("Your attention, un-hijacked ✦", 13f, white, body, Gravity.CENTER).apply {
                setBackgroundColor(indigo); setPadding(dp(16), dp(10), dp(16), dp(10))
            }
        )
        column.addView(ScallopView(this, blue, true, indigo, false), lp(matchP, wrapC))

        // Hero
        val heroFrame = FrameLayout(this).apply { background = grainOn(blue) }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }
        hero.addView(text("Shortstop", 18f, white, fDisplay))

        val (headline, lede, actionLabel, action) = when {
            !serviceEnabled -> Quad(
                "Almost there.",
                "Turn on the Shortstop accessibility service so it can spot Shorts and Reels.",
                "Enable service"
            ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            locked -> Quad(
                "Locked in.", "Focus lock is on. Every supported app closes on sight until the timer ends.", "Locked"
            ) { free() }
            !Store.enabled(this) -> Quad(
                "Blocking is off.", "Shorts and Reels are back. Switch on when you want your thumb back.", "Turn on"
            ) { Store.setEnabled(this, true); render() }
            snoozeLeft > 0 -> Quad(
                "Snoozed ${(snoozeLeft + 59999) / 60000}m.", "Blocking resumes on its own.", "Resume now"
            ) { Store.setSnooze(this, 0); render() }
            offSchedule -> Quad(
                "Off the clock.", "Outside your schedule. Blocking starts again in your next window.", "Turn off"
            ) { Store.setEnabled(this, false); render() }
            else -> Quad(
                "Shorts are off.", "Back is pressed for you the moment a Short or Reel appears.", "Turn off"
            ) { Store.setEnabled(this, false); render() }
        }
        hero.addView(text(headline, 40f, white, fDisplay, Gravity.CENTER).apply { setPadding(0, dp(28), 0, dp(12)) })
        hero.addView(text(lede, 15f, white, body, Gravity.CENTER).apply { setPadding(0, 0, 0, dp(24)) })
        hero.addView(pillButton(actionLabel, white, blue, action))
        heroFrame.addView(hero, FrameLayout.LayoutParams(matchP, wrapC))
        val stamp = StampView(this).apply { rotation = 12f }
        heroFrame.addView(
            stamp,
            FrameLayout.LayoutParams(dp(72), dp(72), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8); marginEnd = dp(12)
            }
        )
        column.addView(heroFrame, lp(matchP, wrapC))
        column.addView(ScallopView(this, bandBg, false, blue, true), lp(matchP, wrapC))

        // Settings band
        val band = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bandBg)
            setPadding(dp(24), dp(8), dp(24), dp(40))
        }

        // -- feeds
        band.addView(sectionLabel("Where to block"))
        for ((key, name) in Store.SITES) {
            val whole = key == "tiktok"
            band.addView(
                switchRow(name, if (whole) "Whole app" else "Auto-Back on the player", Store.site(this, key), true) {
                    Store.setSite(this, key, it)
                }.view
            )
            band.addView(if (whole) hairline() else allowanceRow(key), lp(matchP, if (whole) dp(1) else wrapC))
        }

        // -- sections
        band.addView(sectionLabel("More sections"))
        for (sec in Store.SECTIONS) {
            band.addView(switchRow(sec.title, sec.sub, Store.site(this, sec.key)) { Store.setSite(this, sec.key, it) }.view)
            band.addView(hairline(), lp(matchP, dp(1)))
        }

        // -- whole apps
        band.addView(sectionLabel("Close entire apps"))
        for ((key, name) in Store.APPS) {
            if (key == "tiktok") continue
            val wk = Store.wholeKey(key)
            band.addView(switchRow(name, "Closed the moment you open it", Store.site(this, wk)) { Store.setSite(this, wk, it) }.view)
            band.addView(hairline(), lp(matchP, dp(1)))
        }

        // -- hold to open
        band.addView(sectionLabel("Hold to open"))
        val holdHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val holdOn = Store.holdOn(this)
        holdHolder.visibility = if (holdOn) View.VISIBLE else View.GONE
        val holdSub = if (holdOn) "Hold the button, then it opens for a while" else "Off: blocked screens close straight away"
        band.addView(switchRow("Hold to open", holdSub, holdOn) { v ->
            Store.setHoldOn(this, v)
            holdHolder.visibility = if (v) View.VISIBLE else View.GONE
        }.view)
        holdHolder.addView(stepper({ "Hold for $it seconds" }, Store.holdSeconds(this), 2, 60, 1) { Store.setHoldSeconds(this, it) })
        holdHolder.addView(stepper({ "Then open for $it min" }, Store.passMinutes(this), 1, 30, 1) { Store.setPassMinutes(this, it) })
        holdHolder.addView(switchRow("Longer each time", "+2 seconds per pass today", Store.progressive(this)) {
            Store.setProgressive(this, it)
        }.view)
        band.addView(holdHolder)

        // -- schedule
        band.addView(sectionLabel("Schedule"))
        val schedHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun schedSubText(on: Boolean) = when {
            !on -> "Off: blocking runs all day"
            Store.inSchedule(this) -> "On: blocking right now"
            else -> "On: paused until next window"
        }
        fun refill() {
            schedHolder.removeAllViews()
            schedHolder.addView(scheduleEditor { refill() })
        }
        val schedOn = Store.scheduleOn(this)
        val schedRow = switchRow("Only block on a schedule", schedSubText(schedOn), schedOn) { v ->
            Store.setScheduleOn(this, v)
            schedHolder.visibility = if (v) View.VISIBLE else View.GONE
        }
        refill()
        schedHolder.visibility = if (schedOn) View.VISIBLE else View.GONE
        band.addView(schedRow.view)
        band.addView(schedHolder)

        // -- focus lock
        band.addView(sectionLabel("Focus lock"))
        if (locked) {
            val lt = text("LOCKED · ${lockString()} LEFT", 22f, accent, fDisplay)
            lockText = lt
            band.addView(lt)
            band.addView(text(
                "Every supported app closes the moment you open it. Switches and settings are frozen until the timer ends.",
                14f, muted, body
            ).apply { setPadding(0, dp(8), 0, 0) })
        } else {
            band.addView(text(
                "Closes every supported app and freezes all settings until the timer ends. No way out early.",
                14f, muted, body
            ).apply { setPadding(0, 0, 0, dp(12)) })
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val picks = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours")
            val buttons = mutableListOf<Button>()
            picks.forEachIndexed { i, (mins, name) ->
                val b = chip(name, lockPick == mins) {
                    lockPick = mins
                    buttons.forEachIndexed { j, bb -> styleChip(bb, picks[j].first == mins) }
                }
                buttons.add(b)
                chips.addView(b, lp(0, wrapC, 1f).apply { if (i < picks.size - 1) marginEnd = dp(6) })
            }
            band.addView(chips)
            band.addView(
                pillButton("Lock now", indigo, white) {
                    AlertDialog.Builder(this)
                        .setTitle("Lock for $lockPick min?")
                        .setMessage("Every supported app closes on sight and nothing in Shortstop can be switched off until the timer ends.")
                        .setPositiveButton("Lock now") { _, _ -> Store.startLock(this, lockPick); render(); ui.post(ticker) }
                        .setNegativeButton("Cancel", null)
                        .show()
                },
                lp(matchP, wrapC).apply { topMargin = dp(12) }
            )
        }

        // -- snooze
        band.addView(sectionLabel("Need a break from the break?"))
        val snoozeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5 to "5 min", 15 to "15 min", 60 to "1 hour").forEachIndexed { i, (mins, name) ->
            snoozeRow.addView(
                pillButton(name, indigo, white) {
                    if (free()) { Store.setSnooze(this, System.currentTimeMillis() + mins * 60_000L); render() }
                },
                lp(0, wrapC, 1f).apply { if (i < 2) marginEnd = dp(6) }
            )
        }
        band.addView(snoozeRow)

        // -- rewards
        band.addView(sectionLabel("Streaks and rewards"))
        val streak = Store.streak(this)
        val totalBlocks = Store.total(this)
        val reclaimed = Store.reclaimedMinutes(this)
        band.addView(rewardRow("Clean-day streak", streak, listOf(3, 7, 14, 30, 60, 100), "days", first))
        band.addView(rewardRow("Blocks", totalBlocks, listOf(10, 50, 100, 500, 1000, 5000), "", first))
        band.addView(rewardRow("Time reclaimed", reclaimed, listOf(60, 300, 1440, 6000), "min", first))
        band.addView(text(earnedText(streak, totalBlocks, reclaimed), 14f, muted, body))
        band.addView(label("A clean day is one with no hold-to-open pass taken.", muted).apply { setPadding(0, dp(8), 0, 0) })

        // -- stats
        band.addView(sectionLabel("Last 7 days"))
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val week = (0..6).map { Store.blockedOn(this, it) }.sum()
        stats.addView(stat(Store.today(this), "Today"), lp(0, wrapC, 1f))
        stats.addView(stat(week, "This week"), lp(0, wrapC, 1f))
        stats.addView(stat(Store.total(this), "All time"), lp(0, wrapC, 1f))
        band.addView(stats)
        val vals = (6 downTo 0).map { Store.blockedOn(this, it) }
        val labels = (6 downTo 0).map { Store.dayLetter(it) }
        band.addView(BarChart(this, vals, labels, 6, first), lp(matchP, wrapC).apply { topMargin = dp(16) })
        band.addView(text(summaryText(), 14f, muted, body).apply { setPadding(0, dp(12), 0, 0) })

        // -- insights
        band.addView(sectionLabel("When you scroll most"))
        val byHour = Store.blocksByHour(this)
        val buckets = (0..7).map { i -> byHour.subList(i * 3, i * 3 + 3).sum() }
        val peak = if (buckets.sum() == 0) -1 else (buckets.indices.maxByOrNull { buckets[it] } ?: -1)
        band.addView(
            BarChart(this, buckets, listOf("12a", "3a", "6a", "9a", "12p", "3p", "6p", "9p"), peak, first),
            lp(matchP, wrapC)
        )
        band.addView(text(peakText(), 14f, muted, body).apply { setPadding(0, dp(12), 0, 0) })

        // -- appearance
        band.addView(sectionLabel("Appearance"))
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("System", "Light", "Dark").forEachIndexed { i, name ->
            themeRow.addView(
                chip(name, Store.theme(this) == i) { Store.setTheme(this, i); render() },
                lp(0, wrapC, 1f).apply { if (i < 2) marginEnd = dp(6) }
            )
        }
        band.addView(themeRow)

        column.addView(band, lp(matchP, wrapC))

        column.addView(ScallopView(this, blue, true, bandBg, false), lp(matchP, wrapC))
        column.addView(
            label("Runs offline · no tracking", white).apply {
                gravity = Gravity.CENTER
                background = grainOn(blue)
                setPadding(dp(16), dp(20), dp(16), dp(40))
            },
            lp(matchP, wrapC)
        )

        val sv = ScrollView(this).apply { setBackgroundColor(bandBg); addView(column) }
        scroll = sv
        setContentView(sv)
        sv.post {
            sv.scrollTo(0, keepY)
            band.layoutTransition = LayoutTransition() // smooth expand/collapse of schedule and hold options
        }

        if (first) {
            val kids = mutableListOf<View>(heroFrame)
            for (i in 0 until band.childCount) kids.add(band.getChildAt(i))
            animateIn(kids)
            stamp.rotation = -30f
            stamp.animate().rotation(12f).setStartDelay(250).setDuration(650)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
        } else {
            sv.alpha = 0f
            sv.animate().alpha(1f).setDuration(180).start()
        }
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: () -> Unit)
}
