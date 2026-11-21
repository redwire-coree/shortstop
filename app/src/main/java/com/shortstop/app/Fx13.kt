package com.shortstop.app

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Port of react-bits "Dither Veil": a 1-bit Floyd-Steinberg dithered photo that the finger burns through to the
 * full-colour photo, knitting back cell by cell. Settings are the ones given: floyd, pixel 2, reveal 200,
 * softness 0.6, linger 1, contain, duotone, 2 levels, contrast 1.15, brightness 0, rim 0, click burst on.
 * The photo is assets/dither.png.
 */
class DitherVeil(ctx: Context, private val dark: Boolean, private val edgeColor: Int) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val cell = max(1, (2f * d).roundToInt())
    private val reveal = 200f * d
    private val softness = 0.6f
    private val linger = 1f
    private val hold = 1.6f
    private val contrast = 1.08f

    // Keep the page matte separate from the portrait's warmer two-tone ink.
    private val ink = if (dark) Color.parseColor("#FFEFD9") else Color.parseColor("#4A2A12")
    private val toneLight = if (dark) Color.parseColor("#D99A65") else Color.parseColor("#E7A16C")
    private val paper = if (dark) Color.parseColor("#21170F") else Color.parseColor("#FFF8F0")

    private var src: Bitmap? = null
    private var layer: Bitmap? = null      // backdrop + full-colour photo
    private var veil: Bitmap? = null       // one pixel per dither cell
    private var cols = 0
    private var rows = 0
    private var base = IntArray(0)         // dithered colour per cell
    private var pix = IntArray(0)
    private var mask = FloatArray(0)
    private var key = true

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val veilPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val dst = RectF()
    private val src2 = Rect()

    // soft feathered edges (page colour) and the upward "gradual blur" exit
    private val fadeP = Paint()
    private var fTop: Shader? = null; private var fBot: Shader? = null; private var fL: Shader? = null; private var fR: Shader? = null
    private var away = -1f
    private var snap: Bitmap? = null; private var blur1: Bitmap? = null; private var blur2: Bitmap? = null
    private val maskP = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)

    private var presence = 0f
    private var inside = false
    private var fresh = true
    private var px = 0f; private var py = 0f          // pointer target
    private var bx = 0f; private var by = 0f; private var lx = 0f; private var ly = 0f
    private var last = 0L
    private var trailUntil = 0L
    private var introStart = 0L
    private val bursts = ArrayList<FloatArray>()      // x, y, start(ms as float offset)
    private val burstStart = ArrayList<Long>()

    init {
        src = try { ctx.assets.open("dither.png").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
    }

    private fun toned(v: Float): Float = Math.pow(((v - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f).toDouble(), 1.6).toFloat()
    private fun bayer(x: Int, y: Int): Float {
        val a = x and 7; val b = y and 7; val v = a xor b
        val m = ((v and 1) shl 5) or ((b and 1) shl 4) or ((v and 2) shl 2) or ((b and 2) shl 1) or ((v and 4) shr 1) or ((b and 4) shr 2)
        return (m + 0.5f) / 64f
    }
    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt())
    private fun lum(c: Int) = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val b = src
        val h = if (b != null && w > 0) (w * b.height / b.width.toFloat()).toInt().coerceIn((190f * d).toInt(), (300f * d).toInt())
        else (260f * d).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val bmp = src ?: return
        if (w <= 0 || h <= 0) return
        // contain-fit rect
        // the photo lives in the upper 80%; the bottom 20% is the paper-to-page gradient
        val regionH = h.toFloat()
        val s = min(w / bmp.width.toFloat(), regionH / bmp.height.toFloat())
        val iw = bmp.width * s; val ih = bmp.height * s
        dst.set((w - iw) / 2f, (regionH - ih) / 2f, (w + iw) / 2f, (regionH + ih) / 2f)

        // edge colour -> matte, plain background detection
        val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
        val sq = FloatArray(3)
        for (y in 0 until 32) for (x in 0 until 32) {
            if (x > 0 && y > 0 && x < 31 && y < 31) continue
            val c = small.getPixel(x, y)
            val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
            sr += r; sg += g; sb += b; sq[0] += r * r; sq[1] += g * g; sq[2] += b * b; n++
        }
        val mr = sr / n; val mg = sg / n; val mb = sb / n
        val spread = (sqrt(max(0f, sq[0] / n - mr * mr)) + sqrt(max(0f, sq[1] / n - mg * mg)) + sqrt(max(0f, sq[2] / n - mb * mb))) / 3f
        key = spread < 0.06f
        val matte = Color.rgb((mr * 255).roundToInt(), (mg * 255).roundToInt(), (mb * 255).roundToInt())
        val backdrop = mix(ink, paper, toned(lum(matte)))

        // full-colour layer: backdrop + photo (near-matte pixels melt into the backdrop)
        val lw = w; val lh = h
        val lay = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val lc = Canvas(lay)
        lc.drawColor(if (key) backdrop else ink)
        val photo = Bitmap.createScaledBitmap(bmp, max(1, iw.roundToInt()), max(1, ih.roundToInt()), true).copy(Bitmap.Config.ARGB_8888, true)
        if (key) {
            val pp = IntArray(photo.width * photo.height)
            photo.getPixels(pp, 0, photo.width, 0, 0, photo.width, photo.height)
            for (i in pp.indices) {
                val c = pp[i]
                val dr = Color.red(c) / 255f - mr; val dg = Color.green(c) / 255f - mg; val db = Color.blue(c) / 255f - mb
                val dist = sqrt(dr * dr + dg * dg + db * db)
                val t = ((dist - 0.05f) / (0.22f - 0.05f)).coerceIn(0f, 1f)
                val plain = 1f - t * t * (3f - 2f * t)
                if (plain > 0f) pp[i] = mix(c, backdrop, plain)
            }
            photo.setPixels(pp, 0, photo.width, 0, 0, photo.width, photo.height)
        }
        lc.drawBitmap(photo, dst.left, dst.top, paint)
        layer = lay

        // dither: Floyd-Steinberg (serpentine), 1-bit warm duotone
        cols = ceil(w / cell.toFloat()).toInt(); rows = ceil(h / cell.toFloat()).toInt()
        val samp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        val sc = Canvas(samp)
        sc.drawColor(matte)
        sc.save()
        sc.scale(1f / cell, 1f / cell)
        sc.drawBitmap(bmp, null, dst, paint)
        sc.restore()
        val sp = IntArray(cols * rows)
        samp.getPixels(sp, 0, cols, 0, 0, cols, rows)
        val vals = FloatArray(cols * rows) { toned(lum(sp[it])) }
        base = IntArray(cols * rows)
        for (y in 0 until rows) {
            val dir = if (y and 1 == 1) -1 else 1
            for (i in 0 until cols) {
                val x = if (dir > 0) i else cols - 1 - i
                val p = y * cols + x
                val old = vals[p]
                val q = if (old >= 0.5f) 1f else 0f
                val err = old - q
                val inImg = (x + 0.5f) * cell >= dst.left && (x + 0.5f) * cell <= dst.right &&
                    (y + 0.5f) * cell >= dst.top && (y + 0.5f) * cell <= dst.bottom
                base[p] = if (inImg || key) mix(ink, toneLight, q) else ink
                fun add(dx: Int, dy: Int, k: Float) {
                    val nx = x + dx * dir; val ny = y + dy
                    if (nx < 0 || nx >= cols || ny >= rows) return
                    vals[ny * cols + nx] += err * k
                }
                add(1, 0, 7f / 16); add(-1, 1, 3f / 16); add(0, 1, 5f / 16); add(1, 1, 1f / 16)
            }
        }
        veil = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        pix = IntArray(cols * rows)
        mask = FloatArray(cols * rows)
        introStart = SystemClock.uptimeMillis()
        last = introStart
        px = w / 2f; py = h / 2f
        val clear = edgeColor and 0x00FFFFFF
        fTop = LinearGradient(0f, 0f, 0f, h * 0.45f, edgeColor, clear, Shader.TileMode.CLAMP)
        fBot = LinearGradient(0f, h * 0.80f, 0f, h * 0.99f, paper, edgeColor, Shader.TileMode.CLAMP)
        fL = LinearGradient(0f, 0f, w * 0.16f, 0f, edgeColor, clear, Shader.TileMode.CLAMP)
        fR = LinearGradient(w.toFloat(), 0f, w * 0.84f, 0f, edgeColor, clear, Shader.TileMode.CLAMP)
        compose(0f, 0)
        postInvalidateOnAnimation()
    }

    private fun compose(intro: Float, now: Long) {
        val v = veil ?: return
        val aspectX = width.toFloat() / height
        val bw = max(60f * d, reveal * 0.9f)
        // active bursts
        val act = ArrayList<FloatArray>()
        for (i in bursts.indices) {
            val k = (now - burstStart[i]) / 1000f / 1.2f
            if (k >= 1f) continue
            val b = bursts[i]
            val reach = hypot(max(b[0], width - b[0]), max(b[1], height - b[1])) + bw
            act.add(floatArrayOf(b[0], b[1], reach * sin(k * Math.PI.toFloat() / 2f), 1f - k * k * k))
        }
        val introEase = 1f - (1f - intro).let { it * it }
        for (y in 0 until rows) {
            val cy = (y + 0.5f) * cell
            for (x in 0 until cols) {
                val p = y * cols + x
                val cx = (x + 0.5f) * cell
                var shown = (mask[p] * hold).coerceIn(0f, 1f)
                for (b in act) {
                    val off = hypot(cx - b[0], cy - b[1]) - b[2]
                    val e = if (off > 0f) off / (bw * 0.35f) else -off / bw
                    shown = max(shown, (1f - e).coerceIn(0f, 1f) * b[3])
                }
                var out = if (shown >= bayer(y, x)) 0 else base[p]
                if (introEase < 1f) {
                    val ux = (cx / width - 0.5f) * aspectX; val uy = cy / height - 0.5f
                    val spread = hypot(ux, uy) / hypot(aspectX * 0.5f, 0.5f)
                    if (spread * 0.72f + bayer(x + 3, y + 5) * 0.28f > introEase * 1.001f) out = paper
                }
                pix[p] = out
            }
        }
        v.setPixels(pix, 0, cols, 0, 0, cols, rows)
    }

    private fun step(now: Long) {
        val dt = min(0.05f, max(0f, (now - last) / 1000f)); last = now
        presence += ((if (inside) 1f else 0f) - presence) * (1f - exp(-dt / 0.16f))
        if (fresh) { bx = px; by = py; lx = px; ly = py; fresh = false }
        else { val f = 1f - exp(-dt / 0.035f); bx += (px - bx) * f; by += (py - by) * f }
        if (presence > 0.002f) trailUntil = now + (linger * 1000).toLong() + 150
        val fade = if (linger > 0f) dt / linger else 1f
        for (i in mask.indices) { val m = mask[i] - fade; mask[i] = if (m > 0f) m else 0f }
        val r = reveal * (0.45f + 0.55f * presence)
        val band = max(r * softness, 1f) * hold
        if (presence > 0.002f) {
            val x0 = max(0, ((min(lx, bx) - r) / cell).toInt()); val x1 = min(cols - 1, ((max(lx, bx) + r) / cell).toInt())
            val y0 = max(0, ((min(ly, by) - r) / cell).toInt()); val y1 = min(rows - 1, ((max(ly, by) + r) / cell).toInt())
            val abx = bx - lx; val aby = by - ly
            val ab2 = max(abx * abx + aby * aby, 0.0001f)
            for (y in y0..y1) {
                val cy = (y + 0.5f) * cell
                for (x in x0..x1) {
                    val cx = (x + 0.5f) * cell
                    val h = (((cx - lx) * abx + (cy - ly) * aby) / ab2).coerceIn(0f, 1f)
                    val dist = hypot(cx - lx - abx * h, cy - ly - aby * h)
                    val t = ((r - dist) / band).coerceIn(0f, 1f) * presence
                    val p = y * cols + x
                    if (t > mask[p]) mask[p] = t
                }
            }
        }
        lx = bx; ly = by
    }

    private fun drawScene(c: Canvas) {
        val lay = layer ?: return
        val v = veil ?: return
        c.drawBitmap(lay, 0f, 0f, null)
        c.save()
        c.scale(cell.toFloat(), cell.toFloat())
        c.drawBitmap(v, 0f, 0f, veilPaint)
        c.restore()
    }

    private fun drawFade(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        fadeP.shader = fBot
        c.drawRect(0f, h * 0.94f, w, h, fadeP)
    }

    private fun blurred(src: Bitmap, f: Int): Bitmap {
        val w = src.width; val h = src.height
        var cur = Bitmap.createScaledBitmap(src, max(1, w / 2), max(1, h / 2), true)
        var k = 2
        while (k < f) { k *= 2; cur = Bitmap.createScaledBitmap(cur, max(1, w / k), max(1, h / k), true) }
        while (k > 1) { k /= 2; cur = Bitmap.createScaledBitmap(cur, max(1, w / k), max(1, h / k), true) }
        return cur
    }

    /** Tapping + : the photo blurs upward (a gradual blur front rising from the bottom), lifts and fades, then onDone. */
    fun playAway(onDone: () -> Unit) {
        if (away >= 0f || layer == null || width <= 0) { onDone(); return }
        val sn = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawScene(Canvas(sn))
        snap = sn
        blur1 = blurred(sn, 8)
        blur2 = blurred(sn, 32)
        away = 0f
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 760
            interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { away = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { onDone() }
            })
            start()
        }
    }

    private fun maskedBlur(c: Canvas, b: Bitmap, front: Float, soft: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        c.saveLayer(0f, 0f, w, h, null)
        c.drawBitmap(b, null, RectF(0f, 0f, w, h), bmpP)
        maskP.shader = LinearGradient(0f, front - soft, 0f, front + soft, 0x00000000, -0x1000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, maskP)
        c.restore()
    }

    private fun drawAway(c: Canvas) {
        val sn = snap ?: return
        val p = away
        val w = width.toFloat(); val h = height.toFloat()
        val soft = h * 0.3f
        val front = h + soft - (h + 2f * soft) * p
        val t = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val alpha = ((1f - t * t * (3f - 2f * t)) * 255).toInt()
        c.save()
        c.translate(0f, -28f * d * p)
        c.saveLayerAlpha(0f, 0f, w, h, alpha)
        c.drawBitmap(sn, 0f, 0f, null)
        blur1?.let { maskedBlur(c, it, front, soft) }
        blur2?.let { maskedBlur(c, it, front + 0.12f * h, soft) }
        c.restore()
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        if (away >= 0f) { drawAway(c); drawFade(c); return }
        if (layer == null || veil == null) return
        val now = SystemClock.uptimeMillis()
        val intro = min(1f, (now - introStart) / 1100f)
        step(now)
        for (i in bursts.indices.reversed()) if (now - burstStart[i] > 1200) { bursts.removeAt(i); burstStart.removeAt(i) }
        compose(intro, now)
        drawScene(c)
        drawFade(c)
        val busy = inside || presence > 0.002f || bursts.isNotEmpty() || now < trailUntil || intro < 1f
        if (busy && isShown) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (away >= 0f) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                px = e.x; py = e.y
                if (!inside) { inside = true; fresh = true }
                bursts.add(floatArrayOf(e.x, e.y)); burstStart.add(SystemClock.uptimeMillis())
                if (bursts.size > 4) { bursts.removeAt(0); burstStart.removeAt(0) }
                last = SystemClock.uptimeMillis()
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_MOVE -> { px = e.x; py = e.y; postInvalidateOnAnimation() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { inside = false; postInvalidateOnAnimation() }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        layer?.recycle(); veil?.recycle(); layer = null; veil = null
        snap?.recycle(); blur1?.recycle(); blur2?.recycle(); snap = null; blur1 = null; blur2 = null
    }
}
