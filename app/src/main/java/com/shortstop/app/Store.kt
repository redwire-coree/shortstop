package com.shortstop.app

import android.content.Context
import java.util.Calendar

/** Tiny SharedPreferences wrapper shared by the UI and the accessibility service. */
object Store {
    val SITES = linkedMapOf(
        "youtube" to "YouTube Shorts",
        "instagram" to "Instagram Reels",
        "facebook" to "Facebook Reels",
        "tiktok" to "TikTok"
    )

    private val PACKAGES = mapOf(
        "com.google.android.youtube" to "youtube",
        "com.instagram.android" to "instagram",
        "com.facebook.katana" to "facebook",
        "com.facebook.lite" to "facebook",
        "com.zhiliaoapp.musically" to "tiktok",
        "com.ss.android.ugc.trill" to "tiktok"
    )

    fun siteFor(pkg: String): String? = PACKAGES[pkg]

    private fun p(c: Context) = c.getSharedPreferences("shortstop", Context.MODE_PRIVATE)

    fun enabled(c: Context) = p(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()

    fun site(c: Context, s: String) = p(c).getBoolean("site_$s", true)
    fun setSite(c: Context, s: String, v: Boolean) = p(c).edit().putBoolean("site_$s", v).apply()

    fun snoozeUntil(c: Context) = p(c).getLong("snooze", 0L)
    fun setSnooze(c: Context, until: Long) = p(c).edit().putLong("snooze", until).apply()

    fun isActive(c: Context, site: String) =
        enabled(c) && System.currentTimeMillis() >= snoozeUntil(c) && site(c, site)

    private fun dayKey(): String {
        val d = Calendar.getInstance()
        return "day_${d.get(Calendar.YEAR)}_${d.get(Calendar.DAY_OF_YEAR)}"
    }

    fun bump(c: Context) {
        val sp = p(c)
        sp.edit()
            .putInt("total", sp.getInt("total", 0) + 1)
            .putInt(dayKey(), sp.getInt(dayKey(), 0) + 1)
            .apply()
    }

    fun today(c: Context) = p(c).getInt(dayKey(), 0)
    fun total(c: Context) = p(c).getInt("total", 0)
}
