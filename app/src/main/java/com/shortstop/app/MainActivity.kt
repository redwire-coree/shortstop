package com.shortstop.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Arc-style control panel: electric blue hero, cream settings band. Built in code, no dependencies. */
class MainActivity : Activity() {

    private val blue = Color.parseColor("#3B3BFF")
    private val indigo = Color.parseColor("#1A1A8C")
    private val cream = Color.parseColor("#FFFEF0")
    private val muted = Color.parseColor("#6B6B8C")
    private val white = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = indigo
        window.navigationBarColor = cream
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun font(file: String, fallback: Typeface): Typeface =
        try { Typeface.createFromAsset(assets, "fonts/$file") } catch (e: Exception) { fallback }

    // Display: Nunito Black (fetched at build time, else system black).
    private val fDisplay by lazy { font("Nunito-Black.ttf", Typeface.create("sans-serif-black", Typeface.NORMAL)) }
    private val body by lazy { font("Inter-Regular.otf", Typeface.DEFAULT) }
    private val mono by lazy { font("IBMPlexMono-Bold.ttf", Typeface.create("monospace", Typeface.BOLD)) }

    private fun grain(): Drawable {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val r = java.util.Random(7)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, Color.argb(r.nextInt(46), 255, 255, 255))
        val tile = BitmapDrawable(resources, bmp).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        return LayerDrawable(arrayOf(ColorDrawable(blue), tile))
    }

    private fun pill(color: Int, w: Int, h: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = h / 2f; setSize(w, h)
    }

    private fun styleSwitch(sw: Switch) {
        sw.trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), pill(blue, dp(46), dp(26)))
            addState(intArrayOf(), pill(Color.parseColor("#CFCFE6"), dp(46), dp(26)))
        }
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(white); setSize(dp(20), dp(20)) }
        sw.thumbDrawable = InsetDrawable(dot, dp(3))
        sw.showText = false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.split(':').any { it.startsWith("$packageName/") }
    }

    private fun text(t: String, sp: Float, color: Int, face: Typeface, gravity: Int = Gravity.START) =
        TextView(this).apply {
            text = t; textSize = sp; setTextColor(color); typeface = face; this.gravity = gravity
        }

    private fun label(t: String, color: Int) =
        text(t.uppercase(), 11f, color, mono).apply { letterSpacing = 0.08f }

    private fun pillButton(t: String, fill: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        text = t.uppercase()
        typeface = mono
        textSize = 12f
        letterSpacing = 0.05f
        setTextColor(fg)
        stateListAnimator = null
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(12).toFloat() }
        setPadding(dp(24), dp(12), dp(24), dp(12))
        setOnClickListener { onClick() }
    }

    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 18f, blue, fDisplay))
        col.addView(label(sub, muted))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            styleSwitch(this)
            setOnCheckedChangeListener { _, v -> onChange(v); render() }
        })
        return row
    }

    private fun render() {
        val snoozeLeft = Store.snoozeUntil(this) - System.currentTimeMillis()
        val serviceEnabled = serviceOn()

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Announcement bar
        column.addView(
            text("Your attention, un-hijacked ✦", 13f, white, body, Gravity.CENTER).apply {
                setBackgroundColor(indigo); setPadding(dp(16), dp(10), dp(16), dp(10))
            }
        )

        // Hero
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = grain()
            setPadding(dp(24), dp(36), dp(24), dp(48))
        }
        hero.addView(text("Shortstop", 18f, white, fDisplay))

        val (headline, lede, actionLabel, action) = when {
            !serviceEnabled -> Quad(
                "Almost there.",
                "Turn on the Shortstop accessibility service so it can spot Shorts and Reels.",
                "Enable service"
            ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            !Store.enabled(this) -> Quad(
                "Blocking is off.", "Shorts and Reels are back. Switch on when you want your thumb back.", "Turn on"
            ) { Store.setEnabled(this, true); render() }
            snoozeLeft > 0 -> Quad(
                "Snoozed ${(snoozeLeft + 59999) / 60000}m.", "Blocking resumes on its own.", "Resume now"
            ) { Store.setSnooze(this, 0); render() }
            else -> Quad(
                "Shorts are off.", "Back is pressed for you the moment a Short or Reel appears.", "Turn off"
            ) { Store.setEnabled(this, false); render() }
        }
        hero.addView(text(headline, 40f, white, fDisplay, Gravity.CENTER)
            .apply { setPadding(0, dp(24), 0, dp(12)) })
        hero.addView(text(lede, 15f, white, body, Gravity.CENTER).apply { setPadding(0, 0, 0, dp(24)) })
        hero.addView(pillButton(actionLabel, white, blue, action))
        column.addView(hero)

        // Cream band
        val band = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cream)
            setPadding(dp(24), dp(28), dp(24), dp(40))
        }
        band.addView(label("Where to block", muted))
        for ((key, name) in Store.SITES) {
            band.addView(
                switchRow(name, if (key == "tiktok") "Whole app" else "Auto-Back on the player", Store.site(this, key)) {
                    Store.setSite(this, key, it)
                }
            )
        }

        band.addView(label("Need a break from the break?", muted).apply { setPadding(0, dp(24), 0, dp(12)) })
        val snoozeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5 to "5 min", 15 to "15 min", 60 to "1 hour").forEach { (mins, name) ->
            val b = pillButton(name, indigo, white) {
                Store.setSnooze(this, System.currentTimeMillis() + mins * 60_000L); render()
            }
            snoozeRow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
        }
        band.addView(snoozeRow)

        band.addView(label("Blocked so far", muted).apply { setPadding(0, dp(28), 0, dp(8)) })
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(n: Int, l: String): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("$n", 40f, blue, fDisplay))
            addView(label(l, blue))
        }
        stats.addView(stat(Store.today(this), "Today"), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(stat(Store.total(this), "All time"), LinearLayout.LayoutParams(0, -2, 1f))
        band.addView(stats)

        band.addView(label("Runs offline · no tracking", muted).apply { setPadding(0, dp(32), 0, 0) })
        column.addView(band)

        setContentView(ScrollView(this).apply { setBackgroundColor(cream); addView(column) })
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: () -> Unit)
}
