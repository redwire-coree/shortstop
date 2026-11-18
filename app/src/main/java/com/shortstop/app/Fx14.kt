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
 * Port of react-bits PillNav (split layout, pill items, pill indicator, Ember highlight #FF5A1F, bounce 0.71,
 * stretch 0.5): raised pills on a glass bar, one orange pill glides to the tapped item with a stretching
 * two-edge spring, white labels ride inside it. Each idle tag keeps its small coloured dot.
 */
class TagPicker(
    ctx: Context, private val pal: Palette, private val fonts: Fonts,
    private val tags: List<String>, start: String, private val onPick: (String) -> Unit
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var sel = tags.indexOf(start).coerceAtLeast(0)
    private var x0 = 0f; private var x1 = 0f; private var v0 = 0f; private var v1 = 0f
    private var press = 1f; private var pressV = 0f; private var pressing = false
    private var pop = 1f
    private var ready = false
    private var last = 0L
    private val HI = Color.parseColor("#FF5A1F")
    private val bounce = 0.71f
    private val stretch = 0.5f
    private val pad = 4f * d
    private val gap = 3f * d
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.medium; textAlign = Paint.Align.LEFT; textSize = 13f * d }
    private val r = RectF()
    private val clip = Path()
    private var cell = 0f

    init { isClickable = true }

    private fun t0(i: Int) = pad + i * (cell + gap)
    private fun t1(i: Int) = t0(i) + cell

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cell = (w - pad * 2 - gap * (tags.size - 1)) / tags.size
        x0 = t0(sel); x1 = t1(sel); v0 = 0f; v1 = 0f
        ready = true
    }

    fun select(i: Int) {
        if (i == sel) return
        sel = i
        pop = 0.92f
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        onPick(tags[i])
        last = 0L
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { pressing = true; last = 0L; postInvalidateOnAnimation(); parent?.requestDisallowInterceptTouchEvent(true) }
            android.view.MotionEvent.ACTION_UP -> {
                pressing = false
                select(((e.x - pad) / (cell + gap)).toInt().coerceIn(0, tags.size - 1))
                performClick()
                last = 0L; postInvalidateOnAnimation()
            }
            android.view.MotionEvent.ACTION_CANCEL -> { pressing = false; last = 0L; postInvalidateOnAnimation() }
        }
        return true
    }

    /** Advances the springs; returns true while anything is still moving. */
    private fun step(now: Long): Boolean {
        val dt = if (last == 0L) 1f / 60f else minOf(0.05f, (now - last) / 1e9f)
        last = now
        val a = t0(sel); val b = t1(sel)
        val dir = Math.signum((a + b) / 2f - (x0 + x1) / 2f)
        val base = 520f
        val lead = base * (1f + 0.9f * stretch)
        val trail = base * (1f - 0.5f * stretch)
        val k0 = if (dir < 0) lead else if (dir > 0) trail else base
        val k1 = if (dir > 0) lead else if (dir < 0) trail else base
        val zeta = 1f - 0.55f * bounce
        val steps = Math.ceil((dt / (1f / 240f)).toDouble()).toInt().coerceAtLeast(1)
        val h = dt / steps
        for (i in 0 until steps) {
            v0 += (-k0 * (x0 - a) - 2f * zeta * Math.sqrt(k0.toDouble()).toFloat() * v0) * h
            x0 += v0 * h
            v1 += (-k1 * (x1 - b) - 2f * zeta * Math.sqrt(k1.toDouble()).toFloat() * v1) * h
            x1 += v1 * h
        }
        pop += (1f - pop) * (1f - Math.exp((-dt / 0.09f).toDouble()).toFloat())
        val pt = if (pressing) 0.95f else 1f
        pressV += (-700f * (press - pt) - 30f * pressV) * dt
        press += pressV * dt
        return Math.abs(x0 - a) > 0.05f || Math.abs(x1 - b) > 0.05f || Math.abs(v0) > 0.5f || Math.abs(v1) > 0.5f ||
            Math.abs(1f - pop) > 0.001f || Math.abs(press - pt) > 0.0005f || Math.abs(pressV) > 0.01f
    }

    private fun label(c: Canvas, i: Int, color: Int, dotAlpha: Float) {
        val h = height.toFloat()
        val cx = (t0(i) + t1(i)) / 2f
        val name = tags[i]
        val tw = txt.measureText(name)
        val dot = 2.1f * d
        val dg = 5f * d
        val total = tw + (dot * 2f + dg)
        val left = cx - total / 2f
        val ty = h / 2f - (txt.ascent() + txt.descent()) / 2f
        if (dotAlpha > 0.01f) {
            paint.style = Paint.Style.FILL
            paint.color = TagColors.of(name)
            paint.alpha = (255 * dotAlpha).toInt()
            c.drawCircle(left + dot, h / 2f, dot, paint)
            paint.alpha = 255
        }
        txt.color = color
        c.drawText(name, left + dot * 2f + dg, ty, txt)
    }

    override fun onDraw(c: Canvas) {
        if (!ready) return
        val moving = step(System.nanoTime())
        val w = width.toFloat(); val h = height.toFloat()
        val dark = pal.dark
        // glass bar
        r.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        paint.style = Paint.Style.FILL
        paint.color = if (dark) Color.argb(168, 38, 35, 46) else Color.argb(204, 240, 240, 243)
        c.drawRoundRect(r, h / 2f, h / 2f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d
        paint.color = if (dark) Color.argb(20, 255, 255, 255) else Color.argb(18, 24, 24, 27)
        c.drawRoundRect(r, h / 2f, h / 2f, paint)
        // raised item pills
        val ih = h - pad * 2f
        val strong = if (dark) Color.parseColor("#F4F4F5") else Color.parseColor("#18181B")
        val pillL = x0; val pillR = x1
        for (i in tags.indices) {
            r.set(t0(i), pad, t1(i), pad + ih)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, r.top, 0f, r.bottom,
                if (dark) Color.parseColor("#3A3644") else Color.WHITE,
                if (dark) Color.parseColor("#27242F") else Color.parseColor("#F6F6F8"), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, ih / 2f, ih / 2f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE; paint.strokeWidth = d
            paint.color = if (dark) Color.argb(18, 255, 255, 255) else Color.argb(20, 24, 24, 27)
            r.inset(0.5f, 0.5f)
            c.drawRoundRect(r, ih / 2f, ih / 2f, paint)
            val ov = (minOf(t1(i), pillR) - maxOf(t0(i), pillL)).coerceAtLeast(0f) / cell
            label(c, i, strong, 1f - ov.coerceIn(0f, 1f))
        }
        // gliding orange pill + the white labels riding inside it
        val width = (x1 - x0).coerceAtLeast(0f)
        val scale = press * pop
        val squash = 1f - ((width / cell - 1f) * 0.3f).coerceIn(0f, 0.14f)
        val cxp = (x0 + x1) / 2f; val cyp = pad + ih / 2f
        val hw = width * scale / 2f; val hh = ih * scale * squash / 2f
        r.set(cxp - hw, cyp - hh, cxp + hw, cyp + hh)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(46, 255, 90, 31)
        r.inset(-d * 1.5f, -d * 1.5f)
        c.drawRoundRect(r, hh + d, hh + d, paint)
        r.inset(d * 1.5f, d * 1.5f)
        paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, mixColor(HI, Color.WHITE, 0.1f), HI, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, hh, hh, paint)
        paint.shader = null
        c.save()
        clip.reset(); clip.addRoundRect(r, hh, hh, Path.Direction.CW)
        c.clipPath(clip)
        for (i in tags.indices) if (t1(i) > pillL - 2f && t0(i) < pillR + 2f) label(c, i, Color.WHITE, 0f)
        c.restore()
        if (moving) postInvalidateOnAnimation() else last = 0L
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
