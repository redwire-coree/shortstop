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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
 * Shortstop. Warm paper-and-citrus visual system, Inter type, five tabs:
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
    private var healthCard: LinearLayout? = null
    private var schedIntro: View? = null
    private var schedScroll: View? = null
    private var schedEmptyHost: FrameLayout? = null
    private var schedPageTitle: View? = null
    private var schedHeadCard: View? = null
    private var runExtras: View? = null
    private var calmOrange: OrangeView? = null
    private var companion: FrameLayout? = null
    private var companionOrange: OrangeView? = null
    private var companionSub: TextView? = null
    private var companionMessageToken = 0
    private val sleepRun = Runnable {
        if (tab == 0 && tourView == null && !Store.focusRunning(this)) companionOrange?.play(Move.SLEEP)
    }
    private var calmBreak: Boolean? = null
    private var sitesCountView: OdometerView? = null
    private var suggestBox: ViewGroup? = null
    private var previewBox: LinearLayout? = null
    private var popLabel: View? = null
    private var lastSiteCount = -1
    private var lastAddedSite: String? = null
    private var schedTitle: TextView? = null
    private var schedSub: TextView? = null
    private var schedDot: LiveDot? = null
    private var schedTimeline: WeekTimeline? = null
    private var lastSchedActive: String? = null
    private var exToday: TextView? = null
    private var exStreak: TextView? = null
    private var exBlocked: TextView? = null
    private var exApps: TextView? = null
    private var exHome: TextView? = null
    private var exMode: TextView? = null
    private var setupStepper: SetupStepper? = null
    private var runningCard: View? = null
    private var focusScroll: ScrollView? = null
    private var runRing: TimerDial? = null
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
    private var sheetScrim: SheetBackdrop? = null
    private var sheetDim = 0f
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
        toast("Ultra Focus: ${Store.focusLeftMin(this)} min left")
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
        installCrashLog()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fonts = Fonts(this)
        buildAll()
        loadApps()
        showLastCrash()
        if (!Store.prefs(this).getBoolean("tour_done", false)) window.decorView.postDelayed({ showTour() }, 1400L)
        else window.decorView.postDelayed({
            val p = Store.prefs(this)
            if (!Store.focusRunning(this) && tourView == null && System.currentTimeMillis() - p.getLong("greet_at", 0L) > 6 * 3600_000L) {
                p.edit().putLong("greet_at", System.currentTimeMillis()).apply()
                val c = companionOrange
                if (c != null && tab == 0) { companionMessage("Hi, welcome back!", 2400L); c.play(Move.WAVE, 1f, true) }
                else orangeSay(Move.ENTER, "Hi, welcome back!", false, 1700L)
            }
        }, 1500L)
    }

    // ---------- crash notes: the reason is saved, then shown once on the next start so it can be fixed ----------
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sb = StringBuilder("v" + try { packageManager.getPackageInfo(packageName, 0).versionName } catch (x: Exception) { "?" })
                sb.append("\n").append(e.toString()).append("\n")
                e.stackTrace.take(14).forEach { sb.append("  at ").append(it).append("\n") }
                var c = e.cause
                var depth = 0
                while (c != null && depth < 2) {
                    sb.append("Caused by: ").append(c.toString()).append("\n")
                    c.stackTrace.take(8).forEach { sb.append("  at ").append(it).append("\n") }
                    c = c.cause; depth++
                }
                java.io.File(filesDir, "last_crash.txt").writeText(sb.toString())
            } catch (x: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    // ---- Orange, the mascot
    private var nudge: OrangeView? = null
    private var lastNudge = 0L
    private var tourView: View? = null

    /** A short guest appearance: he drops in, reacts, says one line and leaves. Never blocks touches. */
    private fun orangeSay(move: Move, line: String, top: Boolean, ms: Long, y: Int = 64) {
        val root = window.decorView as ViewGroup
        val v = OrangeView(this, fonts, pal.dark, 84f)
        v.interactive = false
        root.addView(v, FrameLayout.LayoutParams(wrapC, wrapC, (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL).apply {
            if (top) topMargin = dp(y) else bottomMargin = dp(110)
        })
        v.postDelayed({ v.say(line); if (move != Move.ENTER) v.play(move, 1f, true) }, if (move == Move.ENTER) 500L else 900L)
        v.postDelayed({ v.say(null); v.play(Move.EXIT, force = true) }, ms + 900L)
        v.postDelayed({ if (v.parent === root) root.removeView(v) }, ms + 1450L)
    }

    private fun scheduleSleep() {
        val c = companionOrange ?: return
        c.removeCallbacks(sleepRun)
        c.postDelayed(sleepRun, 75_000L)
    }

    /** Keep companion dialogue in the measured text column, never inside the mascot canvas. */
    private fun companionMessage(line: String, durationMs: Long = 3200L) {
        val sub = companionSub ?: return
        companionMessageToken += 1
        val token = companionMessageToken
        sub.text = line
        sub.maxLines = 2
        sub.ellipsize = android.text.TextUtils.TruncateAt.END
        sub.animate().cancel()
        sub.alpha = 0f
        sub.translationY = dp(4).toFloat()
        sub.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        sub.postDelayed({
            if (token == companionMessageToken && sub.isAttachedToWindow) refreshCompanion()
        }, durationMs)
    }

    private fun refreshCompanion() {
        val comp = companion ?: return
        val running = Store.focusRunning(this)
        comp.visibility = if (running || tourView != null) View.GONE else View.VISIBLE
        if (running) return
        val today = Store.focusMinutesAgo(this, 0)
        val goal = Store.goalMin(this)
        val goalMet = goal > 0 && today >= goal
        val line = if (goalMet) "Daily goal reached. Nicely done."
            else if (today > 0) "${fmtMin(today)} of focus today."
            else "Ready when you are."
        companionSub?.let { if (it.text.toString() != line) it.text = line }
        val p = Store.prefs(this)
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        if (goalMet && tab == 0 && !deadPlaying && p.getString("proud_day", "") != day) {
            p.edit().putString("proud_day", day).apply()
            companionMessage("Daily goal reached. Nicely done.")
            companionOrange?.play(Move.PROUD, 1f, true)
            companionOrange?.postDelayed({ if (companionOrange?.isAttachedToWindow == true) refreshCompanion() }, 2600L)
        }
    }

    private val nudgeRun = Runnable { maybeNudge() }

    private fun maybeNudge() {
        if (companion != null || nudge != null || tab != 0 || tourView != null || sheetLayer != null || Store.focusRunning(this)) return
        val now = System.currentTimeMillis()
        if (now - lastNudge < 90_000L) return
        lastNudge = now
        val v = OrangeView(this, fonts, pal.dark, 84f)
        rootFrame.addView(v, FrameLayout.LayoutParams(wrapC, wrapC, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(150); leftMargin = dp(6)
        })
        nudge = v
        v.contentDescription = "Orange. Tap to start a focus session."
        v.say("Ready for a focused session?", 0L, LineCat.IDLE)
        v.play(Move.ENCOURAGE, force = true)
        v.setOnClickListener { dismissNudge(); showStartSheet() }
        v.postDelayed({ if (nudge === v) dismissNudge() }, 9000L)
    }

    private fun dismissNudge() {
        val v = nudge ?: return
        nudge = null
        v.say(null)
        v.play(Move.EXIT)
        v.postDelayed({ (v.parent as? ViewGroup)?.removeView(v) }, 500L)
    }

    private fun showTour() {
        val root = window.decorView as ViewGroup
        tourView?.let { root.removeView(it) }
        dismissNudge()
        companion?.visibility = View.GONE
        val box = FrameLayout(this)
        val dim = TourDim(this)
        box.addView(dim, FrameLayout.LayoutParams(matchP, matchP))
        val guide = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val orange = OrangeView(this, fonts, pal.dark, 104f)
        guide.addView(orange, lp(wrapC, wrapC))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val skip = tv("Skip", 14f, fonts.medium, Color.WHITE).apply { setPadding(dp(18), dp(12), dp(18), dp(12)); isClickable = true; alpha = 0.85f }
        var next: TextView? = null
        btns.addView(skip, lp(wrapC, wrapC))
        guide.addView(btns, lp(wrapC, wrapC).apply { topMargin = dp(4) })
        box.addView(guide, FrameLayout.LayoutParams(wrapC, wrapC, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (resources.displayMetrics.heightPixels * 0.17f).toInt()
        })
        box.isClickable = true
        box.alpha = 0f
        root.addView(box, FrameLayout.LayoutParams(matchP, matchP))
        box.animate().alpha(1f).setDuration(260).start()
        tourView = box

        // text, tab to show, what to light up (-1 nothing, 0 the start bar, 1..4 nav slots)
        val steps = listOf(
            Triple("Hi! I'm Orange. Quick tour?", -1, -1),
            Triple("Tap here to start a focus timer.", 0, 0),
            Triple("Plan study blocks that start on their own.", 1, 2),
            Triple("Pick the apps and sites to block.", 2, 3),
            Triple("Watch your streak and win cards.", 3, 4),
            Triple("Tweak anything here. Replay me anytime!", 4, 5)
        )
        var idx = 0
        fun finish(done: Boolean) {
            Store.prefs(this).edit().putBoolean("tour_done", true).apply()
            tourView = null
            refreshCompanion()
            box.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(box) }.start()
            if (tab != 0) selectTab(0)
            if (done) orangeSay(Move.CELEBRATE, "You're all set!", true, 1900L)
        }
        fun showStep(i: Int) {
            val (line, tabTo, spot) = steps[i]
            if (tabTo >= 0) selectTab(tabTo)
            next?.text = if (i == 0) "Let's go" else if (i == steps.lastIndex) "Done" else "Next"
            box.postDelayed({
                var r: RectF? = null
                val loc = IntArray(2)
                if (spot == 0) {
                    val sb = startBar
                    if (sb != null && sb.visibility == View.VISIBLE && sb.width > 0) {
                        sb.getLocationInWindow(loc)
                        r = RectF(loc[0] + dp(14).toFloat(), loc[1] + dp(4).toFloat(), loc[0] + sb.width - dp(14).toFloat(), loc[1] + sb.height - dp(8).toFloat())
                    }
                } else if (spot >= 2) {
                    val g = gooey
                    if (g != null && g.width > 0) {
                        g.getLocationInWindow(loc)
                        val w5 = g.width / 5f
                        r = RectF(loc[0] + w5 * (spot - 2) + dp(2), loc[1].toFloat(), loc[0] + w5 * (spot - 1) - dp(2), loc[1] + g.height.toFloat())
                    }
                }
                dim.setTarget(r)
                orange.say(line)
                if (r == null) orange.play(if (i == 0) Move.WAVE else Move.HOP)
                else orange.play(Move.POINT, if (r.centerX() < resources.displayMetrics.widthPixels / 2f) -1f else 1f)
            }, 140L)
        }
        val nb = pill("Next", true) {
            if (idx >= steps.lastIndex) finish(true) else { idx++; showStep(idx) }
        }
        next = nb
        btns.addView(nb, 0, lp(wrapC, wrapC))
        skip.setOnClickListener { finish(false) }
        orange.play(Move.ENTER)
        box.postDelayed({ showStep(0) }, 500L)
    }

    private fun showLastCrash() {
        val f = java.io.File(filesDir, "last_crash.txt")
        val text = try { if (f.exists()) f.readText() else null } catch (e: Exception) { null } ?: return
        try { f.delete() } catch (e: Exception) { }
        window.decorView.postDelayed({
            try {
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                box.addView(tv("Shortstop closed unexpectedly last time. Copy this and send it so it can be fixed.", 13f, fonts.regular, pal.muted))
                box.addView(tv(text, 11f, fonts.regular, pal.ink).apply {
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(0, dp(12), 0, dp(12))
                })
                box.addView(pill("Copy details", true) {
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                    toast("Copied")
                }, lp(matchP, wrapC))
                showSheet("Crash details", box)
            } catch (e: Throwable) { }
        }, 700)
    }

    override fun onResume() {
        clearBlurEffects()
        super.onResume()
        Store.finalizeFocus(this)
        window.decorView.removeCallbacks(nudgeRun)
        window.decorView.postDelayed(nudgeRun, 6000L)
        scheduleSleep()
        refreshStatus()
        Cards.sync(this).let { got ->
            if (got.size == 1) toast("${Rarity.of(got[0]).name} card unlocked", "Find it in Stats.")
            else if (got.size > 1) toast("${got.size} cards unlocked", "Find them in Stats.")
        }
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
        window.decorView.removeCallbacks(nudgeRun)
        dismissNudge()
        // Do not let an idle timer change the mascot's pose while the Activity is backgrounded.
        companionOrange?.removeCallbacks(sleepRun)
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
        val action = Color.parseColor(if (pal.dark) "#E88942" else "#E97825")
        v.setTextColor(if (primary) Color.WHITE else pal.ink)
        v.background = round(if (primary) action else pal.surface, 40,
            if (primary) action else pal.border, if (primary) 0 else dp(1))
    }

    private fun card(): LinearLayout = GlowCard(
        this, Color.argb(if (pal.dark) 26 else 20, Color.red(pal.ink), Color.green(pal.ink), Color.blue(pal.ink)),
        dp(24).toFloat(), dp(170).toFloat()
    ).apply {
        orientation = LinearLayout.VERTICAL
        ambient = pal.dark
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
        /** Asked before a tap changes anything; returning false leaves the switch exactly as it was. */
        var veto: ((Boolean) -> Boolean)? = null
        var brand = false
        private var p = 0f
        private var brandW = 0f
        private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
                if (veto?.invoke(!on) == false) return@setOnClickListener
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
            val offKnob = if (pal.dark) Color.parseColor("#BBBBBB") else Color.WHITE
            rect.set(0f, 0f, w, h)
            if (brand) {
                trackPaint.color = pal.track
                knobPaint.color = eval.evaluate(p, offKnob, Color.WHITE) as Int
                c.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)
                if (brandW != w) {
                    brandW = w
                    brandPaint.shader = LinearGradient(0f, 0f, w, 0f, Color.parseColor("#FFB27A"), Color.parseColor("#FF6A2B"), Shader.TileMode.CLAMP)
                }
                brandPaint.alpha = (255 * p).toInt()
                c.drawRoundRect(rect, h / 2f, h / 2f, brandPaint)
            } else {
                trackPaint.color = eval.evaluate(p, pal.track, pal.ink) as Int
                knobPaint.color = eval.evaluate(p, offKnob, pal.canvas) as Int
                c.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)
            }
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
                if (values[i] <= 0) {
                    // An empty day is a quiet round pip on the baseline, not a bar: no heavy black dash, even for today.
                    val d = minOf(bw, dp(10).toFloat())
                    val cx = left + bw / 2f
                    bar.color = pal.track
                    c.drawRoundRect(cx - d / 2f, baseY - d, cx + d / 2f, baseY, d / 2f, d / 2f, bar)
                    if (i == highlight) {
                        bar.color = pal.ink; bar.alpha = 110
                        c.drawCircle(cx, baseY - d / 2f, dp(2).toFloat(), bar); bar.alpha = 255
                    }
                    txt.color = pal.muted
                    c.drawText(labels[i], left + bw / 2f, height - dp(7).toFloat(), txt)
                    continue
                }
                val h = maxOf(dp(6).toFloat(), values[i].toFloat() / top * usable * grow)
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
    private fun toggleRow(title: String, sub: String, checked: Boolean, veto: ((Boolean) -> Boolean)? = null, onChange: (Boolean) -> Unit): View {
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
        t.veto = veto
        t.listener = onChange
        row.addView(t)
        row.setOnClickListener { t.performClick() }
        return row
    }

    /** Centred − / + button (drawn, so the symbol is always dead-centre). */
    private fun stepBtn(plus: Boolean, onClick: () -> Unit): View =
        GlyphBtn(this, plus, pal.canvas, pal.ink, pal.border, false).apply {
            setOnClickListener { onClick() }
            press(this)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(38))
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
        row.addView(stepBtn(false) { change(-step) })
        row.addView(stepBtn(true) { change(step) }, lp(dp(46), dp(38)).apply { marginStart = dp(8) })
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
        statusNow = pal.canvas
        window.navigationBarColor = MonoPalette(this).canvas
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
        val mono = MonoPalette(this)
        rootView.addView(View(this).apply { setBackgroundColor(mono.border) }, lp(matchP, dp(1)))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setBackgroundColor(mono.canvas)
            clipChildren = false
            clipToPadding = false
        }
        rootView.clipChildren = false
        val g = GooeyNav(this, mono, fonts, listOf("Focus", "Schedule", "Blocks", "Stats", "Settings"), tab) { i -> selectTab(i) }
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

    private var statusAnim: ValueAnimator? = null
    private var statusNow = 0

    /** The status bar takes the paper colour while the empty Schedule shows the veil, so they read as one surface. */
    private fun updateStatusBar(forceCanvas: Boolean = false) {
        val paperOn = false
        val to = when {
            paperOn -> pal.canvas
            !forceCanvas && (tab == 0 || tab == 1) -> FocusTheme.bg(pal.dark)
            else -> pal.canvas
        }.let { base ->
            if (sheetDim > 0f) android.animation.ArgbEvaluator().evaluate(sheetDim, base, 0xFF140A04.toInt()) as Int else base
        }
        if (statusNow == 0) statusNow = window.statusBarColor
        if (to == statusNow) return
        statusAnim?.cancel()
        val from = statusNow
        statusAnim = ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, to).apply {
            duration = 320
            addUpdateListener { statusNow = it.animatedValue as Int; window.statusBarColor = statusNow }
            start()
        }
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
        when (i) {
            0 -> { fillFocusStats(); refreshStatus() }
            1 -> fillSchedules()
            3 -> { fillStats(); charts.forEach { it.play() }; if (usageCol?.visibility == View.VISIBLE) fillUsage() }
        }
        val dir = if (i > tabBefore) 1f else -1f
        nw.alpha = 0f
        nw.translationX = dir * dp(36)
        // no hardware layer here: the rows inside are animating too (stagger), and a layer would be re-rendered every frame
        nw.animate().alpha(1f).translationX(0f).setDuration(420).setInterpolator(SpringInterpolator(15f, 0.82f, 0.9f)).start()
        stagger(nw)
        styleNav()
        updateStatusBar()
        window.decorView.removeCallbacks(nudgeRun)
        if (i == 0) window.decorView.postDelayed(nudgeRun, 6000L) else dismissNudge()
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
        a.duration = 700
        a.interpolator = SpringInterpolator(15f, 0.8f, 0.9f)
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
        // blur what is behind the sheet (the real views, not the toast or sparkle layers), then dim it only gently
        clearBlurEffects()
        val blurTargets = (0 until rootFrame.childCount).map { rootFrame.getChildAt(it) }
            .filter { v -> v.tag != "closing-sheet" && v.tag != "toast" && v.tag != "confetti" }
            .filter { v -> (v as Any) !== (toaster as Any?) && (v as Any) !== (spark as Any?) && (v as Any) !== (sheetLayer as Any?) }
        val scrim = SheetBackdrop(this, blurTargets, 0x59140A04, rootFrame).apply {
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
                    scrim.level = (1f - dy / maxOf(1, panel.height)).coerceIn(0f, 1f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.rawY - downY > dp(110)) closeSheet()
                    else {
                        panel.animate().translationY(0f).setDuration(420).setInterpolator(SpringInterpolator(16f, 0.6f, 0.8f)).start()
                        fadeBackdrop(scrim, 1f, 260L)
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
        fadeBackdrop(scrim, 1f, 320L)
        setSheetDim(0.28f)
        panel.animate().translationY(0f).setDuration(520).setInterpolator(SpringInterpolator(14f, 0.8f, 0.9f)).start()
        sheetLayer = layer
        sheetPanel = panel
        sheetScrim = scrim
        sheetClose = onClose
    }

    /** No leftover blur on any screen view when no sheet owns it (a safety net for every close path). */
    private fun clearBlurEffects() {
        if (android.os.Build.VERSION.SDK_INT >= 31 && sheetScrim == null)
            for (i in 0 until rootFrame.childCount) rootFrame.getChildAt(i).setRenderEffect(null)
    }

    private fun fadeBackdrop(b: SheetBackdrop, to: Float, ms: Long) {
        (b.tag as? ValueAnimator)?.cancel()
        b.tag = ValueAnimator.ofFloat(b.level, to).apply {
            duration = ms
            interpolator = android.view.animation.DecelerateInterpolator(1.4f)
            addUpdateListener { b.level = it.animatedValue as Float }
            start()
        }
    }

    /** The status bar follows the dim of an open sheet so it never looks lighter than the screen under it. */
    private fun setSheetDim(v: Float) {
        sheetDim = v
        updateStatusBar()
    }

    private fun closeSheet(animate: Boolean = true) {
        val layer = sheetLayer ?: return
        companionOrange?.let { if (!Store.focusRunning(this) && !deadPlaying) { it.play(Move.IDLE, 1f, true); scheduleSleep() } }
        val panel = sheetPanel
        val scrim = sheetScrim
        val cb = sheetClose
        sheetLayer = null
        sheetPanel = null
        sheetScrim = null
        sheetClose = null
        setSheetDim(0f)
        layer.tag = "closing-sheet"
        if (!animate || panel == null || scrim == null) {
            scrim?.release()
            rootFrame.removeView(layer)
            return
        }
        fadeBackdrop(scrim, 0f, 240L)
        panel.animate().translationY(dp(700).toFloat()).setDuration(240).withEndAction {
            // if another sheet already opened, the blur now belongs to it: only remove our own layer
            scrim.release(sheetScrim == null)
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

    private fun refreshHealth() {
        val hc = healthCard ?: return
        val on = serviceOn()
        val batt = batteryOk()
        hc.removeAllViews()
        if (on && batt && ShortsBlockerService.alive) { hc.visibility = View.GONE; return }
        hc.visibility = View.VISIBLE
        hc.addView(tv("Blocking needs attention", 16f, fonts.semi, pal.ink).apply { setPadding(0, dp(14), 0, dp(2)) })
        val why = when {
            !on -> "Shortstop's accessibility service is off, so nothing can be blocked."
            !ShortsBlockerService.alive -> "The service is switched on but not running. Toggle it off and on again in Accessibility."
            else -> "Battery saving can stop blocking in the background. Allow Shortstop to run freely."
        }
        hc.addView(tv(why, 13f, fonts.regular, pal.muted).apply { setPadding(0, 0, 0, dp(10)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(14)) }
        if (!on || !ShortsBlockerService.alive) {
            row.addView(pill("Open Accessibility", true) {
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { }
            })
        }
        if (!batt) {
            row.addView(pill("Allow background", on) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
                }
            }, lp(wrapC, wrapC).apply { if (row.childCount > 0) marginStart = dp(8) })
        }
        hc.addView(row)
        if (android.os.Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") })
            hc.addView(tv("Xiaomi: also turn on Autostart for Shortstop and set Battery saver to No restrictions.", 12f, fonts.regular, pal.muted).apply { setPadding(0, 0, 0, dp(14)) })
    }

    private fun refreshStatus() {
        refreshHealth()
        val on = serviceOn()
        val batt = batteryOk()
        val ack = Store.prefs(this).getBoolean("setup_ack", false)
        setupStepper?.sync(booleanArrayOf(ack || on, on, batt))
        val sc = setupCard
        if (sc != null) {
            if (Store.focusRunning(this)) {
                // While a session runs the timer owns the top of the page; incomplete setup waits until the session ends.
                if (sc.visibility != View.GONE) sc.visibility = View.GONE
            } else if (on && batt) {
                // the stepper plays its "all set" moment and collapses the card itself
                if (sc.visibility != View.VISIBLE || !sc.isLaidOut) sc.visibility = View.GONE
            } else if (sc.visibility != View.VISIBLE) {
                sc.visibility = View.VISIBLE
                sc.alpha = 1f
                sc.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        if (setupChecks.size == 3) {
            setupChecks[0].setChecked(on, true)
            setupChecks[1].setChecked(on, true)
            setupChecks[2].setChecked(batt, true)
        }
        val aid = Store.scheduleActive(this)?.id
        if (aid != lastSchedActive) {
            lastSchedActive = aid
            if (schedCol != null) fillSchedules()
        } else updateSchedHeader()
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
        // Focus heading only: Bitsand, loaded from assets here and nowhere else (falls back to Inter Bold if missing)
        val bitsand = try { Typeface.createFromAsset(assets, "fonts/Bitsand-DEMO.ttf") } catch (e: Exception) { fonts.bold }
        top.addView(display("Focus", 36f).apply {
            setTextColor(FocusTheme.title(pal.dark))
            typeface = bitsand
            letterSpacing = 0f
        }, lp(0, wrapC, 1f))
        val chip = CallChip(this, pal, fonts)
        statusChip = chip
        top.addView(chip)
        col.addView(top)

        // First-run setup: a stepper that watches the real state and moves on by itself
        val setup = card()
        val stepper = SetupStepper(
            this, pal, fonts,
            listOf(
                SetupStepper.Step(
                    "Allow", "Allow restricted settings",
                    "Android hides accessibility for apps installed outside the Play Store. Open App info, tap the ⋮ menu at the top right, then choose Allow restricted settings.",
                    "Open app info",
                    { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    true
                ),
                SetupStepper.Step(
                    "Turn on", "Turn on Shortstop",
                    "Open Accessibility, find Shortstop and switch it on. This is how Shortstop sees a Short or Reel and closes it.",
                    "Open Accessibility",
                    { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    false
                ),
                SetupStepper.Step(
                    "Keep alive", "Keep it running",
                    "Set battery to No restrictions so Android never stops Shortstop in the background. On Xiaomi, also allow Autostart.",
                    "Open battery settings",
                    { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    false
                )
            ),
            { Store.prefs(this).edit().putBoolean("setup_ack", true).apply() },
            { setupCard?.let { collapseAway(it) } }
        )
        setupStepper = stepper
        setup.addView(stepper, lp(matchP, wrapC))
        setupCard = setup
        col.addView(setup, lp(matchP, wrapC).apply { topMargin = dp(18) })

        // Orange lives here on the idle Focus screen: breathing, blinking, glancing, reacting to taps, dozing off when left alone
        val comp = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val co = OrangeView(this, fonts, pal.dark, 64f).apply { contentDescription = "Orange, your focus companion" }
        co.setOnClickListener { scheduleSleep() }
        // OrangeView reserves a wide canvas for its optional speech bubble. Its actual
        // character is drawn near the left edge; a negative host margin clipped half of him.
        // Keep the view inside the row and give the text a measured, non-overlapping lane.
        comp.addView(co, FrameLayout.LayoutParams(wrapC, wrapC, Gravity.START or Gravity.CENTER_VERTICAL))
        val ct = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ct.addView(tv("Orange", 15f, fonts.semi, FocusTheme.title(pal.dark)), lp(wrapC, wrapC))
        val cs = tv("", 13f, fonts.regular, FocusTheme.muted(pal.dark)).apply { setPadding(0, dp(2), 0, 0) }
        ct.addView(cs, lp(wrapC, wrapC))
        ct.setOnClickListener { if (!Store.focusRunning(this)) showStartSheet() }
        comp.addView(ct, FrameLayout.LayoutParams(matchP, wrapC, Gravity.CENTER_VERTICAL).apply { marginStart = dp(84) })
        companion = comp; companionOrange = co; companionSub = cs
        col.addView(comp, lp(matchP, dp(104)).apply { topMargin = dp(10) })

        // Running session
        val run = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(22), dp(18), dp(20))
        }
        val stat = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(FocusTheme.accentSoft(pal.dark), 40, FocusTheme.cardEdge(pal.dark), dp(1))
            setPadding(dp(12), dp(8), dp(14), dp(8))
            elevation = dp(1).toFloat()
        }
        val lat = LatticeLoader(this, FocusCardColors.ORANGE)
        runLattice = lat
        stat.addView(lat, lp(wrapC, wrapC).apply { marginEnd = dp(10) })
        val rl = tv("Focusing", 13f, fonts.semi, FocusTheme.title(pal.dark))
        runLabel = rl
        stat.addView(rl, lp(wrapC, wrapC))
        val re = tv("", 13f, fonts.medium, FocusTheme.muted(pal.dark))
        runElapsed = re
        stat.addView(re, lp(wrapC, wrapC).apply { marginStart = dp(8) })
        run.addView(stat, lp(wrapC, wrapC).apply { bottomMargin = dp(18) })
        val ring = TimerDial(this, pal, fonts)
        runRing = ring
        ring.emberArc = true
        run.addView(ring, lp(wrapC, wrapC))
        val info = tv("", 14f, fonts.medium, FocusTheme.muted(pal.dark), Gravity.CENTER).apply { setPadding(0, dp(16), 0, dp(14)) }
        runInfo = info
        run.addView(info, lp(matchP, wrapC))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val breakBtn = pill("Take a break", false) { onBreakClick() }
        val endBtn = FuseButton(this, pal, fonts, "End session", "Undo", "Ended", 4000L,
            { if (Store.frozen(this)) { free(); runEndBtn?.refuse(); onUltraEndAttempt(); false } else true }) {
            commitEndSession()
        }
        endBtn.onArm = { onEndArmed() }
        endBtn.onUndo = { onEndUndone() }
        runBreakBtn = breakBtn
        runEndBtn = endBtn
        breakBtn.setSingleLine(true)
        breakBtn.setPadding(dp(12), 0, dp(12), 0)
        breakBtn.setTextColor(if (pal.dark) 0xFFFFE6CC.toInt() else 0xFF8A431B.toInt())
        breakBtn.background = round(FocusTheme.accentSoft(pal.dark), 40, FocusTheme.cardEdge(pal.dark), dp(1))
        endBtn.tint(FocusCardColors.peach(pal.dark), FocusCardColors.peach(pal.dark),
            if (pal.dark) 0xFFFFD9B8.toInt() else 0xFF8A3E10.toInt(), FocusCardColors.ORANGE_B)
        btns.addView(breakBtn, lp(0, dp(50), 1f).apply { marginEnd = dp(8) })
        btns.addView(endBtn, lp(0, dp(50), 1f))
        run.addView(btns, lp(matchP, wrapC))
        val runWrap = FrameLayout(this).apply {
            background = round(FocusTheme.cardFill(pal.dark), 28, FocusTheme.cardEdge(pal.dark), dp(1))
            clipToOutline = true
            elevation = dp(2).toFloat()
            visibility = View.GONE
        }
        runWrap.addView(SoftClouds(this, pal.dark), FrameLayout.LayoutParams(matchP, matchP))
        runWrap.addView(run, FrameLayout.LayoutParams(matchP, wrapC))
        // one quiet Orange lives in the card's top-right corner for the whole session (not tappable)
        calmOrange = OrangeView(this, fonts, pal.dark, 46f).apply { interactive = true; bubbleBelow = true; wakeTo = Move.BREAK;
            onWoke = { postDelayed({ if (isAttachedToWindow && Store.focusRunning(this@MainActivity) && Store.onBreak(this@MainActivity)) play(Move.SLEEP, 1f, true) }, 9000L) }; isClickable = true; contentDescription = "Orange focus companion. Tap, pet, or hold for magic."; visibility = View.GONE }
        runWrap.addView(calmOrange, FrameLayout.LayoutParams(wrapC, wrapC, Gravity.TOP or Gravity.END).apply {
            // beside the status pill, well above the dial: the ring, aurora and speech box never reach the timer
            topMargin = -dp(5); marginEnd = dp(8)
        })
        // OrangeView keeps a wide canvas for speech bubbles; offset that canvas so the
        // mascot itself—not the empty bubble lane—sits in the card's top-right corner.
        calmOrange?.translationX = dp(62).toFloat()
        runningCard = runWrap
        col.addView(runWrap, lp(matchP, wrapC).apply { topMargin = dp(18) })

        // While a session runs: today's numbers, what is being blocked, and your own reminder
        val extras = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        fun tile(label: String): Pair<View, TextView> {
            val t = card().apply {
                setPadding(dp(5), dp(14), dp(5), dp(13)); gravity = Gravity.CENTER_HORIZONTAL
                background = round(FocusTheme.cardFill(pal.dark), 22, FocusTheme.cardEdge(pal.dark), dp(1))
                elevation = dp(1).toFloat()
            }
            val v = tv("", 20f, fonts.bold, FocusTheme.title(pal.dark), Gravity.CENTER).apply {
                letterSpacing = -0.025f; setSingleLine(); minHeight = dp(28); includeFontPadding = false
            }
            t.addView(v, lp(matchP, wrapC))
            t.addView(View(this).apply { background = round(FocusCardColors.ORANGE, 4) }, lp(dp(18), dp(3)).apply { topMargin = dp(7); gravity = Gravity.CENTER_HORIZONTAL })
            t.addView(tv(label, 11f, fonts.medium, FocusTheme.muted(pal.dark), Gravity.CENTER).apply {
                maxLines = 2; setPadding(0, dp(7), 0, 0)
            }, lp(matchP, wrapC))
            return t to v
        }
        val (t1, v1) = tile("Focus today"); val (t2, v2) = tile("Day streak"); val (t3, v3) = tile("Apps blocked")
        exToday = v1; exStreak = v2; exBlocked = v3
        tiles.addView(t1, lp(0, matchP, 1f).apply { marginEnd = dp(10) })
        tiles.addView(t2, lp(0, matchP, 1f).apply { marginEnd = dp(10) })
        tiles.addView(t3, lp(0, matchP, 1f))
        extras.addView(tiles, lp(matchP, wrapC).apply { topMargin = dp(14) })
        val blocking = card().apply {
            setPadding(dp(18), dp(16), dp(18), dp(8))
            background = round(FocusTheme.cardFill(pal.dark), 24, FocusTheme.cardEdge(pal.dark), dp(1))
            elevation = dp(1).toFloat()
        }
        val bh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(6)) }
        bh.addView(View(this).apply { background = round(FocusCardColors.ORANGE, 8) }, lp(dp(8), dp(8)).apply { marginEnd = dp(10) })
        bh.addView(tv("Blocking now", 15f, fonts.semi, FocusTheme.title(pal.dark)))
        blocking.addView(bh, lp(matchP, wrapC))
        fun kv(label: String): TextView {
            if (blocking.childCount > 1) blocking.addView(View(this).apply { setBackgroundColor(FocusTheme.cardEdge(pal.dark)) }, lp(matchP, dp(1)))
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(11), 0, dp(11)) }
            r.addView(tv(label, 13f, fonts.regular, FocusTheme.muted(pal.dark)), lp(0, wrapC, 1f))
            val v = tv("", 13f, fonts.semi, FocusTheme.title(pal.dark))
            r.addView(v, lp(wrapC, wrapC))
            blocking.addView(r, lp(matchP, wrapC))
            return v
        }
        exApps = kv("Focus apps"); exHome = kv("Home screen"); exMode = kv("Mode")
        extras.addView(blocking, lp(matchP, wrapC).apply { topMargin = dp(12) })
        val remind = Store.message(this)
        if (remind.isNotBlank()) {
            val rc = card().apply {
                setPadding(dp(20), dp(18), dp(20), dp(18))
                background = round(FocusTheme.cardFill(pal.dark), 24, FocusTheme.cardEdge(pal.dark), dp(1))
            }
            rc.addView(tv("YOUR REMINDER", 11f, fonts.semi, FocusTheme.muted(pal.dark)).apply { letterSpacing = 0.14f }, lp(matchP, wrapC))
            rc.addView(tv(remind, 16f, fonts.medium, FocusTheme.title(pal.dark)).apply { setPadding(0, dp(8), 0, 0) }, lp(matchP, wrapC))
            extras.addView(rc, lp(matchP, wrapC).apply { topMargin = dp(12) })
        }
        runExtras = extras
        col.addView(extras, lp(matchP, wrapC))

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

        val focusSv = scrollPage(col); focusScroll = focusSv
        page.addView(focusSv, lp(matchP, 0, 1f))

        // Start bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(if (pal.dark) Color.WHITE else Color.BLACK, 40)
            setPadding(dp(26), dp(12), dp(12), dp(12))
            isClickable = true
            setOnClickListener { showStartSheet() }
            press(this)
        }
        bar.addView(tv("Start focus timer", 16f, fonts.semi, if (pal.dark) Color.BLACK else Color.WHITE), lp(0, wrapC, 1f))
        bar.addView(tv("▶", 16f, fonts.bold, if (pal.dark) Color.WHITE else Color.BLACK, Gravity.CENTER).apply {
            background = round(if (pal.dark) Color.BLACK else Color.WHITE, 40)
        }, lp(dp(44), dp(44)))
        val barWrap = LinearLayout(this).apply { setPadding(dp(20), dp(10), dp(20), dp(14)) }
        barWrap.addView(bar, lp(matchP, wrapC))
        startBar = barWrap
        page.addView(barWrap, lp(matchP, wrapC))

        refreshStatus()
        fillFocusStats()
        val root = FrameLayout(this)
        root.addView(AmbientBackdrop(this, pal), FrameLayout.LayoutParams(matchP, matchP))
        root.addView(page, FrameLayout.LayoutParams(matchP, matchP))
        return root
    }

    private fun onBreakClick() {
        val wasOnBreak = Store.onBreak(this)
        val changed = if (wasOnBreak) {
            Store.endBreak(this)
            true
        } else {
            val started = Store.startBreak(this)
            if (started) toast("Break started. Take a moment to recharge.")
            started
        }
        if (!changed) {
            if (!wasOnBreak && Store.breaksLeft(this) <= 0) toast("No breaks remaining in this session.")
            return
        }

        // updateRunning owns the single source of truth for the visual state transition.
        // The same path is used when a break expires automatically.
        updateRunning()
    }

    private val breakSleepRun = Runnable {
        val o = calmOrange ?: return@Runnable
        if (o.isAttachedToWindow && Store.focusRunning(this) && Store.onBreak(this)) {
            o.play(Move.SLEEP, 1f, true)
        }
    }

    private fun playBreakCompanionTransition(onBreak: Boolean) {
        val orange = calmOrange ?: return
        if (endArmed) return   // never override the End-session plea
        // Cancelling a half-finished pop-in leaves the view part-transparent and shrunk: settle it fully instead.
        orange.animate().cancel(); orange.alpha = 1f; orange.scaleX = 1f; orange.scaleY = 1f
        // A newer transition always wins: pending callbacks from the old one are cancelled first.
        orange.removeCallbacks(breakSleepRun)
        if (onBreak) {
            // relax first, then drift off to sleep a few seconds later
            orange.play(Move.BREAK, 1f, true)
            orange.say("Phew. Break time.", 2200L, LineCat.BREAK)
            orange.postDelayed(breakSleepRun, 3200L)
        } else {
            // a full wake-up: eyes open, hand rubs the eye, yawn and stretch, shake it off, then attentive focus
            orange.say("Good morning!", 3000L, LineCat.PLAYFUL)
            orange.playThen(Move.WAKE, Move.FOCUS)
        }
    }

    /**
     * DEVELOPER ONLY. A private stage with its own Orange: pick an animation, replay it, reset to idle.
     * It never touches the focus timer, sessions, streaks or any saved preference.
     */
    private fun showAnimationShowcase() {
        val stage = OrangeView(this, fonts, pal.dark, 104f).apply { interactive = true }
        val name = tv("Idle", 16f, fonts.semi, FocusTheme.title(pal.dark), Gravity.CENTER).apply { setPadding(0, dp(4), 0, dp(8)) }
        val log = tv("", 10f, fonts.regular, FocusTheme.muted(pal.dark)).apply { setPadding(dp(4), dp(8), dp(4), dp(4)); typeface = Typeface.MONOSPACE }
        var last: (() -> Unit)? = null
        fun run(label: String, action: () -> Unit) {
            name.text = label
            last = { name.text = label; stage.resetToIdle(); stage.postDelayed({ action() }, 160L) }
            stage.resetToIdle()
            stage.postDelayed({ action() }, 160L)
        }
        fun mv(m: Move, then: Move? = null): () -> Unit = { if (then != null) stage.playThen(m, then) else stage.play(m, 1f, true) }
        val items: List<Pair<String, () -> Unit>> = listOf<Pair<String, () -> Unit>>(
            "Idle" to mv(Move.IDLE), "Blink" to { stage.blinkNow() }, "Tap" to mv(Move.TAP),
            "Pet" to { stage.petNow() }, "Hold 4.5 s" to { stage.previewHold() }, "Focus" to mv(Move.FOCUS),
            "Break" to mv(Move.BREAK), "Sleeping" to mv(Move.SLEEP), "Waking" to { stage.play(Move.SLEEP, 1f, true); stage.postDelayed({ stage.playThen(Move.WAKE, Move.IDLE) }, 2500L) },
            "Stretch" to mv(Move.STRETCH), "Dead" to mv(Move.DEAD), "Panic" to mv(Move.PANIC),
            "Begging" to mv(Move.BEGGING), "Crying" to mv(Move.CRYING), "Rage (final boss)" to mv(Move.FINAL_BOSS),
            "Overload" to mv(Move.OVERLOAD), "Victory" to mv(Move.VICTORY), "Hop" to mv(Move.HOP),
            "Nod" to mv(Move.NOD), "Wave" to mv(Move.WAVE), "Point" to mv(Move.POINT),
            "Cheer" to mv(Move.CHEER), "Think" to mv(Move.THINK), "Oops" to mv(Move.OOPS),
            "Surprise" to mv(Move.SURPRISE), "Giggle" to mv(Move.GIGGLE), "Listen" to mv(Move.LISTEN),
            "Proud" to mv(Move.PROUD), "Encourage" to mv(Move.ENCOURAGE), "Celebrate" to mv(Move.CELEBRATE),
            "Task done" to mv(Move.TASK), "Enter" to mv(Move.ENTER),
            "Exit, then back" to { stage.play(Move.EXIT, 1f, true); stage.postDelayed({ stage.play(Move.ENTER, 1f, true) }, 1300L) }
        )
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
        box.addView(stage, lp(wrapC, wrapC).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(name, lp(matchP, wrapC))
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctl.addView(pill("Replay", true) { last?.invoke() }, lp(0, wrapC, 1f).apply { rightMargin = dp(8) })
        ctl.addView(pill("Reset to idle", false) { name.text = "Idle"; last = null; stage.resetToIdle() }, lp(0, wrapC, 1f))
        box.addView(ctl, lp(matchP, wrapC).apply { bottomMargin = dp(12) })
        items.chunked(3).forEach { row ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, (label, action) ->
                r.addView(pill(label, false) { run(label, action) }, lp(0, wrapC, 1f).apply { if (i < 2) rightMargin = dp(6); bottomMargin = dp(6) })
            }
            repeat(3 - row.size) { r.addView(View(this), lp(0, wrapC, 1f)) }
            box.addView(r, lp(matchP, wrapC))
        }
        box.addView(tv("Event log (what changes Orange's opacity, scale and moves)", 11f, fonts.medium, FocusTheme.muted(pal.dark)).apply { setPadding(dp(4), dp(10), 0, 0) }, lp(matchP, wrapC))
        box.addView(log, lp(matchP, wrapC))
        val refresh = object : Runnable {
            override fun run() {
                if (!log.isAttachedToWindow) return
                log.text = OrangeDiag.last(9)
                log.postDelayed(this, 600L)
            }
        }
        log.post(refresh)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box) }
        showSheet("Animation showcase", scroll)
    }

    // ---- End session: pressed -> nervous / begging -> (session really ends) -> dead performance.
    // No "played once" flags exist: every press and every ended session runs the whole sequence again.
    private var endArmed = false
    private var deadToken = 0
    private var deadPlaying = false

    // ----- Ultra Focus reactions (Rage on a refused End-session tap, Overload on repeated blocked launches) -----
    private var ultraToken = 0
    private var rageUntil = 0L
    private var overloadUntil = 0L

    /** Back to the calm pose the session is really in, unless a newer reaction (or Dead) has taken over. */
    private fun settleCalm(token: Int) {
        val o = calmOrange ?: return
        if (token != ultraToken || !o.isAttachedToWindow || !Store.focusRunning(this) || endArmed || deadPlaying) return
        o.play(if (Store.onBreak(this)) Move.SLEEP else Move.FOCUS, 1f, true)
    }

    /** Ultra Focus does not allow ending early, so the tap is refused. Orange reacts once per burst of taps. */
    private fun onUltraEndAttempt() {
        val o = calmOrange ?: return
        if (!Store.focusStrict(this) || !Store.focusRunning(this) || Store.onBreak(this) || endArmed || deadPlaying) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now < rageUntil) return
        rageUntil = now + 4300L
        val token = ++ultraToken   // outranks a running Overload: its settle callback becomes stale
        o.removeCallbacks(breakSleepRun)
        o.say("Don't give up yet!", 2400L, LineCat.PLAYFUL)
        o.play(Move.FINAL_BOSS, 1f, true)
        o.postDelayed({ settleCalm(token) }, 4300L)
    }

    /** The blocker noted a third blocked-app launch within 60 s in this Ultra Focus session: play Overload once, when seen. */
    private fun checkOverload() {
        val p = Store.prefs(this)
        val at = p.getLong("ovl_at", 0L)
        if (at == 0L) return
        val stale = System.currentTimeMillis() - at > 180_000L || !Store.focusRunning(this) || !Store.focusStrict(this) ||
            p.getLong("ovl_done", 0L) != Store.focusStart(this)   // a leftover from an earlier session
        if (stale) { p.edit().putLong("ovl_at", 0L).apply(); return }
        val o = calmOrange ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (o.visibility != View.VISIBLE || !o.isAttachedToWindow || tab != 0 || sheetLayer != null || tourView != null ||
            endArmed || deadPlaying || Store.onBreak(this) || now < rageUntil || now < overloadUntil) return
        p.edit().putLong("ovl_at", 0L).apply()
        overloadUntil = now + 4300L
        val token = ++ultraToken
        o.removeCallbacks(breakSleepRun)
        o.say("Still blocked. Stay with it.", 2400L, LineCat.PLAYFUL)
        o.play(Move.OVERLOAD, 1f, true)
        o.postDelayed({ settleCalm(token) }, 4300L)
    }

    /** Stage 1, the instant End session is pressed (and allowed): Orange begs while the Undo window runs. */
    private fun onEndArmed() {
        endArmed = true
        val o = calmOrange ?: return
        o.removeCallbacks(breakSleepRun)
        o.say("Please... one more minute?", 0L, LineCat.ENDING)
        o.play(Move.BEGGING, 1f, true)
    }

    /** The user took it back: relief, then back to whatever state the session is really in. */
    private fun onEndUndone() {
        if (!endArmed) return
        endArmed = false
        val o = calmOrange ?: return
        o.say("Phew. Back to it.", 1800L, LineCat.PLAYFUL)
        o.play(if (Store.onBreak(this)) Move.BREAK else Move.FOCUS, 1f, true)
        if (Store.onBreak(this)) o.postDelayed(breakSleepRun, 3200L)
    }

    /** Stage 2: runs only when the existing end logic has really ended the session. */
    private fun commitEndSession() {
        endArmed = false
        ultraToken++   // Dead outranks everything: a pending Rage/Overload settle must never restart over it
        val ended = Store.endFocusEarly(this)
        wasRunning = false
        updateRunning()
        fillFocusStats()
        if (ended) playEndedDead()
    }

    private fun playEndedDead() {
        val c = companionOrange ?: return
        val token = ++deadToken
        deadPlaying = true
        companionMessage("Well. That happened.", 6400L)
        // a beat after the screen switches, so the first frame of the performance is on screen
        c.postDelayed({
            if (token != deadToken || !c.isAttachedToWindow) return@postDelayed
            c.removeCallbacks(sleepRun)
            c.play(Move.DEAD, 1f, true)
        }, 120L)
        c.postDelayed({
            if (token != deadToken) return@postDelayed
            deadPlaying = false
            if (c.isAttachedToWindow) { c.play(Move.IDLE, 1f, true); scheduleSleep() }
        }, 6900L)
    }

    private fun onEndClick() {
        if (Store.frozen(this)) { free(); return }
        AlertDialog.Builder(this)
            .setTitle("End this session?")
            .setMessage("The focus time so far is saved.")
            .setPositiveButton("End session") { _, _ -> commitEndSession() }
            .setNegativeButton("Keep going", null)
            .show()
    }

    private fun updateRunning() {
        Store.finalizeFocus(this)
        val running = Store.focusRunning(this)
        if (running != (runningCard?.visibility == View.VISIBLE)) {
            // The running card and the idle page swap places: start from the top so the timer (or, after a session, the
            // companion and the completion) is on screen instead of wherever the page was scrolled to.
            focusScroll?.post { focusScroll?.smoothScrollTo(0, 0) }
        }
        runningCard?.visibility = if (running) View.VISIBLE else View.GONE
        statsWrap?.visibility = if (running) View.GONE else View.VISIBLE
        startBar?.visibility = if (running) View.GONE else View.VISIBLE
        if (running) dismissNudge()
        refreshCompanion()
        runExtras?.let { ex ->
            if (running && ex.visibility != View.VISIBLE) {
                ex.visibility = View.VISIBLE
                ex.alpha = 0f
                ex.translationY = dp(14).toFloat()
                ex.animate().alpha(1f).translationY(0f).setDuration(520)
                    .setInterpolator(SpringInterpolator(14f, 0.8f, 0.9f)).start()
            } else if (!running) ex.visibility = View.GONE
        }
        if (running) {
            fun put(v: TextView?, t: String) { if (v != null && v.text.toString() != t) v.text = t }
            val st = Store.streak(this)
            put(exToday, fmtMin(Store.focusMinutesAgo(this, 0)))
            put(exStreak, if (st == 1) "1 day" else "$st days")
            put(exBlocked, Store.total(this).toString())
            val fa = Store.focusApps(this).size
            put(exApps, if (fa == 0) "Always-on only" else "$fa apps")
            put(exHome, if (Store.blockHome(this)) "Covered" else "Open")
            put(exMode, listOfNotNull(if (Store.studyMode(this)) "Study" else null, if (Store.focusStrict(this)) "Strict" else null)
                .joinToString(" · ").ifEmpty { "Standard" })
            val now = System.currentTimeMillis()
            val start = Store.focusStart(this)
            val end = Store.focusEnd(this)
            val frac = if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
            val onBreak = Store.onBreak(this)
            runRing?.big = clock(Store.focusLeftMs(this))
            runRing?.small = if (onBreak) "On break" else Store.focusTag(this).ifEmpty { "Focus" }
            runRing?.setSpan(start, end, onBreak)
            runInfo?.text = if (onBreak) "Break ends in ${clock(Store.breakUntil(this) - now)}. Blocks come back after."
            else Store.breaksLeft(this).let { n -> "${if (n == 0) "No" else n.toString()} break${if (n == 1) "" else "s"} left." + (if (Store.focusStrict(this)) " Ultra Focus is on." else "") }
            runBreakBtn?.let { b ->
                b.visibility = if (!onBreak && Store.breaksLeft(this) == 0) View.GONE else View.VISIBLE
                b.text = if (onBreak) "End break" else "Take a break"
            }
            runEndBtn?.let { b ->
                val fz = Store.frozen(this)
                b.text = if (fz) "Locked" else "End session"
                b.locked = fz
                b.alpha = if (fz) 0.78f else 1f
            }
            runLattice?.running = !onBreak
            calmOrange?.let { c ->
                val newlyShown = c.visibility != View.VISIBLE
                if (newlyShown) {
                    c.visibility = View.VISIBLE
                    c.pivotX = dp(26).toFloat(); c.pivotY = dp(30).toFloat()
                    c.scaleX = 0.2f; c.scaleY = 0.2f; c.alpha = 0f
                    c.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(80).setDuration(620)
                        .setInterpolator(SpringInterpolator(13f, 0.55f, 0.7f)).start()
                    // Establish the initial state before scheduling entrance. Do not immediately
                    // overwrite ENTER with the ordinary end-break SURPRISE transition.
                    calmBreak = onBreak
                    if (onBreak) {
                        c.play(Move.SLEEP, 1f, true)
                        c.say("Taking a little rest.", 0L, LineCat.SLEEP)
                    } else {
                        // The encouraging nod plays once per session start. Coming back to a session that is already
                        // running (Activity recreated, app reopened) goes straight to the calm focus pose instead.
                        val st = Store.focusStart(this)
                        val p = Store.prefs(this)
                        if (p.getLong("reacted_start", 0L) != st) {
                            p.edit().putLong("reacted_start", st).apply()
                            c.play(Move.ENCOURAGE, 1f, true)
                            c.say("Let's focus.", 1800L, LineCat.FOCUS)
                            c.postDelayed({ if (Store.focusRunning(this) && !Store.onBreak(this) && !endArmed) c.play(Move.FOCUS, 1f, true) }, 1500L)
                        } else c.play(Move.FOCUS, 1f, true)
                    }
                } else if (calmBreak == null) {
                    calmBreak = onBreak
                } else if (calmBreak != onBreak) {
                    calmBreak = onBreak
                    playBreakCompanionTransition(onBreak)
                } else {
                    Unit
                }
            }
            checkOverload()
            runLabel?.text = if (onBreak) "On break" else "Focusing"
            runElapsed?.text = clock(now - start)
        } else {
            calmBreak = null
            endArmed = false
            calmOrange?.let { it.animate().cancel(); it.visibility = View.GONE }
            runEndBtn?.reset()
            runRing?.stop()
        }
        if (wasRunning && !running) onSessionDone()
        wasRunning = running
        refreshStatus()
    }

    private fun onSessionDone() {
        fillFocusStats()
        val last = Store.sessions(this).lastOrNull() ?: return
        // One reaction per finished session, remembered across screen refreshes, returning from the background and
        // Activity recreation (the in-memory wasRunning flag alone cannot know that this ending was already celebrated).
        if (Store.prefs(this).getLong("reacted_end", 0L) == last.start) return
        Store.prefs(this).edit().putLong("reacted_end", last.start).apply()
        val goalNow = Store.goalMin(this)
        val todayNow = Store.focusMinutesAgo(this, 0)
        val goalHit = last.done && goalNow > 0 && todayNow >= goalNow && todayNow - last.minutes < goalNow
        val msg = if (goalHit) "Daily goal done!" else if (last.done) "Nice focus!" else "Next one counts."
        val c = companionOrange
        val fullSize = c != null && tab == 0
        if (c != null && tab == 0) {
            refreshCompanion()
            if (goalHit) Store.prefs(this).edit().putString("proud_day", java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())).apply()
            // A finished session gets the Victory performance. A session ended early gets the small gentle OOPS, never a
            // sad, begging or angry one: stopping early is not something to be shamed for. Crying, Rage and Overload
            // stay in the showcase, because no real event calls for them.
            val reaction = if (last.done) Move.VICTORY else Move.OOPS
            companionMessage(msg); c.play(reaction, 1f, true)
            c.postDelayed({ if (c.isAttachedToWindow && !Store.focusRunning(this)) c.play(Move.IDLE, 1f, true) }, 4300L)
            scheduleSleep()
        } else orangeSay(if (last.done) Move.CELEBRATE else Move.OOPS, msg, true, 1700L, 170)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(4)) }
        val tag = last.tag.ifEmpty { "Focus" }
        val streak = Store.streak(this)
        val praise = when {
            last.minutes < 10 -> "Short and clean."
            last.minutes < 25 -> "A clean sprint."
            last.minutes < 45 -> "Real focus."
            last.minutes < 90 -> "Deep work. Well done."
            last.minutes < 180 -> "A solid session. Well done."
            last.minutes < 300 -> "Marathon focus. Rare."
            else -> "Hours of pure focus. Unreal."
        }
        val data = FocusCardData(
            badge = if (last.done) "Focus complete" else "Session ended",
            status = if (last.done) "Done" else "Ended",
            eyebrow = "FOCUSED FOR",
            big = fmtMin(last.minutes),
            line = "$tag · $praise",
            stats = listOf(
                "Today" to fmtMin(Store.focusMinutesAgo(this, 0)),
                "Streak" to (if (streak == 1) "1 day" else "$streak days"),
                "Blocked" to Store.total(this).toString()
            ),
            idLabel = "Get Shortstop",
            idValue = APP_LINK_SHORT,
            tier = Rarity.tierFor(last.minutes, streak, last.done)
        )
        val card = ReflectiveCard(this, fonts, data)
        if (data.tier > 0) toast("${Rarity.of(data.tier).name} card", "Earned with this session.")
        box.addView(card, lp(matchP, wrapC))
        val heartRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heartRow.addView(tv("Keep this session", 14f, fonts.regular, pal.muted), lp(0, wrapC, 1f))
        heartRow.addView(PulseHeart(this, pal, fonts, Store.lovedCount(this), Store.isLoved(this, last.start), true) { on, _ ->
            Store.setLoved(this, last.start, on)
        }, lp(wrapC, wrapC))
        box.addView(heartRow, lp(matchP, wrapC).apply { bottomMargin = dp(14) })
        val shareText = "I stayed focused for ${fmtMin(last.minutes)} with Shortstop. No Shorts, no Reels, just the work.\n$APP_LINK"
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("Share", true) { shareFocusCard(this, card, shareText) }, lp(0, wrapC, 1f).apply { rightMargin = dp(10) })
        btns.addView(pill("Done", false) { closeSheet() }, lp(0, wrapC, 1f))
        box.addView(btns, lp(matchP, wrapC))
        // Victory first, then the summary once the celebration has finished (full-size Orange 4.2 s, small one about 2.6 s).
        // A session that did not complete has no celebration, so its summary shows straight away.
        val showSummary = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            showSheet("Session complete", box)
            if (last.done) {
                // The card settles in after the sheet, and a small confetti burst plays once above everything.
                card.alpha = 0f; card.scaleX = 0.94f; card.scaleY = 0.94f; card.translationY = dp(14).toFloat()
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(180).setDuration(560)
                    .setInterpolator(SpringInterpolator(14f, 0.8f, 0.9f)).start()
                val burst = ConfettiBurst(this)
                rootFrame.addView(burst, FrameLayout.LayoutParams(matchP, matchP))
                burst.post { burst.start() }
            }
        }
        if (last.done) ui.postDelayed(showSummary, if (fullSize) 4300L else 2600L) else showSummary.run()
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
        c.background = round(FocusTheme.cardFill(pal.dark), 24, FocusTheme.cardEdge(pal.dark), dp(1))
        c.addView(tv(title, 15f, fonts.semi, pal.ink).apply { setPadding(0, dp(16), 0, dp(10)) })
        return c
    }

    private var focusOpen: View? = null
    private val focusClosers = HashMap<View, () -> Unit>()

    /** Compact card that swaps a preview for the full detail in place, with a soft spring on its height. */
    private fun expCard(title: String, preview: View, detail: View, expandable: Boolean): LinearLayout {
        val c = card()
        c.background = round(FocusTheme.cardFill(pal.dark), 24, FocusTheme.cardEdge(pal.dark), dp(1))
        val chev = Chevron(this, pal.muted)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, dp(10)) }
        head.addView(tv(title, 15f, fonts.semi, pal.ink), lp(0, wrapC, 1f))
        if (expandable) head.addView(chev, lp(dp(18), dp(18)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(preview, lp(matchP, wrapC))
        body.addView(detail, lp(matchP, wrapC))
        detail.visibility = View.GONE
        c.addView(head, lp(matchP, wrapC))
        c.addView(body, lp(matchP, wrapC))
        if (!expandable) return c
        var open = false
        var anim: ValueAnimator? = null
        fun set(on: Boolean, animate: Boolean) {
            if (on == open) return
            open = on
            val w = body.width
            val h0 = body.height
            anim?.cancel()
            val incoming = if (on) detail else preview
            val outgoing = if (on) preview else detail
            outgoing.visibility = View.GONE
            incoming.visibility = View.VISIBLE
            val lpB = body.layoutParams
            lpB.height = ViewGroup.LayoutParams.WRAP_CONTENT
            val calm = reducedMotion() || !animate || w == 0
            chev.animate().cancel()
            if (calm) {
                chev.rotation = if (on) 180f else 0f
                incoming.alpha = 1f
                body.requestLayout()
                return
            }
            chev.animate().rotation(if (on) 180f else 0f).setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            body.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val h1 = body.measuredHeight
            incoming.alpha = 0f
            lpB.height = h0
            body.layoutParams = lpB
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520
                interpolator = SpringInterpolator(16f, 0.78f, 0.5f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    lpB.height = (h0 + (h1 - h0) * f).toInt().coerceAtLeast(0)
                    body.layoutParams = lpB
                    incoming.alpha = (f * 1.4f).coerceIn(0f, 1f)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        lpB.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        body.layoutParams = lpB
                        incoming.alpha = 1f
                    }
                    override fun onAnimationCancel(a: android.animation.Animator) { incoming.alpha = 1f }
                })
                start()
            }
        }
        focusClosers[c] = { set(false, true) }
        c.isClickable = true
        c.setOnClickListener {
            if (open) { set(false, true); if (focusOpen === c) focusOpen = null }
            else {
                val prev = focusOpen
                if (prev != null && prev !== c) focusClosers[prev]?.invoke()
                focusOpen = c
                set(true, true)
            }
        }
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
                val ring = TimerDial(this, pal, fonts)
                ring.big = fmtMin(mins)
                ring.small = "Focus goal ${fmtMin(goal)}"
                ring.showGoal(if (goal > 0) mins.toFloat() / goal else 0f, true)
                col.addView(ring, lp(wrapC, wrapC).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6) })
                col.addView(pill("Edit goal", false) { showGoalSheet() }.apply { textSize = 13f },
                    lp(wrapC, wrapC).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(14) })

                val sessions = Store.sessionsOn(this, today)
                focusClosers.clear(); focusOpen = null
                val tf = SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", Locale.getDefault())

                // Today's summary
                val sumPrev = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(6)) }
                sumPrev.addView(FocusTimeline(this, pal, fonts, sessions, false), lp(matchP, wrapC))
                val sumDet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(14)) }
                sumDet.addView(FocusTimeline(this, pal, fonts, sessions, true), lp(matchP, wrapC))
                sumDet.addView(tv("${sessions.size} session${if (sessions.size == 1) "" else "s"} · ${fmtMin(sessions.sumOf { it.minutes })} focused",
                    13f, fonts.medium, pal.ink).apply { setPadding(0, dp(6), 0, dp(6)) })
                sessions.forEach { s ->
                    sumDet.addView(tv(tf.format(java.util.Date(s.start)) + " – " + tf.format(java.util.Date(s.start + s.minutes * 60000L)) +
                        "  ·  " + s.tag.ifEmpty { "Focus" }, 12f, fonts.regular, pal.muted).apply { setPadding(0, dp(3), 0, dp(3)) })
                }
                col.addView(expCard("Today's summary", sumPrev, sumDet, sessions.isNotEmpty()), lp(matchP, wrapC).apply { topMargin = dp(18) })

                // Tags
                val tagList = sessions.groupBy { it.tag.ifEmpty { "No tag" } }
                    .map { (t, l) -> t to l.sumOf { it.minutes } }.sortedByDescending { it.second }
                val tagTotal = tagList.sumOf { it.second }.coerceAtLeast(1)
                fun tagRow(t: String, m: Int, bar: Boolean): LinearLayout {
                    val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(if (bar) 12 else 10)) }
                    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    top.addView(TagIcon(this, t, pal.dark), lp(dp(30), dp(30)).apply { marginEnd = dp(12) })
                    top.addView(tv(t, 15f, fonts.medium, pal.ink), lp(0, wrapC, 1f))
                    top.addView(tv(fmtMin(m), 15f, fonts.semi, pal.ink))
                    r.addView(top)
                    if (bar) r.addView(PeachBar(this, pal.dark, m.toFloat() / tagTotal), lp(matchP, dp(4)).apply { topMargin = dp(8); marginStart = dp(42) })
                    return r
                }
                val tagPrev = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(6)) }
                val tagDet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(6)) }
                if (tagList.isEmpty()) {
                    val e = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(14)) }
                    e.addView(TagIcon(this, "", pal.dark), lp(dp(36), dp(36)).apply { marginEnd = dp(12) })
                    val et = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    et.addView(tv("No tags yet", 15f, fonts.medium, pal.ink))
                    et.addView(tv("Tag a focus session and it shows up here.", 12f, fonts.regular, pal.muted))
                    e.addView(et, lp(0, wrapC, 1f))
                    tagPrev.addView(e)
                } else {
                    tagList.take(2).forEach { (t, m) -> tagPrev.addView(tagRow(t, m, false)) }
                    if (tagList.size > 2) tagPrev.addView(tv("+${tagList.size - 2} more", 12f, fonts.medium, pal.muted).apply { setPadding(0, 0, 0, dp(8)) })
                    tagList.forEach { (t, m) -> tagDet.addView(tagRow(t, m, true)) }
                }
                col.addView(expCard("Tags", tagPrev, tagDet, tagList.size > 2 || tagList.isNotEmpty()), lp(matchP, wrapC).apply { topMargin = dp(12) })

                // Sessions
                fun sessRow(s: Store.Session): LinearLayout {
                    val r = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12))
                    }
                    r.isClickable = true
                    r.setOnClickListener { showSessionSheet(s) }
                    press(r)
                    val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    left.addView(tv(s.tag.ifEmpty { "Focus" }, 15f, fonts.medium, pal.ink))
                    val st = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    st.addView(View(this).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (s.done) FocusCardColors.GREEN else FocusCardColors.EARLY) }
                    }, lp(dp(6), dp(6)).apply { marginEnd = dp(6) })
                    st.addView(tv(tf.format(java.util.Date(s.start)) + " · " + if (s.done) "Completed" else "Ended early", 12f, fonts.regular, pal.muted))
                    left.addView(st, lp(wrapC, wrapC).apply { topMargin = dp(2) })
                    r.addView(left, lp(0, wrapC, 1f))
                    r.addView(tv(fmtMin(s.minutes), 15f, fonts.semi, pal.ink).apply { setPadding(0, 0, dp(12), 0) })
                    r.addView(PulseHeart(this, pal, fonts, 0, Store.isLoved(this, s.start), false) { on, _ ->
                        Store.setLoved(this, s.start, on)
                    }, lp(dp(40), dp(40)))
                    return r
                }
                val sessPrev = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(6)) }
                val sessDet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(6)) }
                if (sessions.isEmpty()) {
                    sessPrev.addView(tv("No sessions yet", 13f, fonts.regular, pal.muted).apply { setPadding(0, 0, 0, dp(10)) })
                } else {
                    sessions.takeLast(2).forEach { sessPrev.addView(sessRow(it)) }
                    if (sessions.size > 2) sessPrev.addView(tv("+${sessions.size - 2} earlier", 12f, fonts.medium, pal.muted).apply { setPadding(0, 0, 0, dp(8)) })
                    sessions.forEach { sessDet.addView(sessRow(it)) }
                }
                col.addView(expCard("Sessions", sessPrev, sessDet, sessions.size > 2), lp(matchP, wrapC).apply { topMargin = dp(12) })
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
                val ring = TimerDial(this, pal, fonts)
                ring.big = fmtMin(total)
                ring.small = "Week goal ${fmtMin(goal * 7)}"
                ring.showGoal(if (goal > 0) total.toFloat() / (goal * 7) else 0f, true)
                col.addView(ring, lp(wrapC, wrapC).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6) })
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
        row.addView(stepBtn(false) { g = (g - 30).coerceAtLeast(30); big.text = fmtMin(g) })
        row.addView(stepBtn(true) { g = (g + 30).coerceAtMost(960); big.text = fmtMin(g) }, lp(dp(46), dp(38)).apply { marginStart = dp(8) })
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
        companionOrange?.play(Move.THINK, 1f, true)
        var dur = Store.lastMinutes(this)
        var tag = Store.lastTag(this).let { if (it in Store.TAGS) it else Store.TAGS[0] }
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
        durRow.addView(stepBtn(false) { setDur(dur - 5) })
        durRow.addView(stepBtn(true) { setDur(dur + 5) }, lp(dp(46), dp(38)).apply { marginStart = dp(8) })
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
        lateinit var slide: SlideCommit
        fun tint() {
            slide.accent = mixColor(TagColors.of(tag), Color.WHITE, 0.5f)
            slide.deep = TagColors.deep(tag)
        }
        box.addView(TagPicker(this, pal, fonts, Store.TAGS, tag) { name -> tag = name; tint() }, lp(matchP, dp(46)))

        val opts = card()
        opts.addRow(toggleRow("Ultra Focus", "No quitting until it ends", strict) {
            strict = it
            Store.setStrictDefault(this, it)
        })
        opts.addRow(stepperRow({ "Breaks: $it" }, breaks, 0, 10, 1) { breaks = it })
        opts.addRow(stepperRow({ "Break length: $it min" }, breakMin, 1, 30, 1) { breakMin = it })
        opts.addRow(linkRow("Blocked apps", "${Store.focusApps(this).size} selected") {
            Store.rememberSession(this, dur, tag, breaks, breakMin)
            closeSheet()
            openFocusApps()
        })
        opts.addRow(linkRow("More options", "Study mode, guards, home screen") {
            Store.rememberSession(this, dur, tag, breaks, breakMin)
            showUltraSheet { showStartSheet() }
        })
        box.addView(opts, lp(matchP, wrapC).apply { topMargin = dp(16) })

        slide = SlideCommit(this, pal, fonts, "Slide to start", "Focus started", {
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
        }
        tint()
        box.addView(slide, lp(matchP, wrapC).apply { topMargin = dp(16); bottomMargin = dp(4); gravity = Gravity.CENTER_HORIZONTAL })
        showSheet("Start focus", box)
    }

    /** Tap a session: the same 3D reflective card as the completion sheet, a heart, and a slide to share. */
    private fun showSessionSheet(s: Store.Session) {
        val tag = s.tag.ifEmpty { "Focus" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(4)) }
        val sessions = Store.sessions(this)
        val data = FocusCardData(
            badge = if (s.done) "Focus complete" else "Session ended",
            status = if (s.done) "Done" else "Ended",
            eyebrow = "FOCUSED FOR",
            big = fmtMin(s.minutes),
            line = "$tag · " + SimpleDateFormat("EEE d MMM, " + (if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"), Locale.getDefault()).format(java.util.Date(s.start)),
            stats = listOf(
                "Today" to fmtMin(Store.focusMinutesAgo(this, 0)),
                "Streak" to (Store.streak(this).let { if (it == 1) "1 day" else "$it days" }),
                "Sessions" to sessions.size.toString()
            ),
            idLabel = "Get Shortstop",
            idValue = APP_LINK_SHORT,
            tier = Rarity.tierFor(s.minutes, Store.streak(this), s.done)
        )
        val card = ReflectiveCard(this, fonts, data)
        box.addView(card, lp(matchP, wrapC))
        val heartRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heartRow.addView(tv("Keep this session", 14f, fonts.regular, pal.muted), lp(0, wrapC, 1f))
        heartRow.addView(PulseHeart(this, pal, fonts, Store.lovedCount(this), Store.isLoved(this, s.start), true) { on, _ ->
            Store.setLoved(this, s.start, on)
        }, lp(wrapC, wrapC))
        box.addView(heartRow, lp(matchP, wrapC).apply { bottomMargin = dp(12) })
        val shareText = "I stayed focused for ${fmtMin(s.minutes)} with Shortstop. No Shorts, no Reels, just the work.\n$APP_LINK"
        val slide = SlideCommit(this, pal, fonts, "Slide to share", "Sharing", { true }) {
            shareFocusCard(this, card, shareText)
        }
        Rarity.of(data.tier).let { slide.accent = it.slide; slide.deep = it.slideDeep }
        box.addView(slide, lp(matchP, wrapC).apply { bottomMargin = dp(4) })
        showSheet("Focus session", box)
    }

    private fun showUltraSheet(onClose: (() -> Unit)? = null) {
        val c = card()
        c.addRow(toggleRow("Ultra Focus", "No quitting until it ends", Store.strictDefault(this)) { Store.setStrictDefault(this, it) })
        c.addRow(toggleRow("YouTube Study Mode", "Only Shorts and the feed are blocked", Store.studyMode(this)) { Store.setStudyMode(this, it) })
        c.addRow(toggleRow("Uninstall protection", "Stays on through breaks until the focus cycle ends", Store.uninstallGuard(this),
            veto = { turnOn ->
                // a break is part of the same focus cycle, so protection cannot be switched off until it is completely over
                if (!turnOn && Store.uninstallGuard(this) && Store.guardLocked(this)) {
                    toast("Uninstall protection stays on", "It stays active through breaks until your whole focus cycle ends.")
                    false
                } else true
            }) { Store.setUninstallGuard(this, it) })
        c.addRow(toggleRow("Block notifications", "Hides alerts from blocked apps", Store.blockNotifs(this)) {
            Store.setBlockNotifs(this, it)
            if (it && !notifAccess()) {
                toast("Switch on Notification access for Shortstop.")
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        })
        c.addRow(toggleRow("Block home screen", "Shows the timer instead", Store.blockHome(this)) { Store.setBlockHome(this, it) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(c, lp(matchP, wrapC))
        box.addView(note("Uninstall protection makes removal harder, but it can't stop Android's own safe mode."))
        if (Store.uninstallGuard(this) && !(serviceOn() && ShortsBlockerService.alive))
            box.addView(note("Protection is not working right now: switch Shortstop's Accessibility service on in Settings."))
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
        val col = column().apply { clipToPadding = false }
        val sTitle = display("Schedule", 30f).apply { setTextColor(FocusTheme.title(pal.dark)) }
        schedPageTitle = sTitle
        col.addView(sTitle)
        val intro = tv("Focus starts by itself in these windows. Your focus apps are closed until they end.",
            14f, fonts.regular, FocusTheme.muted(pal.dark)).apply { setPadding(0, dp(8), 0, dp(6)) }
        schedIntro = intro
        col.addView(intro)

        val head = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = round(FocusTheme.cardFill(pal.dark), 24, FocusTheme.cardEdge(pal.dark), dp(1))
            elevation = dp(1).toFloat()
        }
        val dot = LiveDot(this, FocusCardColors.ORANGE)
        schedDot = dot
        head.addView(dot, lp(wrapC, wrapC).apply { marginEnd = dp(8) })
        val hc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        schedTitle = tv("", 16f, fonts.semi, FocusTheme.title(pal.dark))
        schedSub = tv("", 13f, fonts.regular, FocusTheme.muted(pal.dark)).apply { setPadding(0, dp(4), 0, 0) }
        hc.addView(schedTitle, lp(matchP, wrapC))
        hc.addView(schedSub, lp(matchP, wrapC))
        head.addView(hc, lp(0, wrapC, 1f))
        schedHeadCard = head
        col.addView(head, lp(matchP, wrapC).apply { topMargin = dp(14) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        schedCol = list
        col.addView(list, lp(matchP, wrapC).apply { topMargin = dp(14) })
        val sc = scrollPage(col)
        schedScroll = sc
        page.addView(sc, lp(matchP, 0, 1f))
        val eh = FrameLayout(this).apply { visibility = View.GONE }
        schedEmptyHost = eh
        page.addView(eh, lp(matchP, 0, 1f))
        val barWrap = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(10), dp(20), dp(14)) }
        barWrap.addView(pill("Create schedule", true) { showScheduleSheet(null) }.apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            background = round(FocusCardColors.ORANGE, 40)
            elevation = dp(2).toFloat()
            setPadding(dp(40), dp(15), dp(40), dp(15))
        }, lp(wrapC, wrapC))
        page.addView(barWrap, lp(matchP, wrapC))
        fillSchedules()
        val root = FrameLayout(this)
        root.addView(AmbientBackdrop(this, pal, true), FrameLayout.LayoutParams(matchP, matchP))
        root.addView(page, FrameLayout.LayoutParams(matchP, matchP))
        return root
    }

    /** The next window that will start, as (schedule, minutes from now). */
    private fun nextSchedule(): Pair<Store.FocusSchedule, Int>? {
        val items = Store.schedules(this).filter { it.on }
        if (items.isEmpty()) return null
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val today = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        var best: Pair<Store.FocusSchedule, Int>? = null
        for (off in 0..7) {
            val di = (today + off) % 7
            for (s in items) {
                if (((s.days shr di) and 1) == 0) continue
                if (off == 0 && s.start <= nowMin) continue
                val mins = off * 1440 + s.start - nowMin
                if (best == null || mins < best.second) best = s to mins
            }
        }
        return best
    }

    private fun updateSchedHeader() {
        val t = schedTitle ?: return
        val sub = schedSub ?: return
        val active = Store.scheduleActive(this)
        val title: String
        val line: String
        if (active != null) {
            title = "Focus is on"
            line = "${active.name} until ${fmt12(active.end)}"
        } else {
            val next = nextSchedule()
            if (next != null) {
                val (s, mins) = next
                title = "Next up"
                val now = Calendar.getInstance()
                val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                line = if (mins < 60) "${s.name} · in $mins min"
                else if (mins < 1440) "${s.name} · in ${mins / 60}h ${mins % 60}m"
                else {
                    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                    val di = ((now.get(Calendar.DAY_OF_WEEK) + 5) % 7 + (nowMin + mins) / 1440) % 7
                    "${s.name} · ${names[di]} ${fmt12(s.start)}"
                }
            } else {
                title = "Nothing scheduled"
                line = "Add a window and Shortstop starts focus for you."
            }
        }
        if (t.text.toString() != title) t.text = title
        if (sub.text.toString() != line) sub.text = line
        schedDot?.let { if (it.live != (active != null)) it.live = active != null }
    }

    private fun fillSchedules() {
        val list = schedCol ?: return
        list.removeAllViews()
        schedTimeline = null
        updateSchedHeader()
        val items = Store.schedules(this)
        val vis = if (items.isEmpty()) View.GONE else View.VISIBLE
        schedIntro?.visibility = vis
        updateStatusBar()
        schedPageTitle?.visibility = vis
        schedHeadCard?.visibility = vis
        schedScroll?.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        schedEmptyHost?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) { schedEmptyHost?.let { fillScheduleEmpty(it) }; return }

        val activeId = Store.scheduleActive(this)?.id

        // Compact weekly overview: existing schedules, plotted on the real seven-day timeline.
        val weekCard = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = round(FocusTheme.cardFill(pal.dark), 24, FocusTheme.cardEdge(pal.dark), dp(1))
            elevation = dp(1).toFloat()
        }
        weekCard.addView(tv("This week", 14f, fonts.semi, FocusTheme.title(pal.dark)).apply {
            setPadding(0, 0, 0, dp(8))
        })
        val week = WeekTimeline(this, pal, fonts, items)
        schedTimeline = week
        weekCard.addView(week, lp(matchP, wrapC))
        list.addView(weekCard, lp(matchP, wrapC).apply { bottomMargin = dp(12) })

        for (s in items) {
            val live = s.id == activeId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(FocusTheme.cardFill(pal.dark), 24,
                    if (live) FocusCardColors.ORANGE else FocusTheme.cardEdge(pal.dark), dp(if (live) 2 else 1))
                setPadding(dp(18), dp(16), dp(16), dp(16))
                elevation = if (live) dp(2).toFloat() else dp(1).toFloat()
                isClickable = true
                alpha = if (s.on) 1f else 0.62f
                setOnClickListener { showScheduleSheet(s) }
                press(this)
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            nameRow.addView(tv(s.name, 16f, fonts.semi, FocusTheme.title(pal.dark)))
            if (live) nameRow.addView(tv("LIVE", 10f, fonts.bold, Color.WHITE, Gravity.CENTER).apply {
                letterSpacing = 0.1f
                background = round(FocusCardColors.ORANGE, 40)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }, lp(wrapC, wrapC).apply { marginStart = dp(8) })
            left.addView(nameRow)
            left.addView(tv("${fmt12(s.start)} – ${fmt12(s.end)}", 14f, fonts.medium, FocusTheme.accent(pal.dark)).apply { setPadding(0, dp(5), 0, 0) })
            left.addView(tv(daysLabel(s.days), 12f, fonts.regular, FocusTheme.muted(pal.dark)).apply { setPadding(0, dp(3), 0, 0) })
            row.addView(left, lp(0, wrapC, 1f).apply { marginEnd = dp(12) })
            val t = ToggleView(this)
            t.brand = true
            t.setOn(s.on, false)
            t.listener = { on ->
                Store.saveSchedules(this, Store.schedules(this).map { if (it.id == s.id) it.copy(on = on) else it })
                row.animate().alpha(if (on) 1f else 0.62f).setDuration(220).start()
                updateSchedHeader()
                refreshStatus()
            }
            row.addView(t)
            list.addView(row, lp(matchP, wrapC).apply { bottomMargin = dp(10) })
        }
    }

    /** Regain-style empty state: a title, a swipeable row of ready-made windows, dots. */
    private fun fillScheduleEmpty(host: FrameLayout) {
        host.removeAllViews()
        val holder = FrameLayout(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val screenW = resources.displayMetrics.widthPixels
        // Dither Veil: burn through the 1-bit photo with a finger
        content.gravity = Gravity.CENTER_VERTICAL
        val orange = OrangeView(this, fonts, pal.dark, 92f)
        orange.lineCat = { LineCat.SCHEDULE }
        orange.say("Your next focus session is ready.", 0L, LineCat.SCHEDULE)
        orange.play(Move.LISTEN, force = true)
        content.addView(orange, lp(wrapC, wrapC).apply { gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(display("Create your\nstudy schedule", 22f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(22))
        }, lp(matchP, wrapC))
        val templates = listOf(
            Triple("Morning Study", 6 * 60, 8 * 60),
            Triple("Afternoon Study", 14 * 60, 16 * 60),
            Triple("Evening Study", 18 * 60, 20 * 60),
            Triple("Night Study", 20 * 60, 22 * 60)
        )
        val cardW = screenW - dp(76)
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0) }
        templates.forEachIndexed { i, (n, st, en) ->
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(FocusTheme.cardFill(pal.dark), 22, FocusTheme.cardEdge(pal.dark), dp(1))
                setPadding(dp(18), dp(14), dp(14), dp(14))
            }
            val lc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            lc.addView(tv(n, 15f, fonts.semi, FocusTheme.title(pal.dark)))
            lc.addView(tv("${fmt12(st)} – ${fmt12(en)}  ·  ${daysLabel(31)}", 12f, fonts.regular, FocusTheme.muted(pal.dark)).apply { setPadding(0, dp(3), 0, 0) })
            c.addView(lc, lp(0, wrapC, 1f).apply { marginEnd = dp(10) })
            var busy = false
            val add = GlyphBtn(this, true, pal.ink, pal.canvas, 0, true).apply {
                contentDescription = "Add $n schedule"
                setOnClickListener {
                    if (busy) return@setOnClickListener
                    busy = true
                    // Capture ONLY the page (transparent background): the cloudy backdrop stays live underneath and is
                    // never flattened. The page dissolves into one even blur over it while the new list is built below.
                    orange.say(null)
                    val bmp = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                    content.draw(Canvas(bmp))
                    val host = window.decorView as ViewGroup
                    val a = IntArray(2); val b = IntArray(2)
                    content.getLocationInWindow(a); host.getLocationInWindow(b)
                    val blur = PageBlurFade(this@MainActivity)
                    host.addView(blur, FrameLayout.LayoutParams(content.width, content.height).apply {
                        leftMargin = a[0] - b[0]; topMargin = a[1] - b[1]
                    })
                    content.visibility = View.INVISIBLE
                    blur.start(bmp, mid = {
                        Store.saveSchedules(this@MainActivity, Store.schedules(this@MainActivity) +
                            Store.FocusSchedule(System.currentTimeMillis().toString() + i, n, st, en, 31, true))
                        fillSchedules()
                        refreshStatus()
                        toast("$n added")
                        schedCol?.let { col -> for (k in 0 until col.childCount) popIn(col.getChildAt(k), 40L + k * 70L) }
                    }, done = {
                        // always give the real screen back, whichever way the transition ended
                        // not synchronously: when the window is torn down this runs inside the root view's detach loop
                        android.os.Handler(android.os.Looper.getMainLooper()).post { (blur.parent as? ViewGroup)?.removeView(blur) }
                        content.visibility = View.VISIBLE
                        busy = false
                        updateStatusBar()
                    })
                }
                press(this)
            }
            c.addView(add, lp(dp(34), dp(34)))
            // the button looks 34 dp but the touch area is 48 dp
            c.post {
                val r = android.graphics.Rect(); add.getHitRect(r)
                val grow = dp(7)
                r.inset(-grow, -grow)
                c.touchDelegate = android.view.TouchDelegate(r, add)
            }
            strip.addView(c, lp(cardW, wrapC).apply { marginStart = dp(6); marginEnd = dp(6) })
        }
        val hs = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        hs.addView(strip)
        val step = cardW + dp(12)
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val dotViews = templates.indices.map {
            View(this).also { d -> dots.addView(d, lp(dp(6), dp(6)).apply { marginStart = dp(3); marginEnd = dp(3) }) }
        }
        fun paintDots(x: Int) {
            val cur = ((x + step / 2) / step).coerceIn(0, templates.size - 1)
            dotViews.forEachIndexed { i, d -> d.background = round(if (i == cur) pal.ink else pal.track, 40) }
        }
        paintDots(0)
        hs.setOnScrollChangeListener { _, x, _, _, _ -> paintDots(x) }
        hs.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                v.postDelayed({
                    hs.smoothScrollTo(((hs.scrollX + step / 2) / step).coerceIn(0, templates.size - 1) * step, 0)
                }, 90)
            }
            false
        }
        content.addView(hs, lp(matchP, wrapC))
        content.addView(dots, lp(matchP, wrapC).apply { topMargin = dp(14) })
        holder.addView(content, FrameLayout.LayoutParams(matchP, matchP))
        host.addView(holder, FrameLayout.LayoutParams(matchP, matchP))
    }

    private fun showScheduleSheet(existing: Store.FocusSchedule?) {
        if (!free()) return
        var name = existing?.name ?: "Evening Study"
        var start = existing?.start ?: 18 * 60
        var end = existing?.end ?: 20 * 60
        var days = existing?.days ?: 31
        var editing = 0

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameInput = input("Name")
        nameInput.setText(name)
        watch(nameInput) { name = it }
        val preview = DayBar(this, pal, fonts, true).apply { set(start, end, true, false) }

        // Start / End tiles: tap one, then spin the wheel
        val startVal = tv(fmt12(start), 20f, fonts.bold, pal.ink).apply { letterSpacing = -0.01f }
        val endVal = tv(fmt12(end), 20f, fonts.bold, pal.ink).apply { letterSpacing = -0.01f }
        fun tile(label: String, v: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            addView(tv(label, 11f, fonts.semi, pal.muted).apply { letterSpacing = 0.14f })
            addView(v, lp(wrapC, wrapC).apply { topMargin = dp(4) })
            press(this)
        }
        val startTile = tile("STARTS", startVal)
        val endTile = tile("ENDS", endVal)
        lateinit var wheel: TimeWheel
        fun paintTiles() {
            listOf(startTile, endTile).forEachIndexed { i, t ->
                val on = editing == i
                t.background = round(if (on) FocusTheme.accentSoft(pal.dark) else FocusTheme.cardFill(pal.dark),
                    18, if (on) FocusCardColors.ORANGE else FocusTheme.cardEdge(pal.dark), dp(if (on) 2 else 1))
            }
        }
        wheel = TimeWheel(this, pal, fonts, start) { m ->
            if (editing == 0) { start = m; startVal.text = fmt12(m) } else { end = m; endVal.text = fmt12(m) }
            preview.set(start, end, true, true)
        }
        startTile.setOnClickListener { editing = 0; paintTiles(); wheel.set(start, true) }
        endTile.setOnClickListener { editing = 1; paintTiles(); wheel.set(end, true) }
        paintTiles()

        val dayChips = ArrayList<TextView>()
        fun paintDays() { dayChips.forEachIndexed { i, c -> setPill(c, ((days shr i) and 1) == 1) } }
        fun fillForm(n: String, st: Int, en: Int, d: Int) {
            name = n; start = st; end = en; days = d
            nameInput.setText(n)
            startVal.text = fmt12(st)
            endVal.text = fmt12(en)
            wheel.set(if (editing == 0) st else en, true)
            preview.set(st, en, true, true)
            paintDays()
        }

        if (existing == null) {
            box.addView(groupLabel("Quick start").apply { setPadding(dp(4), 0, 0, dp(6)) })
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val templates = listOf(
                Triple("Morning Study", 6 * 60, 8 * 60),
                Triple("Evening Study", 18 * 60, 20 * 60),
                Triple("Night", 22 * 60, 6 * 60)
            )
            templates.forEachIndexed { i, (n, st, en) ->
                val c = pill(n, false) { fillForm(n, st, en, if (n == "Night") 127 else 31) }
                c.textSize = 12f
                c.setPadding(dp(4), dp(10), dp(4), dp(10))
                line.addView(c, lp(0, wrapC, 1f).apply { if (i < 2) marginEnd = dp(6) })
            }
            box.addView(line)
        }

        box.addView(groupLabel("Name").apply { setPadding(dp(4), dp(14), 0, dp(6)) })
        box.addView(nameInput, lp(matchP, wrapC))

        box.addView(groupLabel("Time").apply { setPadding(dp(4), dp(14), 0, dp(6)) })
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(startTile, lp(0, wrapC, 1f).apply { marginEnd = dp(10) })
        tiles.addView(endTile, lp(0, wrapC, 1f))
        box.addView(tiles)
        val wheelBox = LinearLayout(this).apply {
            background = round(FocusTheme.cardFill(pal.dark), 22, FocusTheme.cardEdge(pal.dark), dp(1))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        wheelBox.addView(wheel, lp(matchP, wrapC))
        box.addView(wheelBox, lp(matchP, wrapC).apply { topMargin = dp(10) })
        box.addView(preview, lp(matchP, wrapC).apply { topMargin = dp(14) })
        box.addView(note("Overnight windows work too, for example 10:00 PM to 6:00 AM."))

        box.addView(groupLabel("Repeat").apply { setPadding(dp(4), dp(14), 0, dp(6)) })
        val presetLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Weekdays" to 31, "Weekends" to 96, "Every day" to 127).forEachIndexed { i, (label, mask) ->
            val c = pill(label, false) { days = mask; paintDays() }
            c.textSize = 12f
            c.setPadding(dp(4), dp(10), dp(4), dp(10))
            presetLine.addView(c, lp(0, wrapC, 1f).apply { if (i < 2) marginEnd = dp(6) })
        }
        box.addView(presetLine)
        val dayLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        "MTWTFSS".forEachIndexed { i, ch ->
            val c = pill(ch.toString(), ((days shr i) and 1) == 1) {}
            c.textSize = 12f
            c.setPadding(dp(2), dp(10), dp(2), dp(10))
            c.setOnClickListener { days = days xor (1 shl i); paintDays() }
            dayChips.add(c)
            dayLine.addView(c, lp(0, wrapC, 1f).apply { if (i < 6) marginEnd = dp(4) })
        }
        box.addView(dayLine, lp(matchP, wrapC).apply { topMargin = dp(8) })

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
            if (existing == null) orangeSay(Move.TASK, "Schedule saved!", true, 1300L)
        }.apply {
            setTextColor(Color.WHITE)
            background = round(FocusCardColors.ORANGE, 40)
            elevation = dp(2).toFloat()
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
    private var webQuery = ""
    private var sitesInput: EditText? = null
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
        updateBulk()
        // an empty search result says so instead of leaving a blank list
        loadingText?.let { t ->
            if (allApps.isNotEmpty()) {
                if (shown.isEmpty()) { t.text = if (q.isEmpty()) "No apps to show." else "No apps match \u201c$q\u201d."; t.visibility = View.VISIBLE }
                else t.visibility = View.GONE
            }
        }
    }

    private var bulkBtn: TextView? = null

    /** Apps that must stay usable whatever is blocked: dialer, settings, the launcher, system UI, emergency. */
    private fun essentialPkgs(): Set<String> {
        val out = HashSet<String>()
        out.addAll(listOf(
            "com.android.phone", "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
            "com.android.settings", "com.android.systemui", "com.android.contacts", "com.android.emergency",
            "com.miui.home", "com.mi.android.globallauncher", "com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher"
        ))
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            tm?.defaultDialerPackage?.let { out.add(it) }
        } catch (e: Exception) { }
        try {
            val pm = packageManager
            for (i in listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                Intent(Intent.ACTION_DIAL),
                Intent(Settings.ACTION_SETTINGS)
            )) pm.resolveActivity(i, 0)?.activityInfo?.packageName?.let { if (it != "android") out.add(it) }
        } catch (e: Exception) { }
        return out
    }

    private fun updateBulk() {
        val b = bulkBtn ?: return
        val keep = essentialPkgs()
        val targets = shown.filter { it.pkg !in keep }
        b.text = if (targets.isNotEmpty() && targets.all { appIsOn(it.pkg) }) "Unblock all" else "Block all"
    }

    private fun setAppOn(pkg: String, on: Boolean) {
        if (appMode == 0) Store.setAppBlocked(this, pkg, on) else Store.setFocusApp(this, pkg, on)
    }

    private fun blockAllShown() {
        val keep = essentialPkgs()
        val targets = shown.filter { it.pkg !in keep }
        if (targets.isEmpty()) return
        val unblock = targets.all { appIsOn(it.pkg) }
        if (unblock && !free()) return
        val changed = targets.filter { appIsOn(it.pkg) == unblock }.map { it.pkg }
        for (p in changed) setAppOn(p, !unblock)
        applyFilter()
        val n = changed.size
        toast(
            if (unblock) "Unblocked $n app${if (n == 1) "" else "s"}"
            else "Blocked $n app${if (n == 1) "" else "s"}",
            if (unblock) "" else "Phone, Settings and your launcher stay open.",
            "Undo"
        ) {
            if (unblock || free()) {
                for (p in changed) setAppOn(p, unblock)
                applyFilter()
            }
        }
    }

    private inner class AppItem(ctx: Context) : FrameLayout(ctx) {
        private val icon = ImageView(ctx)
        private val name = tv("", 16f, fonts.semi, pal.ink).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END }
        private val toggle = ToggleView(ctx)
        private val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        private var pkg = ""

        init {
            layoutParams = AbsListView.LayoutParams(matchP, wrapC)
            setPadding(0, 0, 0, dp(8))
            tile.background = round(pal.surface, 18, pal.border, dp(1))
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
                updateBulk()
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
            val enabled = appIsOn(a.pkg)
            tile.background = if (enabled) {
                round(FocusTheme.accentSoft(pal.dark), 18, FocusTheme.cardEdge(pal.dark), dp(1))
            } else round(pal.surface, 18, pal.border, dp(1))
            toggle.setOn(enabled, false)
        }
    }

    private fun blocksPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), 0)
        }
        page.addView(display("Blocks", 30f))
        val hc = card().apply { visibility = View.GONE }
        healthCard = hc
        page.addView(hc, lp(matchP, wrapC).apply { topMargin = dp(10) })
        refreshHealth()

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
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(search, lp(0, wrapC, 1f))
        val bulk = pill("Block all", false) { blockAllShown() }.apply {
            setSingleLine(true)
            setPadding(dp(16), 0, dp(16), 0)
        }
        bulkBtn = bulk
        searchRow.addView(bulk, lp(wrapC, dp(50)).apply { marginStart = dp(8) })
        appsPane.addView(searchRow, lp(matchP, wrapC).apply { topMargin = dp(10); bottomMargin = dp(12) })
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

        // what is blocked, at a glance
        val sum = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val cnt = OdometerView(this, pal.ink, fonts.bold, 40f)
        sitesCountView = cnt
        sum.addView(cnt, lp(wrapC, wrapC).apply { marginEnd = dp(16) })
        val sc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sc.addView(tv("Websites blocked", 16f, fonts.semi, pal.ink))
        sc.addView(tv("Closed in every browser, all day", 13f, fonts.regular, pal.muted).apply { setPadding(0, dp(3), 0, 0) })
        sum.addView(sc, lp(0, wrapC, 1f))
        webCol.addView(sum, lp(matchP, wrapC).apply { bottomMargin = dp(14) })

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lateinit var urlInput: EditText
        fun addSite() {
            val q0 = urlInput.text.toString().trim().lowercase()
            val d = Store.cleanDomain(q0) ?: if (q0.length >= 2 && !q0.contains('.') && !q0.contains(' ')) Store.cleanDomain("$q0.com") else null
            if (d == null) {
                toast("Type a site like reddit.com")
                return
            }
            lastAddedSite = d
            Store.addSite(this, d)
            urlInput.setText("")
            refreshSites()
        }
        val curved = CurvedBar(this, pal, fonts, "reddit.com", "Block") { addSite() }
        urlInput = curved.edit
        sitesInput = urlInput
        watch(urlInput) { webQuery = it.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www."); refreshSites() }
        addRow.addView(curved, lp(matchP, wrapC))
        webCol.addView(addRow)

        // live preview of what you are typing, right under the box so the keyboard never hides it
        val pvBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewBox = pvBox
        webCol.addView(pvBox, lp(matchP, wrapC).apply { topMargin = dp(12) })

        // one-tap popular sites
        val pl = groupLabel("Popular").apply { setPadding(dp(4), dp(16), 0, dp(8)) }
        popLabel = pl
        webCol.addView(pl)
        val sug = FlowLayout(this, dp(8), dp(8))
        suggestBox = sug
        webCol.addView(sug, lp(matchP, wrapC))

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sitesBox = box
        webCol.addView(box, lp(matchP, wrapC).apply { topMargin = dp(16) })
        webCol.addView(note("Works inside Chrome, Brave, Edge, Firefox, Samsung Internet, Opera, Vivaldi, DuckDuckGo and most other browsers by reading the address bar."))
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
        if (lastSiteCount != sites.size) { sitesCountView?.setValue(sites.size, lastSiteCount >= 0); lastSiteCount = sites.size }

        // popular chips (the ones already blocked drop out)
        suggestBox?.let { sug ->
            sug.removeAllViews()
            val popular = listOf("instagram.com", "youtube.com", "x.com", "reddit.com", "facebook.com", "tiktok.com", "netflix.com", "twitch.tv", "snapchat.com", "pinterest.com")
            val q = webQuery.substringBefore('/')
            val left = popular.filter { it !in sites && (q.isEmpty() || it.contains(q)) }
            left.forEachIndexed { i, dom ->
                val chip = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = round(pal.surface, 40, pal.border, dp(1))
                    setPadding(dp(8), dp(7), dp(16), dp(7))
                    isClickable = true
                    press(this)
                    setOnClickListener {
                        if (free()) {
                            lastAddedSite = dom
                            Store.addSite(this@MainActivity, dom)
                            refreshSites()
                        }
                    }
                }
                chip.addView(SiteLogo(this, dom, pal, fonts), lp(dp(26), dp(26)).apply { marginEnd = dp(8) })
                chip.addView(tv(dom, 13f, fonts.semi, pal.ink))
                sug.addView(chip, lp(wrapC, wrapC).apply { if (i < left.size - 1) marginEnd = dp(8) })
            }
            val vis = if (left.isEmpty()) View.GONE else View.VISIBLE
            sug.visibility = vis
            popLabel?.visibility = vis
        }

        // live preview while typing: what will be blocked, with its logo
        val pvb = previewBox
        pvb?.removeAllViews()
        val cand = Store.cleanDomain(webQuery) ?: if (webQuery.length >= 2 && !webQuery.contains('.') && !webQuery.contains(' ')) Store.cleanDomain("$webQuery.com") else null
        if (cand != null && cand !in sites) {
            val pv = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(pal.canvas, 24, pal.ink, dp(2))
                setPadding(dp(14), dp(12), dp(12), dp(12))
            }
            pv.addView(SiteLogo(this, cand, pal, fonts), lp(dp(40), dp(40)).apply { marginEnd = dp(14) })
            val pt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            pt.addView(tv(cand, 16f, fonts.semi, pal.ink))
            pt.addView(tv("Tap Block to add", 12f, fonts.regular, pal.muted).apply { setPadding(0, dp(2), 0, 0) })
            pv.addView(pt, lp(0, wrapC, 1f))
            pv.addView(pill("Block", true) {
                if (free()) {
                    lastAddedSite = cand
                    Store.addSite(this, cand)
                    webQuery = ""
                    sitesInput?.setText("")
                    refreshSites()
                }
            }.apply { textSize = 13f; setPadding(dp(18), dp(9), dp(18), dp(9)) })
            pvb?.addView(pv, lp(matchP, wrapC))
        }

        if (sites.isEmpty()) {
            box.addView(tv("No websites yet", 18f, fonts.semi, pal.ink, Gravity.CENTER).apply { setPadding(0, dp(30), 0, dp(6)) }, lp(matchP, wrapC))
            box.addView(tv("Type a site above, or tap one of the popular ones.", 14f, fonts.regular, pal.muted, Gravity.CENTER), lp(matchP, wrapC))
            return
        }
        sites.forEachIndexed { i, d ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(pal.surface, 24, pal.border, dp(1))
                setPadding(dp(14), dp(12), dp(12), dp(12))
            }
            row.addView(SiteLogo(this, d, pal, fonts), lp(dp(40), dp(40)).apply { marginEnd = dp(14) })
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(tv(d, 16f, fonts.semi, pal.ink))
            txt.addView(tv("Blocked in all browsers", 12f, fonts.regular, pal.muted).apply { setPadding(0, dp(2), 0, 0) })
            row.addView(txt, lp(0, wrapC, 1f))
            row.addView(tv("✕", 14f, fonts.semi, pal.muted, Gravity.CENTER).apply {
                background = round(pal.canvas, 40, pal.border, dp(1))
                isClickable = true
                press(this)
                setOnClickListener {
                    if (free()) {
                        Store.removeSite(this@MainActivity, d)
                        refreshSites()
                        toast("Removed", d, "Undo") {
                            lastAddedSite = d
                            Store.addSite(this@MainActivity, d)
                            refreshSites()
                        }
                    }
                }
            }, lp(dp(36), dp(36)))
            box.addView(row, lp(matchP, wrapC).apply { bottomMargin = dp(8) })
            if (d == lastAddedSite) {
                lastAddedSite = null
                row.alpha = 0f
                row.translationY = dp(12).toFloat()
                row.scaleX = 0.97f
                row.animate().alpha(1f).translationY(0f).scaleX(1f).setDuration(520)
                    .setInterpolator(SpringInterpolator(14f, 0.8f, 0.9f)).start()
            }
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
        // hero: today's count, how it compares with yesterday, and the last two weeks as a line
        val hero = card().apply { setPadding(dp(20), dp(18), dp(20), dp(8)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(tv("Blocked today", 14f, fonts.medium, pal.muted), lp(0, wrapC, 1f))
        val todayN = Store.today(this)
        val yest = Store.blockedOn(this, 1)
        val diff = todayN - yest
        val delta = when {
            todayN == 0 && yest == 0 -> "Clean so far"
            diff == 0 -> "Same as yesterday"
            diff > 0 -> "+$diff vs yesterday"
            else -> "−${-diff} vs yesterday"
        }
        top.addView(tv(delta, 12f, fonts.semi, pal.ink, Gravity.CENTER).apply {
            background = round(pal.canvas, 40, pal.border, dp(1))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        })
        hero.addView(top, lp(matchP, wrapC))
        hero.addView(OdometerView(this, pal.ink, fonts.bold, 64f).apply { setValue(todayN) }, lp(wrapC, wrapC).apply { topMargin = dp(8) })
        hero.addView(BlockSpark(this, pal, (13 downTo 0).map { Store.blockedOn(this, it) }), lp(matchP, wrapC).apply { topMargin = dp(10) })
        col.addView(hero, lp(matchP, wrapC).apply { topMargin = dp(12) })

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        listOf(week to "This week", Store.total(this) to "All time", Store.reclaimedMinutes(this) to "Min reclaimed")
            .forEachIndexed { i, p ->
                val tile = card().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
                tile.addView(statTile(p.first, p.second))
                tiles.addView(tile, lp(0, wrapC, 1f).apply { if (i > 0) marginStart = dp(8) })
            }
        col.addView(tiles, lp(matchP, wrapC).apply { topMargin = dp(10) })

        col.addView(sectionTitle("Last 7 days"))
        val c1 = card().apply { setPadding(dp(20), dp(16), dp(20), dp(18)) }
        val vals = (6 downTo 0).map { Store.blockedOn(this, it) }
        val labels = (6 downTo 0).map { Store.dayLetter(it) }
        c1.addView(BlockBars(this, pal, fonts, vals, labels, 6), lp(matchP, wrapC))
        c1.addView(tv(summaryText(), 14f, fonts.regular, pal.muted).apply { setPadding(0, dp(12), 0, 0) })
        col.addView(c1, lp(matchP, wrapC))

        col.addView(sectionTitle("When you scroll most"))
        val c2 = card().apply { setPadding(dp(20), dp(20), dp(20), dp(18)) }
        c2.addView(HeatStrip(this, pal, fonts, Store.blocksByHour(this)), lp(matchP, wrapC))
        c2.addView(tv(peakText(), 14f, fonts.regular, pal.muted).apply { setPadding(0, dp(10), 0, 0) })
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
        try { addCardCollection(col) } catch (e: Throwable) { }
    }

    // ---------- Card collection (Stats, below everything else) ----------
    private val artCache = HashMap<String, Bitmap>()

    private fun cardArt(i: Int, at: Long, wPx: Int): Bitmap {
        val key = "$i|$at|${pal.dark}|$wPx"
        artCache[key]?.let { return it }
        val t = Rarity.of(i)
        val unlocked = at > 0L
        val data = FocusCardData(
            badge = "Focus card",
            status = "Done",
            eyebrow = if (unlocked) "COLLECTED" else "LOCKED",
            big = if (unlocked) t.name else "",
            line = if (unlocked) "Unlocked " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(java.util.Date(at)) else "",
            stats = listOf(
                "Tier" to "${i + 1} of ${Cards.count}",
                "Unlocked" to (if (unlocked) SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(at)) else "–"),
                "Goal" to Cards.goalLabel(i)
            ),
            idLabel = "Get Shortstop",
            idValue = APP_LINK_SHORT,
            tier = i
        )
        val bmp = try { ReflectiveCard(this, fonts, data).renderFlat(wPx) } catch (e: Throwable) {
            Bitmap.createBitmap(wPx, (wPx * 1.3f).toInt(), Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(t.c2) }
        }
        if (artCache.size > 24) artCache.clear()
        artCache[key] = bmp
        return bmp
    }

    private fun addCardCollection(col: LinearLayout) {
        val fresh = Cards.sync(this)
        val got = Cards.unlockedCount(this)
        val n = Cards.count
        val dark = pal.dark
        val brown = FocusTheme.title(dark)
        val soft = Color.argb(if (dark) 150 else 160, Color.red(brown), Color.green(brown), Color.blue(brown))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(FocusTheme.cardFill(dark), 24, FocusTheme.cardEdge(dark), dp(1))
            setPadding(dp(16), dp(18), dp(16), dp(10))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("Card Collection", 20f, fonts.bold, brown).apply { letterSpacing = -0.01f }, lp(0, wrapC, 1f))
        head.addView(tv("$got/$n collected", 13f, fonts.semi, FocusTheme.orange))
        panel.addView(head)
        val bar = OrangeBar(this, FocusTheme.cardEdge(dark))
        panel.addView(bar, lp(matchP, dp(6)).apply { topMargin = dp(12); bottomMargin = dp(16) })
        bar.setProgress(got.toFloat() / n, true)

        val wPx = ((resources.displayMetrics.widthPixels - dp(40) - dp(32) - dp(12)) / 2f).toInt().coerceAtLeast(dp(120))
        var rowBox: LinearLayout? = null
        for (i in 0 until n) {
            if (i % 2 == 0) {
                rowBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                panel.addView(rowBox, lp(matchP, wrapC).apply { bottomMargin = dp(14) })
            }
            val at = Cards.unlockedAt(this, i)
            val unlocked = at > 0L
            val tile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val art = CardArtView(this, cardArt(i, at, wPx), !unlocked)
            val playNow = unlocked && !Cards.seen(this, i)
            if (playNow) art.holdLocked()
            tile.addView(art, lp(matchP, wrapC))
            tile.addView(tv(Rarity.of(i).name, 15f, fonts.semi, brown, Gravity.CENTER).apply { setPadding(0, dp(10), 0, 0) }, lp(matchP, wrapC))
            tile.addView(tv(if (unlocked) "Unlocked" else Cards.shortRequirement(i), 12f, fonts.regular, if (unlocked) FocusTheme.orange else soft, Gravity.CENTER)
                .apply { setPadding(0, dp(2), 0, 0) }, lp(matchP, wrapC))
            tile.isClickable = true
            tile.setOnClickListener { showCardSheet(i) }
            press(tile)
            if (playNow) {
                art.postDelayed({
                    if (art.isShown) { Cards.markSeen(this, i); art.playReveal() }
                    else art.invalidate()
                }, 600)
            }
            rowBox!!.addView(tile, lp(0, wrapC, 1f).apply { if (i % 2 == 0) marginEnd = dp(12) })
        }
        col.addView(panel, lp(matchP, wrapC).apply { topMargin = dp(30) })
    }

    private fun showCardSheet(i: Int) {
        val t = Rarity.of(i)
        val at = Cards.unlockedAt(this, i)
        val dark = pal.dark
        val brown = FocusTheme.title(dark)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(2), 0, dp(6)) }
        if (at > 0L) {
            val data = FocusCardData(
                badge = "Focus card", status = "Done", eyebrow = "COLLECTED", big = t.name,
                line = "Unlocked " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(java.util.Date(at)),
                stats = listOf("Tier" to "${i + 1} of ${Cards.count}", "Goal" to Cards.goalLabel(i), "Rarity" to t.name),
                idLabel = "Get Shortstop", idValue = APP_LINK_SHORT, tier = i
            )
            box.addView(ReflectiveCard(this, fonts, data), lp(matchP, wrapC))
        } else {
            val w = dp(190)
            box.addView(CardArtView(this, cardArt(i, 0L, w), true), lp(w, wrapC).apply { topMargin = dp(8); bottomMargin = dp(6) })
        }
        box.addView(tv(t.name, 24f, fonts.bold, brown, Gravity.CENTER).apply { setPadding(0, dp(8), 0, 0) }, lp(matchP, wrapC))
        box.addView(tv("Rarity ${i + 1} of ${Cards.count}", 13f, fonts.regular, pal.muted, Gravity.CENTER).apply { setPadding(0, dp(2), 0, dp(12)) }, lp(matchP, wrapC))
        box.addView(tv(Cards.requirement(i), 15f, fonts.medium, pal.ink, Gravity.CENTER), lp(matchP, wrapC))
        if (at > 0L) {
            box.addView(tv("Unlocked on " + SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault()).format(java.util.Date(at)), 13f, fonts.semi, FocusTheme.orange, Gravity.CENTER)
                .apply { setPadding(0, dp(12), 0, 0) }, lp(matchP, wrapC))
        } else {
            val (cur, target) = Cards.progress(this, i)
            val isHours = Cards.rules[i].kind == Cards.Kind.HOURS
            val label = if (isHours) "${fmtMin(cur)} of ${fmtMin(target)}" else "$cur of $target days"
            box.addView(tv(label, 13f, fonts.semi, FocusTheme.orange, Gravity.CENTER).apply { setPadding(0, dp(14), 0, dp(8)) }, lp(matchP, wrapC))
            val bar = OrangeBar(this, FocusTheme.cardEdge(dark))
            box.addView(bar, lp(matchP, dp(8)).apply { marginStart = dp(24); marginEnd = dp(24) })
            bar.post { bar.setProgress(cur.toFloat() / target, true) }
        }
        showSheet(t.name + " card", box)
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
                names.addView(tv(r.label, 16f, fonts.semi, pal.ink).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
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
        if (resources.displayMetrics.widthPixels > dp(640)) {
            col.layoutParams = FrameLayout.LayoutParams(dp(600), wrapC, Gravity.CENTER_HORIZONTAL)
        }
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

    /** Times follow the phone's 12/24-hour setting, the same as the picker that edits them. */
    private fun fmt(minutes: Int): String {
        val h = (minutes / 60) % 24; val m = minutes % 60
        if (DateFormat.is24HourFormat(this)) return String.format("%02d:%02d", h, m)
        val x = h % 12
        return String.format("%d:%02d %s", if (x == 0) 12 else x, m, if (h < 12) "AM" else "PM")
    }

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
            top.addView(smallBtn("✕") {
                if (free()) {
                    Store.removeWindow(this, i); refill()
                    toast("Window removed", "${fmt(w.start)} to ${fmt(w.end)}", "Undo") { Store.addWindow(this, w); refill() }
                }
            }.apply { contentDescription = "Remove window ${fmt(w.start)} to ${fmt(w.end)}" })
            c.addView(top)
            val days = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
            "MTWTFSS".forEachIndexed { d, ch ->
                val on = ((w.days shr d) and 1) == 1
                val chip = pill(ch.toString(), on) {}
                chip.contentDescription = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")[d]
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

    // ---------- Settings-only building blocks (flat grouped rows) ----------
    private val EMBER = Color.parseColor("#FF5A1F")

    private fun reducedMotion(): Boolean =
        try { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: Exception) { false }

    /** Subtle fade reveal (react-bits FadeContent): opacity only, skipped when animations are off. */
    private fun fadeReveal(v: View, show: Boolean) {
        v.animate().cancel()
        if (!show) { v.visibility = View.GONE; v.alpha = 1f; return }
        v.visibility = View.VISIBLE
        if (reducedMotion()) { v.alpha = 1f; return }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator(1.4f)).start()
    }

    private fun sCard(): LinearLayout = GlowCard(
        this, Color.argb(if (pal.dark) 26 else 20, Color.red(pal.ink), Color.green(pal.ink), Color.blue(pal.ink)),
        dp(16).toFloat(), dp(170).toFloat()
    ).apply {
        orientation = LinearLayout.VERTICAL
        ambient = pal.dark
        background = round(pal.surface, 16, pal.border, dp(1))
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
    }

    private fun sDiv() = View(this).apply { setBackgroundColor(pal.border) }

    private fun LinearLayout.sAdd(v: View) {
        if (childCount > 0) addView(sDiv(), lp(matchP, dp(1)).apply { marginStart = dp(16) })
        addView(v, lp(matchP, wrapC))
    }

    /** Section header: small ember tick, uppercase label. */
    private fun sSection(label: String): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(26), 0, dp(10))
        }
        val tick = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#FFB27A"), Color.parseColor("#FF6A2B"))).apply { cornerRadius = dp(2).toFloat() }
        }
        head.addView(tick, lp(dp(10), dp(3)).apply { marginEnd = dp(8) })
        head.addView(tv(label.uppercase(), 11.5f, fonts.semi, pal.muted).apply { letterSpacing = 0.12f })
        box.addView(head)
        return box
    }

    private fun sRow(title: String, sub: String, trailing: View?, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 15f, fonts.semi, pal.ink))
        if (sub.isNotEmpty()) col.addView(tv(sub, 12.5f, fonts.regular, pal.muted).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, lp(0, wrapC, 1f).apply { marginEnd = dp(12) })
        if (trailing != null) row.addView(trailing)
        if (onClick != null) {
            row.isClickable = true
            row.isFocusable = true
            row.foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(if (pal.dark) 34 else 22, Color.red(pal.ink), Color.green(pal.ink), Color.blue(pal.ink))),
                null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            row.setOnClickListener { onClick() }
        }
        return row
    }

    private fun sTrail(first: View?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (first != null) addView(first)
        addView(tv("›", 20f, fonts.regular, pal.muted).apply { setPadding(dp(8), 0, 0, 0) })
    }

    private fun sLink(title: String, sub: String, value: String? = null, onClick: () -> Unit): View =
        sRow(title, sub, sTrail(value?.let { tv(it, 14f, fonts.medium, pal.muted) }), onClick)

    private fun sToggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val t = ToggleView(this)
        t.brand = true
        t.setOn(checked, false)
        t.listener = onChange
        return sRow(title, sub, t) { t.performClick() }
    }

    /** Granted shows a quiet outline, missing shows ember so it draws the eye. */
    private fun sStatus(ok: Boolean): TextView = tv(if (ok) "On" else "Off", 12f, fonts.semi, if (ok) pal.ink else EMBER).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = round(Color.TRANSPARENT, 20, if (ok) pal.border else Color.argb(120, 255, 90, 31), dp(1))
    }

    /** Segmented control: the filled pill glides to the chosen cell. Returns the view and a setter. */
    private fun sSeg(options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Boolean): Pair<View, (Int) -> Unit> {
        val track = FrameLayout(this).apply {
            background = round(pal.canvas, 12, pal.border, dp(1))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val ind = View(this).apply { background = round(pal.ink, 9) }
        track.addView(ind, FrameLayout.LayoutParams(0, wrapC))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        track.addView(row, FrameLayout.LayoutParams(matchP, wrapC))
        val cells = ArrayList<TextView>()
        var cur = options.indexOfFirst { it.first == selected }
        var glide: ValueAnimator? = null
        fun place(animate: Boolean) {
            val cw = row.width / options.size
            if (cw <= 0) return
            ind.layoutParams = (ind.layoutParams as FrameLayout.LayoutParams).apply { width = cw; height = row.height }
            val to = if (cur < 0) 0f else (cur * cw).toFloat()
            val from = ind.translationX
            glide?.cancel()
            ind.visibility = if (cur < 0) View.INVISIBLE else View.VISIBLE
            val fromC = cells.map { it.currentTextColor }
            val toC = cells.indices.map { if (it == cur) pal.canvas else pal.muted }
            if (!animate || reducedMotion()) {
                ind.translationX = to
                cells.forEachIndexed { i, c -> c.setTextColor(toC[i]) }
                return
            }
            glide = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380
                interpolator = SpringInterpolator(16f, 0.8f, 0.38f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    ind.translationX = from + (to - from) * f
                    val g = f.coerceIn(0f, 1f)
                    cells.forEachIndexed { i, c -> c.setTextColor(android.animation.ArgbEvaluator().evaluate(g, fromC[i], toC[i]) as Int) }
                }
                start()
            }
        }
        options.forEachIndexed { i, (value, name) ->
            val c = tv(name, 13f, fonts.semi, if (i == cur) pal.canvas else pal.muted, Gravity.CENTER).apply {
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { if (onPick(value)) { cur = i; place(true) } }
            }
            cells.add(c)
            row.addView(c, lp(0, wrapC, 1f))
        }
        row.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> if (r - l > 0) place(false) }
        val wrap = LinearLayout(this).apply { setPadding(dp(16), dp(10), dp(16), dp(10)); addView(track, lp(matchP, wrapC)) }
        return wrap to { v: Int -> cur = options.indexOfFirst { it.first == v }; place(true) }
    }

    private fun sStepper(caption: (Int) -> String, start: Int, min: Int, max: Int, onSet: (Int) -> Unit): Pair<View, (Int) -> Unit> {
        var v = start
        val cap = tv(caption(v), 14.5f, fonts.medium, pal.ink)
        fun setV(n: Int) { v = n.coerceIn(min, max); cap.text = caption(v) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        row.addView(cap, lp(0, wrapC, 1f))
        row.addView(stepBtn(false) { if (free()) { setV(v - 1); onSet(v) } })
        row.addView(stepBtn(true) { if (free()) { setV(v + 1); onSet(v) } }, lp(dp(46), dp(38)).apply { marginStart = dp(8) })
        return row to { n: Int -> setV(n) }
    }

    private fun fillSettings() {
        val col = settingsCol ?: return
        val keepY = settingsScroll?.scrollY ?: 0
        col.removeAllViews()

        col.addView(display("Settings", 30f))

        // Focus
        val sFocus = sSection("Focus")
        val focus = sCard()
        focus.sAdd(sLink("Ultra Focus", "Lock, study mode, guards") { showUltraSheet() })
        focus.sAdd(sLink("Daily focus goal", "Your target each day", fmtMin(Store.goalMin(this))) { showGoalSheet() })
        sFocus.addView(focus, lp(matchP, wrapC))
        col.addView(sFocus)

        // Hold to open
        val sHold = sSection("Hold to open")
        val hold = sCard()
        val holdHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holdHolder.visibility = if (Store.holdOn(this)) View.VISIBLE else View.GONE
        hold.sAdd(sToggle("Hold to open", "Blocked screens ask you to hold first", Store.holdOn(this)) {
            Store.setHoldOn(this, it)
            fadeReveal(holdHolder, it)
        })
        var stepSet: (Int) -> Unit = {}
        val (seg, segSet) = sSeg(listOf(10 to "10s", 30 to "30s", 60 to "60s", 180 to "180s"), Store.holdSeconds(this)) {
            if (free()) { Store.setHoldSeconds(this, it); stepSet(it); true } else false
        }
        val (holdStep, hs) = sStepper({ "Hold for $it seconds" }, Store.holdSeconds(this), 2, 180) {
            Store.setHoldSeconds(this, it); segSet(it)
        }
        stepSet = hs
        holdHolder.addView(sDiv(), lp(matchP, dp(1)).apply { marginStart = dp(16) })
        holdHolder.addView(seg, lp(matchP, wrapC))
        holdHolder.addView(sDiv(), lp(matchP, dp(1)).apply { marginStart = dp(16) })
        holdHolder.addView(holdStep, lp(matchP, wrapC))
        holdHolder.addView(sDiv(), lp(matchP, dp(1)).apply { marginStart = dp(16) })
        holdHolder.addView(sStepper({ "Then open for $it min" }, Store.passMinutes(this), 1, 180) { Store.setPassMinutes(this, it) }.first, lp(matchP, wrapC))
        holdHolder.addView(sDiv(), lp(matchP, dp(1)).apply { marginStart = dp(16) })
        holdHolder.addView(sToggle("Longer each time", "+2 seconds for every pass today", Store.progressive(this)) {
            Store.setProgressive(this, it)
        }, lp(matchP, wrapC))
        holdHolder.addView(sDiv(), lp(matchP, dp(1)).apply { marginStart = dp(16) })
        holdHolder.addView(HoldPill(this, pal, fonts, { Store.holdSeconds(this) }) {
            toast("That is the hold", "This is what the wall asks for.")
        }, lp(matchP, wrapC).apply { setMargins(dp(16), dp(14), dp(16), dp(14)) })
        hold.addView(holdHolder, lp(matchP, wrapC))
        sHold.addView(hold, lp(matchP, wrapC))

        sHold.addView(tv("Message on the wall", 13f, fonts.semi, pal.muted).apply { setPadding(dp(4), dp(18), 0, dp(8)) })
        val msg = input("Why are you here?")
        msg.background = round(pal.surface, 14, pal.border, dp(1))
        msg.setText(Store.message(this))
        watch(msg) { Store.setMessage(this, it) }
        sHold.addView(msg, lp(matchP, wrapC))
        col.addView(sHold)

        // Routine
        val sRoutine = sSection("Routine")
        val routine = sCard()
        val schedHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        fun refill() {
            schedHolder.removeAllViews()
            schedHolder.addView(scheduleEditor { refill() })
        }
        refill()
        schedHolder.visibility = if (Store.scheduleOn(this)) View.VISIBLE else View.GONE
        routine.sAdd(sToggle("Run on a schedule", "Off means always-on blocking runs all day", Store.scheduleOn(this)) {
            Store.setScheduleOn(this, it)
            fadeReveal(schedHolder, it)
        })
        routine.addView(schedHolder, lp(matchP, wrapC))
        sRoutine.addView(routine, lp(matchP, wrapC))
        col.addView(sRoutine)

        // Pause (weakens protection, so it is set apart in ember)
        val sPause = sSection("Pause blocking")
        val snooze = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5 to "5 min", 15 to "15 min", 60 to "1 hour").forEachIndexed { i, (mins, name) ->
            val b = tv(name, 14f, fonts.semi, EMBER, Gravity.CENTER).apply {
                setPadding(dp(8), dp(13), dp(8), dp(13))
                background = round(Color.TRANSPARENT, 14, Color.argb(130, 255, 90, 31), dp(1))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (free()) {
                        Store.setSnooze(this@MainActivity, System.currentTimeMillis() + mins * 60_000L)
                        toast("Always-on blocking paused for $name")
                    }
                }
            }
            press(b)
            snooze.addView(b, lp(0, wrapC, 1f).apply { if (i < 2) marginEnd = dp(8) })
        }
        sPause.addView(snooze, lp(matchP, wrapC))
        sPause.addView(note("Always-on blocking switches itself back on afterwards."))
        col.addView(sPause)

        // Extras
        val sExtras = sSection("Extras")
        val extras = sCard()
        extras.sAdd(sToggle("Skip YouTube ads", "Taps the Skip button for you", Store.autoSkip(this)) { Store.setAutoSkip(this, it) })
        extras.sAdd(sToggle("Guard Shortstop itself", "Hold to open before settings change", Store.selfLock(this)) { Store.setSelfLock(this, it) })
        sExtras.addView(extras, lp(matchP, wrapC))
        col.addView(sExtras)

        // Appearance
        val sLook = sSection("Appearance")
        val look = sCard()
        look.addView(sSeg(listOf(0 to "System", 1 to "Light", 2 to "Dark"), Store.theme(this)) {
            Store.setTheme(this, it)
            ui.post { buildAll() }
            true
        }.first, lp(matchP, wrapC))
        sLook.addView(look, lp(matchP, wrapC))
        col.addView(sLook)

        // Permissions and help
        val sPerm = sSection("Permissions and help")
        val fix = sCard()
        fix.sAdd(sLink("Show me around", "Meet Orange and replay the quick tour") { showTour() })
        // DEVELOPER ONLY: remove this one line (and showAnimationShowcase) before a production release.
        // only in debuggable (test) builds, so a store release never shows a developer tool
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            fix.sAdd(sLink("Animation showcase", "Developer preview of every Orange animation. Saves nothing.") { showAnimationShowcase() })
        fix.sAdd(sRow("Accessibility service", "Needed to block Shorts and apps", sTrail(sStatus(serviceOn()))) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        fix.sAdd(sRow("Notification access", "Lets Shortstop see notifications", sTrail(sStatus(notifAccess()))) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        fix.sAdd(sRow("Usage access", "For screen time and stats", sTrail(sStatus(usageGranted()))) { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        fix.sAdd(sLink("App info", "Allow restricted settings from the ⋮ menu") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        fix.sAdd(sLink("Battery optimisation", "Stop the phone from closing Shortstop") {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        val brand = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).lowercase()
        if (brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) fix.sAdd(sLink("Xiaomi / Redmi autostart", "Let Shortstop run in the background") {
            try {
                startActivity(Intent().setComponent(ComponentName(
                    "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            } catch (e: Exception) {
                toast("Open Security > Permissions > Autostart and switch Shortstop on.")
            }
        })
        sPerm.addView(fix, lp(matchP, wrapC))
        sPerm.addView(note("Shortstop runs fully offline. Nothing leaves your phone."))
        col.addView(sPerm)

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
