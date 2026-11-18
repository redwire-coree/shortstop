package com.shortstop.app

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Website logos: fetched once as favicons, cached on disk, with a branded letter tile until they arrive. */
object Favicons {
    private val mem = HashMap<String, Bitmap>()
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private val waiting = HashMap<String, MutableList<(Bitmap) -> Unit>>()

    val BRAND = mapOf(
        "instagram.com" to "#E1306C", "youtube.com" to "#FF0000", "x.com" to "#111111", "reddit.com" to "#FF4500",
        "facebook.com" to "#1877F2", "tiktok.com" to "#111111", "netflix.com" to "#E50914", "twitch.tv" to "#9146FF",
        "snapchat.com" to "#FFD400", "pinterest.com" to "#E60023", "twitter.com" to "#1D9BF0", "linkedin.com" to "#0A66C2",
        "discord.com" to "#5865F2", "whatsapp.com" to "#25D366", "amazon.com" to "#FF9900", "spotify.com" to "#1DB954"
    )

    fun brandColor(domain: String): Int {
        val root = BRAND.keys.firstOrNull { domain == it || domain.endsWith(".$it") }
        if (root != null) return Color.parseColor(BRAND[root])
        val h = (domain.hashCode() and 0x7fffffff) % 360
        return Color.HSVToColor(floatArrayOf(h.toFloat(), 0.55f, 0.72f))
    }

    fun get(ctx: Context, domain: String, cb: (Bitmap) -> Unit) {
        mem[domain]?.let { cb(it); return }
        if (domain in failed) return
        val list = waiting.getOrPut(domain) { ArrayList() }
        list.add(cb)
        if (list.size > 1) return
        val app = ctx.applicationContext
        pool.execute {
            var bmp: Bitmap? = null
            try {
                val f = File(app.cacheDir, "fav_" + domain.replace(Regex("[^a-z0-9.]"), "_") + ".png")
                if (f.exists()) bmp = BitmapFactory.decodeFile(f.path)
                if (bmp == null) {
                    val c = URL("https://www.google.com/s2/favicons?domain=$domain&sz=128").openConnection() as HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 5000
                    c.inputStream.use { bmp = BitmapFactory.decodeStream(it) }
                    bmp?.let { b -> f.outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) } }
                }
            } catch (e: Exception) { }
            ui.post {
                val cbs = waiting.remove(domain) ?: return@post
                val b = bmp
                if (b == null) failed.add(domain) else { mem[domain] = b; cbs.forEach { it(b) } }
            }
        }
    }
}

class SiteLogo(ctx: Context, private val domain: String, private val pal: Palette, private val fonts: Fonts) : View(ctx) {
    private var icon: Bitmap? = null
    private val brand = Favicons.brandColor(domain)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = fonts.bold }
    private val r = RectF()
    private var shown = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (icon == null) Favicons.get(context, domain) { b ->
            icon = b
            animate().cancel()
            shown = 0f
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = w * 0.28f
        r.set(0f, 0f, w, h)
        val ic = icon
        // branded letter tile underneath
        p.style = Paint.Style.FILL
        p.color = brand
        c.drawRoundRect(r, rad, rad, p)
        val lum = (0.299f * Color.red(brand) + 0.587f * Color.green(brand) + 0.114f * Color.blue(brand)) / 255f
        t.color = if (lum > 0.7f) Color.parseColor("#111111") else Color.WHITE
        t.textSize = h * 0.46f
        c.drawText(domain.first().uppercaseChar().toString(), w / 2f, h / 2f - (t.ascent() + t.descent()) / 2f, t)
        if (ic != null && shown > 0f) {
            p.color = Color.WHITE
            p.alpha = (255 * shown).toInt()
            c.drawRoundRect(r, rad, rad, p)
            p.alpha = 255
            val pad = w * 0.2f
            val dst = RectF(pad, pad, w - pad, h - pad)
            val bp = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * shown).toInt() }
            c.drawBitmap(ic, null, dst, bp)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f
            p.color = Color.argb((40 * shown).toInt(), 0, 0, 0)
            c.drawRoundRect(r, rad, rad, p)
        }
    }
}

/** Focus-category colours: a green that sits on the warm palette, not a flat default green. */
object TagColors {
    fun of(tag: String): Int = Color.parseColor(when (tag) {
        "Study" -> "#2E9E6B"
        "Work" -> "#4763EB"
        "Read" -> "#D08A2E"
        "Fitness" -> "#E2574C"
        else -> "#7C7C8A"
    })
    fun deep(tag: String): Int = Color.parseColor(when (tag) {
        "Study" -> "#0F3D2A"
        "Work" -> "#14216B"
        "Read" -> "#5A3A0A"
        "Fitness" -> "#5E1410"
        else -> "#262630"
    })
}
