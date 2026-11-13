package com.shortstop.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Shortstop control panel. Black and white (Easlo design), Inter type, four tabs.
 * Every control updates its own view in place: nothing on screen is rebuilt when you tap.
 */
class MainActivity : Activity() {

    private lateinit var pal: Palette
    private lateinit var fonts: Fonts
    private val ui = Handler(Looper.getMainLooper())

    private val matchP = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapC = ViewGroup.LayoutParams.WRAP_CONTENT

    private val pages = ArrayList<View>()
    private val navItems = ArrayList<TextView>()
    private var tab = 0

    // live bits
    private var statusHead: TextView? = null
    private var statusSub: TextView? = null
    private var statusBtn: TextView? = null
    private var statusChip: TextView? = null
    private var statusAction: () -> Unit = {}
    private var blockedNum: TextView? = null
    private var statsCol: LinearLayout? = null
    private var settingsCol: LinearLayout? = null
    private var settingsScroll: ScrollView? = null
    private var charts = ArrayList<BarChart>()
    private var lockText: TextView? = null
    private var lockPick = 30
    private var wasLocked = false

    private val ticker = object : Runnable {
        override fun run() {
            if (Store.locked(this@MainActivity)) {
                lockText?.text = "Locked · ${lockString()} left"
                ui.postDelayed(this, 1000)
            } else if (wasLocked) {
                wasLocked = false
                fillSettings()
                refreshStatus()
            }
        }
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

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

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.split(':').any { it.startsWith("$packageName/") }
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open that screen on this phone.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fonts = Fonts(this)
        buildAll()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        fillStats()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------- text and shapes ----------
    private fun tv(t: String, sp: Float, face: Typeface, color: Int, gravity: Int = Gravity.START) =
        TextView(this).apply {
            text = t; textSize = sp; typeface = face; setTextColor(color); this.gravity = gravity
            includeFontPadding = false
            setLineSpacing(0f, 1.18f)
        }

    private fun display(t: String, sp: Float) = tv(t, sp, fonts.bold, pal.ink).apply { letterSpacing = -0.02f }

    private fun round(fill: Int, radius: Int, strokeColor: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    private fun press(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }

    private fun pill(t: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = t
        textSize = 14f
        typeface = fonts.semi
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPill(this, primary)
        setPadding(dp(22), dp(13), dp(22), dp(13))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        press(this)
    }

    private fun setPill(v: TextView, primary: Boolean) {
        v.setTextColor(if (primary) pal.canvas else pal.ink)
        v.background = round(if (primary) pal.ink else pal.canvas, 40, pal.border, if (primary) 0 else dp(1))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(pal.surface, 24, pal.border, dp(1))
        setPadding(dp(20), dp(6), dp(20), dp(6))
    }

    private fun divider() = View(this).apply { setBackgroundColor(pal.border) }

    private fun LinearLayout.addRow(v: View) {
        if (childCount > 0) addView(divider(), lp(matchP, dp(1)))
        addView(v, lp(matchP, wrapC))
    }

    private fun sectionTitle(t: String) = display(t, 24f).apply { setPadding(0, dp(30), 0, dp(12)) }

    private fun groupLabel(t: String) = tv(t, 13f, fonts.semi, pal.muted).apply { setPadding(dp(4), dp(18), 0, dp(8)) }

    private fun note(t: String) = tv(t, 13f, fonts.regular, pal.muted).apply { setPadding(dp(4), dp(8), dp(4), 0) }

    private fun scrollPage(col: LinearLayout): ScrollView = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(col, FrameLayout.LayoutParams(matchP, wrapC))
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(28))
    }

    private fun watch(et: EditText, onText: (String) -> Unit) {
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onText(s?.toString() ?: "") }
        })
    }

    private fun input(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(pal.muted)
        setTextColor(pal.ink)
        typeface = fonts.regular
        textSize = 15f
        background = round(pal.surface, 20, pal.border, dp(1))
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
    }

    // ---------- custom views ----------
    /** Pill switch: black track when on, round knob glides across. */
    private inner class ToggleView(ctx: Context) : View(ctx) {
        var on = false
            private set
        var listener: ((Boolean) -> Unit)? = null
        private var p = 0f
        private var anim: ValueAnimator? = null
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val eval = android.animation.ArgbEvaluator()

        init {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!free()) return@setOnClickListener
                setOn(!on, true)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                listener?.invoke(on)
            }
        }

        fun setOn(v: Boolean, animate: Boolean) {
            on = v
            contentDescription = if (v) "On" else "Off"
            anim?.cancel()
            val target = if (v) 1f else 0f
            if (!animate) {
                p = target
                invalidate()
            } else {
                anim = ValueAnimator.ofFloat(p, target).apply {
                    duration = 180
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { p = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(dp(48), dp(28))
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            trackPaint.color = eval.evaluate(p, pal.track, pal.ink) as Int
            val offKnob = if (pal.dark) Color.parseColor("#BBBBBB") else Color.WHITE
            knobPaint.color = eval.evaluate(p, offKnob, pal.canvas) as Int
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)
            val pad = dp(3).toFloat()
            val r = h / 2f - pad
            val left = pad + r
            val right = w - pad - r
            c.drawCircle(left + (right - left) * p, h / 2f, r, knobPaint)
        }
    }

    /** Bar chart. The highlighted bar is black, the rest grey. [play] grows the bars in. */
    private inner class BarChart(
        ctx: Context,
        private val values: List<Int>,
        private val labels: List<String>,
        private val highlight: Int
    ) : View(ctx) {
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.medium; textAlign = Paint.Align.CENTER }
        private var grow = 1f
        private val rect = RectF()

        fun play() {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 650
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { grow = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(140))
        }

        override fun onDraw(c: Canvas) {
            val n = values.size
            val top = maxOf(1, values.maxOrNull() ?: 1)
            val gap = dp(if (n > 8) 6 else 10).toFloat()
            val bw = (width - gap * (n - 1)) / n
            val baseY = height - dp(24).toFloat()
            val usable = baseY - dp(20)
            txt.textSize = dp(11).toFloat()
            for (i in 0 until n) {
                val left = i * (bw + gap)
                val h = maxOf(dp(4).toFloat(), values[i].toFloat() / top * usable * grow)
                bar.color = if (i == highlight) pal.ink else pal.track
                rect.set(left, baseY - h, left + bw, baseY)
                c.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), bar)
                txt.color = pal.muted
                if (values[i] > 0) {
                    txt.alpha = (grow * 255).toInt()
                    c.drawText(values[i].toString(), left + bw / 2f, baseY - h - dp(5), txt)
                    txt.alpha = 255
                }
                c.drawText(labels[i], left + bw / 2f, height - dp(7).toFloat(), txt)
            }
        }
    }

    private inner class MeterView(ctx: Context, private val frac: Float) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(8))
        }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.color = pal.track
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, h / 2f, h / 2f, paint)
            paint.color = pal.ink
            rect.set(0f, 0f, maxOf(h, w * frac.coerceIn(0f, 1f)), h)
            c.drawRoundRect(rect, h / 2f, h / 2f, paint)
        }
    }

    // ---------- reusable rows ----------
    private fun toggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(15), 0, dp(15))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 16f, fonts.semi, pal.ink))
        if (sub.isNotEmpty()) col.addView(tv(sub, 13f, fonts.regular, pal.muted).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(col, lp(0, wrapC, 1f).apply { marginEnd = dp(12) })
        val t = ToggleView(this)
        t.setOn(checked, false)
        t.listener = onChange
        row.addView(t)
        row.setOnClickListener { t.performClick() }
        return row
    }

    private fun smallBtn(t: String, onClick: () -> Unit): TextView =
        pill(t, false, onClick).apply { textSize = 16f; setPadding(dp(16), dp(7), dp(16), dp(7)) }

    /** "− / +" row that updates its own caption in place. */
    private fun stepperRow(
        caption: (Int) -> String, start: Int, min: Int, max: Int, step: Int, onSet: (Int) -> Unit
    ): View {
        var v = start
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val cap = tv(caption(v), 15f, fonts.medium, pal.ink)
        fun change(d: Int) {
            if (!free()) return
            v = (v + d).coerceIn(min, max)
            onSet(v)
            cap.text = caption(v)
        }
        row.addView(cap, lp(0, wrapC, 1f))
        row.addView(smallBtn("−") { change(-step) })
        row.addView(smallBtn("+") { change(step) }, lp(wrapC, wrapC).apply { marginStart = dp(8) })
        return row
    }

    /** Single-choice chips; restyles itself in place. */
    private fun chipRow(options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        val chips = ArrayList<TextView>()
        options.forEachIndexed { i, (value, name) ->
            val c = pill(name, value == selected) {}
            c.textSize = 13f
            c.setPadding(dp(8), dp(11), dp(8), dp(11))
            c.setOnClickListener {
                onPick(value)
                chips.forEachIndexed { j, cc -> setPill(cc, options[j].first == value) }
            }
            chips.add(c)
            row.addView(c, lp(0, wrapC, 1f).apply { if (i < options.size - 1) marginEnd = dp(6) })
        }
        return row
    }

    // ---------- build ----------
    private fun buildAll() {
        pal = Palette(this)
        window.statusBarColor = pal.canvas
        window.navigationBarColor = pal.canvas
        window.setBackgroundDrawable(ColorDrawable(pal.canvas))
        var flags = window.decorView.systemUiVisibility
        flags = if (pal.dark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags

        charts = ArrayList()
        pages.clear()
        navItems.clear()

        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.canvas)
        }
        val holder = FrameLayout(this)

        pages.add(shortsPage())
        pages.add(appsPage())
        pages.add(statsPage())
        pages.add(settingsPage())
        pages.forEachIndexed { i, p ->
            p.visibility = if (i == tab) View.VISIBLE else View.GONE
            holder.addView(p, FrameLayout.LayoutParams(matchP, matchP))
        }
        rootView.addView(holder, lp(matchP, 0, 1f))
        rootView.addView(View(this).apply { setBackgroundColor(pal.border) }, lp(matchP, dp(1)))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(pal.canvas)
        }
        listOf("Shorts", "Apps", "Stats", "Settings").forEachIndexed { i, name ->
            val item = tv(name, 13f, fonts.semi, pal.muted, Gravity.CENTER).apply {
                setPadding(dp(6), dp(10), dp(6), dp(10))
                isClickable = true
                setOnClickListener { selectTab(i) }
                press(this)
            }
            navItems.add(item)
            nav.addView(item, lp(0, wrapC, 1f).apply { if (i < 3) marginEnd = dp(6) })
        }
        rootView.addView(nav, lp(matchP, wrapC))
        setContentView(rootView)
        styleNav()
        refreshStatus()
        fillStats()
    }

    private fun styleNav() {
        navItems.forEachIndexed { i, v ->
            val sel = i == tab
            v.setTextColor(if (sel) pal.canvas else pal.muted)
            v.background = round(if (sel) pal.ink else Color.TRANSPARENT, 40)
        }
    }

    private fun selectTab(i: Int) {
        if (i == tab) return
        val old = pages[tab]
        val nw = pages[i]
        tab = i
        old.animate().cancel()
        old.visibility = View.GONE
        nw.animate().cancel()
        nw.visibility = View.VISIBLE
        nw.alpha = 0f
        nw.translationY = dp(10).toFloat()
        nw.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator(1.4f)).start()
        styleNav()
        if (i == 2) { fillStats(); charts.forEach { it.play() } }
        if (i == 0) refreshStatus()
    }

    // ---------- Shorts tab ----------
    private data class St(val head: String, val sub: String, val btn: String, val chip: String, val act: () -> Unit)

    private fun refreshStatus() {
        val snoozeLeft = Store.snoozeUntil(this) - System.currentTimeMillis()
        val offSchedule = Store.scheduleOn(this) && !Store.inSchedule(this)
        val st = when {
            !serviceOn() -> St(
                "One step left.",
                "Turn on the Shortstop accessibility service so it can see Shorts, Reels and blocked apps.",
                "Enable service", "Setup needed"
            ) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            Store.locked(this) -> St(
                "Locked in.", "Focus lock is on. Nothing can be switched off until the timer ends.",
                "Locked", "Locked"
            ) { free() }
            !Store.enabled(this) -> St(
                "Blocking is off.", "Shorts, Reels and your blocked apps are open. Turn it back on any time.",
                "Turn on", "Off"
            ) { Store.setEnabled(this, true); refreshStatus() }
            snoozeLeft > 0 -> St(
                "Paused for ${(snoozeLeft + 59999) / 60000} min.", "Blocking comes back on its own.",
                "Resume now", "Paused"
            ) { Store.setSnooze(this, 0); refreshStatus() }
            offSchedule -> St(
                "Off the clock.", "Outside your routine. Blocking starts again in your next window.",
                "Turn off", "Waiting"
            ) { Store.setEnabled(this, false); refreshStatus() }
            else -> St(
                "You're protected.", "Shorts, Reels, sections, apps and sites you picked are blocked.",
                "Turn off", "On"
            ) { Store.setEnabled(this, false); refreshStatus() }
        }
        statusHead?.text = st.head
        statusSub?.text = st.sub
        statusBtn?.text = st.btn
        statusChip?.text = st.chip
        statusAction = st.act
        blockedNum?.text = Store.total(this).toString()
    }

    private fun shortsPage(): View {
        val col = column()

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(display("Shortstop", 26f), lp(0, wrapC, 1f))
        val chip = tv("", 12f, fonts.semi, pal.ink, Gravity.CENTER).apply {
            background = round(pal.canvas, 40, pal.border, dp(1))
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        statusChip = chip
        top.addView(chip)
        col.addView(top)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(pal.surface, 24, pal.border, dp(1))
            setPadding(dp(22), dp(24), dp(22), dp(24))
        }
        statusHead = display("", 32f)
        statusSub = tv("", 15f, fonts.regular, pal.muted).apply { setPadding(0, dp(10), 0, dp(20)) }
        val btn = pill("", true) { statusAction() }
        statusBtn = btn
        hero.addView(statusHead)
        hero.addView(statusSub)
        hero.addView(btn, lp(wrapC, wrapC))
        col.addView(hero, lp(matchP, wrapC).apply { topMargin = dp(20) })

        val wins = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(pal.surface, 24, pal.border, dp(1))
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        val num = display("0", 34f)
        blockedNum = num
        wins.addView(num)
        val winsText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        winsText.addView(tv("distractions blocked", 15f, fonts.semi, pal.ink))
        winsText.addView(tv("Total wins against doomscrolling", 13f, fonts.regular, pal.muted).apply { setPadding(0, dp(3), 0, 0) })
        wins.addView(winsText, lp(0, wrapC, 1f).apply { marginStart = dp(16) })
        col.addView(wins, lp(matchP, wrapC).apply { topMargin = dp(12) })

        col.addView(sectionTitle("Block short content"))

        for ((site, appName) in Store.APPS) {
            col.addView(groupLabel(appName))
            val card = card()
            val feedTitle = Store.SITES[site] ?: appName
            card.addRow(toggleRow(feedTitle, if (site == "tiktok") "Whole app" else "Auto-Back on the player",
                Store.site(this, site)) { Store.setSite(this, site, it) })
            if (site != "tiktok") {
                card.addRow(stepperRow(
                    { m -> if (m == 0) "Daily allowance: none" else "Daily allowance: $m min" },
                    Store.allowMinutes(this, site), 0, 120, 5
                ) { Store.setAllowMinutes(this, site, it) })
            }
            for (sec in Store.SECTIONS.filter { it.site == site }) {
                card.addRow(toggleRow(sec.title, sec.sub, Store.site(this, sec.key)) { Store.setSite(this, sec.key, it) })
            }
            if (site != "tiktok") {
                val wk = Store.wholeKey(site)
                card.addRow(toggleRow("Close $appName entirely", "Blocked the moment you open it",
                    Store.site(this, wk)) { Store.setSite(this, wk, it) })
            }
            col.addView(card, lp(matchP, wrapC))
        }
        col.addView(note("Detection reads each app's screen. Apps change their layouts now and then, so a new section may need a tweak."))
        return scrollPage(col)
    }

    // ---------- Apps tab ----------
    private class AppRow(val pkg: String, val label: String, val icon: Bitmap?)

    private val allApps = ArrayList<AppRow>()
    private val shown = ArrayList<AppRow>()
    private var appQuery = ""
    private var appAdapter: BaseAdapter? = null
    private var loadingText: TextView? = null
    private var sitesBox: LinearLayout? = null

    private fun iconBitmap(d: Drawable): Bitmap {
        val size = dp(48)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val out = ArrayList<AppRow>()
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val seen = HashSet<String>()
                for (r in pm.queryIntentActivities(intent, 0)) {
                    val pkg = r.activityInfo.packageName
                    if (pkg == packageName || !seen.add(pkg)) continue
                    val bmp = try { iconBitmap(r.loadIcon(pm)) } catch (e: Exception) { null }
                    out.add(AppRow(pkg, r.loadLabel(pm).toString(), bmp))
                }
            } catch (e: Exception) { }
            out.sortBy { it.label.lowercase() }
            runOnUiThread {
                allApps.clear()
                allApps.addAll(out)
                loadingText?.visibility = View.GONE
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = appQuery.trim()
        val blocked = Store.blockedApps(this)
        val list = allApps.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }
            .sortedBy { if (it.pkg in blocked) 0 else 1 }
        shown.clear()
        shown.addAll(list)
        appAdapter?.notifyDataSetChanged()
    }

    private inner class AppItem(ctx: Context) : FrameLayout(ctx) {
        private val icon = ImageView(ctx)
        private val name = tv("", 16f, fonts.semi, pal.ink)
        private val toggle = ToggleView(ctx)
        private var pkg = ""

        init {
            layoutParams = AbsListView.LayoutParams(matchP, wrapC)
            setPadding(0, 0, 0, dp(8))
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(pal.surface, 24, pal.border, dp(1))
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            icon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            icon.clipToOutline = true
            tile.addView(icon, lp(dp(44), dp(44)))
            tile.addView(name, lp(0, wrapC, 1f).apply { marginStart = dp(14); marginEnd = dp(12) })
            tile.addView(toggle)
            toggle.listener = { on -> Store.setAppBlocked(this@MainActivity, pkg, on) }
            tile.setOnClickListener { toggle.performClick() }
            addView(tile, FrameLayout.LayoutParams(matchP, wrapC))
        }

        fun bind(a: AppRow) {
            pkg = a.pkg
            name.text = a.label
            icon.setImageBitmap(a.icon)
            toggle.setOn(Store.isAppBlocked(this@MainActivity, a.pkg), false)
        }
    }

    private fun appsPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), 0)
        }
        page.addView(display("Block apps & websites", 30f))

        val appsPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val webPane = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }

        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(pal.surface, 40, pal.border, dp(1))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val segApps = pill("Apps", true) {}
        val segWeb = pill("Websites", false) {}
        segApps.setOnClickListener {
            setPill(segApps, true); setPill(segWeb, false)
            appsPane.visibility = View.VISIBLE; webPane.visibility = View.GONE
        }
        segWeb.setOnClickListener {
            setPill(segApps, false); setPill(segWeb, true)
            appsPane.visibility = View.GONE; webPane.visibility = View.VISIBLE
        }
        seg.addView(segApps, lp(0, wrapC, 1f))
        seg.addView(segWeb, lp(0, wrapC, 1f).apply { marginStart = dp(4) })
        page.addView(seg, lp(matchP, wrapC).apply { topMargin = dp(18); bottomMargin = dp(12) })

        // apps
        val search = input("Search apps")
        watch(search) { appQuery = it; applyFilter() }
        appsPane.addView(search, lp(matchP, wrapC).apply { bottomMargin = dp(12) })
        val loading = tv("Loading your apps…", 14f, fonts.regular, pal.muted, Gravity.CENTER).apply { setPadding(0, dp(24), 0, 0) }
        loadingText = loading
        if (allApps.isNotEmpty()) loading.visibility = View.GONE
        appsPane.addView(loading, lp(matchP, wrapC))
        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            cacheColorHint = Color.TRANSPARENT
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = (convertView as? AppItem) ?: AppItem(this@MainActivity)
                item.bind(shown[position])
                return item
            }
        }
        appAdapter = adapter
        list.adapter = adapter
        appsPane.addView(list, lp(matchP, 0, 1f))
        page.addView(appsPane, lp(matchP, 0, 1f))

        // websites
        val webCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val urlInput = input("https://reddit.com")
        fun addSite() {
            val d = Store.cleanDomain(urlInput.text.toString())
            if (d == null) {
                Toast.makeText(this, "Type a site like reddit.com", Toast.LENGTH_SHORT).show()
                return
            }
            Store.addSite(this, d)
            urlInput.setText("")
            refreshSites()
        }
        urlInput.setOnEditorActionListener { _, _, _ -> addSite(); true }
        addRow.addView(urlInput, lp(0, wrapC, 1f))
        addRow.addView(pill("Block", true) { addSite() }, lp(wrapC, wrapC).apply { marginStart = dp(8) })
        webCol.addView(addRow)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sitesBox = box
        webCol.addView(box, lp(matchP, wrapC).apply { topMargin = dp(14) })
        webCol.addView(note("Works inside Chrome, Brave, Edge, Firefox, Samsung Internet, Opera, Vivaldi and DuckDuckGo by reading the address bar."))
        webPane.addView(webCol, FrameLayout.LayoutParams(matchP, wrapC))
        webPane.visibility = View.GONE
        page.addView(webPane, lp(matchP, 0, 1f))
        refreshSites()
        return page
    }

    private fun refreshSites() {
        val box = sitesBox ?: return
        box.removeAllViews()
        val sites = Store.blockedSites(this)
        if (sites.isEmpty()) {
            box.addView(tv("No websites blocked yet.", 14f, fonts.regular, pal.muted, Gravity.CENTER).apply {
                setPadding(0, dp(28), 0, dp(12))
            }, lp(matchP, wrapC))
            return
        }
        for (d in sites) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(pal.surface, 24, pal.border, dp(1))
                setPadding(dp(20), dp(12), dp(14), dp(12))
            }
            row.addView(tv(d, 16f, fonts.semi, pal.ink), lp(0, wrapC, 1f))
            row.addView(pill("Remove", false) {
                if (free()) { Store.removeSite(this, d); refreshSites() }
            }.apply { textSize = 13f; setPadding(dp(16), dp(9), dp(16), dp(9)) })
            box.addView(row, lp(matchP, wrapC).apply { bottomMargin = dp(8) })
        }
    }

    // ---------- Stats tab ----------
    private fun statsPage(): View {
        val col = column()
        statsCol = col
        return scrollPage(col)
    }

    private fun statTile(n: String, l: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(display(n, 30f))
        addView(tv(l, 13f, fonts.regular, pal.muted).apply { setPadding(0, dp(4), 0, 0) })
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

    private fun rewardRow(title: String, value: Int, targets: List<Int>, unit: String): View {
        val next = targets.firstOrNull { it > value }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, dp(14)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(tv(title, 16f, fonts.semi, pal.ink), lp(0, wrapC, 1f))
        top.addView(tv(if (next != null) "$value / $next $unit".trim() else "$value $unit · done".trim(), 13f, fonts.regular, pal.muted))
        box.addView(top)
        box.addView(MeterView(this, if (next == null) 1f else value.toFloat() / next), lp(matchP, wrapC).apply { topMargin = dp(10) })
        return box
    }

    private fun earnedText(streak: Int, total: Int, mins: Int): String {
        val e = mutableListOf<String>()
        listOf(3, 7, 14, 30, 60, 100).filter { it <= streak }.forEach { e.add("$it-day streak") }
        listOf(10, 50, 100, 500, 1000, 5000).filter { it <= total }.forEach { e.add("$it blocks") }
        listOf(60, 300, 1440, 6000).filter { it <= mins }.forEach { e.add("${it / 60} h reclaimed") }
        return if (e.isEmpty()) "No badges yet. The first one is a few blocks away." else "Earned: " + e.joinToString(" · ")
    }

    private fun fillStats() {
        val col = statsCol ?: return
        col.removeAllViews()
        charts = ArrayList()
        col.addView(display("Stats", 30f))

        val week = (0..6).map { Store.blockedOn(this, it) }.sum()
        val tiles = card().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(statTile(Store.today(this).toString(), "Today"), lp(0, wrapC, 1f))
        row.addView(statTile(week.toString(), "This week"), lp(0, wrapC, 1f))
        row.addView(statTile(Store.total(this).toString(), "All time"), lp(0, wrapC, 1f))
        tiles.addView(row)
        col.addView(tiles, lp(matchP, wrapC).apply { topMargin = dp(18) })

        col.addView(sectionTitle("Last 7 days"))
        val c1 = card().apply { setPadding(dp(20), dp(20), dp(20), dp(18)) }
        val vals = (6 downTo 0).map { Store.blockedOn(this, it) }
        val labels = (6 downTo 0).map { Store.dayLetter(it) }
        val chart1 = BarChart(this, vals, labels, 6)
        charts.add(chart1)
        c1.addView(chart1, lp(matchP, wrapC))
        c1.addView(tv(summaryText(), 14f, fonts.regular, pal.muted).apply { setPadding(0, dp(14), 0, 0) })
        col.addView(c1, lp(matchP, wrapC))

        col.addView(sectionTitle("When you scroll most"))
        val c2 = card().apply { setPadding(dp(20), dp(20), dp(20), dp(18)) }
        val byHour = Store.blocksByHour(this)
        val buckets = (0..7).map { i -> byHour.subList(i * 3, i * 3 + 3).sum() }
        val peak = if (buckets.sum() == 0) -1 else (buckets.indices.maxByOrNull { buckets[it] } ?: -1)
        val chart2 = BarChart(this, buckets, listOf("12a", "3a", "6a", "9a", "12p", "3p", "6p", "9p"), peak)
        charts.add(chart2)
        c2.addView(chart2, lp(matchP, wrapC))
        c2.addView(tv(peakText(), 14f, fonts.regular, pal.muted).apply { setPadding(0, dp(14), 0, 0) })
        col.addView(c2, lp(matchP, wrapC))

        col.addView(sectionTitle("Streaks and rewards"))
        val streak = Store.streak(this)
        val total = Store.total(this)
        val reclaimed = Store.reclaimedMinutes(this)
        val rc = card()
        rc.addRow(rewardRow("Clean-day streak", streak, listOf(3, 7, 14, 30, 60, 100), "days"))
        rc.addRow(rewardRow("Blocks", total, listOf(10, 50, 100, 500, 1000, 5000), ""))
        rc.addRow(rewardRow("Time reclaimed", reclaimed, listOf(60, 300, 1440, 6000), "min"))
        col.addView(rc, lp(matchP, wrapC))
        col.addView(note(earnedText(streak, total, reclaimed)))
        col.addView(note("A clean day is one with no hold-to-open pass taken."))
        blockedNum?.text = total.toString()
    }

    // ---------- Settings tab ----------
    private fun settingsPage(): View {
        val col = column()
        settingsCol = col
        val sv = scrollPage(col)
        settingsScroll = sv
        fillSettings()
        return sv
    }

    private fun pickTime(initial: Int, onPick: (Int) -> Unit) {
        if (!free()) return
        TimePickerDialog(
            this,
            TimePickerDialog.OnTimeSetListener { _, h, m -> onPick(h * 60 + m) },
            initial / 60, initial % 60, DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun fmt(minutes: Int) = String.format("%02d:%02d", minutes / 60, minutes % 60)

    private fun scheduleEditor(refill: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(14)) }
        val ws = Store.windows(this)
        if (ws.isEmpty()) box.addView(tv("No windows yet. Add one below.", 13f, fonts.regular, pal.muted).apply { setPadding(0, 0, 0, dp(10)) })
        ws.forEachIndexed { i, w ->
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = round(pal.canvas, 20, pal.border, dp(1))
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(pill(fmt(w.start), false) { pickTime(w.start) { m -> Store.updateWindow(this, i, w.copy(start = m)); refill() } })
            top.addView(tv("to", 13f, fonts.regular, pal.muted).apply { setPadding(dp(10), 0, dp(10), 0) })
            top.addView(pill(fmt(w.end), false) { pickTime(w.end) { m -> Store.updateWindow(this, i, w.copy(end = m)); refill() } })
            top.addView(View(this), lp(0, 1, 1f))
            top.addView(smallBtn("✕") { if (free()) { Store.removeWindow(this, i); refill() } })
            c.addView(top)
            val days = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
            "MTWTFSS".forEachIndexed { d, ch ->
                val on = ((w.days shr d) and 1) == 1
                val chip = pill(ch.toString(), on) {}
                chip.textSize = 12f
                chip.setPadding(dp(4), dp(9), dp(4), dp(9))
                var sel = on
                chip.setOnClickListener {
                    if (free()) {
                        sel = !sel
                        setPill(chip, sel)
                        Store.updateWindow(this, i, w.copy(days = Store.windows(this)[i].days xor (1 shl d)))
                    }
                }
                days.addView(chip, lp(0, wrapC, 1f).apply { if (d < 6) marginEnd = dp(4) })
            }
            c.addView(days)
            box.addView(c, lp(matchP, wrapC).apply { bottomMargin = dp(10) })
        }
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun preset(name: String, w: Window, last: Boolean = false) {
            val p = pill("+ $name", false) { if (free()) { Store.addWindow(this, w); refill() } }
            p.textSize = 12f
            p.setPadding(dp(4), dp(10), dp(4), dp(10))
            presets.addView(p, lp(0, wrapC, 1f).apply { if (!last) marginEnd = dp(6) })
        }
        preset("Work", Window(9 * 60, 17 * 60, 31))
        preset("Study", Window(18 * 60, 22 * 60, 127))
        preset("Night", Window(0, 6 * 60, 127))
        preset("Custom", Window(9 * 60, 17 * 60, 127), true)
        box.addView(presets)
        box.addView(tv("Work 9-5 weekdays · Study 6-10 pm · Night 12-6 am", 12f, fonts.regular, pal.muted).apply { setPadding(0, dp(10), 0, 0) })
        return box
    }

    private fun fillSettings() {
        val col = settingsCol ?: return
        val keepY = settingsScroll?.scrollY ?: 0
        col.removeAllViews()
        val locked = Store.locked(this)
        wasLocked = locked
        lockText = null

        col.addView(display("Settings", 30f))

        // Hold to open
        col.addView(sectionTitle("Hold to open"))
        val hold = card()
        val holdHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holdHolder.visibility = if (Store.holdOn(this)) View.VISIBLE else View.GONE
        hold.addRow(toggleRow("Hold to open", "Blocked screens ask you to hold a button first", Store.holdOn(this)) {
            Store.setHoldOn(this, it)
            holdHolder.visibility = if (it) View.VISIBLE else View.GONE
        })
        holdHolder.addView(divider(), lp(matchP, dp(1)))
        holdHolder.addView(chipRow(listOf(10 to "10s", 30 to "30s", 60 to "60s", 180 to "180s"),
            Store.holdSeconds(this)) { if (free()) Store.setHoldSeconds(this, it) })
        holdHolder.addView(divider(), lp(matchP, dp(1)))
        holdHolder.addView(stepperRow({ "Hold for $it seconds" }, Store.holdSeconds(this), 2, 180, 1) { Store.setHoldSeconds(this, it) })
        holdHolder.addView(divider(), lp(matchP, dp(1)))
        holdHolder.addView(stepperRow({ "Then open for $it min" }, Store.passMinutes(this), 1, 180, 1) { Store.setPassMinutes(this, it) })
        holdHolder.addView(divider(), lp(matchP, dp(1)))
        holdHolder.addView(toggleRow("Longer each time", "+2 seconds for every pass taken today", Store.progressive(this)) {
            Store.setProgressive(this, it)
        })
        hold.addView(holdHolder, lp(matchP, wrapC))
        col.addView(hold, lp(matchP, wrapC))

        col.addView(groupLabel("Message on the wall"))
        val msg = input("Why are you here?")
        msg.setText(Store.message(this))
        watch(msg) { Store.setMessage(this, it) }
        col.addView(msg, lp(matchP, wrapC))

        // Routine
        col.addView(sectionTitle("Routine"))
        val routine = card()
        val schedHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refill() {
            schedHolder.removeAllViews()
            schedHolder.addView(scheduleEditor { refill() })
        }
        refill()
        schedHolder.visibility = if (Store.scheduleOn(this)) View.VISIBLE else View.GONE
        routine.addRow(toggleRow("Only block on a schedule", "Off: blocking runs all day", Store.scheduleOn(this)) {
            Store.setScheduleOn(this, it)
            schedHolder.visibility = if (it) View.VISIBLE else View.GONE
            refreshStatus()
        })
        routine.addView(schedHolder, lp(matchP, wrapC))
        col.addView(routine, lp(matchP, wrapC))

        // Focus lock
        col.addView(sectionTitle("Focus lock"))
        val lock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(pal.surface, 24, pal.border, dp(1))
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        if (locked) {
            val lt = display("Locked · ${lockString()} left", 22f)
            lockText = lt
            lock.addView(lt)
            lock.addView(tv("Apps, sites and sections are closed on sight. Settings are frozen until the timer ends.",
                14f, fonts.regular, pal.muted).apply { setPadding(0, dp(10), 0, 0) })
        } else {
            lock.addView(tv("Closes every blocked app, site and short-video feed and freezes settings until the timer ends. There is no way out early.",
                14f, fonts.regular, pal.muted))
            lock.addView(chipRow(listOf(15 to "15m", 30 to "30m", 60 to "1h", 120 to "2h", 240 to "4h"), lockPick) { lockPick = it },
                lp(matchP, wrapC).apply { topMargin = dp(10) })
            lock.addView(pill("Lock now", true) {
                AlertDialog.Builder(this)
                    .setTitle("Lock for $lockPick min?")
                    .setMessage("Blocked apps, sites and feeds close on sight and nothing in Shortstop can be switched off until the timer ends.")
                    .setPositiveButton("Lock now") { _, _ ->
                        Store.startLock(this, lockPick)
                        fillSettings()
                        refreshStatus()
                        ui.removeCallbacks(ticker)
                        ui.post(ticker)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, lp(wrapC, wrapC).apply { topMargin = dp(14) })
        }
        col.addView(lock, lp(matchP, wrapC))

        // Snooze
        col.addView(sectionTitle("Need a break?"))
        val snooze = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5 to "5 min", 15 to "15 min", 60 to "1 hour").forEachIndexed { i, (mins, name) ->
            snooze.addView(pill(name, false) {
                if (free()) {
                    Store.setSnooze(this, System.currentTimeMillis() + mins * 60_000L)
                    refreshStatus()
                    Toast.makeText(this, "Paused for $name", Toast.LENGTH_SHORT).show()
                }
            }, lp(0, wrapC, 1f).apply { if (i < 2) marginEnd = dp(8) })
        }
        col.addView(snooze)

        // Extras
        col.addView(sectionTitle("Extras"))
        val extras = card()
        extras.addRow(toggleRow("Skip YouTube ads", "Taps the Skip button for you", Store.autoSkip(this)) { Store.setAutoSkip(this, it) })
        extras.addRow(toggleRow("Guard Shortstop itself", "Hold to open before settings can be changed", Store.selfLock(this)) { Store.setSelfLock(this, it) })
        col.addView(extras, lp(matchP, wrapC))

        // Appearance
        col.addView(sectionTitle("Appearance"))
        col.addView(chipRow(listOf(0 to "System", 1 to "Light", 2 to "Dark"), Store.theme(this)) {
            Store.setTheme(this, it)
            ui.post { buildAll(); selectedTabFix() }
        })

        // Troubleshooting
        col.addView(sectionTitle("Troubleshooting"))
        val fix = card()
        fix.addRow(linkRow("Accessibility settings", "Make sure Shortstop is switched on") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        fix.addRow(linkRow("App info and permissions", "Allow restricted settings (⋮ menu), set battery to No restrictions") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        fix.addRow(linkRow("Battery optimisation", "Stop the phone from closing Shortstop") {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        fix.addRow(linkRow("Xiaomi / Redmi autostart", "Allow Shortstop to start itself and run in the background") {
            try {
                startActivity(Intent().setComponent(ComponentName(
                    "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            } catch (e: Exception) {
                Toast.makeText(this, "Open Security > Permissions > Autostart and switch Shortstop on.", Toast.LENGTH_LONG).show()
            }
        })
        col.addView(fix, lp(matchP, wrapC))
        col.addView(note("Shortstop runs fully offline. Nothing leaves your phone."))

        settingsScroll?.post { settingsScroll?.scrollTo(0, keepY) }
    }

    private fun selectedTabFix() {
        // buildAll() keeps the same tab selected; just make sure the nav matches.
        styleNav()
    }

    private fun linkRow(title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 16f, fonts.semi, pal.ink))
        col.addView(tv(sub, 13f, fonts.regular, pal.muted).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(col, lp(0, wrapC, 1f).apply { marginEnd = dp(12) })
        row.addView(tv("›", 22f, fonts.regular, pal.muted))
        return row
    }
}
