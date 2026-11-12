package com.shortstop.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches the short-video apps. When a Shorts/Reels style screen is showing:
 *  - if the app has a daily allowance left, it counts the time and lets it play;
 *  - otherwise it presses Back (or Home if Back keeps landing in the same feed).
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

    private val tick = Runnable { tickOnce() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val site = Store.siteFor(pkg) ?: return
        if (!Store.isActive(this, site)) return
        if (tracking == site) return // the timer loop is already watching this app

        val now = SystemClock.uptimeMillis()
        if (now - lastBlock < 900) return // let the Back animation finish
        if (!onShortsScreen(site, pkg)) return

        val allow = Store.allowSeconds(this, site)
        val used = Store.usedSeconds(this, site)
        if (allow > 0 && used < allow) {
            startTracking(site, allow - used)
        } else {
            block(site, allow > 0)
        }
    }

    private fun onShortsScreen(site: String, pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        return site == "tiktok" || looksLikeShorts(site, root)
    }

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
            Store.isActive(this, site) && (site == "tiktok" || looksLikeShorts(site, root))
        if (!stillThere) {
            tracking = null
            return
        }
        Store.addUsage(this, site, 2)
        if (Store.usedSeconds(this, site) >= Store.allowSeconds(this, site)) {
            tracking = null
            block(site, true)
        } else {
            handler.postDelayed(tick, 2000)
        }
    }

    private fun block(site: String, allowanceUsedUp: Boolean) {
        val now = SystemClock.uptimeMillis()
        lastBlock = now
        strikes = if (now - lastStrike < 4000) strikes + 1 else 1
        lastStrike = now

        val label = Store.SITES[site] ?: "Short video"
        if (site == "tiktok" || strikes >= 3) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            strikes = 0
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        Store.bump(this)
        val msg = if (allowanceUsedUp) "Daily allowance used up for $label" else "Shortstop blocked $label"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun looksLikeShorts(site: String, root: AccessibilityNodeInfo): Boolean {
        val ids = ID_KEYWORDS[site] ?: emptyList()
        val labels = SELECTED_LABELS[site] ?: emptySet()
        // Breadth-first with a hard cap so a huge feed tree can't stall the service.
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 500) {
            val n = queue.removeFirst()
            seen++

            val id = n.viewIdResourceName?.lowercase()
            if (id != null && ids.any { id.contains(it) }) return true

            if (n.isSelected && labels.isNotEmpty()) {
                val t = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase()
                if (t != null && t in labels) return true
            }

            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    override fun onInterrupt() {
        tracking = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private val CLASSIC_IDS = listOf(
            "reel_player", "reel_recycler", "reel_watch", "shorts_player", "shorts_container",
            "clips_viewer", "clips_tab_selected", "reels_viewer", "fb_shorts", "reels_tab_selected"
        )

        // View-ID keywords per app. Newer apps start with few: nav bars often contain these
        // names even when the feed is closed, so a loose keyword would block the whole app.
        private val ID_KEYWORDS = mapOf(
            "youtube" to CLASSIC_IDS,
            "instagram" to CLASSIC_IDS,
            "facebook" to CLASSIC_IDS,
            "twitter" to listOf("immersive_player", "video_immersive")
        )

        // A selected tab/button with one of these labels counts as "in the short-video feed".
        private val SELECTED_LABELS = mapOf(
            "youtube" to setOf("shorts"),
            "instagram" to setOf("reels", "reel"),
            "facebook" to setOf("reels", "reel"),
            "snapchat" to setOf("spotlight"),
            "twitter" to setOf("videos"),
            "linkedin" to setOf("videos"),
            "pinterest" to setOf("watch", "videos"),
            "reddit" to setOf("videos", "watch")
        )
    }
}
