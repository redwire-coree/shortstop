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
        enabled(c) && System.currentTimeMillis() >= snoozeUntil(c) && site(c, s) && inSchedule(c)

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
    fun setHoldSeconds(c: Context, v: Int) = p(c).edit().putInt("hold_sec", v.coerceIn(2, 60)).apply()

    fun passMinutes(c: Context) = p(c).getInt("pass_min", 5)
    fun setPassMinutes(c: Context, v: Int) = p(c).edit().putInt("pass_min", v.coerceIn(1, 30)).apply()

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
}
