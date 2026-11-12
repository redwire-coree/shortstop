package com.shortstop.app

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches the four short-video apps. When the Shorts/Reels player is on screen it
 * presses Back (or Home if Back keeps landing in the same feed).
 *
 * Detection uses two signals so one app update doesn't break everything:
 *  1. view IDs that contain known Shorts/Reels keywords
 *  2. a *selected* tab/button labelled "Shorts" / "Reels"
 * Apps rename view IDs now and then; keywords live in ID_KEYWORDS below.
 */
class ShortsBlockerService : AccessibilityService() {

    private var lastBlock = 0L
    private var strikes = 0
    private var lastStrike = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val site = Store.siteFor(pkg) ?: return
        if (!Store.isActive(this, site)) return

        val now = SystemClock.uptimeMillis()
        if (now - lastBlock < 900) return // let the Back animation finish

        val hit = if (site == "tiktok") true else {
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != pkg) return
            looksLikeShorts(root)
        }
        if (!hit) return

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
        Toast.makeText(this, "Shortstop blocked $label", Toast.LENGTH_SHORT).show()
    }

    private fun looksLikeShorts(root: AccessibilityNodeInfo): Boolean {
        // Breadth-first with a hard cap so a huge feed tree can't stall the service.
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 500) {
            val n = queue.removeFirst()
            seen++

            val id = n.viewIdResourceName?.lowercase()
            if (id != null && ID_KEYWORDS.any { id.contains(it) }) return true

            if (n.isSelected) {
                val t = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase()
                if (t != null && t in SELECTED_LABELS) return true
            }

            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    override fun onInterrupt() {}

    companion object {
        private val ID_KEYWORDS = listOf(
            "reel_player", "reel_recycler", "reel_watch", "shorts_player", "shorts_container",
            "clips_viewer", "clips_tab_selected", "reels_viewer", "fb_shorts", "reels_tab_selected"
        )
        private val SELECTED_LABELS = setOf("shorts", "reels", "reel")
    }
}
