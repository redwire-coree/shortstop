package com.shortstop.app

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

private fun Context.dp3(v: Float) = v * resources.displayMetrics.density

/** Underdamped spring, so motion settles the way a real object does instead of following a fixed curve. */
class SpringInterpolator(
    private val omega: Float = 15f,
    private val zeta: Float = 0.55f,
    private val seconds: Float = 0.9f
) : TimeInterpolator {
    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        val s = t * seconds
        val wd = omega * Math.sqrt(1.0 - zeta * zeta).toFloat()
        val e = Math.exp(-(zeta * omega * s).toDouble()).toFloat()
        return 1f - e * (Math.cos((wd * s).toDouble()).toFloat() + zeta * omega / wd * Math.sin((wd * s).toDouble()).toFloat())
    }
}

// ------------------------------------------------------------------ Gooey nav (react-bits, ported engine)

/**
 * Same engine as react-bits GooeyNav: the old pill gives up its mass as droplets that fly on bezier paths and are
 * absorbed by the new pill, which grows on a damped spring. The shapes are merged with a smooth-min distance
 * field, exactly like the original shader, then drawn as one soft blob.
 */
class GooeyNav(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    private val labels: List<String>,
    initial: Int,
    private val onTap: (Int) -> Unit
) : View(ctx) {
    private val particleCount = 15
    private val spreadDp = 56f
    private val animationTime = 600f
    private val timeVariance = 300f
    private val gooeyness = 0.5f
    private val wobble = 0.5f
    private val maxDrops = 32

    private class Pill { var mass = 0f; var scale = 0f; var velocity = 0f; var outgoing = 0f }

    private class Drop(var x: Float, var y: Float, var mass: Float, val size: Float, val source: Int, var index: Int, var leave: Long) {
        var vx = 0f
        var vy = 0f
        var given = 0f
        var emerged = 0f
        var absorbed = 0f
        var state = 0 // 0 waiting, 1 flying, 2 absorbing
        var start = 0L
        var duration = 0f
        val path = FloatArray(8)
    }

    private val d = ctx.dp3(1f)
    private val rects = ArrayList<RectF>()
    private val pills = LinkedHashMap<Int, Pill>()
    private val drops = ArrayList<Drop>()
    private var target = initial
    private var last = 0L
    private val rnd = java.util.Random()

    private var hIndex = -1
    private var hx = 0f
    private var hy = 0f
    private var hw = 0f
    private var hh = 0f
    private var hAlpha = 0f

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surface }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.border; style = Paint.Style.STROKE; strokeWidth = ctx.dp3(1f)
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.medium; textAlign = Paint.Align.CENTER }
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var bmp: Bitmap? = null
    private var buf = IntArray(0)
    private val src = Rect()
    private val dst = RectF()
    private val tmp = RectF()
    private val clip = Path()

    private val bcx = FloatArray(8)
    private val bcy = FloatArray(8)
    private val bhw = FloatArray(8)
    private val bhh = FloatArray(8)
    private val dx = FloatArray(maxDrops)
    private val dy = FloatArray(maxDrops)
    private val dr = FloatArray(maxDrops)
    private val dtx = FloatArray(maxDrops)
    private val dty = FloatArray(maxDrops)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (52f * d).toInt())
    }

    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

    private var labelPx = 0f

    /** Each tab is as wide as its label plus an even share of the spare room, so labels never touch or crowd. */
    private fun layoutRects() {
        rects.clear()
        val n = labels.size
        val pad = 6f * d
        val gap = 2f * d
        val avail = width - 2 * pad - gap * (n - 1)
        var size = 12.5f * resources.displayMetrics.scaledDensity
        txt.textSize = size
        val minPad = 16f * d
        while (size > 9f * d && labels.sumOf { txt.measureText(it).toDouble() } + n * minPad > avail) {
            size -= 0.5f * d
            txt.textSize = size
        }
        labelPx = size
        val tw = labels.map { txt.measureText(it) }
        val slack = (avail - tw.sum()) / n
        val top = pad
        val itemH = height - 2 * pad
        var x = pad
        for (i in 0 until n) {
            val w = tw[i] + maxOf(slack, minPad)
            rects.add(RectF(x, top, x + w, top + itemH))
            x += w + gap
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        layoutRects()
        settle(target)
    }

    private fun pill(i: Int): Pill = pills.getOrPut(i) { Pill() }

    private fun settle(i: Int) {
        pills.clear()
        drops.clear()
        target = i
        val p = pill(i)
        p.mass = 1f; p.scale = 1f; p.velocity = 0f
        invalidate()
    }

    fun select(i: Int, animate: Boolean) {
        if (i == target) return
        if (!animate || i !in rects.indices || width == 0) { settle(i); return }
        val now = SystemClock.uptimeMillis()
        target = i
        pill(i)
        for (drop in drops) {
            drop.index = i
            if (drop.state == 1) plan(drop, i, now, false)
            if (drop.state == 2) {
                drop.state = 1
                drop.mass *= 1f - drop.absorbed
                drop.absorbed = 0f
                plan(drop, i, now, false)
            }
        }
        for (from in pills.keys.toList()) if (from != i) emit(from, i, now)
        last = now
        invalidate()
    }

    private fun plan(drop: Drop, index: Int, now: Long, fresh: Boolean) {
        val rect = rects.getOrNull(index) ?: return
        val reach = spreadDp * d
        val ex = rect.centerX() + (rnd.nextFloat() - 0.5f) * rect.width() * 0.45f
        val ey = rect.centerY() + (rnd.nextFloat() - 0.5f) * rect.height() * 0.2f
        val base = maxOf(0.15f, animationTime / 1000f)
        val variance = (rnd.nextFloat() - 0.5f) * (maxOf(0f, timeVariance) / 1000f)
        val duration = maxOf(0.15f, base * (0.62f + rnd.nextFloat() * 0.32f) + variance * 0.6f)
        val out = rnd.nextFloat() * Math.PI.toFloat() * 2f
        val into = rnd.nextFloat() * Math.PI.toFloat() * 2f
        val burst = reach * (0.7f + rnd.nextFloat() * 0.6f)
        val swing = reach * (0.55f + rnd.nextFloat() * 0.55f)
        val carry = minOf(duration / 3f, 0.12f)
        drop.index = index
        drop.start = now
        drop.duration = duration
        val p = drop.path
        p[0] = drop.x
        p[1] = drop.y
        p[2] = if (fresh) drop.x + Math.cos(out.toDouble()).toFloat() * burst * 1.6f else drop.x + drop.vx * carry
        p[3] = if (fresh) drop.y + Math.sin(out.toDouble()).toFloat() * burst * 1.6f else drop.y + drop.vy * carry
        p[4] = ex + Math.cos(into.toDouble()).toFloat() * swing * 1.5f
        p[5] = ey + Math.sin(into.toDouble()).toFloat() * swing * 1.5f
        p[6] = ex
        p[7] = ey
    }

    private fun emit(from: Int, to: Int, now: Long) {
        val source = pills[from] ?: return
        val rect = rects.getOrNull(from) ?: return
        if (rects.getOrNull(to) == null) return
        val available = source.mass - source.outgoing
        if (available <= 0.002f) return
        val room = maxDrops - drops.size
        val count = minOf(room, maxOf(1, Math.round(maxOf(1, particleCount) * available)))
        if (count <= 0) return
        val window = maxOf(0.15f, animationTime / 1000f) * 0.16f
        for (n in 0 until count) {
            val mass = available / count
            source.outgoing += mass
            drops.add(
                Drop(
                    rect.centerX() + (rnd.nextFloat() - 0.5f) * rect.width() * 0.6f,
                    rect.centerY() + (rnd.nextFloat() - 0.5f) * rect.height() * 0.3f,
                    mass, 0.8f + rnd.nextFloat() * 0.4f, from, to,
                    now + (rnd.nextFloat() * window * 1000f).toLong()
                )
            )
        }
    }

    private fun travelEase(t: Float): Float {
        val x = clamp01(t)
        return if (x < 0.5f) 4f * x * x * x else 1f - Math.pow((-2f * x + 2f).toDouble(), 3.0).toFloat() / 2f
    }

    private fun bezier(a: Float, b: Float, c: Float, e: Float, t: Float): Float {
        val u = 1f - t
        return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * e
    }

    /** Advance the physics. Returns true while anything is still moving. */
    private fun step(now: Long): Boolean {
        var dt = (now - last) / 1000f
        if (dt < 0f || dt.isNaN()) dt = 0f
        if (dt > 0.05f) dt = 0.05f
        last = now
        val emergeTime = 0.09f
        val absorbTime = 0.12f

        for (i in drops.size - 1 downTo 0) {
            val drop = drops[i]
            if (drop.state == 0) {
                if (now < drop.leave) continue
                drop.state = 1
                plan(drop, drop.index, now, true)
            }
            if (drop.state == 1) {
                drop.emerged = minOf(1f, drop.emerged + dt / emergeTime)
                val source = pills[drop.source]
                if (source != null && drop.given < drop.mass) {
                    val st = minOf(drop.mass * (dt / emergeTime), drop.mass - drop.given)
                    source.mass = maxOf(0f, source.mass - st)
                    source.outgoing = maxOf(0f, source.outgoing - st)
                    drop.given += st
                }
                val t = clamp01((now - drop.start) / 1000f / drop.duration)
                val e = travelEase(t)
                val p = drop.path
                val x = bezier(p[0], p[2], p[4], p[6], e)
                val y = bezier(p[1], p[3], p[5], p[7], e)
                if (dt > 0f) {
                    drop.vx = (x - drop.x) / dt
                    drop.vy = (y - drop.y) / dt
                }
                drop.x = x
                drop.y = y
                if (t >= 1f) drop.state = 2
            }
            if (drop.state == 2) {
                val st = minOf(1f - drop.absorbed, dt / absorbTime)
                drop.absorbed += st
                drop.vx *= 0.8f
                drop.vy *= 0.8f
                val host = pill(drop.index)
                host.mass = minOf(1f, host.mass + drop.mass * st)
                if (drop.absorbed >= 1f) drops.removeAt(i)
            }
        }

        val zeta = 1f - 0.72f * clamp01(wobble)
        val omega = 26f
        val steps = maxOf(1, Math.ceil((dt / (1f / 240f)).toDouble()).toInt())
        val h = dt / steps
        val it = pills.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val st = e.value
            for (n in 0 until steps) {
                st.velocity += (omega * omega * (st.mass - st.scale) - 2f * zeta * omega * st.velocity) * h
                st.scale += st.velocity * h
            }
            if (e.key != target && st.mass <= 0.002f && st.outgoing <= 0.002f && Math.abs(st.scale) < 0.004f) it.remove()
        }

        val hr = rects.getOrNull(hIndex)
        if (hr != null) {
            val k = 1f - Math.exp((-dt / 0.06f).toDouble()).toFloat()
            hx += (hr.centerX() - hx) * k
            hy += (hr.centerY() - hy) * k
            hw += (hr.width() - hw) * k
            hh += (hr.height() - hh) * k
        }
        val hoverGoal = if (hIndex >= 0 && hIndex != target) 1f else 0f
        hAlpha += (hoverGoal - hAlpha) * (1f - Math.exp((-dt / 0.08f).toDouble()).toFloat())

        val host = pills[target]
        val resting = drops.isEmpty() && pills.size == 1 && host != null &&
            Math.abs(host.mass - host.scale) < 0.0008f && Math.abs(host.velocity) < 0.004f
        val hovering = Math.abs(hoverGoal - hAlpha) > 0.003f ||
            (hr != null && Math.abs(hr.centerX() - hx) + Math.abs(hr.width() - hw) > 0.1f * d)
        if (resting && host != null) {
            host.mass = 1f; host.scale = 1f; host.velocity = 0f
            hAlpha = hoverGoal
        }
        return !resting || hovering
    }

    private fun smin(a: Float, v: Float, k: Float): Float {
        val h = clamp01(0.5f + 0.5f * (v - a) / k)
        return v + (a - v) * h - k * h * (1f - h)
    }

    private fun drawLabels(c: Canvas, ink: Int, activeInk: Boolean) {
        txt.textSize = labelPx
        val base = (height / 2f) - (txt.ascent() + txt.descent()) / 2f
        labels.forEachIndexed { i, l ->
            val r = rects[i]
            txt.color = if (activeInk) ink else if (i == target) pal.ink else pal.muted
            c.drawText(l, r.centerX(), base, txt)
        }
    }

    override fun onDraw(c: Canvas) {
        if (rects.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (last == 0L) last = now
        val moving = step(now)
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, framePaint)
        val e = d * 0.5f
        c.drawRoundRect(e, e, w - e, h - e, h / 2f - e, h / 2f - e, edgePaint)

        if (hAlpha > 0.01f) {
            hoverPaint.alpha = (255 * 0.06f * hAlpha).toInt()
            tmp.set(hx - hw / 2f, hy - hh / 2f, hx + hw / 2f, hy + hh / 2f)
            c.drawRoundRect(tmp, tmp.height() / 2f, tmp.height() / 2f, hoverPaint)
        }
        drawLabels(c, pal.ink, false)

        // shapes
        val height = rects[target.coerceIn(0, rects.size - 1)].height()
        val smooth = height * (0.18f + 0.5f * clamp01(gooeyness))
        var nb = 0
        for ((index, st) in pills) {
            val rect = rects.getOrNull(index) ?: continue
            val amount = st.scale
            if (amount <= 0.002f || nb >= 8) continue
            val hh2 = rect.height() * minOf(1f, 0.42f + 0.58f * Math.sqrt(maxOf(0f, amount).toDouble()).toFloat())
            val ww = maxOf(hh2, rect.width() * Math.pow(maxOf(0f, amount).toDouble(), 0.75).toFloat())
            bcx[nb] = rect.centerX(); bcy[nb] = rect.centerY(); bhw[nb] = ww / 2f; bhh[nb] = hh2 / 2f
            nb++
        }
        var nd = 0
        for (drop in drops) {
            if (drop.state == 0 || nd >= maxDrops) continue
            val radius = 0.26f * height * drop.size * minOf(drop.emerged, 1f - drop.absorbed)
            if (radius <= 0.05f * d) continue
            val speed = Math.hypot(drop.vx.toDouble(), drop.vy.toDouble()).toFloat()
            val length = minOf(radius * 1.1f, speed * 0.018f)
            dx[nd] = drop.x; dy[nd] = drop.y; dr[nd] = radius
            dtx[nd] = if (speed > 0.01f) drop.vx / speed * length else 0f
            dty[nd] = if (speed > 0.01f) drop.vy / speed * length else 0f
            nd++
        }

        if (nb == 1 && nd == 0) {
            tmp.set(bcx[0] - bhw[0], bcy[0] - bhh[0], bcx[0] + bhw[0], bcy[0] + bhh[0])
            val r = minOf(tmp.width(), tmp.height()) / 2f
            c.drawRoundRect(tmp, r, r, inkPaint)
            clip.reset()
            clip.addRoundRect(tmp, r, r, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            drawLabels(c, pal.canvas, true)
            c.restore()
        } else if (nb > 0 || nd > 0) {
            blob(c, nb, nd, smooth)
        }
        if (moving) postInvalidateOnAnimation()
    }

    private fun blob(c: Canvas, nb: Int, nd: Int, k: Float) {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until nb) {
            minX = minOf(minX, bcx[i] - bhw[i]); maxX = maxOf(maxX, bcx[i] + bhw[i])
            minY = minOf(minY, bcy[i] - bhh[i]); maxY = maxOf(maxY, bcy[i] + bhh[i])
        }
        for (i in 0 until nd) {
            val reach = dr[i] + Math.abs(dtx[i]) + Math.abs(dty[i])
            minX = minOf(minX, dx[i] - reach); maxX = maxOf(maxX, dx[i] + reach)
            minY = minOf(minY, dy[i] - reach); maxY = maxOf(maxY, dy[i] + reach)
        }
        val m = k + 4f * d
        minX = maxOf(minX - m, -60f * d); maxX = minOf(maxX + m, width + 60f * d)
        minY = maxOf(minY - m, -150f * d); maxY = minOf(maxY + m, height + 70f * d)
        if (maxX <= minX || maxY <= minY) return
        // keep the per-frame work bounded: a big area samples coarser, the bilinear upscale keeps it soft
        val step = maxOf(d * 0.75f, Math.sqrt(((maxX - minX) * (maxY - minY) / 9000f).toDouble()).toFloat())
        val cols = Math.ceil(((maxX - minX) / step).toDouble()).toInt().coerceAtLeast(1)
        val rows = Math.ceil(((maxY - minY) / step).toDouble()).toInt().coerceAtLeast(1)
        if (buf.size < cols * rows) buf = IntArray(cols * rows)
        var b = bmp
        if (b == null || b.width < cols || b.height < rows) {
            b?.recycle()
            b = Bitmap.createBitmap(maxOf(cols, b?.width ?: 0, 64), maxOf(rows, b?.height ?: 0, 64), Bitmap.Config.ARGB_8888)
            bmp = b
        }
        val rgb = pal.ink and 0x00FFFFFF
        val aa = step
        for (ry in 0 until rows) {
            val py = minY + (ry + 0.5f) * step
            val row = ry * cols
            for (cx in 0 until cols) {
                val px = minX + (cx + 0.5f) * step
                var dd = 1e5f
                for (i in 0 until nb) {
                    val r = minOf(bhw[i], bhh[i])
                    val qx = Math.abs(px - bcx[i]) - bhw[i] + r
                    val qy = Math.abs(py - bcy[i]) - bhh[i] + r
                    val ox = if (qx > 0f) qx else 0f
                    val oy = if (qy > 0f) qy else 0f
                    val v = Math.sqrt((ox * ox + oy * oy).toDouble()).toFloat() + minOf(maxOf(qx, qy), 0f) - r
                    dd = smin(dd, v, k)
                }
                for (i in 0 until nd) {
                    val reach = dr[i] + Math.abs(dtx[i]) + Math.abs(dty[i]) + k
                    val ax = px - dx[i]
                    val ay = py - dy[i]
                    if (ax > reach || ax < -reach || ay > reach || ay < -reach) continue
                    val bx = -dtx[i]
                    val by = -dty[i]
                    val span = bx * bx + by * by
                    val hh = if (span > 0.0001f) clamp01((ax * bx + ay * by) / span) else 0f
                    val qx = ax - bx * hh
                    val qy = ay - by * hh
                    val v = Math.sqrt((qx * qx + qy * qy).toDouble()).toFloat() - dr[i]
                    if (v > dd + k) continue
                    dd = smin(dd, v, k)
                }
                val body = clamp01(0.5f - dd / aa)
                buf[row + cx] = if (body <= 0.004f) 0 else ((body * 255f).toInt() shl 24) or rgb
            }
        }
        b.setPixels(buf, 0, cols, 0, 0, cols, rows)
        src.set(0, 0, cols, rows)
        dst.set(minX, minY, minX + cols * step, minY + rows * step)
        c.drawBitmap(b, src, dst, bmpPaint)
        val layer = c.saveLayer(dst, null)
        drawLabels(c, pal.canvas, true)
        c.drawBitmap(b, src, dst, maskPaint)
        c.restoreToCount(layer)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = indexAt(e.x)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (hAlpha < 0.01f) {
                    val r = rects.getOrNull(i)
                    if (r != null) { hx = r.centerX(); hy = r.centerY(); hw = r.width(); hh = r.height() }
                }
                hIndex = i
                last = SystemClock.uptimeMillis()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (i != hIndex) { hIndex = i; invalidate() }
            MotionEvent.ACTION_UP -> {
                hIndex = -1
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (i != target) onTap(i)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { hIndex = -1; invalidate() }
        }
        return true
    }

    private fun indexAt(x: Float): Int {
        if (rects.isEmpty()) return 0
        var best = 0
        var bd = Float.MAX_VALUE
        rects.forEachIndexed { i, r ->
            val dd = Math.abs(r.centerX() - x)
            if (dd < bd) { bd = dd; best = i }
        }
        return best
    }
}

// ------------------------------------------------------------------ Pulse heart

/** Like button, react-bits Pulse Heart: contracts to a dot, flips colour, pulses back while the count rolls over. */
class PulseHeart(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    count: Int,
    liked: Boolean,
    private val showCount: Boolean,
    private val onChange: (Boolean, Int) -> Unit
) : View(ctx) {
    var liked = liked
        private set
    var count = count
        private set
    private var shown = liked
    private var t = 1f
    private var anim: ValueAnimator? = null
    private val roll = RollPainter(this)
    // exact react-bits values: liked #ff4d6d, idle #71717a, pill #f0f0f2, text #18181b
    private val likedColor = Color.parseColor("#FF4D6D")
    private val idleColor = Color.parseColor(if (pal.dark) "#A1A1AA" else "#71717A")
    private val pillColor = Color.parseColor(if (pal.dark) "#26262B" else "#F0F0F2")
    private val textColor = Color.parseColor(if (pal.dark) "#F4F4F5" else "#18181B")
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pillColor }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = likedColor }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = idleColor; style = Paint.Style.STROKE; strokeWidth = ctx.dp3(1.8f)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; typeface = fonts.semi }
    private val heart = Path()
    private val rect = RectF()

    private fun label() = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(count)

    init {
        roll.set(label(), false)
        isClickable = true
    }

    override fun onMeasure(w: Int, h: Int) {
        txt.textSize = context.dp3(14f)
        val wide = if (showCount) context.dp3(15f) + context.dp3(22f) + context.dp3(8f) + txt.measureText(label()) + context.dp3(16f) else context.dp3(40f)
        setMeasuredDimension(wide.toInt(), context.dp3(40f).toInt())
    }

    private fun heartPath(cx: Float, cy: Float, w: Float) {
        heart.reset()
        heart.moveTo(cx, cy + w * 0.42f)
        heart.cubicTo(cx - w * 0.62f, cy + w * 0.05f, cx - w * 0.52f, cy - w * 0.42f, cx - w * 0.2f, cy - w * 0.42f)
        heart.cubicTo(cx - w * 0.06f, cy - w * 0.42f, cx, cy - w * 0.3f, cx, cy - w * 0.2f)
        heart.cubicTo(cx, cy - w * 0.3f, cx + w * 0.06f, cy - w * 0.42f, cx + w * 0.2f, cy - w * 0.42f)
        heart.cubicTo(cx + w * 0.52f, cy - w * 0.42f, cx + w * 0.62f, cy + w * 0.05f, cx, cy + w * 0.42f)
        heart.close()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, h / 2f, h / 2f, bg)
        val sc: Float = when {
            t >= 1f -> 1f
            t < 0.3f -> 1f - 0.7f * (t / 0.3f)
            else -> 0.3f + 0.7f * OvershootInterpolator(1.7f).getInterpolation((t - 0.3f) / 0.7f)
        }
        val hx = if (showCount) context.dp3(15f) + context.dp3(11f) else w / 2f
        val size = context.dp3(22f) * sc
        heartPath(hx, h / 2f, size)
        if (shown) c.drawPath(heart, fillP) else c.drawPath(heart, lineP)
        if (showCount) {
            txt.textSize = context.dp3(14f)
            val base = h / 2f - (txt.ascent() + txt.descent()) / 2f
            val left = hx + context.dp3(11f) + context.dp3(8f)
            roll.draw(c, txt, left + txt.measureText(label()) / 2f, base)
        }
    }

    override fun performClick(): Boolean {
        val before = label().length
        liked = !liked
        count = maxOf(0, count + if (liked) 1 else -1)
        roll.set(label(), true)
        if (label().length != before) requestLayout()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        anim?.cancel()
        t = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560
            addUpdateListener {
                t = it.animatedValue as Float
                if (t >= 0.3f) shown = liked
                val beat = 1f + 0.03f * Math.sin(Math.PI * t).toFloat()
                scaleX = beat
                scaleY = beat
                invalidate()
            }
            start()
        }
        onChange(liked, count)
        return super.performClick()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

// ------------------------------------------------------------------ Call chip

/** Tool-call style chip: the fill wipes across as time passes, then it ends done or shakes. */
class CallChip(ctx: Context, private val pal: Palette, private val fonts: Fonts) : View(ctx) {
    companion object {
        const val IDLE = 0
        const val RUNNING = 1
        const val DONE = 2
        const val ERROR = 3
    }

    private var name = ""
    private var arg = ""
    private var timer: String? = null
    private var progress = 0f
    private var state = IDLE
    private var lastWidth = 0
    private var shake: ValueAnimator? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surface }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.border; style = Paint.Style.STROKE; strokeWidth = ctx.dp3(1f)
    }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink; alpha = 36 }
    private val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink; typeface = fonts.semi }
    private val argP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.muted; typeface = fonts.regular }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.ink; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; strokeWidth = ctx.dp3(1.6f)
    }
    private val rect = RectF()
    private val clip = Path()

    fun set(name: String, arg: String, timer: String?, progress: Float, state: Int) {
        val prev = this.state
        this.name = name
        this.arg = arg
        this.timer = timer
        this.progress = progress.coerceIn(0f, 1f)
        this.state = state
        if (state == ERROR && prev != ERROR) doShake()
        if (measureWidth() != lastWidth) requestLayout()
        invalidate()
    }

    private fun doShake() {
        shake?.cancel()
        shake = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            addUpdateListener {
                val t = it.animatedValue as Float
                translationX = (Math.sin(t * Math.PI * 5).toFloat() * (1f - t)) * context.dp3(6f)
            }
            start()
        }
    }

    private fun measureWidth(): Int {
        nameP.textSize = context.dp3(13f)
        argP.textSize = context.dp3(13f)
        var w = context.dp3(10f) + context.dp3(16f) + context.dp3(8f) + nameP.measureText(name)
        if (arg.isNotEmpty()) w += context.dp3(6f) + argP.measureText(arg)
        timer?.let { w += context.dp3(8f) + argP.measureText("0") * it.length }
        w += context.dp3(12f)
        return Math.ceil(w.toDouble()).toInt()
    }

    override fun onMeasure(w: Int, h: Int) {
        lastWidth = measureWidth()
        setMeasuredDimension(lastWidth, context.dp3(34f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = context.dp3(10f)
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, bg)
        val p = if (state == DONE) 1f else progress
        if (p > 0f && state != IDLE) {
            clip.reset()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            c.drawRect(0f, 0f, w * p, h, wash)
            c.restore()
        }
        val i = context.dp3(0.5f)
        rect.set(i, i, w - i, h - i)
        c.drawRoundRect(rect, r, r, edge)

        val cy = h / 2f
        val ix = context.dp3(10f)
        val s = context.dp3(4f)
        when (state) {
            DONE -> {
                c.drawLine(ix + 1f, cy, ix + s, cy + s * 0.8f, icon)
                c.drawLine(ix + s, cy + s * 0.8f, ix + s * 2.8f, cy - s * 0.8f, icon)
            }
            ERROR -> {
                c.drawLine(ix + 1f, cy - s, ix + s * 2.6f, cy + s, icon)
                c.drawLine(ix + s * 2.6f, cy - s, ix + 1f, cy + s, icon)
            }
            else -> {
                c.drawLine(ix + 1f, cy - s, ix + s * 1.6f, cy, icon)
                c.drawLine(ix + s * 1.6f, cy, ix + 1f, cy + s, icon)
                c.drawLine(ix + s * 1.9f, cy + s, ix + s * 3.2f, cy + s, icon)
            }
        }
        nameP.textSize = context.dp3(13f)
        argP.textSize = context.dp3(13f)
        val base = cy - (nameP.ascent() + nameP.descent()) / 2f
        var x = ix + context.dp3(16f) + context.dp3(8f)
        c.drawText(name, x, base, nameP)
        x += nameP.measureText(name)
        if (arg.isNotEmpty()) {
            x += context.dp3(6f)
            c.drawText(arg, x, base, argP)
            x += argP.measureText(arg)
        }
        timer?.let {
            x += context.dp3(8f)
            val cw = argP.measureText("0")
            for (ch in it) {
                val tw = argP.measureText(ch.toString())
                c.drawText(ch.toString(), x + (cw - tw) / 2f, base, argP)
                x += cw
            }
        }
    }

    override fun onDetachedFromWindow() {
        shake?.cancel()
        super.onDetachedFromWindow()
    }
}

// ------------------------------------------------------------------ Curved input

private class GlobeIcon(ctx: Context, color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = ctx.dp3(1.5f)
    }
    private val oval = RectF()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val r = w / 2f - p.strokeWidth
        c.drawCircle(w / 2f, w / 2f, r, p)
        c.drawLine(w / 2f - r, w / 2f, w / 2f + r, w / 2f, p)
        oval.set(w / 2f - r * 0.45f, w / 2f - r, w / 2f + r * 0.45f, w / 2f + r)
        c.drawOval(oval, p)
    }
}

/** Input bar on a gentle arc. Drawn as a crisp vector outline; the field and button stay straight so typing is exact. */
class CurvedBar(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    hint: String,
    buttonLabel: String,
    private val onSubmit: (String) -> Unit
) : FrameLayout(ctx) {
    val edit: EditText
    private val bend = ctx.dp3(10f)
    private val barH = ctx.dp3(64f).toInt()
    private val inset = ctx.dp3(1f)
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.canvas }
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.ink; style = Paint.Style.STROKE; strokeWidth = ctx.dp3(1.5f); strokeJoin = Paint.Join.ROUND
    }
    private val shape = Path()

    init {
        setWillNotDraw(false)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(10), 0)
        }
        row.addView(GlobeIcon(ctx, pal.muted), LinearLayout.LayoutParams(dp(20), dp(20)))
        edit = EditText(ctx).apply {
            this.hint = hint
            setHintTextColor(pal.muted)
            setTextColor(pal.ink)
            typeface = fonts.regular
            textSize = 16f
            background = null
            setPadding(dp(12), 0, dp(8), 0)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> onSubmit(text.toString()); true }
        }
        row.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btn = TextView(ctx).apply {
            text = buttonLabel
            textSize = 14f
            typeface = fonts.semi
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            // the one accent in the app: a violet-to-indigo gradient on the action
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#5B5BF0"))
            ).apply { cornerRadius = dp(14).toFloat() }
            setPadding(dp(20), 0, dp(20), 0)
            isClickable = true
            alpha = 0.55f
            setOnClickListener { onSubmit(edit.text.toString()) }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(SpringInterpolator(18f, 0.5f, 0.8f)).start()
                }
                false
            }
        }
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                btn.animate().alpha(if (s.isNullOrBlank()) 0.55f else 1f).setDuration(160).start()
            }
        })
        row.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.TOP).apply { topMargin = (bend / 2f).toInt() + 1 })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(barH + bend.toInt() + dp(2), MeasureSpec.EXACTLY))
    }

    private fun edgeY(x: Float, w: Float): Float {
        val u = (x / w) * 2f - 1f
        return inset + bend * u * u
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat() - inset * 2f
        if (w <= 0f) return
        val r = context.dp3(18f)
        val left = inset
        val hgt = barH - inset * 2f
        shape.reset()
        fun top(x: Float) = edgeY(x - left, w) // x in view coordinates
        shape.moveTo(left, top(left) + r)
        shape.quadTo(left, top(left), left + r, top(left + r))
        var x = left + r
        while (x < left + w - r) { shape.lineTo(x, top(x)); x += context.dp3(4f) }
        shape.lineTo(left + w - r, top(left + w - r))
        shape.quadTo(left + w, top(left + w), left + w, top(left + w) + r)
        shape.lineTo(left + w, top(left + w) + hgt - r)
        shape.quadTo(left + w, top(left + w) + hgt, left + w - r, top(left + w - r) + hgt)
        x = left + w - r
        while (x > left + r) { shape.lineTo(x, top(x) + hgt); x -= context.dp3(4f) }
        shape.lineTo(left + r, top(left + r) + hgt)
        shape.quadTo(left, top(left) + hgt, left, top(left) + hgt - r)
        shape.close()
        c.drawPath(shape, fillP)
        c.drawPath(shape, edgeP)
    }
}
