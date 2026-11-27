package com.shortstop.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches every app and reacts when something blocked is on screen:
 *  - any app the person picked, and any website typed into a supported browser;
 *  - feed (Shorts/Reels/...): daily allowance first, otherwise the wall or Back/Home;
 *  - sections (Explore, Stories, comments, Discover): same rules;
 *  - whole app: Home straight away (or the wall);
 *  - focus lock: every supported app is closed until the timer ends, no wall, no pass.
 * Blocking only runs while enabled, not snoozed, and inside the schedule.
 *
 * Detection uses view IDs and a *selected* tab labelled with the feature name.
 * Apps rename these now and then; the keyword tables are at the bottom of this file.
 */
class ShortsBlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var tracking: String? = null
    private var lastBlock = 0L
    private var lastCoverCheck = 0L
    private var lastUrlCheck = 0L
    private var lastSkip = 0L
    private var strikes = 0
    private var lastStrike = 0L

    private var wall: Wall? = null
    private var wallPkg: String? = null
    private var wallKey: String? = null

    // Cached copies so busy apps don't make us re-read preferences on every event.
    private var focusCache: Set<String> = emptySet()
    private var hardVal = false
    private var hardAt = 0L
    private var appsCache: Set<String> = emptySet()
    private var sitesCache: List<String> = emptyList()
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadCache() }

    private val tick = Runnable { tickOnce() }
    private val wallWatch = object : Runnable {
        override fun run() { watchWall() }
    }

    private fun reloadCache() {
        appsCache = Store.blockedApps(this)
        focusCache = Store.focusApps(this)
        sitesCache = Store.blockedSites(this)
        hardAt = 0L
    }

    /** Focus blocking in force (session or schedule). Cached for a few seconds. */
    private fun hard(): Boolean {
        val n = SystemClock.uptimeMillis()
        if (n - hardAt > 3000) {
            hardVal = Store.hard(this)
            hardAt = n
        }
        return hardVal
    }

    private var cycleAt = 0L
    private var cycleVal = false
    /** Focus cycle in progress (session, break or schedule). Cached for a few seconds like [hard]. */
    private fun cycleNow(now: Long): Boolean {
        if (now - cycleAt > 3000) { cycleVal = Store.cycleActive(this); cycleAt = now }
        return cycleVal
    }

    private val launcherPkg: String? by lazy {
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
            packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (e: Exception) { null }
    }

    private fun focusLeftText(): String {
        if (Store.focusRunning(this)) {
            val sec = Store.focusLeftMs(this) / 1000
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val x = sec % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, x) else String.format("%02d:%02d", m, x)
        }
        return Store.scheduleActive(this)?.name ?: "Focus schedule"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        reloadCache()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        alive = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (Store.finalizeFocus(this)) {
            hardAt = 0L
            Toast.makeText(this, "Focus session complete. Well done.", Toast.LENGTH_LONG).show()
        }
        if (wall != null) return

        val now = SystemClock.uptimeMillis()
        if (pkg == "com.google.android.youtube" && Store.autoSkip(this)) trySkipAd(now)

        if (tracking != null && Store.siteFor(pkg) == tracking) return // allowance timer is watching
        if (now - lastBlock < 900) return // let the Back animation finish

        // 1. Shortstop's own screen (optional wall in front of settings)
        if (pkg == packageName) {
            val key = Store.appKey(pkg)
            if (Store.selfLock(this) && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                Store.passUntil(this, key) <= System.currentTimeMillis() && !Store.locked(this)
            ) showWall(key, pkg)
            return
        }

        val hardNow = hard()

        // 1b. Uninstall protection: keep Settings away from Shortstop while focus is on
        // Protection follows the whole focus cycle (breaks included), not the blocking state: a break must not open a way to uninstall.
        if (Store.uninstallGuard(this) && pkg in SETTINGS_PKGS && cycleNow(now) && now - lastBlock > 700) {
            val root = rootInActiveWindow
            if (root != null && mentionsUs(root)) {
                lastBlock = now
                performGlobalAction(GLOBAL_ACTION_HOME)
                Toast.makeText(this, "Uninstall protection stays on until your focus cycle ends", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 1c. Block the home screen: cover the launcher with the focus timer
        // Not only when the launcher window opens: it also covers coming back from the shade, recents or a popup,
        // where the launcher never fires a "window opened" event. Content events are only trusted when the
        // launcher really is the screen in front, so the cover never lands on top of Shortstop or another app.
        if (hardNow && Store.blockHome(this) && pkg == launcherPkg) {
            val opened = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            if (opened || now - lastCoverCheck > 250) {
                lastCoverCheck = now
                if (opened || rootInActiveWindow?.packageName?.toString() == launcherPkg) {
                    showCover(pkg)
                    return
                }
            }
        }

        // 2. Any app the person picked (always-on list, or the focus list during focus)
        val studyYoutube = hardNow && Store.studyMode(this) && pkg == "com.google.android.youtube"
        val inFocus = hardNow && Store.isFocusApp(this, pkg) && !studyYoutube
        if (Store.isAppBlocked(this, pkg) || inFocus) {
            val key = Store.appKey(pkg)
            if (inFocus || Store.isActive(this, key)) {
                handleHit(null, key, pkg)
                return
            }
        }

        // 3. Websites inside a browser (the address bar is read; any browser works, known ones are just quicker)
        if (sitesCache.isNotEmpty() && isBrowser(pkg)) {
            startWebWatch()
            if (now - lastUrlCheck > 250) {
                lastUrlCheck = now
                if (checkWeb(pkg, hardNow)) return
            } else {
                // throttled: look again shortly so the final address-bar text is never missed
                handler.removeCallbacks(webRecheck)
                handler.postDelayed(webRecheck, 300)
            }
        }

        // 4. Short-video feeds, sections and whole-app switches
        val site = Store.siteFor(pkg) ?: return
        val key = findHit(site, pkg) ?: return
        handleHit(site, key, pkg)
    }

    // ---------- website watch ----------
    private var watchingWeb = false

    private fun checkWeb(pkg: String, hardNow: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        val d = blockedDomainOn(pkg, root) ?: return false
        val key = Store.webKey(d)
        if (hardNow || Store.isActive(this, key)) {
            handleHit(null, key, pkg)
            return true
        }
        return false
    }

    private val webRecheck = Runnable {
        if (wall != null) return@Runnable
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return@Runnable
        if (sitesCache.isNotEmpty() && isBrowser(pkg) && SystemClock.uptimeMillis() - lastBlock > 900) {
            lastUrlCheck = SystemClock.uptimeMillis()
            checkWeb(pkg, hard())
        }
    }

    private val webTick = object : Runnable {
        override fun run() {
            val pkg = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
            if (pkg != null && sitesCache.isNotEmpty() && isBrowser(pkg)) {
                webRecheck.run()
                handler.postDelayed(this, 800)
            } else {
                watchingWeb = false
            }
        }
    }

    private fun startWebWatch() {
        if (watchingWeb) return
        watchingWeb = true
        handler.postDelayed(webTick, 800)
    }

    // ---------- websites ----------
    private fun hostOf(raw: String): String? {
        var t = raw.trim().lowercase()
        if (t.isEmpty() || t.contains(' ')) return null
        t = t.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        t = t.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        return if (t.contains('.')) t else null
    }

    private fun isBrowser(pkg: String): Boolean =
        BROWSER_IDS.containsKey(pkg) || pkg.split('.').any { it in BROWSERISH }

    private fun matchBlocked(host: String): String? {
        for (d in sitesCache) if (host == d || host.endsWith(".$d")) return d
        return null
    }

    private fun blockedDomainOn(pkg: String, root: AccessibilityNodeInfo): String? {
        // 1. the known address-bar ids
        BROWSER_IDS[pkg]?.let { ids ->
            for (id in ids) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNullOrEmpty()) continue
                for (n in nodes) {
                    val host = hostOf(n.text?.toString() ?: continue) ?: continue
                    matchBlocked(host)?.let { return it }
                }
            }
        }
        // 2. any browser: look through the top of the window for the address bar
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 220) {
            val n = queue.removeFirst()
            seen++
            val id = n.viewIdResourceName?.lowercase()
            val looksLikeBar = (id != null && URL_HINTS.any { id.contains(it) }) ||
                n.className?.toString()?.endsWith("EditText") == true
            if (looksLikeBar) {
                val host = hostOf(n.text?.toString() ?: "")
                if (host != null) matchBlocked(host)?.let { return it }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    // ---------- YouTube ads ----------
    private fun trySkipAd(now: Long) {
        if (now - lastSkip < 600) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != "com.google.android.youtube") return
        var target: AccessibilityNodeInfo? = root.findAccessibilityNodeInfosByViewId(
            "com.google.android.youtube:id/skip_ad_button"
        )?.firstOrNull()
        if (target == null) {
            target = root.findAccessibilityNodeInfosByText("Skip")?.firstOrNull {
                val t = it.text?.toString()?.trim()?.lowercase()
                t == "skip" || t == "skip ad" || t == "skip ads"
            }
        }
        var n: AccessibilityNodeInfo? = target
        var hops = 0
        while (n != null && hops < 4) {
            if (n.isClickable) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastSkip = now
                return
            }
            n = n.parent
            hops++
        }
    }

    /** Which blocked key (if any) is on screen right now for this app. */
    private fun findHit(site: String, pkg: String): String? {
        val hardNow = hard()
        val whole = Store.wholeKey(site)
        val studyYoutube = hardNow && Store.studyMode(this) && site == "youtube"
        val keys = Store.keysFor(site).filter { Store.isActive(this, it) }.toMutableList()
        if (studyYoutube) {
            if ("youtube" !in keys) keys.add("youtube")
            if ("youtube_home" !in keys) keys.add("youtube_home")
        }
        val focusWhole = hardNow && Store.isFocusApp(this, pkg) && !studyYoutube
        if (!focusWhole && keys.isEmpty()) return null

        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != pkg) return null

        if (focusWhole) return whole
        if (whole in keys) return whole
        if (site == "tiktok" && site in keys) return site
        return scan(keys.filter { it != whole }, root)
    }

    private fun handleHit(site: String?, key: String, pkg: String) {
        val locked = hard()
        if (!locked) {
            val isFeed = site != null && key == site && site != "tiktok"
            if (isFeed && site != null) {
                val allow = Store.allowSeconds(this, site)
                val used = Store.usedSeconds(this, site)
                if (allow > 0 && used < allow) {
                    startTracking(site, allow - used)
                    return
                }
            }
            if (Store.passUntil(this, key) > System.currentTimeMillis()) return
            if (Store.holdOn(this)) {
                showWall(key, pkg)
                return
            }
        }
        val usedUp = !locked && site != null && key == site && Store.allowSeconds(this, site) > 0
        block(site, key, usedUp, locked)
    }

    private fun labelFor(key: String): String = when {
        key.startsWith("app:") -> try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(key.removePrefix("app:"), 0)).toString()
        } catch (e: Exception) { key.removePrefix("app:") }
        key.startsWith("web:") -> key.removePrefix("web:")
        else -> Store.label(key)
    }

    private fun mentionsUs(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 300) {
            val n = queue.removeFirst()
            seen++
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            if ((t != null && t.contains("Shortstop", true)) || (d != null && d.contains("Shortstop", true))) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    private fun showCover(pkg: String) {
        val w = Wall(
            this, "Focus", Store.message(this), 0, 0,
            onPass = {},
            onLeave = {
                closeWall()
                lastBlock = SystemClock.uptimeMillis()
                val i = android.content.Intent(this, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            },
            cover = true,
            coverText = { focusLeftText() }
        )
        if (!w.show()) return
        wall = w
        wallPkg = pkg
        wallKey = COVER_KEY
        handler.removeCallbacks(wallWatch)
        handler.postDelayed(wallWatch, 700)
    }

    // ---------- wall ----------
    private fun showWall(key: String, pkg: String) {
        val w = Wall(
            this, labelFor(key), Store.message(this), Store.holdFor(this), Store.passMinutes(this),
            onPass = { passWall(key) },
            onLeave = { leaveWall() }
        )
        if (!w.show()) {
            block(Store.siteFor(pkg), key, false, false)
            return
        }
        wall = w
        wallPkg = pkg
        wallKey = key
        handler.removeCallbacks(wallWatch)
        handler.postDelayed(wallWatch, 700)
    }

    private fun passWall(key: String) {
        Store.grantPass(this, key)
        closeWall()
        Toast.makeText(this, "${labelFor(key)} open for ${Store.passMinutes(this)} min", Toast.LENGTH_SHORT).show()
    }

    private fun leaveWall() {
        closeWall()
        lastBlock = SystemClock.uptimeMillis()
        Store.bump(this)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun closeWall() {
        handler.removeCallbacks(wallWatch)
        wall?.dismiss()
        wall = null
        wallPkg = null
        wallKey = null
    }

    /** Removes the wall as soon as the person leaves the blocked screen some other way. */
    private fun watchWall() {
        val w = wall ?: return
        val pkg = wallPkg
        val key = wallKey
        if (pkg == null || key == null || !w.showing()) { closeWall(); return }

        val root = rootInActiveWindow
        val now = root?.packageName?.toString()
        var gone = false
        if (now != null && now != pkg && now != packageName) gone = true
        if (!gone && root != null && now == pkg) {
            val site = Store.siteFor(pkg)
            val simple = key.startsWith("whole_") || key.startsWith("app:") || key == "tiktok"
            if (key.startsWith("web:")) {
                if (blockedDomainOn(pkg, root) != key.removePrefix("web:")) gone = true
            } else if (!simple && site != null && scan(listOf(key), root) == null) gone = true
        }
        if (key == COVER_KEY) {
            if (!hard() || now != launcherPkg) gone = true
        } else if (hard()) gone = true // focus started: the focus rules take over
        if (gone) {
            closeWall()
            lastBlock = SystemClock.uptimeMillis()
        } else {
            handler.postDelayed(wallWatch, 700)
        }
    }

    // ---------- allowance ----------
    private fun startTracking(site: String, leftSeconds: Int) {
        tracking = site
        val mins = (leftSeconds + 59) / 60
        val label = Store.SITES[site] ?: "Short video"
        Toast.makeText(this, "$label allowance: $mins min left today", Toast.LENGTH_SHORT).show()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 2000)
    }

    /** Runs every 2 seconds while an allowance is being used. */
    private fun tickOnce() {
        val site = tracking ?: return
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        val stillThere = root != null && pkg != null && Store.siteFor(pkg) == site &&
            Store.isActive(this, site) && !hard() &&
            (site == "tiktok" || scan(listOf(site), root) != null)
        if (!stillThere) {
            tracking = null
            return
        }
        Store.addUsage(this, site, 2)
        if (Store.usedSeconds(this, site) >= Store.allowSeconds(this, site)) {
            tracking = null
            block(site, site, true, false)
        } else {
            handler.postDelayed(tick, 2000)
        }
    }

    // ---------- blocking ----------
    private fun block(site: String?, key: String, allowanceUsedUp: Boolean, locked: Boolean) {
        val now = SystemClock.uptimeMillis()
        lastBlock = now
        strikes = if (now - lastStrike < 4000) strikes + 1 else 1
        lastStrike = now

        val goHome = locked || site == "tiktok" || key.startsWith("whole_") ||
            key.startsWith("app:") || key.startsWith("web:") || strikes >= 3
        if (goHome) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            strikes = 0
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        Store.bump(this)

        val label = labelFor(key)
        val msg = when {
            locked -> "Focus · ${focusLeftText()}"
            allowanceUsedUp -> "Daily allowance used up for $label"
            else -> "Shortstop blocked $label"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- detection ----------
    /** Breadth-first scan (capped at 500 nodes) for the first of [keys] that is on screen. */
    private fun scan(keys: List<String>, root: AccessibilityNodeInfo): String? {
        if (keys.isEmpty()) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 500) {
            val n = queue.removeFirst()
            seen++

            val id = n.viewIdResourceName?.lowercase()
            if (id != null) {
                for (k in keys) {
                    val ids = ID_KEYWORDS[k] ?: continue
                    if (ids.any { id.contains(it) }) return k
                }
            }

            if (n.isSelected) {
                val t = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase()
                if (t != null) {
                    for (k in keys) {
                        val labels = SELECTED_LABELS[k] ?: continue
                        if (t in labels) return k
                    }
                }
            }

            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    override fun onInterrupt() {
        tracking = null
        closeWall()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        alive = false
        closeWall()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        alive = false
        try { Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener) } catch (e: Exception) { }
        closeWall()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** True while the system has this service connected (used by the in-app health card). */
        @Volatile var alive = false

        private const val COVER_KEY = "cover:home"

        private val SETTINGS_PKGS = setOf(
            "com.android.settings", "com.miui.securitycenter", "com.android.packageinstaller",
            "com.google.android.packageinstaller", "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller", "com.samsung.android.settings"
        )

        /** URL-bar view IDs of common browsers (used to spot blocked websites). */
        private val BROWSER_IDS = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
            "com.chrome.dev" to listOf("com.chrome.dev:id/url_bar"),
            "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
            "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
            "com.kiwibrowser.browser" to listOf("com.kiwibrowser.browser:id/url_bar"),
            "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
            "com.sec.android.app.sbrowser" to listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text"
            ),
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title"
            ),
            "org.mozilla.fenix" to listOf("org.mozilla.fenix:id/mozac_browser_toolbar_url_view"),
            "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
            "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),
            "com.mi.globalbrowser" to listOf("com.mi.globalbrowser:id/url", "com.mi.globalbrowser:id/addressbar_url"),
            "com.android.browser" to listOf("com.android.browser:id/url"),
            "com.miui.browser" to listOf("com.miui.browser:id/url")
        )

        private val BROWSERISH = listOf(
            "browser", "chrome", "firefox", "opera", "brave", "vivaldi", "duckduckgo", "emmx", "sbrowser", "fenix"
        )
        private val URL_HINTS = listOf(
            "url_bar", "url_field", "urlbar", "omnibox", "location_bar", "address", "toolbar_url", "url_view", "search_box"
        )

        private val CLASSIC_IDS = listOf(
            "reel_player", "reel_recycler", "reel_watch", "shorts_player", "shorts_container",
            "clips_viewer", "clips_tab_selected", "reels_viewer", "fb_shorts", "reels_tab_selected"
        )

        // View-ID keywords per key. Newer entries start with few: nav bars often contain these
        // names even when the screen is closed, so a loose keyword would block the whole app.
        private val ID_KEYWORDS = mapOf(
            "youtube" to CLASSIC_IDS,
            "instagram" to CLASSIC_IDS,
            "facebook" to CLASSIC_IDS,
            "twitter" to listOf("immersive_player", "video_immersive"),
            "instagram_stories" to listOf("reel_viewer_media_container", "story_viewer", "stories_viewer"),
            "instagram_comments" to listOf("layout_comment_thread", "comment_composer"),
            "facebook_stories" to listOf("story_viewer", "stories_viewer")
        )

        // A selected tab/button with one of these labels counts as "on that screen".
        private val SELECTED_LABELS = mapOf(
            "youtube" to setOf("shorts"),
            "instagram" to setOf("reels", "reel"),
            "facebook" to setOf("reels", "reel"),
            "snapchat" to setOf("spotlight"),
            "twitter" to setOf("videos"),
            "linkedin" to setOf("videos"),
            "pinterest" to setOf("watch", "videos"),
            "reddit" to setOf("videos", "watch"),
            "youtube_home" to setOf("home"),
            "instagram_home" to setOf("home"),
            "facebook_home" to setOf("home"),
            "instagram_explore" to setOf("search and explore", "explore"),
            "snapchat_stories" to setOf("stories"),
            "snapchat_discover" to setOf("discover")
        )
    }
}
