package com.shortstop.app

import android.content.Context

/**
 * The card collection: six cards in rarity order. A card is earned only by real data (total focus time or the
 * streak) and the moment is saved, so it stays earned. To change a milestone, edit [rules] below.
 */
object Cards {
    enum class Kind { HOURS, STREAK }
    class Rule(val kind: Kind, val value: Int)

    /** Index matches [Rarity.tiers]: Common, Rare, Gold, Diamond, Mythic, Legendary. */
    val rules: List<Rule> = listOf(
        Rule(Kind.HOURS, 1),
        Rule(Kind.HOURS, 5),
        Rule(Kind.HOURS, 15),
        Rule(Kind.HOURS, 30),
        Rule(Kind.STREAK, 7),
        Rule(Kind.STREAK, 30)
    )

    val count: Int get() = rules.size

    fun requirement(i: Int): String {
        val r = rules.getOrNull(i) ?: return ""
        return if (r.kind == Kind.HOURS) "Complete ${r.value} hour${if (r.value == 1) "" else "s"} of total focus time"
        else "Achieve a ${r.value}-day focus streak"
    }

    fun shortRequirement(i: Int): String {
        val r = rules.getOrNull(i) ?: return ""
        return if (r.kind == Kind.HOURS) "${r.value}h of focus" else "${r.value}-day streak"
    }

    fun goalLabel(i: Int): String {
        val r = rules.getOrNull(i) ?: return ""
        return if (r.kind == Kind.HOURS) "${r.value}h" else "${r.value} days"
    }

    /** Total minutes of focus ever recorded (0 when nothing is readable). */
    fun totalMinutes(c: Context): Int = try {
        var s = 0
        for (n in 0..800) s += Store.focusMinutesAgo(c, n)
        s
    } catch (e: Exception) { 0 }

    /** Best streak seen so far: the current one, or a higher one saved earlier. */
    fun bestStreak(c: Context): Int = try {
        val sp = Store.prefs(c)
        val now = Store.streak(c)
        val best = sp.getInt("card_best_streak", 0)
        if (now > best) { sp.edit().putInt("card_best_streak", now).apply(); now } else best
    } catch (e: Exception) { 0 }

    /** Progress toward a card as (current, target) in minutes for focus time or days for streaks. */
    fun progress(c: Context, i: Int): Pair<Int, Int> {
        val r = rules.getOrNull(i) ?: return 0 to 1
        return if (r.kind == Kind.HOURS) totalMinutes(c) to r.value * 60 else bestStreak(c) to r.value
    }

    fun unlockedAt(c: Context, i: Int): Long = try { Store.prefs(c).getLong("card_at_$i", 0L) } catch (e: Exception) { 0L }
    fun isUnlocked(c: Context, i: Int) = unlockedAt(c, i) > 0L
    fun unlockedCount(c: Context): Int = (0 until count).count { isUnlocked(c, it) }

    fun seen(c: Context, i: Int): Boolean = try { Store.prefs(c).getBoolean("card_seen_$i", false) } catch (e: Exception) { true }
    fun markSeen(c: Context, i: Int) { try { Store.prefs(c).edit().putBoolean("card_seen_$i", true).apply() } catch (e: Exception) { } }

    /** Saves any card whose milestone is reached. Returns the indexes that were unlocked just now (never twice). */
    fun sync(c: Context): List<Int> {
        val fresh = ArrayList<Int>()
        try {
            val sp = Store.prefs(c)
            for (i in 0 until count) {
                if (isUnlocked(c, i)) continue
                val (cur, target) = progress(c, i)
                if (cur >= target) {
                    sp.edit().putLong("card_at_$i", System.currentTimeMillis()).apply()
                    fresh.add(i)
                }
            }
        } catch (e: Exception) { }
        return fresh
    }
}
