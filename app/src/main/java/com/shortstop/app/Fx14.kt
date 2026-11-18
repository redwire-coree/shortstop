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

/** Focus-category colours: calm, slightly deepened tones that sit well on both themes. */
object TagColors {
    fun of(tag: String): Int = Color.parseColor(when (tag) {
        "Study" -> "#1F8A5B"
        "Work" -> "#3B5BDB"
        "Read" -> "#B7791F"
        "Fitness" -> "#D9483B"
        else -> "#6B7280"
    })
    fun deep(tag: String): Int = Color.parseColor(when (tag) {
        "Study" -> "#0B3B27"
        "Work" -> "#142369"
        "Read" -> "#4A2F06"
        "Fitness" -> "#561410"
        else -> "#1F2430"
    })
}

/** One track, one pill that glides (spring) to the chosen tag and melts into its colour. */
class TagPicker(
    ctx: Context, private val pal: Palette, private val fonts: Fonts,
    private val tags: List<String>, start: String, private val onPick: (String) -> Unit
) : View(ctx) {
    private var sel = tags.indexOf(start).coerceAtLeast(0)
    private var pos = sel.toFloat()          // animated pill position in cells
    private var color = TagColors.of(tags[sel])
    private var pulse = 0f
    private var anim: android.animation.ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.semi; textAlign = Paint.Align.CENTER; textSize = ctx.dpf(13f) }
    private val r = RectF()
    private val gap = ctx.dpf(4f)

    init { isClickable = true }

    fun select(i: Int) {
        if (i == sel) return
        val from = pos
        val c0 = color
        val c1 = TagColors.of(tags[i])
        sel = i
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = SpringInterpolator(10f, 0.72f, 0.52f)
            addUpdateListener {
                val f = it.animatedValue as Float
                pos = from + (i - from) * f
                color = mixColor(c0, c1, f.coerceIn(0f, 1f))
                pulse = kotlin.math.sin(Math.PI * it.animatedFraction.coerceIn(0f, 1f)).toFloat()
                invalidate()
            }
            start()
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        onPick(tags[i])
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
            select((e.x / (width.toFloat() / tags.size)).toInt().coerceIn(0, tags.size - 1))
            performClick()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cell = w / tags.size
        // track
        r.set(1f, 1f, w - 1f, h - 1f)
        p.style = Paint.Style.FILL; p.color = pal.surface
        c.drawRoundRect(r, h / 2f, h / 2f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1f); p.color = pal.border
        c.drawRoundRect(r, h / 2f, h / 2f, p)
        // pill
        val grow = pulse * context.dpf(2f)
        r.set(pos * cell + gap - grow, gap - grow, (pos + 1) * cell - gap + grow, h - gap + grow)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, mixColor(color, Color.WHITE, 0.14f), mixColor(color, Color.BLACK, 0.12f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, p)
        p.shader = null
        // labels
        for (i in tags.indices) {
            val near = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
            val cx = (i + 0.5f) * cell
            t.color = mixColor(pal.muted, Color.WHITE, near)
            val ty = h / 2f - (t.ascent() + t.descent()) / 2f
            val tw = t.measureText(tags[i])
            val dot = context.dpf(3.2f) * (1f - near)
            val off = (dot * 2f + context.dpf(5f)) * (1f - near) / 2f
            if (dot > 0.2f) {
                p.color = TagColors.of(tags[i]); p.alpha = (255 * (1f - near)).toInt()
                c.drawCircle(cx - tw / 2f - off + dot - context.dpf(1f), h / 2f, dot, p)
                p.alpha = 255
            }
            c.drawText(tags[i], cx + off, ty, t)
        }
    }
}
