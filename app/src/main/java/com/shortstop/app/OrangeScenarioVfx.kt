package com.shortstop.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight deterministic looping effects for Orange scenarios.
 *
 * All particles are derived from elapsed time and a stable phase. This avoids per-frame
 * allocations, unbounded particle lists, random flicker and callback/timer leaks.
 */
internal object OrangeScenarioVfx {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()

    fun draw(c: Canvas, scenario: OrangeScenario, t: Float, left: Float, top: Float,
             size: Float, density: Float, reducedMotion: Boolean) {
        val amp = if (reducedMotion) 0.48f else 1f
        val cx = left + size * 0.5f
        val cy = top + size * 0.55f
        when (scenario) {
            OrangeScenario.NONE -> Unit
            OrangeScenario.BEGGING -> begging(c, t, cx, cy, size, amp)
            OrangeScenario.DEAD -> soul(c, t, cx, cy, size, amp)
            OrangeScenario.PANIC -> panic(c, t, cx, cy, size, amp)
            OrangeScenario.CRYING -> crying(c, t, cx, cy, size, amp)
            OrangeScenario.FINAL_BOSS -> fire(c, t, cx, cy, size, amp)
            OrangeScenario.OVERLOAD -> smoke(c, t, cx, cy, size, amp)
            OrangeScenario.VICTORY -> victory(c, t, cx, cy, size, amp)
        }
    }

    private fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or
        ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    private fun begging(c: Canvas, t: Float, x: Float, y: Float, s: Float, a: Float) {
        // Trembling emphasis marks and small tears that cycle smoothly.
        val shake = sin(t * 18f) * s * 0.008f * a
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = s * 0.012f
        p.color = alpha(0xFF45A7E8.toInt(), 0.75f)
        for (side in -1..1 step 2) {
            val xx = x + side * s * 0.39f + shake
            c.drawLine(xx, y - s * 0.24f, xx + side * s * 0.025f, y - s * 0.13f, p)
        }
        for (i in 0..1) {
            val phase = ((t * 0.72f + i * 0.5f) % 1f)
            val yy = y - s * 0.04f + phase * s * 0.34f
            p.style = Paint.Style.FILL
            p.color = alpha(0xFF56BCEB.toInt(), (1f - phase) * 0.85f * a)
            c.drawOval(x - s * 0.20f + i * s * 0.40f, yy, x - s * 0.17f + i * s * 0.40f,
                yy + s * 0.055f, p)
        }
    }

    private fun soul(c: Canvas, t: Float, x: Float, y: Float, s: Float, a: Float) {
        val phase = (t % 3.2f) / 3.2f
        val rise = phase
        val sx = x + sin(t * 1.9f) * s * 0.055f
        val sy = y - s * (0.38f + rise * 0.78f)
        val fade = (1f - ((phase - 0.62f) / 0.38f).coerceIn(0f, 1f)) * a
        val radius = s * 0.105f * (0.96f + 0.04f * sin(t * 4f))
        p.style = Paint.Style.FILL
        p.color = alpha(0xFFEAF7FF.toInt(), fade * 0.94f)
        c.drawCircle(sx, sy, radius, p)
        path.reset()
        path.moveTo(sx - radius * 0.82f, sy + radius * 0.25f)
        path.quadTo(sx - radius, sy + radius * 1.0f, sx - radius * 0.48f, sy + radius * 0.78f)
        path.quadTo(sx, sy + radius * 1.18f, sx + radius * 0.48f, sy + radius * 0.78f)
        path.quadTo(sx + radius, sy + radius, sx + radius * 0.82f, sy + radius * 0.25f)
        path.close()
        c.drawPath(path, p)
        p.color = alpha(0xFF6C7A8A.toInt(), fade * 0.75f)
        c.drawCircle(sx - radius * 0.28f, sy, radius * 0.075f, p)
        c.drawCircle(sx + radius * 0.28f, sy, radius * 0.075f, p)
        // tiny glint follows the soul and fades with it
        p.color = alpha(Color.WHITE, fade * 0.9f)
        c.drawCircle(sx - radius * 0.35f, sy - radius * 0.38f, radius * 0.13f, p)
    }

    private fun panic(c: Canvas, t: Float, x: Float, y: Float, s: Float, a: Float) {
        val wobble = (0.5f + 0.5f * sin(t * 14f)) * a
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = s * 0.013f
        p.color = alpha(0xFF55B8F0.toInt(), 0.5f + wobble * 0.35f)
        c.drawLine(x - s * 0.43f, y - s * 0.26f, x - s * 0.40f, y - s * 0.42f, p)
        c.drawLine(x + s * 0.43f, y - s * 0.26f, x + s * 0.40f, y - s * 0.42f, p)
        for (i in 0..2) {
            val phase = ((t * 0.62f + i * 0.34f) % 1f)
            val xx = x + (i - 1) * s * 0.28f
            val yy = y - s * 0.55f + phase * s * 0.52f
            p.style = Paint.Style.FILL
            p.color = alpha(0xFF70C9F4.toInt(), (1f - phase) * a)
            c.drawOval(xx - s * 0.018f, yy, xx + s * 0.018f, yy + s * 0.055f, p)
        }
    }

    private fun crying(c: Canvas, t: Float, x: Float, y: Float, s: Float, a: Float) {
        // Two continuous streams with a soft sinusoidal edge and timed splash droplets.
        for (side in -1..1 step 2) {
            val xx = x + side * s * 0.255f
            val wave = sin(t * 5f + side) * s * 0.012f
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = s * 0.052f
            p.color = alpha(0xFF55B9F4.toInt(), 0.82f * a)
            c.drawLine(xx, y - s * 0.10f, xx + wave, y + s * 0.42f, p)
            p.style = Paint.Style.FILL
            p.color = alpha(0xFFBDEEFF.toInt(), 0.65f * a)
            c.drawCircle(xx - s * 0.012f, y + s * 0.12f, s * 0.012f, p)
        }
        for (i in 0..5) {
            val phase = ((t * 0.95f + i / 6f) % 1f)
            val side = if (i % 2 == 0) -1f else 1f
            val xx = x + side * s * (0.25f + phase * 0.23f)
            val yy = y + s * (0.36f + sin(phase * PI).toFloat() * 0.12f)
            p.color = alpha(0xFF71C9F4.toInt(), (1f - phase) * a)
            c.drawCircle(xx, yy, s * (0.012f + 0.009f * (1f - phase)), p)
        }
    }

    private fun fire(c: Canvas, t: Float, x: Float, y: Float, s: Float, a: Float) {
        // Layered flame tongues pulse at different frequencies; embers have independent phases.
        for (i in 0..4) {
            val side = (i - 2) / 2f
            val pulse = 0.78f + 0.22f * sin(t * (5.1f + i * 0.31f) + i * 1.7f)
            val fx = x + side * s * 0.30f
            val baseY = y + s * 0.42f
            val h = s * (0.18f + (i % 3) * 0.045f) * pulse * a
            path.reset()
            path.moveTo(fx - s * 0.065f, baseY)
            path.quadTo(fx - s * 0.12f, baseY - h * 0.55f, fx + sin(t * 4f + i) * s * 0.025f, baseY - h)
            path.quadTo(fx + s * 0.12f, baseY - h * 0.45f, fx + s * 0.065f, baseY)
            path.close()
            p.style = Paint.Style.FILL
            p.color = alpha(if (i % 2 == 0) 0xFFFFA51E.toInt() else 0xFFFFD34E.toInt(), 0.66f * a)
            c.drawPath(path, p)
        }
        for (i in 0..10) {
            val phase = ((t * (0.34f + (i % 4) * 0.07f) + i * 0.137f) % 1f)
            val drift = sin(t * 1.8f + i * 2.1f) * s * 0.045f
            val xx = x + ((i % 5) - 2) * s * 0.13f + drift
            val yy = y + s * 0.37f - phase * s * (0.48f + (i % 3) * 0.16f)
            val radius = s * (0.009f + (i % 3) * 0.003f) * (1f - phase * 0.45f)
            p.color = alpha(if (i % 2 == 0) 0xFFFFB52D.toInt() else 0xFFFFE18A.toInt(), (1f - phase) * a)
            c.drawCircle(xx, yy, radius, p)
        }
    }

    private fun smoke(c: Canvas, t: Float, x: Float, y: Float, s: Float, a: Float) {
        for (i in 0..4) {
            val phase = ((t * 0.22f + i * 0.2f) % 1f)
            val xx = x + sin(t * 1.4f + i * 1.6f) * s * 0.10f + (i - 2) * s * 0.035f
            val yy = y - s * 0.45f - phase * s * 0.48f
            val radius = s * (0.025f + phase * 0.055f)
            p.style = Paint.Style.FILL
            p.color = alpha(0xFF9BA5B2.toInt(), (1f - phase) * 0.45f * a)
            c.drawCircle(xx, yy, radius, p)
        }
    }

    private fun victory(c: Canvas, t: Float, x: Float, y: Float, s: Float, a: Float) {
        // Confetti follows staggered falling arcs, while sparkles pulse at separate rates.
        for (i in 0..13) {
            val phase = ((t * (0.24f + (i % 3) * 0.035f) + i * 0.071f) % 1f)
            val xx = x + sin(t * 1.7f + i * 1.2f) * s * 0.07f + ((i % 7) - 3) * s * 0.105f
            val yy = y - s * 0.66f + phase * s * 1.12f
            val w = s * (0.012f + (i % 3) * 0.004f)
            val h = w * (1.5f + (i % 2))
            p.color = alpha(when (i % 4) {
                0 -> 0xFFFFC94F.toInt()
                1 -> 0xFFFF8A45.toInt()
                2 -> 0xFF70C9A5.toInt()
                else -> 0xFF83BDF4.toInt()
            }, (1f - phase * 0.3f) * a)
            c.save()
            c.rotate((t * 150f + i * 47f) % 360f, xx, yy)
            c.drawRoundRect(xx - w, yy - h, xx + w, yy + h, w, w, p)
            c.restore()
        }
        for (i in 0..5) {
            val ang = i * (PI / 3.0).toFloat()
            val pulse = 0.55f + 0.45f * sin(t * 3.0f + i)
            val xx = x + cos(ang) * s * 0.48f
            val yy = y - s * 0.15f + sin(ang) * s * 0.34f
            p.color = alpha(0xFFFFD45A.toInt(), pulse * a)
            c.drawCircle(xx, yy, s * 0.014f * pulse, p)
        }
    }
}
