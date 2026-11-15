package com.shortstop.app

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Where people can get the app. Change this one line when the app has a public page. */
const val APP_LINK = "https://github.com/redwire-coree/shortstop"
const val APP_LINK_SHORT = "redwire-coree/shortstop"

class FocusCardData(
    val badge: String,
    val status: String,
    val eyebrow: String,
    val big: String,
    val line: String,
    val stats: List<Pair<String, String>>,
    val idLabel: String,
    val idValue: String
)

private fun erfF(x: Float): Float {
    val x2 = x * x
    return sign(x) * sqrt(1f - exp(-x2 * (1.2732395f + 0.147f * x2) / (1f + 0.147f * x2)))
}

private fun bandF(x: Float, half: Float, sigma: Float): Float {
    val s = 1.4142136f * max(sigma, 0.0001f)
    return 0.5f * (erfF((x + half) / s) - erfF((x - half) / s))
}

private fun smooth01(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * A brushed-metal card that catches the light as it tilts. This is the react-bits
 * "Reflective Card" ported to Canvas: the card follows your finger on a spring with real
 * perspective, a soft studio reflection slides over the metal, a light band sweeps across
 * as the angle changes, the polished edge catches light and a glare follows the touch.
 * The metal is light green (success); the content is drawn on top so it tilts with it.
 */
class ReflectiveCard(ctx: Context, private val fonts: Fonts, private val data: FocusCardData) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val ratio = 1.3f
    private val margin = density * 24f

    private var cardW = 0f
    private var cardH = 0f
    private var cardL = 0f

    // spring pose, same constants as the web component
    private var px = 0f
    private var vx = 0f
    private var tx = 0f
    private var py = 0f
    private var vy = 0f
    private var ty = 0f
    private var light = 0f
    private var lightT = 0f
    private var ptrNx = 0f
    private var ptrNy = 0f
    private var presence = 1f
    private var presenceStart = 0L
    private var slowUntil = 0L
    private var lastT = 0L
    private var touching = false

    private val tiltDeg = 12f
    private val ink = Color.parseColor("#13180E")
    private val muted = Color.argb(150, 19, 24, 14)
    private val deep = intArrayOf(36, 78, 18)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val rr = RectF()
    private val tmpRect = RectF()
    private val proj = Matrix()
    private val grainMatrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val path = Path()

    private val grainBmp: Bitmap by lazy {
        val b = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(7)
        for (y in 0 until 48) for (x in 0 until 96) {
            val v = rnd.nextFloat()
            val a = (v * v * 52f).toInt()
            b.setPixel(x, y, if (rnd.nextBoolean()) Color.argb(a, 255, 255, 255) else Color.argb(a, 20, 40, 10))
        }
        b
    }

    init {
        contentDescription = "${data.big} focused. ${data.line}"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startIntro()
    }

    private fun startIntro() {
        val now = SystemClock.uptimeMillis()
        px = 0.62f; py = -0.55f; vx = 0f; vy = 0f; tx = 0f; ty = 0f
        light = 1f; lightT = 0f
        ptrNx = 0.62f; ptrNy = -0.55f
        presence = 0f
        presenceStart = now
        slowUntil = now + 1400
        lastT = 0L
        performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var avail = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        if (avail <= 0f) avail = density * 340f
        val maxCardH = resources.displayMetrics.heightPixels * 0.5f
        var cw = min(avail - 2f * margin, density * 320f)
        cw = min(cw, maxCardH / ratio)
        cw = max(cw, density * 200f)
        cardW = cw
        cardH = cw * ratio
        cardL = (avail - cw) / 2f
        setMeasuredDimension(avail.toInt(), (cardH + 2f * margin).toInt())
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touching = true
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                aim(e)
            }
            MotionEvent.ACTION_MOVE -> aim(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                tx = 0f; ty = 0f; lightT = 0f
                slowUntil = 0L
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    private fun aim(e: MotionEvent) {
        if (cardW <= 0f) return
        val nx = (((e.x - cardL) / cardW) * 2f - 1f).coerceIn(-1f, 1f)
        val ny = (((e.y - margin) / cardH) * 2f - 1f).coerceIn(-1f, 1f)
        tx = nx; ty = ny
        ptrNx = nx; ptrNy = ny
        lightT = 1f
        slowUntil = 0L
        postInvalidateOnAnimation()
    }

    private fun step(dt: Float, now: Long): Boolean {
        val slow = now < slowUntil
        val k = if (slow) 24f else 120f
        val c = if (slow) 9f else 16f
        val steps = max(1, ceil(dt / (1f / 120f)).toInt())
        val h = dt / steps
        for (i in 0 until steps) {
            vx += (k * (tx - px) - c * vx) * h
            px += vx * h
            vy += (k * (ty - py) - c * vy) * h
            py += vy * h
        }
        val tau = if (lightT > light) 0.18f else if (slow) 0.9f else 0.45f
        light += (lightT - light) * (1f - exp(-dt / tau))
        if (presence < 1f) presence = ((now - presenceStart) / 650f).coerceIn(0f, 1f)
        val moving = abs(tx - px) > 0.0005f || abs(ty - py) > 0.0005f || abs(vx) > 0.0005f ||
            abs(vy) > 0.0005f || abs(lightT - light) > 0.002f || presence < 1f
        if (!moving) {
            px = tx; py = ty; vx = 0f; vy = 0f; light = lightT
        }
        return moving
    }

    override fun onDraw(canvas: Canvas) {
        if (cardW <= 0f) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 0.016f else min(0.05f, (now - lastT) / 1000f)
        lastT = now
        val moving = step(dt, now)

        val w = cardW
        val h = cardH
        val ang = Math.toRadians(tiltDeg.toDouble()).toFloat()
        val ry = -px * ang
        val rx = py * ang
        val eased = 1f - (1f - presence).pow(3)
        val lift = (1f - eased) * 14f * density
        val d = 900f * (w / 320f)
        src[0] = 0f; src[1] = 0f; src[2] = w; src[3] = 0f; src[4] = w; src[5] = h; src[6] = 0f; src[7] = h
        val cx = cardL + w / 2f
        val cy = margin + h / 2f
        for (i in 0 until 4) {
            val lx = src[i * 2] - w / 2f
            val ly = src[i * 2 + 1] - h / 2f
            val x1 = lx * cos(ry)
            val z1 = -lx * sin(ry)
            val y2 = ly * cos(rx) - z1 * sin(rx)
            val z2 = ly * sin(rx) + z1 * cos(rx)
            val s = d / (d - z2)
            dst[i * 2] = cx + x1 * s
            dst[i * 2 + 1] = cy + y2 * s + lift
        }
        proj.setPolyToPoly(src, 0, dst, 0, 4)

        val layered = presence < 1f
        val saved = if (layered) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (eased * 255f).toInt()) else canvas.save()
        canvas.concat(proj)
        drawShadow(canvas, w, h)
        drawCard(canvas, w, h, px, py, 0.5f * light, ptrNx, ptrNy)
        canvas.restoreToCount(saved)

        if (moving) postInvalidateOnAnimation()
    }

    private fun drawShadow(c: Canvas, w: Float, h: Float) {
        val u = w / 300f
        for (i in 0 until 9) {
            val grow = i * 2.4f * u
            tmpRect.set(-grow + 6f * u, -grow + 18f * u, w + grow - 6f * u, h + grow + 8f * u)
            fill.shader = null
            fill.color = Color.argb(11, deep[0], deep[1], deep[2])
            c.drawRoundRect(tmpRect, 24f * u + grow, 24f * u + grow, fill)
        }
    }

    private fun strip(
        c: Canvas, r: Float, w: Float, h: Float, k: Float,
        tqx: Float, tqy: Float, cqx: Float, cqy: Float, dx0: Float, dy0: Float,
        half: Float, sigma: Float, red: Int, green: Int, blue: Int, maxA: Float
    ) {
        if (maxA <= 0.004f) return
        val len = sqrt(dx0 * dx0 + dy0 * dy0)
        val dx = dx0 / len
        val dy = dy0 / len
        val nx = -dy
        val ny = dx
        val t0 = (tqx - cqx) * nx + (tqy - cqy) * ny
        val ccx = w / 2f - nx * t0 * k
        val ccy = h / 2f - ny * t0 * k
        val ext = half + sigma * 3.2f
        val reach = ext * k
        val n = 15
        val colors = IntArray(n)
        val pos = FloatArray(n)
        for (i in 0 until n) {
            val f = i / (n - 1f)
            val t = -ext + 2f * ext * f
            pos[i] = f
            colors[i] = Color.argb((bandF(t, half, sigma) * maxA * 255f).toInt().coerceIn(0, 255), red, green, blue)
        }
        strip.shader = LinearGradient(ccx - nx * reach, ccy - ny * reach, ccx + nx * reach, ccy + ny * reach, colors, pos, Shader.TileMode.CLAMP)
        c.drawRoundRect(rr, r, r, strip)
    }

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, face: Typeface, color: Int,
        align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f
    ) {
        tp.typeface = face
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        tp.letterSpacing = spacing
        c.drawText(s, x, y, tp)
    }

    /** Draws the whole card flat, in its own coordinates (0..w, 0..h). ax/ay are the tilt, -1..1. */
    private fun drawCard(c: Canvas, w: Float, h: Float, ax: Float, ay: Float, glare: Float, pnx: Float, pny: Float) {
        val u = w / 300f
        val s = w / 320f
        val k = 900f * s
        val r = 22f * u
        rr.set(0f, 0f, w, h)
        val tqx = -0.42f * ax * (tiltDeg / 12f)
        val tqy = -0.42f * ay * (tiltDeg / 12f)

        // 1. the metal itself: light green steel
        fill.shader = LinearGradient(
            0f, 0f, w * 0.3f, h,
            intArrayOf(Color.parseColor("#E3F8CB"), Color.parseColor("#D2F0AE"), Color.parseColor("#C6EAA0")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rr, r, r, fill)

        // 2. the room: bright above, darker below; slides as the card tilts
        val rows = 8
        val rc = IntArray(rows)
        val rp = FloatArray(rows)
        for (i in 0 until rows) {
            val f = i / (rows - 1f)
            val qy = (f - 0.5f) * h / k + tqy
            val scene = 0.85f + (0.12f - 0.85f) * smooth01(-0.55f, 0.5f, qy)
            rp[i] = f
            rc[i] = Color.argb(((1f - scene) * 0.34f * 255f).toInt().coerceIn(0, 255), deep[0], deep[1], deep[2])
        }
        fill.shader = LinearGradient(0f, 0f, 0f, h, rc, rp, Shader.TileMode.CLAMP)
        c.drawRoundRect(rr, r, r, fill)

        // soft studio shapes: blurred windows that drift at different speeds (parallax)
        val blobs = arrayOf(floatArrayOf(0.20f, 0.20f, 0.07f, 1.0f), floatArrayOf(0.52f, 0.14f, 0.05f, 0.7f), floatArrayOf(0.84f, 0.18f, 0.07f, 1.3f))
        for (b in blobs) {
            val bx = w * b[0] - ax * w * 0.10f * b[3]
            val bw = w * b[1]
            fill.shader = LinearGradient(
                bx - bw, 0f, bx + bw, 0f,
                intArrayOf(Color.argb(0, deep[0], deep[1], deep[2]), Color.argb((b[2] * 255f).toInt(), deep[0], deep[1], deep[2]), Color.argb(0, deep[0], deep[1], deep[2])),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRoundRect(rr, r, r, fill)
        }
        // the flag that darkens a diagonal strip of the room
        strip(c, r, w, h, k, tqx, tqy, 0.24f, -0.02f, 0.5f, 0.86f, 0.09f, 0.10f, deep[0], deep[1], deep[2], 0.14f)

        // 3. brushed grain, stretched along the brush direction
        grainMatrix.setScale(9f * u, 0.7f * u)
        val gs = BitmapShader(grainBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        gs.setLocalMatrix(grainMatrix)
        grain.shader = gs
        grain.alpha = 150
        c.drawRoundRect(rr, r, r, grain)

        // 4. the lights: key strip (the sweep), top and side softboxes
        strip(c, r, w, h, k, tqx, tqy, -0.10f, -0.16f, 0.5f, 0.86f, 0.022f, 0.03f, 255, 255, 255, 0.62f)
        strip(c, r, w, h, k, tqx, tqy, 0f, -0.6f, 1f, 0f, 0.12f, 0.08f, 255, 255, 255, 0.5f)
        strip(c, r, w, h, k, tqx, tqy, 0.46f, 0f, 0f, 1f, 0.05f, 0.07f, 255, 255, 255, 0.22f)

        // 5. glare that follows the finger
        if (glare > 0.01f) {
            val gx = w / 2f + pnx * w / 2f
            val gy = h / 2f + pny * h / 2f
            val gr = 144f * s * 2.3f
            val n = 12
            val gc = IntArray(n)
            val gp = FloatArray(n)
            for (i in 0 until n) {
                val f = i / (n - 1f)
                val rd = f * 2.3f
                val a = (exp(-rd * rd * 1.6f) + exp(-rd * rd * 0.35f) * 0.15f) * glare * 0.55f
                gp[i] = f
                gc[i] = Color.argb((a * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
            }
            fill.shader = RadialGradient(gx, gy, gr, gc, gp, Shader.TileMode.CLAMP)
            c.drawRoundRect(rr, r, r, fill)
        }

        // 6. polished bevel edge that catches the light
        val bw = 3f * s
        tmpRect.set(bw / 2f, bw / 2f, w - bw / 2f, h - bw / 2f)
        stroke.strokeWidth = bw
        stroke.shader = LinearGradient(
            -ax * w * 0.5f, -ay * h * 0.5f, w - ax * w * 0.5f, h - ay * h * 0.5f,
            intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(30, 255, 255, 255), Color.argb(46, deep[0], deep[1], deep[2]), Color.argb(150, 255, 255, 255)),
            floatArrayOf(0f, 0.38f, 0.64f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(tmpRect, r - bw / 2f, r - bw / 2f, stroke)
        stroke.shader = null

        drawContent(c, w, h, u)

        // 7. thin rim and the highlight along the top edge
        tmpRect.set(u * 0.5f, u * 0.5f, w - u * 0.5f, h - u * 0.5f)
        stroke.strokeWidth = u
        stroke.color = Color.argb(22, 19, 24, 14)
        c.drawRoundRect(tmpRect, r - u * 0.5f, r - u * 0.5f, stroke)
        tmpRect.set(u * 1.5f, u * 1.5f, w - u * 1.5f, h - u * 1.5f)
        stroke.strokeWidth = u
        stroke.shader = LinearGradient(
            0f, 0f, 0f, 30f * u,
            intArrayOf(Color.argb(210, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(tmpRect, r - u * 1.5f, r - u * 1.5f, stroke)
        stroke.shader = null
    }

    private fun drawContent(c: Canvas, w: Float, h: Float, u: Float) {
        val pad = 19f * u
        val green = Color.parseColor("#16A34A")

        // header: badge left, status right
        val rowY = pad + 10f * u
        val ix = pad + 8f * u
        stroke.shader = null
        stroke.color = ink
        stroke.strokeWidth = 1.7f * u
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        c.drawCircle(ix, rowY, 7.6f * u, stroke)
        path.reset()
        path.moveTo(ix - 3.4f * u, rowY + 0.2f * u)
        path.lineTo(ix - 0.8f * u, rowY + 2.8f * u)
        path.lineTo(ix + 3.6f * u, rowY - 2.6f * u)
        c.drawPath(path, stroke)
        text(c, data.badge, pad + 22f * u, rowY + 4.6f * u, 13f * u, fonts.medium, ink)

        text(c, data.status, w - pad, rowY + 4.6f * u, 13f * u, fonts.regular, muted, Paint.Align.RIGHT)
        val sw = tp.measureText(data.status)
        fill.shader = null
        fill.color = green
        c.drawCircle(w - pad - sw - 9f * u, rowY, 3.2f * u, fill)

        // footer: link on the left, tile on the right
        val tile = 44f * u
        val tileL = w - pad - tile
        val tileT = h - pad - tile
        tmpRect.set(tileL, tileT, tileL + tile, tileT + tile)
        fill.shader = LinearGradient(0f, tileT, 0f, tileT + tile, Color.WHITE, Color.parseColor("#F1F8EA"), Shader.TileMode.CLAMP)
        c.drawRoundRect(tmpRect, 12f * u, 12f * u, fill)
        stroke.strokeWidth = u
        stroke.color = Color.argb(24, 19, 24, 14)
        tmpRect.inset(u * 0.5f, u * 0.5f)
        c.drawRoundRect(tmpRect, 11.5f * u, 11.5f * u, stroke)
        stroke.color = Color.argb(150, 19, 24, 14)
        stroke.strokeWidth = 2.1f * u
        val tcx = tileL + tile / 2f
        val tcy = tileT + tile / 2f
        path.reset()
        path.moveTo(tcx - 7f * u, tcy + 0.5f * u)
        path.lineTo(tcx - 2.2f * u, tcy + 5f * u)
        path.lineTo(tcx + 7.5f * u, tcy - 5f * u)
        c.drawPath(path, stroke)

        val idMax = tileL - pad - 12f * u
        var vs = 14.5f * u
        tp.typeface = Typeface.MONOSPACE
        tp.letterSpacing = 0f
        tp.textSize = vs
        while (tp.measureText(data.idValue) > idMax && vs > 8f * u) {
            vs -= 0.5f * u
            tp.textSize = vs
        }
        val valueBase = h - pad - 6f * u
        text(c, data.idValue, pad, valueBase, vs, Typeface.MONOSPACE, ink)
        text(c, data.idLabel, pad, valueBase - 22f * u, 12f * u, fonts.regular, muted)

        // stats row with a hairline above
        val statsBottom = h - pad - tile - 20f * u
        val statsTop = statsBottom - 46f * u
        fill.shader = null
        fill.color = Color.argb(30, 19, 24, 14)
        c.drawRect(pad, statsTop, w - pad, statsTop + u, fill)
        val colW = (w - 2f * pad) / max(1, data.stats.size)
        for ((i, st) in data.stats.withIndex()) {
            val x = pad + i * colW
            text(c, st.second, x, statsTop + 28f * u, 17f * u, fonts.semi, ink)
            text(c, st.first, x, statsTop + 43f * u, 11.5f * u, fonts.regular, muted)
        }

        // identity: eyebrow, the big number, one line of praise
        val roleBase = statsTop - 22f * u
        text(c, data.line, pad, roleBase, 14f * u, fonts.regular, muted)
        var bs = 58f * u
        tp.typeface = fonts.bold
        tp.letterSpacing = -0.03f
        tp.textSize = bs
        while (tp.measureText(data.big) > w - 2f * pad && bs > 28f * u) {
            bs -= 2f * u
            tp.textSize = bs
        }
        val bigBase = roleBase - 30f * u
        text(c, data.big, pad - 1.5f * u, bigBase, bs, fonts.bold, ink, Paint.Align.LEFT, -0.03f)
        text(c, data.eyebrow, pad, bigBase - bs * 0.74f - 12f * u, 11f * u, fonts.semi, muted, Paint.Align.LEFT, 0.14f)
    }

    /** The picture people share: the card at a pleasing angle on a soft page, with the link under it. */
    fun snapshot(): Bitmap {
        val bw = 1080
        val bh = 1440
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint()
        bg.shader = LinearGradient(0f, 0f, 0f, bh.toFloat(), Color.parseColor("#E9F7DC"), Color.WHITE, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), bg)
        val cw = 780f
        val ch = cw * ratio
        c.save()
        c.translate((bw - cw) / 2f, 120f)
        drawShadow(c, cw, ch)
        drawCard(c, cw, ch, 0.30f, -0.22f, 0.5f, -0.45f, -0.5f)
        c.restore()
        val y = 120f + ch + 118f
        text(c, "Shortstop", bw / 2f, y, 48f, fonts.bold, ink, Paint.Align.CENTER, -0.02f)
        text(c, APP_LINK_SHORT, bw / 2f, y + 52f, 30f, Typeface.MONOSPACE, muted, Paint.Align.CENTER)
        return bmp
    }
}

/** Saves the card picture and opens the share sheet with it plus a message that carries the app link. */
fun shareFocusCard(act: Activity, card: ReflectiveCard, message: String) {
    val bmp = try { card.snapshot() } catch (e: Exception) { null }
    Thread {
        var uri: Uri? = null
        if (bmp != null && Build.VERSION.SDK_INT >= 29) {
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "shortstop-focus-${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Shortstop")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val res = act.contentResolver
                val u = res.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                if (u != null) {
                    res.openOutputStream(u)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    res.update(u, done, null, null)
                    uri = u
                }
            } catch (e: Exception) {
                uri = null
            }
        }
        val shared = uri
        act.runOnUiThread {
            try {
                val send = Intent(Intent.ACTION_SEND)
                send.putExtra(Intent.EXTRA_TEXT, message)
                if (shared != null) {
                    send.type = "image/png"
                    send.putExtra(Intent.EXTRA_STREAM, shared)
                    send.clipData = ClipData.newRawUri("", shared)
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    send.type = "text/plain"
                }
                act.startActivity(Intent.createChooser(send, "Share your focus"))
            } catch (e: Exception) {
                // no app could take it; nothing more to do
            }
        }
    }.start()
}
