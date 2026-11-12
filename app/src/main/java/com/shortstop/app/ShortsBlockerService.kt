package com.shortstop.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches the supported apps and reacts when a blocked part of one is on screen:
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
    private var strikes = 0
    private var lastStrike = 0L

    private var wall: Wall? = null
    private var wallPkg: String? = null
    private var wallKey: String? = null

    private val tick = Runnable { tickOnce() }
    private val wallWatch = object : Runnable {
        override fun run() { watchWall() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val site = Store.siteFor(pkg) ?: return
        if (wall != null) return
        if (tracking == site) return // the timer loop is already watching this app

        val now = SystemClock.uptimeMillis()
        if (now - lastBlock < 900) return // let the Back animation finish

        val key = findHit(site, pkg) ?: return
        handleHit(site, key, pkg)
    }

    /** Which blocked key (if any) is on screen right now for this app. */
    private fun findHit(site: String, pkg: String): String? {
        val locked = Store.locked(this)
        val whole = Store.wholeKey(site)
        val keys = Store.keysFor(site).filter { Store.isActive(this, it) }
        if (!locked && keys.isEmpty()) return null

        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != pkg) return null

        if (locked) return whole
        if (whole in keys) return whole
        if (site == "tiktok" && site in keys) return site
        return scan(keys.filter { it != whole }, root)
    }

    private fun handleHit(site: String, key: String, pkg: String) {
        val locked = Store.locked(this)
        if (!locked) {
            val isFeed = key == site && site != "tiktok"
            if (isFeed) {
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
        val usedUp = !locked && key == site && Store.allowSeconds(this, site) > 0
        block(site, key, usedUp, locked)
    }

    // ---------- wall ----------
    private fun showWall(key: String, pkg: String) {
        val w = Wall(
            this, Store.label(key), Store.holdFor(this), Store.passMinutes(this),
            onPass = { passWall(key) },
            onLeave = { leaveWall() }
        )
        if (!w.show()) {
            block(Store.siteFor(pkg) ?: key, key, false, false)
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
        Toast.makeText(this, "${Store.label(key)} open for ${Store.passMinutes(this)} min", Toast.LENGTH_SHORT).show()
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
            val simple = key.startsWith("whole_") || key == "tiktok"
            if (!simple && site != null && scan(listOf(key), root) == null) gone = true
        }
        if (Store.locked(this)) gone = true // a lock started: the lock rules take over
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
            Store.isActive(this, site) && !Store.locked(this) &&
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
    private fun block(site: String, key: String, allowanceUsedUp: Boolean, locked: Boolean) {
        val now = SystemClock.uptimeMillis()
        lastBlock = now
        strikes = if (now - lastStrike < 4000) strikes + 1 else 1
        lastStrike = now

        val goHome = locked || site == "tiktok" || key.startsWith("whole_") || strikes >= 3
        if (goHome) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            strikes = 0
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        Store.bump(this)

        val label = Store.label(key)
        val msg = when {
            locked -> "Focus lock: ${Store.lockLeftMin(this)} min left"
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
        closeWall()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        closeWall()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
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
            "instagram_explore" to setOf("search and explore", "explore"),
            "snapchat_stories" to setOf("stories"),
            "snapchat_discover" to setOf("discover")
        )
    }
}
