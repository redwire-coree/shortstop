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

/** Focus-category colours (the little dots): vivid, distinct, readable on light and dark. */
object TagColors {
    fun of(tag: String): Int = Color.parseColor(when (tag) {
        "Study" -> "#10B981"
        "Work" -> "#6366F1"
        "Code", "Read" -> "#06B6D4"
        "Fitness" -> "#F43F5E"
        else -> "#A855F7"
    })
    fun deep(tag: String): Int = mixColor(of(tag), Color.BLACK, 0.68f)
}

/**
 * Port of react-bits PillNav (hoverEffect "fill", indicator "pill"): raised pills on a glass bar. The tapped pill
 * fills with orange rising from its bottom edge in a circle while its label rolls up and a white copy rolls in;
 * the previous pill drains back down. Orange is the same ember gradient as the Stats chart.
 */
class TagPicker(
    ctx: Context, private val pal: Palette, private val fonts: Fonts,
    private val tags: List<String>, start: String, private val onPick: (String) -> Unit
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var sel = tags.indexOf(start).coerceAtLeast(0)
    private val f = FloatArray(tags.size).also { it[sel] = 1f }
    private val anims = arrayOfNulls<android.animation.ValueAnimator>(tags.size)
    private val HI = Color.parseColor("#FFB27A")
    private val LO = Color.parseColor("#FF6A2B")
    private val pad = 4f * d
    private val gap = 3f * d
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.semi; textAlign = Paint.Align.CENTER; textSize = 14f * d }
    private val r = RectF()
    private val clip = Path()
    private var cell = 0f
    private val easeIn = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val easeOut = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

    init { isClickable = true }

    private fun t0(i: Int) = pad + i * (cell + gap)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cell = (w - pad * 2 - gap * (tags.size - 1)) / tags.size
    }

    private fun run(i: Int, to: Float) {
        anims[i]?.cancel()
        val from = f[i]
        anims[i] = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = if (to > from) 560 else 380
            interpolator = if (to > from) easeIn else easeOut
            addUpdateListener { f[i] = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun select(i: Int) {
        if (i == sel) return
        run(sel, 0f)
        sel = i
        run(i, 1f)
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        onPick(tags[i])
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
            select(((e.x - pad) / (cell + gap)).toInt().coerceIn(0, tags.size - 1))
            performClick()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (cell <= 0f) return
        val w = width.toFloat(); val h = height.toFloat()
        val dark = pal.dark
        r.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        paint.style = Paint.Style.FILL
        paint.color = if (dark) Color.argb(168, 38, 35, 46) else Color.argb(204, 240, 240, 243)
        c.drawRoundRect(r, h / 2f, h / 2f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d
        paint.color = if (dark) Color.argb(20, 255, 255, 255) else Color.argb(18, 24, 24, 27)
        c.drawRoundRect(r, h / 2f, h / 2f, paint)

        val ih = h - pad * 2f
        val strong = if (dark) Color.parseColor("#F4F4F5") else Color.parseColor("#18181B")
        val roll = ih / 2f + 0.7f * txt.textSize
        val reach = Math.sqrt(((cell / 2f) * (cell / 2f) + ih * ih).toDouble()).toFloat() + 2f * d
        val ty0 = pad + ih / 2f - (txt.ascent() + txt.descent()) / 2f
        for (i in tags.indices) {
            val l = t0(i); val rgt = l + cell
            r.set(l, pad, rgt, pad + ih)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, r.top, 0f, r.bottom,
                if (dark) Color.parseColor("#3A3644") else Color.WHITE,
                if (dark) Color.parseColor("#27242F") else Color.parseColor("#F6F6F8"), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, ih / 2f, ih / 2f, paint)
            paint.shader = null
            c.save()
            clip.reset(); clip.addRoundRect(r, ih / 2f, ih / 2f, Path.Direction.CW)
            c.clipPath(clip)
            val fi = f[i]
            if (fi > 0.001f) {
                paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, HI, LO, Shader.TileMode.CLAMP)
                paint.alpha = 255
                c.drawCircle((l + rgt) / 2f, r.bottom, reach * fi, paint)
                paint.shader = null
            }
            val cx = (l + rgt) / 2f
            txt.color = strong
            c.drawText(tags[i], cx, ty0 - roll * fi, txt)
            if (fi > 0.001f) {
                txt.color = Color.WHITE
                c.drawText(tags[i], cx, ty0 + roll * (1f - fi), txt)
            }
            c.restore()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = d
            paint.color = if (dark) Color.argb(18, 255, 255, 255) else Color.argb(20, 24, 24, 27)
            r.inset(0.5f, 0.5f)
            c.drawRoundRect(r, ih / 2f, ih / 2f, paint)
        }
    }
}

/** Left-to-right wrapping rows (used for the Popular sites so nothing is cut off). */
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : android.view.ViewGroup(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec)
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

private fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** A +, − or round "+" drawn from lines so the glyph is exactly centred (text glyphs sit off-centre in their box). */
class GlyphBtn(ctx: Context, private val plus: Boolean, private val bg: Int, private val fg: Int, private val stroke: Int, private val round: Boolean) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    init { isClickable = true }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        p.style = Paint.Style.FILL; p.color = bg
        c.drawRoundRect(r, h / 2f, h / 2f, p)
        if (stroke != 0) { p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = stroke; c.drawRoundRect(r, h / 2f, h / 2f, p) }
        p.style = Paint.Style.STROKE; p.color = fg; p.strokeWidth = 2f * d; p.strokeCap = Paint.Cap.ROUND
        val cx = w / 2f; val cy = h / 2f
        val a = (if (round) 0.26f else 0.2f) * minOf(w, h * 1.4f)
        val arm = minOf(a, 7f * d)
        c.drawLine(cx - arm, cy, cx + arm, cy, p)
        if (plus) c.drawLine(cx, cy - arm, cx, cy + arm, p)
    }
}
