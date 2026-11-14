package com.shortstop.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.TimePickerDialog
import android.app.usage.UsageStatsManager
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
import android.view.animation.OvershootInterpolator
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Shortstop. Black and white (Easlo design), Inter type, five tabs:
 * Focus (timer, goal, sessions), Schedule, Blocks, Stats, Settings.
 * Controls update their own views in place: nothing is rebuilt when you tap.
 */
class MainActivity : Activity() {

    private lateinit var pal: Palette
    private lateinit var fonts: Fonts
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var rootFrame: FrameLayout

    private val matchP = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapC = ViewGroup.LayoutParams.WRAP_CONTENT

    private val pages = ArrayList<View>()
    private var gooey: GooeyNav? = null
    private var tab = 0

    // Focus tab
    private var statusChip: CallChip? = null
    private var setupCard: View? = null
    private var runningCard: View? = null
    private var runRing: RingView? = null
    private var runInfo: TextView? = null
    private var runBreakBtn: TextView? = null
    private var runEndBtn: FuseButton? = null
    private var runLattice: LatticeLoader? = null
    private var runLabel: TextView? = null
    private var runElapsed: TextView? = null
    private val setupChecks = ArrayList<SpringCheck>()
    private var statsWrap: View? = null
    private var focusStatsCol: LinearLayout? = null
    private var startBar: View? = null
    private var focusSeg = 0
    private var weekOffset = 0
    private var monthOffset = 0
    private var wasRunning = false

    // Schedule tab
    private var schedCol: LinearLayout? = null

    // Blocks tab
    private var appMode = 0 // 0 = always on, 1 = focus only
    private var blocksSelect: (Int) -> Unit = {}
    private var appModeSelect: (Int) -> Unit = {}

    // Stats tab
    private var blockStatsCol: LinearLayout? = null
    private var usageCol: LinearLayout? = null
    private var usageFilter = 0
    private var charts = ArrayList<BarChart>()

    // Settings tab
    private var settingsCol: LinearLayout? = null
    private var settingsScroll: ScrollView? = null

    // bottom sheet
    private var sheetLayer: FrameLayout? = null
    private var sheetPanel: View? = null
    private var sheetScrim: View? = null
    private var sheetClose: (() -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateRunning()
            ui.postDelayed(this, 1000)
        }
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    /** False (with a short message) while strict mode is on: nothing may be switched off. */
    private fun free(): Boolean {
        if (!Store.frozen(this)) return true
        toast("Strict mode: ${Store.focusLeftMin(this)} min left. Nothing can change until it ends.")
        return false
    }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.split(':').any { it.startsWith("$packageName/") }
    }

    private fun notifAccess(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(packageName)
    }

    private fun usageGranted(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
    }

    private var toaster: Toaster? = null

    /** In-app toast (react-bits SwipeToast). First sentence is the title, the rest is the description. */
    private fun toast(msg: String, desc: String = "", action: String? = null, onAction: (() -> Unit)? = null) {
        var t = msg
        var d = desc
        if (d.isEmpty()) {
            val i = msg.indexOf(". ")
            if (i in 4 until msg.length - 3) { t = msg.substring(0, i); d = msg.substring(i + 2) }
        }
        val tz = toaster
        if (tz != null) tz.show(t.trimEnd('.'), d, action, onAction)
        else android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            toast("Couldn't open that screen on this phone.")
        }
    }

    private fun clock(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val x = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, x) else String.format("%02d:%02d", m, x)
    }

    private fun fmtMin(m: Int): String = when {
        m <= 0 -> "0m"
        m < 60 -> "${m}m"
        m % 60 == 0 -> "${m / 60}h"
        else -> "${m / 60}h ${m % 60}m"
    }

    private fun fmt12(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val x = h % 12
        return String.format("%02d:%02d %s", if (x == 0) 12 else x, m, if (h < 12) "AM" else "PM")
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
        Store.finalizeFocus(this)
        refreshStatus()
        fillStats()
        fillFocusStats()
        fillSchedules()
        updateRunning()
        if (tab == 3 && usageCol?.visibility == View.VISIBLE) fillUsage()
        ui.removeCallbacks(ticker)
        ui.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (sheetLayer != null) closeSheet() else super.onBackPressed()
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
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                    .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(SpringInterpolator(18f, 0.5f, 0.8f)).start()
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

    private fun card(): LinearLayout = GlowCard(
        this, Color.argb(if (pal.dark) 26 else 20, Color.red(pal.ink), Color.green(pal.ink), Color.blue(pal.ink)),
        dp(24).toFloat(), dp(170).toFloat()
    ).apply {
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
        setupChecks.clear()
        sheetLayer = null

        rootFrame = FrameLayout(this).apply { setBackgroundColor(pal.canvas) }
        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val holder = FrameLayout(this)

        pages.add(focusPage())
        pages.add(schedulePage())
        pages.add(blocksPage())
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
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(pal.canvas)
            clipChildren = false
            clipToPadding = false
        }
        rootView.clipChildren = false
        val g = GooeyNav(this, pal, fonts, listOf("Focus", "Schedule", "Blocks", "Stats", "Settings"), tab) { i -> selectTab(i) }
        gooey = g
        nav.addView(g, lp(matchP, wrapC))
        rootView.addView(nav, lp(matchP, wrapC))
        rootFrame.addView(rootView, FrameLayout.LayoutParams(matchP, matchP))
        toaster = Toaster(this, rootFrame, pal, fonts, dp(84))
        spark = SparkLayer(this, pal.ink)
        rootFrame.addView(spark, FrameLayout.LayoutParams(matchP, matchP))
        setContentView(rootFrame)
        styleNav()
        refreshStatus()
        updateRunning()
        stagger(pages[tab])
    }

    private fun styleNav() {
        gooey?.select(tab, true)
    }

    private fun selectTab(i: Int) {
        if (i == tab) return
        val old = pages[tab]
        val nw = pages[i]
        val tabBefore = tab
        tab = i
        old.animate().cancel()
        old.visibility = View.GONE
        nw.animate().cancel()
        nw.visibility = View.VISIBLE
        val dir = if (i > tabBefore) 1f else -1f
        nw.alpha = 0f
        nw.translationX = dir * dp(36)
        nw.animate().alpha(1f).translationX(0f).setDuration(520).setInterpolator(SpringInterpolator(15f, 0.75f, 0.9f)).start()
        stagger(nw)
        styleNav()
        when (i) {
            0 -> { fillFocusStats(); refreshStatus() }
            1 -> fillSchedules()
            3 -> { fillStats(); charts.forEach { it.play() }; if (usageCol?.visibility == View.VISIBLE) fillUsage() }
        }
    }

    private val stagAnims = ArrayList<ValueAnimator>()

    /** Rows spring into view (react-bits AnimatedList, pop). */
    private fun popIn(v: View, delay: Long) {
        (v.getTag(R_POP) as? ValueAnimator)?.cancel()
        v.alpha = 0f
        v.scaleX = 0.86f
        v.scaleY = 0.86f
        v.translationY = dp(26).toFloat()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.startDelay = delay
        a.duration = 900
        a.interpolator = SpringInterpolator(15f, 0.6f, 0.9f)
        a.addUpdateListener {
            val f = it.animatedValue as Float
            v.alpha = (f * 2.4f).coerceIn(0f, 1f)
            v.scaleX = 0.86f + 0.14f * f
            v.scaleY = 0.86f + 0.14f * f
            v.translationY = (1f - f) * dp(26)
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: android.animation.Animator) {
                v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; v.translationY = 0f
            }
        })
        v.setTag(R_POP, a)
        a.start()
    }

    /** Cards of a page glide in one after another (react-bits AnimatedList). */
    private fun stagger(page: View) {
        stagAnims.forEach { it.cancel() }
        stagAnims.clear()
        var col: ViewGroup? = null
        if (page is ViewGroup) {
            for (i in 0 until page.childCount) {
                val ch = page.getChildAt(i)
                if (ch is ScrollView && ch.childCount > 0) { col = ch.getChildAt(0) as? ViewGroup; break }
            }
        }
        val list = col ?: return
        val n = minOf(list.childCount, 9)
        for (i in 0 until n) {
            val v = list.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(16).toFloat()
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.startDelay = i * 40L
            a.duration = 320
            a.interpolator = DecelerateInterpolator(1.6f)
            a.addUpdateListener {
                val f = it.animatedValue as Float
                v.alpha = f
                v.translationY = (1f - f) * dp(16)
            }
            a.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    v.alpha = 1f; v.translationY = 0f
                }
            })
            stagAnims.add(a)
            a.start()
        }
    }

    private val R_POP = 0x7f0a0001
    private val visibleKeys = HashSet<String>()
    private var lastPop = 0L
    private var popIdx = 0
    private var spark: SparkLayer? = null
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; downT = ev.eventTime }
            MotionEvent.ACTION_UP -> {
                val s = spark
                if (s != null && ev.eventTime - downT < 350 &&
                    Math.abs(ev.rawX - downX) < dp(10) && Math.abs(ev.rawY - downY) < dp(10)) {
                    val loc = IntArray(2)
                    s.getLocationOnScreen(loc)
                    s.burst(ev.rawX - loc[0], ev.rawY - loc[1])
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun openFocusApps() {
        selectTab(2)
        blocksSelect(1)
        appModeSelect(1)
    }

    /** Pill-shaped segmented control. Returns the view and a function that selects a segment. */
    private fun segControl(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): Pair<LinearLayout, (Int) -> Unit> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(pal.surface, 40, pal.border, dp(1))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val items = ArrayList<TextView>()
        val select: (Int) -> Unit = { i ->
            items.forEachIndexed { j, v -> setPill(v, j == i) }
            onSelect(i)
        }
        labels.forEachIndexed { i, l ->
            val t = pill(l, i == selected) {}
            t.textSize = 13f
            t.setPadding(dp(8), dp(11), dp(8), dp(11))
            t.setOnClickListener { select(i) }
            items.add(t)
            box.addView(t, lp(0, wrapC, 1f).apply { if (i > 0) marginStart = dp(4) })
        }
        return box to select
    }

    // ---------- bottom sheet ----------
    private fun showSheet(title: String, content: View, onClose: (() -> Unit)? = null) {
        closeSheet(false)
        val layer = FrameLayout(this)
        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            alpha = 0f
            setOnClickListener { closeSheet() }
        }
        val r = dp(28).toFloat()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.canvas)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), pal.border)
            }
            setPadding(dp(20), dp(10), dp(20), dp(20))
            isClickable = true
        }
        val handle = View(this).apply { background = round(pal.border, 4) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
        head.addView(handle, lp(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        head.addView(display(title, 22f), lp(wrapC, wrapC))
        var downY = 0f
        head.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).coerceAtLeast(0f)
                    panel.translationY = dy
                    scrim.alpha = (1f - dy / maxOf(1, panel.height)).coerceIn(0f, 1f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.rawY - downY > dp(110)) closeSheet()
                    else {
                        panel.animate().translationY(0f).setDuration(420).setInterpolator(SpringInterpolator(16f, 0.6f, 0.8f)).start()
                        scrim.animate().alpha(1f).setDuration(200).start()
                    }
                    true
                }
                else -> true
            }
        }
        panel.addView(head, lp(matchP, wrapC))
        val maxH = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        val sc = CapScroll(this, maxH).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, FrameLayout.LayoutParams(matchP, wrapC))
        }
        panel.addView(sc, lp(matchP, wrapC))
        layer.addView(scrim, FrameLayout.LayoutParams(matchP, matchP))
        layer.addView(panel, FrameLayout.LayoutParams(matchP, wrapC, Gravity.BOTTOM))
        rootFrame.addView(layer, FrameLayout.LayoutParams(matchP, matchP))
        toaster?.front()
        spark?.bringToFront()
        panel.translationY = dp(700).toFloat()
        scrim.animate().alpha(1f).setDuration(200).start()
        panel.animate().translationY(0f).setDuration(520).setInterpolator(SpringInterpolator(14f, 0.8f, 0.9f)).start()
        sheetLayer = layer
        sheetPanel = panel
        sheetScrim = scrim
        sheetClose = onClose
    }

    private fun closeSheet(animate: Boolean = true) {
        val layer = sheetLayer ?: return
        val panel = sheetPanel
        val scrim = sheetScrim
        val cb = sheetClose
        sheetLayer = null
        sheetPanel = null
        sheetScrim = null
        sheetClose = null
        if (!animate || panel == null || scrim == null) {
            rootFrame.removeView(layer)
            return
        }
        scrim.animate().alpha(0f).setDuration(180).start()
        panel.animate().translationY(dp(700).toFloat()).setDuration(220).withEndAction {
            rootFrame.removeView(layer)
            cb?.invoke()
        }.start()
    }

    // ---------- Focus tab ----------
    private fun batteryOk(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (e: Exception) { true }

    private fun stepRow(title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val chk = SpringCheck(this, pal, fonts, title, 18f)
        setupChecks.add(chk)
        row.addView(chk, lp(wrapC, wrapC))
        row.addView(tv(sub, 13f, fonts.regular, pal.muted).apply { setPadding(dp(40), dp(4), 0, 0) })
        return row
    }

    private fun refreshStatus() {
        val on = serviceOn()
        val batt = batteryOk()
        setupCard?.visibility = if (on && batt) View.GONE else View.VISIBLE
        if (setupChecks.size == 3) {
            setupChecks[0].setChecked(on, true)
            setupChecks[1].setChecked(on, true)
            setupChecks[2].setChecked(batt, true)
        }
        val running = Store.focusRunning(this)
        val chip = statusChip
        if (chip != null) {
            val now = System.currentTimeMillis()
            val start = Store.focusStart(this)
            val end = Store.focusEnd(this)
            val frac = if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
            when {
                !on -> chip.set("setup", "needed", null, 0f, CallChip.ERROR)
                running && Store.onBreak(this) -> chip.set("break", "paused", clock(maxOf(0L, Store.breakUntil(this) - now)), 0f, CallChip.IDLE)
                running -> chip.set("focus", Store.focusTag(this).lowercase().ifEmpty { "session" }, clock(Store.focusLeftMs(this)), frac, CallChip.RUNNING)
                Store.scheduleActive(this) != null -> chip.set("routine", "on", null, 1f, CallChip.DONE)
                else -> chip.set("shortstop", "ready", null, 0f, CallChip.IDLE)
            }
        }
    }

    private fun focusPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = column()

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(display("Focus", 30f), lp(0, wrapC, 1f))
        val chip = CallChip(this, pal, fonts)
        statusChip = chip
        top.addView(chip)
        col.addView(top)

        // First-run setup
        val setup = card()
        setup.addView(display("Finish setup", 20f).apply { setPadding(0, dp(16), 0, dp(4)) })
        setup.addRow(stepRow("Turn on Shortstop", "Accessibility settings > Shortstop > On") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        setup.addRow(stepRow("Allow restricted settings", "App info > ⋮ menu > Allow restricted settings") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        setup.addRow(stepRow("Keep it running", "Battery: No restrictions (Xiaomi: allow Autostart)") {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        setupCard = setup
        col.addView(setup, lp(matchP, wrapC).apply { topMargin = dp(18) })

        // Running session
        val run = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        val stat = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val lat = LatticeLoader(this, pal.ink)
        runLattice = lat
        stat.addView(lat, lp(wrapC, wrapC).apply { marginEnd = dp(10) })
        val rl = tv("Focusing", 14f, fonts.medium, pal.ink)
        runLabel = rl
        stat.addView(rl, lp(wrapC, wrapC))
        val re = tv("", 13f, fonts.regular, pal.muted)
        runElapsed = re
        stat.addView(re, lp(wrapC, wrapC).apply { marginStart = dp(10) })
        run.addView(stat, lp(wrapC, wrapC).apply { bottomMargin = dp(18) })
        val ring = RingView(this, pal, fonts, 240, 16, true)
        runRing = ring
        run.addView(ring, lp(wrapC, wrapC))
        val info = tv("", 14f, fonts.regular, pal.muted, Gravity.CENTER).apply { setPadding(0, dp(18), 0, dp(16)) }
        runInfo = info
        run.addView(info, lp(matchP, wrapC))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val breakBtn = pill("Take a break", false) { onBreakClick() }
        val endBtn = FuseButton(this, pal, fonts, "End session", "Undo", "Ended", 4000L,
            { if (Store.frozen(this)) { free(); false } else true }) {
            Store.endFocusEarly(this)
            wasRunning = false
            updateRunning()
            fillFocusStats()
        }
        runBreakBtn = breakBtn
        runEndBtn = endBtn
        btns.addView(breakBtn, lp(0, wrapC, 1f).apply { marginEnd = dp(8) })
        btns.addView(endBtn, lp(0, wrapC, 1f))
        run.addView(btns, lp(matchP, wrapC))
        val runWrap = FrameLayout(this).apply {
            background = round(pal.surface, 24, pal.border, dp(1))
            clipToOutline = true
            visibility = View.GONE
        }
        runWrap.addView(SilkView(this, pal.surface, pal.dark), FrameLayout.LayoutParams(matchP, matchP))
        runWrap.addView(run, FrameLayout.LayoutParams(matchP, wrapC))
        runningCard = runWrap
        col.addView(runWrap, lp(matchP, wrapC).apply { topMargin = dp(18) })

        // Day / Week / Month
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (seg, _) = segControl(listOf("Day", "Week", "Month"), focusSeg) { i ->
            focusSeg = i
            fillFocusStats()
        }
        wrap.addView(seg, lp(matchP, wrapC).apply { topMargin = dp(18) })
        val statsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        focusStatsCol = statsBox
        wrap.addView(statsBox, lp(matchP, wrapC))
        statsWrap = wrap
        col.addView(wrap, lp(matchP, wrapC))

        page.addView(scrollPage(col), lp(matchP, 0, 1f))

        // Start bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(pal.ink, 40)
            setPadding(dp(26), dp(12), dp(12), dp(12))
            isClickable = true
            setOnClickListener { showStartSheet() }
            press(this)
        }
        bar.addView(tv("Start focus timer", 16f, fonts.semi, pal.canvas), lp(0, wrapC, 1f))
        bar.addView(tv("▶", 16f, fonts.bold, pal.ink, Gravity.CENTER).apply {
            background = round(pal.canvas, 40)
        }, lp(dp(44), dp(44)))
        val barWrap = LinearLayout(this).apply { setPadding(dp(20), dp(10), dp(20), dp(14)) }
        barWrap.addView(bar, lp(matchP, wrapC))
        startBar = barWrap
        page.addView(barWrap, lp(matchP, wrapC))

        refreshStatus()
        fillFocusStats()
        return page
    }

    private fun onBreakClick() {
        if (Store.onBreak(this)) {
            Store.endBreak(this)
        } else if (Store.startBreak(this)) {
            toast("Break started. Blocks return when it ends.")
        }
        updateRunning()
    }

    private fun onEndClick() {
        if (Store.frozen(this)) { free(); return }
        AlertDialog.Builder(this)
            .setTitle("End this session?")
            .setMessage("The focus time so far is saved.")
            .setPositiveButton("End session") { _, _ ->
                Store.endFocusEarly(this)
                wasRunning = false
                updateRunning()
                fillFocusStats()
            }
            .setNegativeButton("Keep going", null)
            .show()
    }

    private fun updateRunning() {
        Store.finalizeFocus(this)
        val running = Store.focusRunning(this)
        runningCard?.visibility = if (running) View.VISIBLE else View.GONE
        statsWrap?.visibility = if (running) View.GONE else View.VISIBLE
        startBar?.visibility = if (running) View.GONE else View.VISIBLE
        if (running) {
            val now = System.currentTimeMillis()
            val start = Store.focusStart(this)
            val end = Store.focusEnd(this)
            val frac = if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
            val onBreak = Store.onBreak(this)
            runRing?.big = clock(Store.focusLeftMs(this))
            runRing?.small = if (onBreak) "On break" else Store.focusTag(this).ifEmpty { "Focus" }
            runRing?.setProgress(frac, true, 900)
            runInfo?.text = if (onBreak) "Break ends in ${clock(Store.breakUntil(this) - now)}. Blocks come back after."
            else "${Store.breaksLeft(this)} break(s) left. ${if (Store.focusStrict(this)) "Strict mode is on." else ""}"
            runBreakBtn?.let { b ->
                b.visibility = if (!onBreak && Store.breaksLeft(this) == 0) View.GONE else View.VISIBLE
                b.text = if (onBreak) "End break" else "Take a break"
            }
            runEndBtn?.let { b ->
                b.text = if (Store.frozen(this)) "Strict mode" else "End session"
                b.alpha = if (Store.frozen(this)) 0.5f else 1f
            }
            runLattice?.running = !onBreak
            runLabel?.text = if (onBreak) "On break" else "Focusing"
            runElapsed?.text = clock(now - start)
        } else {
            runEndBtn?.reset()
        }
        if (wasRunning && !running) onSessionDone()
        wasRunning = running
        refreshStatus()
    }

    private fun onSessionDone() {
        fillFocusStats()
        val last = Store.sessions(this).lastOrNull() ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        box.addView(display(fmtMin(last.minutes), 44f))
        box.addView(tv("${last.tag.ifEmpty { "Focus" }} session finished.", 15f, fonts.regular, pal.muted).apply { setPadding(0, dp(6), 0, dp(16)) })
        val heartRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heartRow.addView(tv("Sessions you kept", 14f, fonts.regular, pal.muted), lp(0, wrapC, 1f))
        heartRow.addView(PulseHeart(this, pal, fonts, Store.lovedCount(this), Store.isLoved(this, last.start), true) { on, _ ->
            Store.setLoved(this, last.start, on)
        }, lp(wrapC, wrapC))
        box.addView(heartRow, lp(matchP, wrapC).apply { bottomMargin = dp(18) })
        box.addView(pill("Done", true) { closeSheet() }, lp(matchP, wrapC))
        showSheet("Session complete", box)
    }

    // ----- Day / Week / Month -----
    private fun weekStart(offset: Int): Calendar {
        val c = Calendar.getInstance()
        c.add(Calendar.WEEK_OF_YEAR, offset)
        val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        c.add(Calendar.DAY_OF_YEAR, -dow)
        return c
    }

    private fun dayRings(start: Calendar): View {
        val goal = Store.goalMin(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, dp(6)) }
        val todayKey = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
        for (i in 0..6) {
            val d = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
            val isToday = d.get(Calendar.YEAR) * 1000 + d.get(Calendar.DAY_OF_YEAR) == todayKey
            val m = Store.focusMinutesOn(this, d)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(tv("MTWTFSS"[i].toString(), 12f, if (isToday) fonts.bold else fonts.medium,
                if (isToday) pal.ink else pal.muted, Gravity.CENTER))
            val r = RingView(this, pal, fonts, 34, 4, false)
            r.setProgress(if (goal > 0) m.toFloat() / goal else 0f, true, 700)
            col.addView(r, lp(wrapC, wrapC).apply { topMargin = dp(6) })
            row.addView(col, lp(0, wrapC, 1f))
        }
        return row
    }

    private fun navRow(label: String, onPrev: () -> Unit, onNext: (() -> Unit)?): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        row.addView(smallBtn("‹") { onPrev() })
        row.addView(tv(label, 16f, fonts.semi, pal.ink, Gravity.CENTER), lp(0, wrapC, 1f))
        val next = smallBtn("›") { onNext?.invoke() }
        next.alpha = if (onNext == null) 0.3f else 1f
        row.addView(next)
        return row
    }

    private fun sectionCard(title: String): LinearLayout {
        val c = card()
        c.addView(tv(title, 15f, fonts.semi, pal.ink).apply { setPadding(0, dp(16), 0, dp(10)) })
        return c
    }

    private fun fillFocusStats() {
        val col = focusStatsCol ?: return
        col.removeAllViews()
        val goal = Store.goalMin(this)
        when (focusSeg) {
            0 -> {
                col.addView(dayRings(weekStart(0)))
                val today = Calendar.getInstance()
                val mins = Store.focusMinutesOn(this, today)
                val ring = RingView(this, pal, fonts, 220, 14, true)
                ring.big = fmtMin(mins)
                ring.small = "Focus goal ${fmtMin(goal)}"
                ring.setProgress(if (goal > 0) mins.toFloat() / goal else 0f, true, 900)
                col.addView(ring, lp(wrapC, wrapC).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
                col.addView(pill("Edit goal", false) { showGoalSheet() }.apply { textSize = 13f },
                    lp(wrapC, wrapC).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(14) })

                val sessions = Store.sessionsOn(this, today)
                val segs = sessions.map { s ->
                    val c = Calendar.getInstance().apply { timeInMillis = s.start }
                    val a = (c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)) / 1440f
                    a to (a + s.minutes / 1440f)
                }
                val sum = sectionCard("Today's summary")
                sum.addView(TimelineView(this, pal, fonts, segs), lp(matchP, wrapC).apply { bottomMargin = dp(10) })
                col.addView(sum, lp(matchP, wrapC).apply { topMargin = dp(18) })

                val tags = sectionCard("Tags")
                if (sessions.isEmpty()) tags.addView(tv("No sessions yet", 13f, fonts.regular, pal.muted).apply { setPadding(0, 0, 0, dp(16)) })
                sessions.groupBy { it.tag.ifEmpty { "No tag" } }.forEach { (t, l) ->
                    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(12)) }
                    r.addView(tv(t, 15f, fonts.medium, pal.ink), lp(0, wrapC, 1f))
                    r.addView(tv(fmtMin(l.sumOf { it.minutes }), 15f, fonts.semi, pal.ink))
                    tags.addView(r)
                }
                col.addView(tags, lp(matchP, wrapC).apply { topMargin = dp(12) })

                val sess = sectionCard("Sessions")
                if (sessions.isEmpty()) sess.addView(tv("No sessions yet", 13f, fonts.regular, pal.muted).apply { setPadding(0, 0, 0, dp(16)) })
                val tf = SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", Locale.getDefault())
                sessions.forEachIndexed { idx, s ->
                    val r = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12))
                    }
                    val mark = StatusMark(this, pal, 24f)
                    mark.setStatus(if (s.done) StatusMark.DONE else StatusMark.CANCELLED, true, idx * 70L)
                    r.addView(mark, lp(wrapC, wrapC).apply { marginEnd = dp(12) })
                    val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    left.addView(tv(s.tag.ifEmpty { "Focus" }, 15f, fonts.medium, pal.ink).apply {
                        if (!s.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    })
                    left.addView(tv(tf.format(java.util.Date(s.start)) + if (s.done) "" else " · ended early", 12f, fonts.regular, pal.muted))
                    r.addView(left, lp(0, wrapC, 1f))
                    r.addView(tv(fmtMin(s.minutes), 15f, fonts.semi, pal.ink).apply { setPadding(0, 0, dp(12), 0) })
                    r.addView(PulseHeart(this, pal, fonts, 0, Store.isLoved(this, s.start), false) { on, _ ->
                        Store.setLoved(this, s.start, on)
                    }, lp(dp(40), dp(40)))
                    sess.addView(r)
                }
                col.addView(sess, lp(matchP, wrapC).apply { topMargin = dp(12) })
            }
            1 -> {
                val start = weekStart(weekOffset)
                val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }
                val f = SimpleDateFormat("d MMM", Locale.getDefault())
                col.addView(navRow("${f.format(start.time)} – ${f.format(end.time)}",
                    { weekOffset--; fillFocusStats() },
                    if (weekOffset < 0) ({ weekOffset++; fillFocusStats() }) else null))
                col.addView(dayRings(start))
                val vals = (0..6).map { i -> Store.focusMinutesOn(this, (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }) }
                val total = vals.sum()
                val ring = RingView(this, pal, fonts, 220, 14, true)
                ring.big = fmtMin(total)
                ring.small = "Week goal ${fmtMin(goal * 7)}"
                ring.setProgress(if (goal > 0) total.toFloat() / (goal * 7) else 0f, true, 900)
                col.addView(ring, lp(wrapC, wrapC).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
                col.addView(tv("Daily average ${fmtMin(total / 7)}", 14f, fonts.regular, pal.muted, Gravity.CENTER).apply { setPadding(0, dp(12), 0, dp(4)) },
                    lp(matchP, wrapC))
                val cc = card().apply { setPadding(dp(20), dp(20), dp(20), dp(18)) }
                val chart = BarChart(this, vals, listOf("M", "T", "W", "T", "F", "S", "S"), if (weekOffset == 0) (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 else -1)
                charts.add(chart)
                cc.addView(chart, lp(matchP, wrapC))
                col.addView(cc, lp(matchP, wrapC).apply { topMargin = dp(12) })
                chart.play()
            }
            else -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, monthOffset)
                val year = cal.get(Calendar.YEAR)
                val month = cal.get(Calendar.MONTH)
                val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                val arr = IntArray(32)
                var total = 0
                var met = 0
                for (d in 1..days) {
                    val c = Calendar.getInstance().apply { set(year, month, d, 12, 0, 0) }
                    arr[d] = Store.focusMinutesOn(this, c)
                    total += arr[d]
                    if (arr[d] >= goal) met++
                }
                val f = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                col.addView(navRow(f.format(cal.time),
                    { monthOffset--; fillFocusStats() },
                    if (monthOffset < 0) ({ monthOffset++; fillFocusStats() }) else null))
                col.addView(MonthView(this, pal, fonts, year, month, arr, goal), lp(matchP, wrapC).apply { topMargin = dp(14) })
                val cc = card().apply { setPadding(dp(20), dp(18), dp(20), dp(18)) }
                cc.addView(display(fmtMin(total), 30f))
                cc.addView(tv("Total focus · $met day(s) reached the goal", 13f, fonts.regular, pal.muted).apply { setPadding(0, dp(4), 0, 0) })
                col.addView(cc, lp(matchP, wrapC).apply { topMargin = dp(12) })
            }
        }
    }

    // ----- sheets for focus -----
    private fun showGoalSheet() {
        var g = Store.goalMin(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        val big = display(fmtMin(g), 44f)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(big, lp(0, wrapC, 1f))
        row.addView(smallBtn("−") { g = (g - 30).coerceAtLeast(30); big.text = fmtMin(g) })
        row.addView(smallBtn("+") { g = (g + 30).coerceAtMost(960); big.text = fmtMin(g) }, lp(wrapC, wrapC).apply { marginStart = dp(8) })
        box.addView(row)
        box.addView(tv("How much focus time you want each day.", 13f, fonts.regular, pal.muted).apply { setPadding(0, dp(8), 0, dp(18)) })
        box.addView(pill("Save", true) {
            Store.setGoalMin(this, g)
            closeSheet()
            fillFocusStats()
        }, lp(matchP, wrapC))
        showSheet("Daily focus goal", box)
    }

    private fun showStartSheet() {
        var dur = Store.lastMinutes(this)
        var tag = Store.lastTag(this)
        var strict = Store.strictDefault(this)
        var breaks = Store.lastBreaks(this)
        var breakMin = Store.lastBreakMin(this)

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        box.addView(groupLabel("Duration").apply { setPadding(dp(4), 0, 0, dp(6)) })
        val durText = display("$dur min", 34f)
        val presets = listOf(15, 25, 45, 60, 90)
        val chips = ArrayList<TextView>()
        fun setDur(v: Int) {
            dur = v.coerceIn(5, 480)
            durText.text = "$dur min"
            chips.forEachIndexed { i, ch -> setPill(ch, presets[i] == dur) }
        }
        val durRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        durRow.addView(durText, lp(0, wrapC, 1f))
        durRow.addView(smallBtn("−") { setDur(dur - 5) })
        durRow.addView(smallBtn("+") { setDur(dur + 5) }, lp(wrapC, wrapC).apply { marginStart = dp(8) })
        box.addView(durRow)
        val chipLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(4)) }
        presets.forEachIndexed { i, v ->
            val ch = pill("$v", v == dur) { setDur(v) }
            ch.textSize = 13f
            ch.setPadding(dp(4), dp(10), dp(4), dp(10))
            chips.add(ch)
            chipLine.addView(ch, lp(0, wrapC, 1f).apply { if (i < presets.size - 1) marginEnd = dp(6) })
        }
        box.addView(chipLine)

        box.addView(groupLabel("Tag").apply { setPadding(dp(4), dp(12), 0, dp(6)) })
        val tagChips = ArrayList<TextView>()
        val tagLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Store.TAGS.forEachIndexed { i, name ->
            val ch = pill(name, name == tag) {}
            ch.textSize = 12f
            ch.setPadding(dp(2), dp(10), dp(2), dp(10))
            ch.setOnClickListener {
                tag = name
                tagChips.forEachIndexed { j, c -> setPill(c, Store.TAGS[j] == name) }
            }
            tagChips.add(ch)
            tagLine.addView(ch, lp(0, wrapC, 1f).apply { if (i < Store.TAGS.size - 1) marginEnd = dp(5) })
        }
        box.addView(tagLine)

        val opts = card()
        opts.addRow(toggleRow("Strict mode", "You can't quit until the timer ends", strict) {
            strict = it
            Store.setStrictDefault(this, it)
        })
        opts.addRow(stepperRow({ "Breaks: $it" }, breaks, 0, 10, 1) { breaks = it })
        opts.addRow(stepperRow({ "Break length: $it min" }, breakMin, 1, 30, 1) { breakMin = it })
        opts.addRow(linkRow("Blocked during focus", "${Store.focusApps(this).size} app(s) selected") {
            Store.rememberSession(this, dur, tag, breaks, breakMin)
            closeSheet()
            openFocusApps()
        })
        opts.addRow(linkRow("Ultra Focus settings", "Study mode, uninstall guard, notifications, home screen") {
            Store.rememberSession(this, dur, tag, breaks, breakMin)
            showUltraSheet { showStartSheet() }
        })
        box.addView(opts, lp(matchP, wrapC).apply { topMargin = dp(16) })

        box.addView(SlideCommit(this, pal, fonts, "Slide to start", "Started", {
            if (!serviceOn()) {
                toast("Turn on the Shortstop accessibility service first.")
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                false
            } else true
        }) {
            if (Store.focusApps(this).isEmpty()) {
                toast("No focus apps picked yet, so only your always-on rules apply.")
            }
            Store.startFocus(this, dur, tag, strict, breaks, breakMin)
            closeSheet()
            wasRunning = true
            updateRunning()
        }, lp(matchP, wrapC).apply { topMargin = dp(16); bottomMargin = dp(4); gravity = Gravity.CENTER_HORIZONTAL })
        showSheet("Start focus", box)
    }

    private fun showUltraSheet(onClose: (() -> Unit)? = null) {
        val c = card()
        c.addRow(toggleRow("Strict mode", "You cannot quit until the timer ends", Store.strictDefault(this)) { Store.setStrictDefault(this, it) })
        c.addRow(toggleRow("YouTube Study Mode", "YouTube stays open, but Shorts and the home feed are blocked", Store.studyMode(this)) { Store.setStudyMode(this, it) })
        c.addRow(toggleRow("Uninstall protection", "Keeps Settings away from Shortstop during focus", Store.uninstallGuard(this)) { Store.setUninstallGuard(this, it) })
        c.addRow(toggleRow("Block notifications", "Hides notifications from your blocked apps", Store.blockNotifs(this)) {
            Store.setBlockNotifs(this, it)
            if (it && !notifAccess()) {
                toast("Switch on Notification access for Shortstop.")
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        })
        c.addRow(toggleRow("Block home screen", "Covers the home screen with the focus timer", Store.blockHome(this)) { Store.setBlockHome(this, it) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(c, lp(matchP, wrapC))
        box.addView(note("Uninstall protection makes removal harder, but it can't stop Android's own safe mode."))
        showSheet("Ultra Focus settings", box, onClose)
    }

    // ---------- Schedule tab ----------
    private fun daysLabel(mask: Int): String = when (mask) {
        127 -> "Every day"
        31 -> "Mon–Fri"
        96 -> "Sat–Sun"
        0 -> "No days"
        else -> {
            val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            names.filterIndexed { i, _ -> ((mask shr i) and 1) == 1 }.joinToString(" ")
        }
    }

    private fun schedulePage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = column()
        col.addView(display("Schedule", 30f))
        col.addView(tv("Focus starts by itself in these windows. Your focus apps are closed until they end.",
            14f, fonts.regular, pal.muted).apply { setPadding(0, dp(8), 0, dp(6)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        schedCol = list
        col.addView(list, lp(matchP, wrapC).apply { topMargin = dp(10) })
        page.addView(scrollPage(col), lp(matchP, 0, 1f))
        val barWrap = LinearLayout(this).apply { setPadding(dp(20), dp(10), dp(20), dp(14)) }
        barWrap.addView(pill("Create schedule", true) { showScheduleSheet(null) }, lp(matchP, wrapC))
        page.addView(barWrap, lp(matchP, wrapC))
        fillSchedules()
        return page
    }

    private fun fillSchedules() {
        val list = schedCol ?: return
        list.removeAllViews()
        val items = Store.schedules(this)
        if (items.isEmpty()) {
            list.addView(display("Create your study schedule", 26f).apply { setPadding(0, dp(40), 0, dp(8)) })
            list.addView(tv("Pick the hours you want to stay focused. Shortstop does the rest.", 15f, fonts.regular, pal.muted))
            return
        }
        for (s in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(pal.surface, 24, pal.border, dp(1))
                setPadding(dp(20), dp(16), dp(16), dp(16))
                isClickable = true
                setOnClickListener { showScheduleSheet(s) }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(s.name, 17f, fonts.semi, pal.ink))
            col.addView(tv("${fmt12(s.start)} – ${fmt12(s.end)}", 13f, fonts.regular, pal.muted).apply { setPadding(0, dp(4), 0, 0) })
            col.addView(tv(daysLabel(s.days), 13f, fonts.regular, pal.muted).apply { setPadding(0, dp(2), 0, 0) })
            row.addView(col, lp(0, wrapC, 1f).apply { marginEnd = dp(12) })
            val t = ToggleView(this)
            t.setOn(s.on, false)
            t.listener = { on ->
                Store.saveSchedules(this, Store.schedules(this).map { if (it.id == s.id) it.copy(on = on) else it })
                refreshStatus()
            }
            row.addView(t)
            list.addView(row, lp(matchP, wrapC).apply { bottomMargin = dp(10) })
        }
    }

    private fun showScheduleSheet(existing: Store.FocusSchedule?) {
        if (!free()) return
        var name = existing?.name ?: "Evening Study"
        var start = existing?.start ?: 18 * 60
        var end = existing?.end ?: 20 * 60
        var days = existing?.days ?: 31

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameInput = input("Name")
        nameInput.setText(name)
        watch(nameInput) { name = it }
        val startBtn = pill(fmt12(start), false) {}
        val endBtn = pill(fmt12(end), false) {}
        val dayChips = ArrayList<TextView>()
        fun paintDays() { dayChips.forEachIndexed { i, c -> setPill(c, ((days shr i) and 1) == 1) } }
        fun fillForm(n: String, s: Int, e: Int, d: Int) {
            name = n; start = s; end = e; days = d
            nameInput.setText(n)
            startBtn.text = fmt12(s)
            endBtn.text = fmt12(e)
            paintDays()
        }

        if (existing == null) {
            box.addView(groupLabel("Templates").apply { setPadding(dp(4), 0, 0, dp(6)) })
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val templates = listOf(
                Triple("Morning Study", 6 * 60, 8 * 60),
                Triple("Evening Study", 18 * 60, 20 * 60),
                Triple("Night", 22 * 60, 6 * 60)
            )
            templates.forEachIndexed { i, (n, s, e) ->
                val c = pill(n, false) { fillForm(n, s, e, if (n == "Night") 127 else 31) }
                c.textSize = 12f
                c.setPadding(dp(4), dp(10), dp(4), dp(10))
                line.addView(c, lp(0, wrapC, 1f).apply { if (i < 2) marginEnd = dp(6) })
            }
            box.addView(line)
        }

        box.addView(groupLabel("Name").apply { setPadding(dp(4), dp(14), 0, dp(6)) })
        box.addView(nameInput, lp(matchP, wrapC))

        box.addView(groupLabel("Time").apply { setPadding(dp(4), dp(14), 0, dp(6)) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        startBtn.setOnClickListener { pickTime(start) { m -> start = m; startBtn.text = fmt12(m) } }
        endBtn.setOnClickListener { pickTime(end) { m -> end = m; endBtn.text = fmt12(m) } }
        times.addView(startBtn, lp(0, wrapC, 1f))
        times.addView(tv("to", 14f, fonts.regular, pal.muted).apply { setPadding(dp(12), 0, dp(12), 0) })
        times.addView(endBtn, lp(0, wrapC, 1f))
        box.addView(times)

        box.addView(groupLabel("Active days").apply { setPadding(dp(4), dp(14), 0, dp(6)) })
        val dayLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        "MTWTFSS".forEachIndexed { i, ch ->
            val c = pill(ch.toString(), ((days shr i) and 1) == 1) {}
            c.textSize = 12f
            c.setPadding(dp(2), dp(10), dp(2), dp(10))
            c.setOnClickListener { days = days xor (1 shl i); paintDays() }
            dayChips.add(c)
            dayLine.addView(c, lp(0, wrapC, 1f).apply { if (i < 6) marginEnd = dp(4) })
        }
        box.addView(dayLine)
        box.addView(note("Overnight windows work too, for example 10:00 PM to 6:00 AM."))

        box.addView(pill("Save", true) {
            if (start == end) { toast("Start and end can't be the same."); return@pill }
            if (days == 0) { toast("Pick at least one day."); return@pill }
            val n = name.trim().ifEmpty { "Schedule" }
            val all = Store.schedules(this)
            val updated = if (existing == null) {
                all + Store.FocusSchedule(System.currentTimeMillis().toString(), n, start, end, days, true)
            } else {
                all.map { if (it.id == existing.id) it.copy(name = n, start = start, end = end, days = days) else it }
            }
            Store.saveSchedules(this, updated)
            closeSheet()
            fillSchedules()
            refreshStatus()
        }, lp(matchP, wrapC).apply { topMargin = dp(18) })
        if (existing != null) {
            box.addView(pill("Delete schedule", false) {
                val before = Store.schedules(this)
                Store.saveSchedules(this, before.filter { it.id != existing.id })
                closeSheet()
                fillSchedules()
                refreshStatus()
                toast("Schedule deleted", existing.name, "Undo") {
                    Store.saveSchedules(this, before)
                    fillSchedules()
                    refreshStatus()
                }
            }, lp(matchP, wrapC).apply { topMargin = dp(10) })
        }
        showSheet(if (existing == null) "New schedule" else "Edit schedule", box)
    }

    // ---------- Blocks tab ----------
    private class AppRow(val pkg: String, val label: String, val icon: Bitmap?)

    private val allApps = ArrayList<AppRow>()
    private val shown = ArrayList<AppRow>()
    private var appQuery = ""
    private var appAdapter: BaseAdapter? = null
    private var loadingText: TextView? = null
    private var sitesBox: LinearLayout? = null
    private var appHint: TextView? = null

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

    private fun appIsOn(pkg: String) = if (appMode == 0) Store.isAppBlocked(this, pkg) else Store.isFocusApp(this, pkg)

    private fun applyFilter() {
        val q = appQuery.trim()
        val on = if (appMode == 0) Store.blockedApps(this) else Store.focusApps(this)
        val list = allApps.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }
            .sortedBy { if (it.pkg in on) 0 else 1 }
        shown.clear()
        shown.addAll(list)
        visibleKeys.clear()
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
                background = round(pal.surface, 16, pal.border, dp(1))
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
            toggle.listener = { on ->
                if (appMode == 0) Store.setAppBlocked(this@MainActivity, pkg, on)
                else Store.setFocusApp(this@MainActivity, pkg, on)
            }
            tile.setOnClickListener { toggle.performClick() }
            tile.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.975f).scaleY(0.975f).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(SpringInterpolator(18f, 0.5f, 0.8f)).start()
                }
                false
            }
            addView(tile, FrameLayout.LayoutParams(matchP, wrapC))
        }

        fun bind(a: AppRow) {
            pkg = a.pkg
            name.text = a.label
            icon.setImageBitmap(a.icon)
            toggle.setOn(appIsOn(a.pkg), false)
        }
    }

    private fun blocksPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), 0)
        }
        page.addView(display("Blocks", 30f))

        val shortsPane = scrollPage(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(28))
            val m = card()
            m.addRow(toggleRow("Always-on blocking", "Master switch for the rules below", Store.enabled(this@MainActivity)) {
                Store.setEnabled(this@MainActivity, it)
            })
            addView(m, lp(matchP, wrapC).apply { topMargin = dp(4) })
            for ((site, appName) in Store.APPS) {
                addView(groupLabel(appName))
                val c = card()
                val feedTitle = Store.SITES[site] ?: appName
                c.addRow(toggleRow(feedTitle, if (site == "tiktok") "Whole app" else "Auto-Back on the player",
                    Store.site(this@MainActivity, site)) { Store.setSite(this@MainActivity, site, it) })
                if (site != "tiktok") {
                    c.addRow(stepperRow(
                        { m2 -> if (m2 == 0) "Daily allowance: none" else "Daily allowance: $m2 min" },
                        Store.allowMinutes(this@MainActivity, site), 0, 120, 5
                    ) { Store.setAllowMinutes(this@MainActivity, site, it) })
                }
                for (sec in Store.SECTIONS.filter { it.site == site }) {
                    c.addRow(toggleRow(sec.title, sec.sub, Store.site(this@MainActivity, sec.key)) {
                        Store.setSite(this@MainActivity, sec.key, it)
                    })
                }
                if (site != "tiktok") {
                    val wk = Store.wholeKey(site)
                    c.addRow(toggleRow("Close $appName entirely", "Blocked the moment you open it",
                        Store.site(this@MainActivity, wk)) { Store.setSite(this@MainActivity, wk, it) })
                }
                addView(c, lp(matchP, wrapC))
            }
            addView(note("Detection reads each app's screen. Apps change their layouts now and then, so a new section may need a tweak."))
        })

        val appsPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val webPane = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }

        val panes = listOf<View>(shortsPane, appsPane, webPane)
        val (seg, select) = segControl(listOf("Shorts", "Apps", "Websites"), 1) { i ->
            panes.forEachIndexed { j, v -> v.visibility = if (j == i) View.VISIBLE else View.GONE }
        }
        blocksSelect = select
        page.addView(seg, lp(matchP, wrapC).apply { topMargin = dp(18); bottomMargin = dp(12) })

        // apps
        val (modeSeg, modeSelect) = segControl(listOf("Always on", "Focus only"), appMode) { i ->
            appMode = i
            appHint?.text = if (i == 0) "Closed whenever you open them (after a hold, if Hold to open is on)."
            else "Closed only during focus sessions and schedules."
            applyFilter()
        }
        appModeSelect = modeSelect
        appsPane.addView(modeSeg, lp(matchP, wrapC))
        val hint = note(if (appMode == 0) "Closed whenever you open them (after a hold, if Hold to open is on)."
        else "Closed only during focus sessions and schedules.")
        appHint = hint
        appsPane.addView(hint)
        val search = input("Search apps")
        watch(search) { appQuery = it; applyFilter() }
        appsPane.addView(search, lp(matchP, wrapC).apply { topMargin = dp(10); bottomMargin = dp(12) })
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
            cacheColorHint = pal.canvas
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(56))
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = (convertView as? AppItem) ?: AppItem(this@MainActivity)
                val key = shown[position].pkg
                item.bind(shown[position])
                if (visibleKeys.add(key)) {
                    val now = android.os.SystemClock.uptimeMillis()
                    popIdx = if (now - lastPop < 90) popIdx + 1 else 0
                    lastPop = now
                    popIn(item, minOf(popIdx, 8) * 55L)
                } else {
                    (item.getTag(R_POP) as? ValueAnimator)?.cancel()
                    item.alpha = 1f; item.scaleX = 1f; item.scaleY = 1f; item.translationY = 0f
                }
                return item
            }
        }
        appAdapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, state: Int) {}
            override fun onScroll(view: AbsListView?, first: Int, count: Int, total: Int) {
                val keep = HashSet<String>()
                for (i in first until minOf(first + count, shown.size)) keep.add(shown[i].pkg)
                visibleKeys.retainAll(keep)
            }
        })
        list.adapter = adapter
        appsPane.addView(list, lp(matchP, 0, 1f))

        // websites
        val webCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lateinit var urlInput: EditText
        fun addSite() {
            val d = Store.cleanDomain(urlInput.text.toString())
            if (d == null) {
                toast("Type a site like reddit.com")
                return
            }
            Store.addSite(this, d)
            urlInput.setText("")
            refreshSites()
        }
        val curved = CurvedBar(this, pal, fonts, "reddit.com", "Block") { addSite() }
        urlInput = curved.edit
        addRow.addView(curved, lp(matchP, wrapC))
        webCol.addView(addRow)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sitesBox = box
        webCol.addView(box, lp(matchP, wrapC).apply { topMargin = dp(14) })
        webCol.addView(note("Works inside Chrome, Brave, Edge, Firefox, Samsung Internet, Opera, Vivaldi and DuckDuckGo by reading the address bar."))
        webPane.addView(webCol, FrameLayout.LayoutParams(matchP, wrapC))
        refreshSites()

        page.addView(shortsPane, lp(matchP, 0, 1f))
        page.addView(appsPane, lp(matchP, 0, 1f))
        page.addView(webPane, lp(matchP, 0, 1f))
        shortsPane.visibility = View.GONE
        webPane.visibility = View.GONE
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
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), 0)
        }
        page.addView(display("Stats", 30f))

        val blocksCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(28)) }
        blockStatsCol = blocksCol
        val blocksScroll = scrollPage(blocksCol)
        val useCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(28)) }
        usageCol = useCol
        val useScroll = scrollPage(useCol)

        val (seg, _) = segControl(listOf("Blocks", "Screen time"), 0) { i ->
            blocksScroll.visibility = if (i == 0) View.VISIBLE else View.GONE
            useScroll.visibility = if (i == 1) View.VISIBLE else View.GONE
            useCol.visibility = if (i == 1) View.VISIBLE else View.GONE
            if (i == 1) fillUsage() else { fillStats(); charts.forEach { it.play() } }
        }
        page.addView(seg, lp(matchP, wrapC).apply { topMargin = dp(18); bottomMargin = dp(6) })
        page.addView(blocksScroll, lp(matchP, 0, 1f))
        page.addView(useScroll, lp(matchP, 0, 1f))
        useScroll.visibility = View.GONE
        useCol.visibility = View.GONE
        fillStats()
        return page
    }

    private fun statTile(n: Int, l: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(OdometerView(this@MainActivity, pal.ink, fonts.bold, 28f).apply { setValue(n) })
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
        if (today >= 5 && today > before * 2) out.append(" Heavier than usual today. A focus session can help.")
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
        val col = blockStatsCol ?: return
        col.removeAllViews()
        charts = ArrayList()

        val week = (0..6).map { Store.blockedOn(this, it) }.sum()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val big = card().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); gravity = Gravity.BOTTOM }
        big.addView(OdometerView(this, pal.ink, fonts.bold, 56f).apply { setValue(Store.today(this@MainActivity)) })
        big.addView(tv("Blocked today", 13f, fonts.regular, pal.muted).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(big, LinearLayout.LayoutParams(0, matchP, 1.15f))
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(week to "This week", Store.total(this) to "All time").forEachIndexed { i, p ->
            val tile = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
            tile.addView(statTile(p.first, p.second))
            side.addView(tile, lp(matchP, wrapC).apply { if (i > 0) topMargin = dp(8) })
        }
        row.addView(side, lp(0, wrapC, 1f).apply { marginStart = dp(8) })
        col.addView(row, lp(matchP, wrapC).apply { topMargin = dp(12) })

        col.addView(sectionTitle("Distractions blocked"))
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
    }

    // ----- screen time (needs Usage access) -----
    private class UseRow(val label: String, val icon: Bitmap?, val minutes: Int, val distracting: Boolean)

    private fun fillUsage() {
        val col = usageCol ?: return
        col.removeAllViews()
        if (!usageGranted()) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = round(pal.surface, 24, pal.border, dp(1))
                setPadding(dp(22), dp(22), dp(22), dp(22))
            }
            c.addView(display("See where your time goes", 22f))
            c.addView(tv("Allow Usage access to see how long you spend in each app. It stays on your phone.",
                14f, fonts.regular, pal.muted).apply { setPadding(0, dp(8), 0, dp(16)) })
            c.addView(pill("Allow usage access", true) { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }, lp(wrapC, wrapC))
            col.addView(c, lp(matchP, wrapC).apply { topMargin = dp(12) })
            return
        }
        col.addView(tv("Loading…", 14f, fonts.regular, pal.muted, Gravity.CENTER).apply { setPadding(0, dp(30), 0, 0) },
            lp(matchP, wrapC))
        Thread {
            val rows = ArrayList<UseRow>()
            val week = IntArray(7)
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val pm = packageManager
                val launchable = HashMap<String, Boolean>()
                fun isApp(pkg: String): Boolean = launchable.getOrPut(pkg) {
                    pkg != packageName && try { pm.getLaunchIntentForPackage(pkg) != null } catch (e: Exception) { false }
                }
                fun dayStart(ago: Int): Long = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -ago)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val bad = HashSet<String>()
                bad.addAll(Store.blockedApps(this)); bad.addAll(Store.focusApps(this))
                for (i in 0..6) {
                    val ago = 6 - i
                    val s = dayStart(ago)
                    val e = if (ago == 0) System.currentTimeMillis() else dayStart(ago - 1)
                    val map = usm.queryAndAggregateUsageStats(s, e)
                    var sum = 0L
                    for ((pkg, st) in map) {
                        if (st.totalTimeInForeground <= 0L || !isApp(pkg)) continue
                        sum += st.totalTimeInForeground
                        if (ago == 0) {
                            val mins = (st.totalTimeInForeground / 60000L).toInt()
                            if (mins >= 1) {
                                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e2: Exception) { pkg }
                                val icon = try { iconBitmap(pm.getApplicationIcon(pkg)) } catch (e2: Exception) { null }
                                rows.add(UseRow(label, icon, mins, pkg in bad || Store.isKnownApp(pkg)))
                            }
                        }
                    }
                    week[i] = (sum / 60000L).toInt()
                }
                rows.sortByDescending { it.minutes }
            } catch (e: Exception) { }
            runOnUiThread { renderUsage(rows.take(40), week) }
        }.start()
    }

    private fun renderUsage(rows: List<UseRow>, week: IntArray) {
        val col = usageCol ?: return
        col.removeAllViews()
        val total = week.last()
        col.addView(display(fmtMin(total), 40f).apply { setPadding(0, dp(18), 0, 0) })
        col.addView(tv("Screen time today", 14f, fonts.regular, pal.muted).apply { setPadding(0, dp(4), 0, dp(12)) })
        val cc = card().apply { setPadding(dp(20), dp(20), dp(20), dp(18)) }
        val labels = (6 downTo 0).map { Store.dayLetter(it) }
        val chart = BarChart(this, week.toList(), labels, 6)
        charts.add(chart)
        cc.addView(chart, lp(matchP, wrapC))
        col.addView(cc, lp(matchP, wrapC))
        chart.play()

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fillList() {
            listBox.removeAllViews()
            val list = rows.filter { usageFilter == 0 || (usageFilter == 1 && it.distracting) || (usageFilter == 2 && !it.distracting) }
            if (list.isEmpty()) {
                listBox.addView(tv("Nothing here yet.", 14f, fonts.regular, pal.muted, Gravity.CENTER).apply { setPadding(0, dp(20), 0, 0) })
            }
            for (r in list) {
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = round(pal.surface, 24, pal.border, dp(1))
                    setPadding(dp(16), dp(12), dp(18), dp(12))
                }
                val icon = ImageView(this).apply {
                    setImageBitmap(r.icon)
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                        }
                    }
                    clipToOutline = true
                }
                tile.addView(icon, lp(dp(44), dp(44)))
                val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                names.addView(tv(r.label, 16f, fonts.semi, pal.ink))
                names.addView(tv(if (r.distracting) "Distracting" else "Others", 12f, fonts.regular, pal.muted).apply { setPadding(0, dp(2), 0, 0) })
                tile.addView(names, lp(0, wrapC, 1f).apply { marginStart = dp(14) })
                tile.addView(tv(fmtMin(r.minutes), 15f, fonts.semi, pal.ink))
                listBox.addView(tile, lp(matchP, wrapC).apply { bottomMargin = dp(8) })
            }
        }
        val (fseg, _) = segControl(listOf("All apps", "Distracting", "Others"), usageFilter) { i -> usageFilter = i; fillList() }
        col.addView(fseg, lp(matchP, wrapC).apply { topMargin = dp(16); bottomMargin = dp(12) })
        col.addView(listBox, lp(matchP, wrapC))
        fillList()
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

    private fun statusText(ok: Boolean) = if (ok) "On" else "Off"

    private fun fillSettings() {
        val col = settingsCol ?: return
        val keepY = settingsScroll?.scrollY ?: 0
        col.removeAllViews()

        col.addView(display("Settings", 30f))

        // Ultra Focus
        col.addView(sectionTitle("Focus"))
        val focus = card()
        focus.addRow(linkRow("Ultra Focus settings", "Strict mode, study mode, uninstall guard, notifications, home screen") { showUltraSheet() })
        focus.addRow(linkRow("Daily focus goal", fmtMin(Store.goalMin(this))) { showGoalSheet() })
        col.addView(focus, lp(matchP, wrapC))

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
        holdHolder.addView(divider(), lp(matchP, dp(1)))
        holdHolder.addView(HoldPill(this, pal, fonts, { Store.holdSeconds(this) }) {
            toast("That is the hold", "This is what the wall asks for.")
        }, lp(matchP, wrapC).apply { setMargins(dp(16), dp(14), dp(16), dp(14)) })
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
        routine.addRow(toggleRow("Only run always-on blocking on a schedule", "Off: blocking runs all day", Store.scheduleOn(this)) {
            Store.setScheduleOn(this, it)
            schedHolder.visibility = if (it) View.VISIBLE else View.GONE
        })
        routine.addView(schedHolder, lp(matchP, wrapC))
        col.addView(routine, lp(matchP, wrapC))

        // Snooze
        col.addView(sectionTitle("Need a break?"))
        val snooze = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5 to "5 min", 15 to "15 min", 60 to "1 hour").forEachIndexed { i, (mins, name) ->
            snooze.addView(pill(name, false) {
                if (free()) {
                    Store.setSnooze(this, System.currentTimeMillis() + mins * 60_000L)
                    toast("Always-on blocking paused for $name")
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
            ui.post { buildAll() }
        })

        // Permissions and help
        col.addView(sectionTitle("Permissions and help"))
        val fix = card()
        fix.addRow(linkRow("Accessibility service", statusText(serviceOn())) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        fix.addRow(linkRow("Notification access", statusText(notifAccess())) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        fix.addRow(linkRow("Usage access", statusText(usageGranted())) { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        fix.addRow(linkRow("App info and restricted settings", "Allow restricted settings from the ⋮ menu") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        fix.addRow(linkRow("Battery optimisation", "Stop the phone from closing Shortstop") {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        fix.addRow(linkRow("Xiaomi / Redmi autostart", "Let Shortstop start itself and run in the background") {
            try {
                startActivity(Intent().setComponent(ComponentName(
                    "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            } catch (e: Exception) {
                toast("Open Security > Permissions > Autostart and switch Shortstop on.")
            }
        })
        col.addView(fix, lp(matchP, wrapC))
        col.addView(note("Shortstop runs fully offline. Nothing leaves your phone."))

        settingsScroll?.post { settingsScroll?.scrollTo(0, keepY) }
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
