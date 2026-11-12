package com.shortstop.app

import android.app.Activity
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
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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

    /** Hand-drawn on/off switch: 46x26 dp track, round knob that always stays inside it. */
    private inner class ToggleView(ctx: Context, private var on: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
        private val rect = RectF()

        init {
            isClickable = true
            isFocusable = true
            contentDescription = if (on) "On" else "Off"
            setOnClickListener {
                on = !on
                contentDescription = if (on) "On" else "Off"
                invalidate()
                onChange(on)
            }
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(dp(46), dp(26))
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            trackPaint.color = if (on) blue else trackOff
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)
            val pad = dp(3).toFloat()
            val r = h / 2f - pad
            val cx = if (on) w - pad - r else pad + r
            c.drawCircle(cx, h / 2f, r, knobPaint)
        }
    }

    /** Seven-day bar chart, oldest day on the left, today highlighted. */
    private inner class BarChart(ctx: Context, private val values: List<Int>, private val labels: List<String>) : View(ctx) {
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textAlign = Paint.Align.CENTER }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(130))
        }

        override fun onDraw(c: Canvas) {
            val n = values.size
            val top = maxOf(1, values.maxOrNull() ?: 1)
            val gap = dp(10).toFloat()
            val bw = (width - gap * (n - 1)) / n
            val labelH = dp(22).toFloat()
            val baseY = height - labelH
            val usable = baseY - dp(18)
            txt.textSize = dp(11).toFloat()
            for (i in 0 until n) {
                val left = i * (bw + gap)
                val h = maxOf(dp(3).toFloat(), values[i].toFloat() / top * usable)
                bar.color = accent
                bar.alpha = if (i == n - 1) 255 else 120
                c.drawRoundRect(RectF(left, baseY - h, left + bw, baseY), dp(5).toFloat(), dp(5).toFloat(), bar)
                txt.color = muted
                if (values[i] > 0) c.drawText(values[i].toString(), left + bw / 2f, baseY - h - dp(4), txt)
                c.drawText(labels[i], left + bw / 2f, height - dp(6).toFloat(), txt)
            }
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
    }

    private fun chip(t: String, selected: Boolean, onClick: () -> Unit) =
        pillButton(t, if (selected) blue else trackOff, if (selected) white else chipText, onClick)
            .apply { textSize = 11f; setPadding(dp(6), dp(10), dp(6), dp(10)) }

    private fun stepBtn(t: String, onClick: () -> Unit) =
        pillButton(t, indigo, white, onClick).apply { textSize = 14f; setPadding(dp(14), dp(6), dp(14), dp(6)) }

    private fun timeBtn(t: String, onClick: () -> Unit) =
        pillButton(t, trackOff, chipText, onClick).apply { textSize = 14f; setPadding(dp(14), dp(8), dp(14), dp(8)) }

    private fun hairline() = View(this).apply { setBackgroundColor(rule) }

    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(6))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 18f, accent, fDisplay))
        col.addView(label(sub, muted))
        row.addView(col, lp(0, wrapC, 1f))
        row.addView(ToggleView(this, checked) { v -> onChange(v); render() })
        return row
    }

    private fun fmt(minutes: Int) = String.format("%02d:%02d", minutes / 60, minutes % 60)

    private fun pickTime(initial: Int, onPick: (Int) -> Unit) {
        TimePickerDialog(
            this,
            TimePickerDialog.OnTimeSetListener { _, h, m -> onPick(h * 60 + m) },
            initial / 60, initial % 60, DateFormat.is24HourFormat(this)
        ).show()
    }

    // ---------- sections ----------
    private fun allowanceRow(key: String): View {
        val mins = Store.allowMinutes(this, key)
        val usedMin = Store.usedSeconds(this, key) / 60
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val caption = if (mins == 0) "Allowance: none" else "Allowance: $mins min/day, used $usedMin"
        row.addView(label(caption, muted), lp(0, wrapC, 1f))
        row.addView(stepBtn("−") { Store.setAllowMinutes(this, key, maxOf(0, mins - 5)); render() })
        row.addView(stepBtn("+") { Store.setAllowMinutes(this, key, minOf(120, mins + 5)); render() },
            lp(wrapC, wrapC).apply { marginStart = dp(8) })
        box.addView(row)
        box.addView(hairline(), lp(matchP, dp(1)))
        return box
    }

    private fun scheduleEditor(): View {
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
            top.addView(timeBtn(fmt(w.start)) { pickTime(w.start) { m -> Store.updateWindow(this, i, w.copy(start = m)); render() } })
            top.addView(label("to", muted).apply { setPadding(dp(10), 0, dp(10), 0) })
            top.addView(timeBtn(fmt(w.end)) { pickTime(w.end) { m -> Store.updateWindow(this, i, w.copy(end = m)); render() } })
            top.addView(View(this), lp(0, 1, 1f))
            top.addView(stepBtn("✕") { Store.removeWindow(this, i); render() })
            card.addView(top)

            val days = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            "MTWTFSS".forEachIndexed { d, ch ->
                val on = ((w.days shr d) and 1) == 1
                days.addView(
                    chip(ch.toString(), on) { Store.updateWindow(this, i, w.copy(days = w.days xor (1 shl d))); render() },
                    lp(0, wrapC, 1f).apply { if (d < 6) marginEnd = dp(4) }
                )
            }
            card.addView(days)
            box.addView(card, lp(matchP, wrapC).apply { bottomMargin = dp(10) })
        }

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun preset(name: String, w: Window, last: Boolean = false) {
            presets.addView(chip("+ $name", false) { Store.addWindow(this, w); render() },
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

    private fun stat(n: Int, l: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text("$n", 36f, accent, fDisplay))
        addView(label(l, accent))
    }

    // ---------- screen ----------
    private fun render() {
        applyPalette()
        val keepY = scroll?.scrollY ?: 0
        val snoozeLeft = Store.snoozeUntil(this) - System.currentTimeMillis()
        val serviceEnabled = serviceOn()
        val offSchedule = Store.scheduleOn(this) && !Store.inSchedule(this)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Announcement bar + scallop into the hero
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
        heroFrame.addView(
            StampView(this).apply { rotation = 12f },
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

        band.addView(sectionLabel("Where to block"))
        for ((key, name) in Store.SITES) {
            band.addView(
                switchRow(name, if (key == "tiktok") "Whole app" else "Auto-Back on the player", Store.site(this, key)) {
                    Store.setSite(this, key, it)
                }
            )
            band.addView(allowanceRow(key))
        }

        band.addView(sectionLabel("Schedule"))
        val schedSub = when {
            !Store.scheduleOn(this) -> "Off: blocking runs all day"
            Store.inSchedule(this) -> "On: blocking right now"
            else -> "On: paused until next window"
        }
        band.addView(switchRow("Only block on a schedule", schedSub, Store.scheduleOn(this)) { Store.setScheduleOn(this, it) })
        if (Store.scheduleOn(this)) band.addView(scheduleEditor())

        band.addView(sectionLabel("Need a break from the break?"))
        val snoozeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5 to "5 min", 15 to "15 min", 60 to "1 hour").forEachIndexed { i, (mins, name) ->
            snoozeRow.addView(
                pillButton(name, indigo, white) {
                    Store.setSnooze(this, System.currentTimeMillis() + mins * 60_000L); render()
                },
                lp(0, wrapC, 1f).apply { if (i < 2) marginEnd = dp(6) }
            )
        }
        band.addView(snoozeRow)

        band.addView(sectionLabel("Last 7 days"))
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val week = (0..6).map { Store.blockedOn(this, it) }.sum()
        stats.addView(stat(Store.today(this), "Today"), lp(0, wrapC, 1f))
        stats.addView(stat(week, "This week"), lp(0, wrapC, 1f))
        stats.addView(stat(Store.total(this), "All time"), lp(0, wrapC, 1f))
        band.addView(stats)
        val vals = (6 downTo 0).map { Store.blockedOn(this, it) }
        val labels = (6 downTo 0).map { Store.dayLetter(it) }
        band.addView(BarChart(this, vals, labels), lp(matchP, wrapC).apply { topMargin = dp(16) })
        band.addView(text(summaryText(), 14f, muted, body).apply { setPadding(0, dp(12), 0, 0) })

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

        // Footer
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
        sv.post { sv.scrollTo(0, keepY) }
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: () -> Unit)
}
