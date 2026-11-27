package com.shortstop.app

import android.content.Context
import java.util.Calendar
import java.util.Locale

/** A blocking window. start/end are minutes after midnight. days is a bitmask, bit0 = Monday ... bit6 = Sunday. */
data class Window(val start: Int, val end: Int, val days: Int)

/** SharedPreferences wrapper shared by the UI and the accessibility service. */
object Store {
    val SITES = linkedMapOf(
        "youtube" to "YouTube Shorts",
        "instagram" to "Instagram Reels",
        "facebook" to "Facebook Reels",
        "tiktok" to "TikTok",
        "snapchat" to "Snapchat Spotlight",
        "twitter" to "X video feed",
        "linkedin" to "LinkedIn video",
        "pinterest" to "Pinterest video",
        "reddit" to "Reddit video"
    )

    /** Whole-app names, used for the "block entire app" rows. */
    val APPS = linkedMapOf(
        "youtube" to "YouTube", "instagram" to "Instagram", "facebook" to "Facebook",
        "tiktok" to "TikTok", "snapchat" to "Snapchat", "twitter" to "X",
        "linkedin" to "LinkedIn", "pinterest" to "Pinterest", "reddit" to "Reddit"
    )

    /** Extra parts of an app that can be blocked on their own. Off until switched on. */
    data class Section(val key: String, val site: String, val title: String, val sub: String)

    val SECTIONS = listOf(
        Section("instagram_explore", "instagram", "Instagram Explore", "Search and Explore tab"),
        Section("instagram_stories", "instagram", "Instagram Stories", "Story viewer"),
        Section("instagram_comments", "instagram", "Instagram comments", "Comment threads"),
        Section("youtube_home", "youtube", "YouTube home feed", "Home tab"),
        Section("instagram_home", "instagram", "Instagram home feed", "Home tab"),
        Section("facebook_home", "facebook", "Facebook home feed", "Home tab"),
        Section("facebook_stories", "facebook", "Facebook Stories", "Story viewer"),
        Section("snapchat_stories", "snapchat", "Snapchat Stories", "Stories tab"),
        Section("snapchat_discover", "snapchat", "Snapchat Discover", "Discover tab")
    )

    fun wholeKey(site: String) = "whole_$site"

    /** Every key that can fire for an app: its feed, its sections, and the whole app. */
    fun keysFor(site: String): List<String> =
        listOf(site) + SECTIONS.filter { it.site == site }.map { it.key } + wholeKey(site)

    fun label(key: String): String = when {
        SITES.containsKey(key) -> SITES[key]!!
        key.startsWith("whole_") -> (APPS[key.removePrefix("whole_")] ?: "App") + " (whole app)"
        else -> SECTIONS.firstOrNull { it.key == key }?.title ?: "Short video"
    }

    private val PACKAGES = mapOf(
        "com.google.android.youtube" to "youtube",
        "com.instagram.android" to "instagram",
        "com.facebook.katana" to "facebook",
        "com.facebook.lite" to "facebook",
        "com.zhiliaoapp.musically" to "tiktok",
        "com.ss.android.ugc.trill" to "tiktok",
        "com.snapchat.android" to "snapchat",
        "com.twitter.android" to "twitter",
        "com.linkedin.android" to "linkedin",
        "com.pinterest" to "pinterest",
        "com.reddit.frontpage" to "reddit"
    )

    fun siteFor(pkg: String): String? = PACKAGES[pkg]

    fun prefs(c: Context) = p(c)

    private fun p(c: Context) = c.getSharedPreferences("shortstop", Context.MODE_PRIVATE)

    private fun keyOf(d: Calendar) = "${d.get(Calendar.YEAR)}_${d.get(Calendar.DAY_OF_YEAR)}"
    private fun daysAgo(n: Int): Calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }

    // ---- master switches ----
    fun enabled(c: Context) = p(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()

    fun site(c: Context, s: String) = p(c).getBoolean("site_$s", SITES.containsKey(s))
    fun setSite(c: Context, s: String, v: Boolean) = p(c).edit().putBoolean("site_$s", v).apply()

    fun snoozeUntil(c: Context) = p(c).getLong("snooze", 0L)
    fun setSnooze(c: Context, until: Long) = p(c).edit().putLong("snooze", until).apply()

    /** 0 = follow system, 1 = light, 2 = dark */
    fun theme(c: Context) = p(c).getInt("theme", 0)
    fun setTheme(c: Context, v: Int) = p(c).edit().putInt("theme", v).apply()

    // ---- schedule ----
    fun scheduleOn(c: Context) = p(c).getBoolean("sched_on", false)
    fun setScheduleOn(c: Context, v: Boolean) = p(c).edit().putBoolean("sched_on", v).apply()

    fun windows(c: Context): List<Window> {
        val raw = p(c).getString("windows", "") ?: ""
        return raw.split(';').mapNotNull { part ->
            val n = part.split(',').mapNotNull { it.toIntOrNull() }
            if (n.size == 3) Window(n[0], n[1], n[2]) else null
        }
    }

    fun setWindows(c: Context, list: List<Window>) {
        p(c).edit().putString("windows", list.joinToString(";") { "${it.start},${it.end},${it.days}" }).apply()
    }

    fun addWindow(c: Context, w: Window) {
        val l = windows(c)
        if (l.size < 6) setWindows(c, l + w)
    }

    fun updateWindow(c: Context, i: Int, w: Window) {
        val l = windows(c).toMutableList()
        if (i in l.indices) { l[i] = w; setWindows(c, l) }
    }

    fun removeWindow(c: Context, i: Int) {
        val l = windows(c).toMutableList()
        if (i in l.indices) { l.removeAt(i); setWindows(c, l) }
    }

    /** True when blocking should be running right now. Always true when no schedule is set. */
    private fun inWindow(startM: Int, endM: Int, days: Int, now: Calendar): Boolean {
        val dow = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val prev = (dow + 6) % 7
        val t = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val today = ((days shr dow) and 1) == 1
        val yesterday = ((days shr prev) and 1) == 1
        return when {
            startM == endM -> false
            startM < endM -> today && t >= startM && t < endM
            else -> (today && t >= startM) || (yesterday && t < endM)
        }
    }

    fun inSchedule(c: Context, now: Calendar = Calendar.getInstance()): Boolean {
        if (!scheduleOn(c)) return true
        val dow = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon = 0 ... Sun = 6
        val prev = (dow + 6) % 7
        val t = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return windows(c).any { w ->
            val today = ((w.days shr dow) and 1) == 1
            val yesterday = ((w.days shr prev) and 1) == 1
            when {
                w.start == w.end -> false
                w.start < w.end -> today && t >= w.start && t < w.end
                else -> (today && t >= w.start) || (yesterday && t < w.end) // crosses midnight
            }
        }
    }

    fun isActive(c: Context, s: String) =
        enabled(c) && System.currentTimeMillis() >= snoozeUntil(c) &&
            (s.startsWith("app:") || s.startsWith("web:") || site(c, s)) && inSchedule(c)

    // ---- daily allowance (minutes of short video allowed per app per day) ----
    fun allowMinutes(c: Context, s: String) = p(c).getInt("allow_$s", 0)
    fun setAllowMinutes(c: Context, s: String, v: Int) = p(c).edit().putInt("allow_$s", v).apply()
    fun allowSeconds(c: Context, s: String) = allowMinutes(c, s) * 60

    private fun useKey(s: String) = "use_${s}_${keyOf(Calendar.getInstance())}"
    fun usedSeconds(c: Context, s: String) = p(c).getInt(useKey(s), 0)
    fun addUsage(c: Context, s: String, sec: Int) =
        p(c).edit().putInt(useKey(s), usedSeconds(c, s) + sec).apply()

    fun usedTodaySeconds(c: Context): Int = SITES.keys.map { usedSeconds(c, it) }.sum()

    // ---- stats ----
    fun bump(c: Context) {
        val sp = p(c)
        val now = Calendar.getInstance()
        val k = "day_${keyOf(now)}"
        val h = "hr_${now.get(Calendar.HOUR_OF_DAY)}"
        sp.edit()
            .putInt("total", sp.getInt("total", 0) + 1)
            .putInt(k, sp.getInt(k, 0) + 1)
            .putInt(h, sp.getInt(h, 0) + 1)
            .apply()
    }

    /** Blocks per hour of day (0..23), all time. Powers the peak-usage insight. */
    fun blocksByHour(c: Context): List<Int> = (0..23).map { p(c).getInt("hr_$it", 0) }

    fun blockedOn(c: Context, ago: Int) = p(c).getInt("day_${keyOf(daysAgo(ago))}", 0)
    fun today(c: Context) = blockedOn(c, 0)
    fun total(c: Context) = p(c).getInt("total", 0)
    fun dayLetter(ago: Int): String = "SMTWTFS"[daysAgo(ago).get(Calendar.DAY_OF_WEEK) - 1].toString()
    fun dayName(ago: Int): String =
        daysAgo(ago).getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault()) ?: ""

    // ---- hold to open (the "wall") ----
    fun holdOn(c: Context) = p(c).getBoolean("hold_on", false)
    fun setHoldOn(c: Context, v: Boolean) = p(c).edit().putBoolean("hold_on", v).apply()

    fun holdSeconds(c: Context) = p(c).getInt("hold_sec", 5)
    fun setHoldSeconds(c: Context, v: Int) = p(c).edit().putInt("hold_sec", v.coerceIn(2, 180)).apply()

    fun passMinutes(c: Context) = p(c).getInt("pass_min", 5)
    fun setPassMinutes(c: Context, v: Int) = p(c).edit().putInt("pass_min", v.coerceIn(1, 180)).apply()

    /** When on, every pass you take today adds 2 seconds to the next hold (up to +20). */
    fun progressive(c: Context) = p(c).getBoolean("hold_prog", true)
    fun setProgressive(c: Context, v: Boolean) = p(c).edit().putBoolean("hold_prog", v).apply()

    private fun passKey(d: Calendar) = "pass_${keyOf(d)}"
    fun passesOn(c: Context, ago: Int) = p(c).getInt(passKey(daysAgo(ago)), 0)
    fun passesToday(c: Context) = passesOn(c, 0)

    /** Seconds the person must hold right now. */
    fun holdFor(c: Context): Int =
        holdSeconds(c) + if (progressive(c)) minOf(20, passesToday(c) * 2) else 0

    fun passUntil(c: Context, key: String) = p(c).getLong("passuntil_$key", 0L)

    fun grantPass(c: Context, key: String) {
        val sp = p(c)
        val k = passKey(Calendar.getInstance())
        sp.edit()
            .putLong("passuntil_$key", System.currentTimeMillis() + passMinutes(c) * 60_000L)
            .putInt(k, sp.getInt(k, 0) + 1)
            .putInt("pass_total", sp.getInt("pass_total", 0) + 1)
            .apply()
    }

    // ---- focus lock (cannot be undone until it ends) ----
    fun lockUntil(c: Context) = p(c).getLong("lock_until", 0L)
    fun locked(c: Context) = System.currentTimeMillis() < lockUntil(c)
    fun lockLeftMs(c: Context) = maxOf(0L, lockUntil(c) - System.currentTimeMillis())
    fun lockLeftMin(c: Context) = ((lockLeftMs(c) + 59_999L) / 60_000L).toInt()

    fun startLock(c: Context, minutes: Int) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        if (end > lockUntil(c)) p(c).edit().putLong("lock_until", end).apply()
    }

    // ---- streaks and rewards ----
    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun firstDay(c: Context): Long {
        val sp = p(c)
        var f = sp.getLong("first", 0L)
        if (f == 0L) { f = System.currentTimeMillis(); sp.edit().putLong("first", f).apply() }
        return f
    }

    /** Days in a row (today included) with no hold-to-open pass taken, counted from first use. */
    fun streak(c: Context): Int {
        val first = startOfDay(firstDay(c))
        var n = 0
        while (n < 400) {
            if (startOfDay(daysAgo(n).timeInMillis) < first) break
            if (passesOn(c, n) > 0) break
            n++
        }
        return n
    }

    /** Estimated minutes of scrolling not done: about 40 seconds per block. */
    fun reclaimedMinutes(c: Context) = total(c) * 40 / 60

    // ---- any app, any website ----
    fun appKey(pkg: String) = "app:$pkg"
    fun webKey(domain: String) = "web:$domain"

    fun blockedApps(c: Context): Set<String> = HashSet(p(c).getStringSet("apps", emptySet()) ?: emptySet())
    fun isAppBlocked(c: Context, pkg: String) = blockedApps(c).contains(pkg)
    fun setAppBlocked(c: Context, pkg: String, on: Boolean) {
        val s = blockedApps(c).toMutableSet()
        if (on) s.add(pkg) else s.remove(pkg)
        p(c).edit().putStringSet("apps", s).apply()
    }

    fun blockedSites(c: Context): List<String> = (p(c).getStringSet("webs", emptySet()) ?: emptySet()).sorted()
    fun addSite(c: Context, domain: String) {
        val s = HashSet(p(c).getStringSet("webs", emptySet()) ?: emptySet())
        s.add(domain)
        p(c).edit().putStringSet("webs", s).apply()
    }
    fun removeSite(c: Context, domain: String) {
        val s = HashSet(p(c).getStringSet("webs", emptySet()) ?: emptySet())
        s.remove(domain)
        p(c).edit().putStringSet("webs", s).apply()
    }

    /** "https://www.Reddit.com/r/x" -> "reddit.com". Returns null when it isn't a usable domain. */
    fun cleanDomain(raw: String): String? {
        var d = raw.trim().lowercase()
        d = d.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        d = d.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        if (d.length < 3 || !d.contains('.') || d.contains(' ') || d.startsWith('.') || d.endsWith('.')) return null
        return d
    }

    // ---- misc settings ----
    fun message(c: Context) = p(c).getString("message", "") ?: ""
    fun setMessage(c: Context, v: String) = p(c).edit().putString("message", v).apply()

    /** Put the hold wall in front of Shortstop's own screen too, so settings are harder to change. */
    fun selfLock(c: Context) = p(c).getBoolean("self_lock", false)
    fun setSelfLock(c: Context, v: Boolean) = p(c).edit().putBoolean("self_lock", v).apply()

    /** Taps "Skip" on YouTube video ads for you. */
    fun autoSkip(c: Context) = p(c).getBoolean("autoskip", false)
    fun setAutoSkip(c: Context, v: Boolean) = p(c).edit().putBoolean("autoskip", v).apply()

    // =====================================================================
    // Focus mode (timer sessions, breaks, strict mode, schedules, goal)
    // =====================================================================
    data class Session(val start: Long, val minutes: Int, val tag: String, val done: Boolean)
    data class FocusSchedule(val id: String, val name: String, val start: Int, val end: Int, val days: Int, val on: Boolean)

    val TAGS = listOf("Study", "Work", "Code", "Fitness", "Other")

    fun isLoved(c: Context, start: Long) = (p(c).getStringSet("loved", emptySet()) ?: emptySet()).contains(start.toString())
    fun lovedCount(c: Context) = (p(c).getStringSet("loved", emptySet()) ?: emptySet()).size
    fun setLoved(c: Context, start: Long, on: Boolean) {
        val s = HashSet(p(c).getStringSet("loved", emptySet()) ?: emptySet())
        if (on) s.add(start.toString()) else s.remove(start.toString())
        p(c).edit().putStringSet("loved", s).apply()
    }

    fun goalMin(c: Context) = p(c).getInt("goal", 240)
    fun setGoalMin(c: Context, v: Int) = p(c).edit().putInt("goal", v.coerceIn(30, 960)).apply()

    // Ultra Focus options
    fun strictDefault(c: Context) = p(c).getBoolean("u_strict", false)
    fun setStrictDefault(c: Context, v: Boolean) = p(c).edit().putBoolean("u_strict", v).apply()
    fun studyMode(c: Context) = p(c).getBoolean("u_study", false)
    fun setStudyMode(c: Context, v: Boolean) = p(c).edit().putBoolean("u_study", v).apply()
    fun uninstallGuard(c: Context) = p(c).getBoolean("u_guard", false)

    /**
     * The whole focus cycle is one protected span: the session running, any break inside it (a break only extends the
     * session's end time) and an active focus schedule. Unlike [hard], this does NOT drop out while a break is on,
     * because a break is not permission to uninstall.
     */
    fun cycleActive(c: Context): Boolean = focusRunning(c) || scheduleActive(c) != null

    /**
     * Three separate things: the saved preference ("u_guard", what the user chose), whether the service is really
     * working (checked where it is shown), and this session lock ("g_lock"). The lock is taken when a session starts
     * with protection ON, or the moment the user switches protection ON during a running session, and is held for the
     * whole cycle: breaks, pauses, resumes, navigation, backgrounding and recreation never release it, because it is
     * stored, not derived from the timer. It is released only when the cycle ends. Protection that was OFF at the
     * start is never switched on for the user. An active focus schedule keeps it locked as before.
     */
    fun guardLocked(c: Context): Boolean =
        (focusRunning(c) && p(c).getBoolean("g_lock", false)) || scheduleActive(c) != null

    /** Switching protection off is refused while it is locked. Returns whether the stored value now equals [v]. */
    fun setUninstallGuard(c: Context, v: Boolean): Boolean {
        if (!v && uninstallGuard(c) && guardLocked(c)) return false
        val e = p(c).edit().putBoolean("u_guard", v)
        if (v && focusRunning(c)) e.putBoolean("g_lock", true)   // switched on mid-session: locked for the rest of the cycle
        e.apply()
        return true
    }
    fun blockNotifs(c: Context) = p(c).getBoolean("u_notif", false)
    fun setBlockNotifs(c: Context, v: Boolean) = p(c).edit().putBoolean("u_notif", v).apply()
    fun blockHome(c: Context) = p(c).getBoolean("u_home", false)
    fun setBlockHome(c: Context, v: Boolean) = p(c).edit().putBoolean("u_home", v).apply()

    // Last-used session choices
    fun lastMinutes(c: Context) = p(c).getInt("last_min", 25)
    fun lastTag(c: Context) = p(c).getString("last_tag", "Study") ?: "Study"
    fun lastBreaks(c: Context) = p(c).getInt("last_breaks", 2)
    fun lastBreakMin(c: Context) = p(c).getInt("last_break_min", 5)
    fun rememberSession(c: Context, minutes: Int, tag: String, breaks: Int, breakMin: Int) {
        p(c).edit().putInt("last_min", minutes).putString("last_tag", tag)
            .putInt("last_breaks", breaks).putInt("last_break_min", breakMin).apply()
    }

    // Apps closed during focus
    fun focusApps(c: Context): Set<String> = HashSet(p(c).getStringSet("fapps", emptySet()) ?: emptySet())
    fun isFocusApp(c: Context, pkg: String) = focusApps(c).contains(pkg)
    fun setFocusApp(c: Context, pkg: String, on: Boolean) {
        val s = focusApps(c).toMutableSet()
        if (on) s.add(pkg) else s.remove(pkg)
        p(c).edit().putStringSet("fapps", s).apply()
    }

    // Running session
    fun focusEnd(c: Context) = p(c).getLong("f_end", 0L)
    fun focusStart(c: Context) = p(c).getLong("f_start", 0L)
    fun focusPlanned(c: Context) = p(c).getInt("f_min", 0)
    fun focusTag(c: Context) = p(c).getString("f_tag", "") ?: ""
    fun focusStrict(c: Context) = p(c).getBoolean("f_strict", false)
    fun breaksLeft(c: Context) = p(c).getInt("f_bleft", 0)
    fun breakUntil(c: Context) = p(c).getLong("f_bu", 0L)
    private fun breakTotalMs(c: Context) = p(c).getLong("f_bt", 0L)

    fun focusRunning(c: Context) = focusEnd(c) > System.currentTimeMillis()
    fun onBreak(c: Context) = breakUntil(c) > System.currentTimeMillis()
    fun focusLeftMs(c: Context) = maxOf(0L, focusEnd(c) - System.currentTimeMillis())
    fun focusLeftMin(c: Context) = ((focusLeftMs(c) + 59_999L) / 60_000L).toInt()

    /** True while the settings are frozen: a strict session is running. */
    fun frozen(c: Context) = System.currentTimeMillis() < lockUntil(c)

    /** True when focus blocking is in force: a session (not on break) or an active focus schedule. */
    fun hard(c: Context): Boolean = (focusRunning(c) && !onBreak(c)) || scheduleActive(c) != null

    fun startFocus(c: Context, minutes: Int, tag: String, strict: Boolean, breaks: Int, breakMin: Int) {
        val now = System.currentTimeMillis()
        val end = now + minutes * 60_000L
        p(c).edit()
            .putLong("f_start", now).putLong("f_end", end).putInt("f_min", minutes)
            .putString("f_tag", tag).putBoolean("f_strict", strict)
            .putInt("f_bleft", breaks).putInt("f_blen", breakMin)
            .putLong("f_bu", 0L).putLong("f_bs", 0L).putLong("f_bt", 0L)
            .putLong("lock_until", if (strict) end else 0L)
            // the session lock is taken now if protection is already on; it is released when the cycle ends
            .putBoolean("g_lock", uninstallGuard(c))
            .apply()
        rememberSession(c, minutes, tag, breaks, breakMin)
    }

    fun startBreak(c: Context): Boolean {
        if (!focusRunning(c) || onBreak(c) || breaksLeft(c) <= 0) return false
        val ms = p(c).getInt("f_blen", 5) * 60_000L
        val now = System.currentTimeMillis()
        val newEnd = focusEnd(c) + ms
        val e = p(c).edit()
            .putInt("f_bleft", breaksLeft(c) - 1)
            .putLong("f_bu", now + ms)
            .putLong("f_bs", now)
            .putLong("f_end", newEnd)
            .putLong("f_bt", breakTotalMs(c) + ms)
        if (focusStrict(c)) e.putLong("lock_until", newEnd)
        e.apply()
        return true
    }

    /** End a break early without charging the unused portion to the focus timer or break accounting.
     * Older active sessions without f_bs are migrated using their stored break deadline and configured duration.
     */
    fun endBreak(c: Context) {
        val sp = p(c)
        val now = System.currentTimeMillis()
        val until = sp.getLong("f_bu", 0L)
        if (until <= 0L) return
        val plannedBreakMs = sp.getInt("f_blen", 5).coerceAtLeast(1) * 60_000L
        val remaining = (until - now).coerceIn(0L, plannedBreakMs)
        val currentEnd = sp.getLong("f_end", 0L)
        val currentBreakTotal = sp.getLong("f_bt", 0L)
        val edit = sp.edit()
            .putLong("f_bu", 0L)
            .putLong("f_bs", 0L)
            .putLong("f_end", (currentEnd - remaining).coerceAtLeast(now))
            .putLong("f_bt", (currentBreakTotal - remaining).coerceAtLeast(0L))
        if (sp.getBoolean("f_strict", false)) {
            val lockEnd = sp.getLong("lock_until", currentEnd)
            edit.putLong("lock_until", (lockEnd - remaining).coerceAtLeast(now))
        }
        edit.apply()
    }

    fun isKnownApp(pkg: String) = PACKAGES.containsKey(pkg)

    /** Ends a session early. Not allowed while strict mode is on. */
    fun endFocusEarly(c: Context): Boolean {
        if (!focusRunning(c) || frozen(c)) return false
        recordSession(c, false)
        clearFocus(c)
        return true
    }

    /** Call often: closes out a session whose time is up. Returns true when it just finished. */
    fun finalizeFocus(c: Context): Boolean {
        val end = focusEnd(c)
        if (end == 0L || end > System.currentTimeMillis()) return false
        recordSession(c, true)
        clearFocus(c)
        return true
    }

    private fun clearFocus(c: Context) {
        p(c).edit().putLong("f_end", 0L).putLong("f_start", 0L).putLong("f_bu", 0L).putLong("f_bs", 0L).putLong("lock_until", 0L)
            .putBoolean("g_lock", false).apply()
    }

    private fun recordSession(c: Context, completed: Boolean) {
        val start = focusStart(c)
        if (start == 0L) return
        val now = System.currentTimeMillis()
        val planned = focusPlanned(c)
        val mins = if (completed) planned
        else minOf(planned, maxOf(0L, (now - start - breakTotalMs(c)) / 60_000L).toInt())
        if (mins <= 0) return
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        val k = "focus_${keyOf(cal)}"
        val sp = p(c)
        sp.edit().putInt(k, sp.getInt(k, 0) + mins).apply()
        addSession(c, Session(start, mins, focusTag(c), completed))
    }

    fun focusMinutesOn(c: Context, cal: Calendar) = p(c).getInt("focus_${keyOf(cal)}", 0)
    fun focusMinutesAgo(c: Context, ago: Int) = focusMinutesOn(c, daysAgo(ago))

    fun sessions(c: Context): List<Session> {
        val raw = p(c).getString("sessions", "") ?: ""
        return raw.split('\n').mapNotNull { line ->
            val a = line.split(',')
            if (a.size < 4) return@mapNotNull null
            val s = a[0].toLongOrNull()
            val m = a[1].toIntOrNull()
            if (s == null || m == null) null else Session(s, m, a[2], a[3] == "1")
        }
    }

    private fun addSession(c: Context, s: Session) {
        val keep = (sessions(c) + s).takeLast(300)
        val text = keep.joinToString("\n") {
            "${it.start},${it.minutes},${it.tag.replace(',', ' ').replace('\n', ' ')},${if (it.done) 1 else 0}"
        }
        p(c).edit().putString("sessions", text).apply()
    }

    /** Sessions that started on the given day. */
    fun sessionsOn(c: Context, cal: Calendar): List<Session> {
        val k = keyOf(cal)
        return sessions(c).filter { keyOf(Calendar.getInstance().apply { timeInMillis = it.start }) == k }
    }

    // ---- focus schedules ----
    fun schedules(c: Context): List<FocusSchedule> {
        val raw = p(c).getString("fscheds", "") ?: ""
        return raw.split('\n').mapNotNull { line ->
            val a = line.split('|')
            if (a.size < 6) return@mapNotNull null
            val st = a[2].toIntOrNull()
            val en = a[3].toIntOrNull()
            val dy = a[4].toIntOrNull()
            if (st == null || en == null || dy == null) null else FocusSchedule(a[0], a[1], st, en, dy, a[5] == "1")
        }
    }

    fun saveSchedules(c: Context, list: List<FocusSchedule>) {
        val text = list.joinToString("\n") {
            "${it.id}|${it.name.replace('|', ' ').replace('\n', ' ')}|${it.start}|${it.end}|${it.days}|${if (it.on) 1 else 0}"
        }
        p(c).edit().putString("fscheds", text).apply()
    }

    fun scheduleActive(c: Context): FocusSchedule? {
        val now = Calendar.getInstance()
        return schedules(c).firstOrNull { it.on && inWindow(it.start, it.end, it.days, now) }
    }
}
