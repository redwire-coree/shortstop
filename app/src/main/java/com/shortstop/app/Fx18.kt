package com.shortstop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.util.Calendar

private fun Context.dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Warm colors shared by the three Focus cards. */
object FocusCardColors {
    const val ORANGE_A = 0xFFFFB27A.toInt()
    const val ORANGE_B = 0xFFFF6A2B.toInt()
    const val ORANGE = 0xFFFF7A2B.toInt()
    fun peach(dark: Boolean) = if (dark) 0xFF4A3220.toInt() else 0xFFFFE8D4.toInt()
    fun peachSoft(dark: Boolean) = if (dark) 0xFF4D3322.toInt() else 0xFFFFEEDD.toInt()
    const val GREEN = 0xFF5FB878.toInt()
    const val EARLY = 0xFFE0A06A.toInt()
}

/** Small rotating chevron shown in the card header. */
class Chevron(ctx: Context, color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun onDraw(c: Canvas) {
        p.strokeWidth = context.dpi(2).toFloat()
        val w = width.toFloat(); val h = height.toFloat()
        path.reset()
        path.moveTo(w * 0.25f, h * 0.38f); path.lineTo(w * 0.5f, h * 0.63f); path.lineTo(w * 0.75f, h * 0.38f)
        c.drawPath(path, p)
    }
}

/** 24-hour strip: peach track, orange rounded sessions from real timestamps, thin now marker. */
class FocusTimeline(
    ctx: Context,
    private val pal: Palette,
    private val fonts: Fonts,
    private val sessions: List<Store.Session>,
    private val tall: Boolean
) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FocusCardColors.peach(pal.dark) }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val now = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FocusCardColors.ORANGE_B; alpha = 220; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FocusTheme.muted(pal.dark); typeface = fonts.medium }
    private val rect = RectF()
    private val dayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val dayEnd = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    private val shaders = ArrayList<Shader>()
    private val rects = ArrayList<RectF>()

    private fun barH() = context.dpi(if (tall) 22 else 14).toFloat()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(if (tall) 60 else 50))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shaders.clear(); rects.clear()
        val span = (dayEnd - dayStart).toFloat()
        val bh = barH()
        val minW = context.dpi(8).toFloat()
        for (s in sessions) {
            val a = ((s.start - dayStart) / span).coerceIn(0f, 1f)
            val b = ((s.start + s.minutes * 60000L - dayStart) / span).coerceIn(0f, 1f)
            val l = w * a
            val r = maxOf(l + minW, w * b).coerceAtMost(w.toFloat())
            val rc = RectF(l.coerceAtMost(r - minW), 0f, r, bh)
            rects.add(rc)
            shaders.add(LinearGradient(rc.left, 0f, rc.right, 0f, FocusCardColors.ORANGE_A, FocusCardColors.ORANGE_B, Shader.TileMode.CLAMP))
        }
    }

    override fun onDraw(c: Canvas) {
        val bh = barH()
        val r = bh / 2f
        rect.set(0f, 0f, width.toFloat(), bh)
        c.drawRoundRect(rect, r, r, track)
        for (i in rects.indices) {
            pill.shader = shaders[i]
            c.drawRoundRect(rects[i], r, r, pill)
        }
        pill.shader = null
        val span = (dayEnd - dayStart).toFloat()
        val f = ((System.currentTimeMillis() - dayStart) / span).coerceIn(0f, 1f)
        val x = (width * f).coerceIn(context.dpi(1).toFloat(), width - context.dpi(1).toFloat())
        now.strokeWidth = context.dpi(2).toFloat()
        c.drawLine(x, context.dpi(1).toFloat(), x, bh - context.dpi(1), now)
        txt.textSize = context.dpi(11).toFloat()
        val labels = listOf("12 AM", "6 AM", "12 PM", "6 PM", "12 AM")
        labels.forEachIndexed { i, s ->
            txt.textAlign = when (i) { 0 -> Paint.Align.LEFT; 4 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            val lx = when (i) { 0 -> 0f; 4 -> width.toFloat(); else -> width * i / 4f }
            c.drawText(s, lx, bh + context.dpi(22), txt)
        }
    }
}

/** Thin peach track with an orange fill; the fill comes from real data. */
class PeachBar(ctx: Context, private val dark: Boolean, private val frac: Float) : View(ctx) {
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FocusCardColors.peach(dark) }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val r = h / 2f
        rect.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(rect, r, r, t)
        val w = maxOf(h, width * frac.coerceIn(0f, 1f))
        f.shader = LinearGradient(0f, 0f, w, 0f, FocusCardColors.ORANGE_A, FocusCardColors.ORANGE_B, Shader.TileMode.CLAMP)
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, f)
    }
}

/** Subtle line icon on a peach tile for a tag. */
class TagIcon(ctx: Context, private val name: String, private val dark: Boolean) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FocusCardColors.peachSoft(dark) }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FocusCardColors.ORANGE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        rect.set(0f, 0f, s, s)
        c.drawRoundRect(rect, s * 0.32f, s * 0.32f, bg)
        val g = s * 0.46f
        val ox = (s - g) / 2f; val oy = (s - g) / 2f
        p.strokeWidth = context.dpi(2).toFloat() * 0.85f
        fun X(v: Float) = ox + g * v
        fun Y(v: Float) = oy + g * v
        path.reset()
        when (name.lowercase()) {
            "study" -> {
                path.moveTo(X(0f), Y(0.2f)); path.lineTo(X(0.5f), Y(0.32f)); path.lineTo(X(1f), Y(0.2f))
                path.lineTo(X(1f), Y(0.85f)); path.lineTo(X(0.5f), Y(0.97f)); path.lineTo(X(0f), Y(0.85f)); path.close()
                path.moveTo(X(0.5f), Y(0.32f)); path.lineTo(X(0.5f), Y(0.97f))
            }
            "work" -> {
                rect.set(X(0f), Y(0.3f), X(1f), Y(0.92f))
                path.addRoundRect(rect, g * 0.12f, g * 0.12f, Path.Direction.CW)
                path.moveTo(X(0.34f), Y(0.3f)); path.lineTo(X(0.34f), Y(0.1f)); path.lineTo(X(0.66f), Y(0.1f)); path.lineTo(X(0.66f), Y(0.3f))
                path.moveTo(X(0f), Y(0.6f)); path.lineTo(X(1f), Y(0.6f))
            }
            "code" -> {
                path.moveTo(X(0.3f), Y(0.18f)); path.lineTo(X(0f), Y(0.5f)); path.lineTo(X(0.3f), Y(0.82f))
                path.moveTo(X(0.7f), Y(0.18f)); path.lineTo(X(1f), Y(0.5f)); path.lineTo(X(0.7f), Y(0.82f))
            }
            "fitness" -> {
                path.moveTo(X(0.2f), Y(0.5f)); path.lineTo(X(0.8f), Y(0.5f))
                path.moveTo(X(0.2f), Y(0.22f)); path.lineTo(X(0.2f), Y(0.78f))
                path.moveTo(X(0.8f), Y(0.22f)); path.lineTo(X(0.8f), Y(0.78f))
                path.moveTo(X(0.02f), Y(0.36f)); path.lineTo(X(0.02f), Y(0.64f))
                path.moveTo(X(0.98f), Y(0.36f)); path.lineTo(X(0.98f), Y(0.64f))
            }
            else -> {
                path.addCircle(X(0.5f), Y(0.5f), g * 0.46f, Path.Direction.CW)
                path.addCircle(X(0.5f), Y(0.5f), g * 0.08f, Path.Direction.CW)
            }
        }
        c.drawPath(path, p)
    }
}

/** Two faint peach clouds that sit behind the running-session content. Static, so it costs nothing per frame. */
class SoftClouds(ctx: Context, private val dark: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float) {
        val a = if (dark) 46 else 120
        p.shader = android.graphics.RadialGradient(cx, cy, r,
            android.graphics.Color.argb(a, 255, 178, 122), android.graphics.Color.argb(0, 255, 178, 122), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        blob(c, w * 0.12f, h * 0.10f, w * 0.55f)
        blob(c, w * 0.92f, h * 0.88f, w * 0.6f)
    }
}
