package com.shortstop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build

/**
 * react-bits "Shape Blur" with the Atom logo, preset "Lens": the logo stays crisp while a soft lens drifts over it
 * and pushes whatever it touches out of focus, with a little colour fringing. Settings are the ones from the page:
 * size 0.6, fill on, blur 16, lens size 0.3, softness 0.7, chroma 0.3, idle drift on. The effect scales with the
 * view, so it looks the same at any size. Runs as one AGSL pass on Android 13+; older phones get the crisp logo.
 */
class ShapeBlurLogo(private val ctx: Context, private val containerDp: Float, private val dark: Boolean, private val inkDark: Int) {
    private val d = ctx.resources.displayMetrics.density
    private val v = Math.round(containerDp * d).coerceAtLeast(8)
    private val sup = 3
    private val mask: Bitmap?
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rs: Any? = null
    private var t = 0f
    private var last = 0L
    private var lx = 0f   // lens position, px from centre, y up
    private var ly = 0f
    private var lp = 0f
    private var placed = false
    private val color = if (dark) inkDark else Color.parseColor("#120F17")

    init {
        var m: Bitmap? = null
        try {
            val src = ctx.assets.open("atom.png").use { BitmapFactory.decodeStream(it) }
            val c = v * sup
            m = Bitmap.createBitmap(c, c, Bitmap.Config.ARGB_8888)
            val cv = Canvas(m)
            val s = c * SIZE
            val o = (c - s) / 2f
            val dst = android.graphics.RectF(o, o, o + s, o + s)
            // shrink in halves so the logo is properly smoothed instead of skipping pixels
            var cur = src
            while (cur.width / 2 >= s.toInt() * 1.0f && cur.width > 8) {
                val nxt = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
                if (cur !== src) cur.recycle()
                cur = nxt
            }
            cv.drawBitmap(cur, null, dst, tint)
            if (cur !== src) cur.recycle()
            src.recycle()
        } catch (e: Exception) { m = null }
        mask = m
        if (Build.VERSION.SDK_INT >= 33 && m != null) {
            try { rs = build(m) } catch (e: Throwable) { rs = null }
        }
        tint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    @android.annotation.TargetApi(33)
    private fun build(m: Bitmap): Any {
        val sh = RuntimeShader(SRC)
        val bs = BitmapShader(m, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bs.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        val mx = Matrix()
        mx.setScale(1f / sup, 1f / sup)
        bs.setLocalMatrix(mx)
        sh.setInputShader("mask", bs)
        sh.setFloatUniform("res", v.toFloat(), v.toFloat())
        sh.setFloatUniform("lensShape", LENS_SIZE * v, LENS_SOFT)
        sh.setFloatUniform("blur", BLUR * (containerDp / REF_DP) * d)
        sh.setFloatUniform("chroma", CHROMA)
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        sh.setFloatUniform("col", r, g, b)
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        sh.setFloatUniform("backdrop", if (lum < 0.5f) 1f else 0f)
        sh.setFloatUniform("lens", 0f, 0f, 0f)
        return sh
    }

    /** True while the lens is moving, so the host knows to keep redrawing. */
    val animated: Boolean get() = rs != null

    private fun wander(tt: Float) {
        val reach = SIZE * v / 2f
        lx = reach * (0.75f * Math.sin(tt * 0.37).toFloat() + 0.2f * Math.sin(tt * 1.13 + 1.7).toFloat())
        ly = reach * (0.7f * Math.sin(tt * 0.53 + 0.9).toFloat() + 0.2f * Math.cos(tt * 0.91 + 0.4).toFloat())
    }

    private var tx = 0f
    private var ty = 0f

    @android.annotation.TargetApi(33)
    private fun frame(now: Long) {
        val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        t += dt
        val reach = SIZE * v / 2f
        tx = reach * (0.75f * Math.sin(t * 0.37).toFloat() + 0.2f * Math.sin(t * 1.13 + 1.7).toFloat())
        ty = reach * (0.7f * Math.sin(t * 0.53 + 0.9).toFloat() + 0.2f * Math.cos(t * 0.91 + 0.4).toFloat())
        if (!placed) { lx = tx; ly = ty; placed = true }
        val f = 1f - Math.exp((-dt * 3f).toDouble()).toFloat()
        lx += (tx - lx) * f
        ly += (ty - ly) * f
        lp += (0.85f - lp) * (1f - Math.exp((-dt * 4f).toDouble()).toFloat())
        (rs as RuntimeShader).setFloatUniform("lens", v / 2f + lx, v / 2f - ly, lp)
    }

    /** Draws the logo centred on (cx, cy). */
    fun draw(c: Canvas, cx: Float, cy: Float) {
        val m = mask ?: return
        val sh = rs
        c.save()
        c.translate(cx - v / 2f, cy - v / 2f)
        if (sh != null && Build.VERSION.SDK_INT >= 33) {
            frame(System.nanoTime())
            paint.shader = sh as Shader
            c.drawRect(0f, 0f, v.toFloat(), v.toFloat(), paint)
        } else {
            c.drawBitmap(m, null, android.graphics.RectF(0f, 0f, v.toFloat(), v.toFloat()), tint)
        }
        c.restore()
    }

    fun reset() { last = 0L }

    companion object {
        const val SIZE = 0.6f
        const val BLUR = 16f
        const val LENS_SIZE = 0.3f
        const val LENS_SOFT = 0.7f
        const val CHROMA = 0.3f
        const val REF_DP = 260f

        private const val SRC = """
uniform shader mask;
uniform float2 res;
uniform float3 lens;
uniform float2 lensShape;
uniform float blur;
uniform float chroma;
uniform float3 col;
uniform float backdrop;

float cov(float2 p, float sigma) {
  if (sigma <= 0.35) return mask.eval(p).a;
  float sum = 0.0;
  float wsum = 0.0;
  for (int i = 0; i < 24; i++) {
    float fi = float(i) + 0.5;
    float rr = sqrt(fi / 24.0) * 2.4;
    float a = fi * 2.39996323;
    float2 o = float2(cos(a), sin(a)) * rr * sigma;
    float w = exp(-0.5 * rr * rr);
    sum += mask.eval(p + o).a * w;
    wsum += w;
  }
  return sum / wsum;
}

half4 main(float2 fc) {
  float2 p = fc - lens.xy;
  float dist = length(p);
  float la = lens.z * (1.0 - smoothstep(lensShape.x * (1.0 - lensShape.y), lensShape.x, dist));
  float sigma = blur * la;
  float2 dir = dist > 0.001 ? p / dist : float2(0.0);
  float2 split = dir * chroma * 0.3 * sigma;
  float3 cv = float3(
    cov(fc - split, sigma * (1.0 + chroma * 0.25)),
    cov(fc, sigma),
    cov(fc + split, sigma * (1.0 - chroma * 0.2))
  );
  cv = mix(cv, 0.85 + 0.15 * (1.0 - exp((0.85 - cv) / 0.15)), step(0.85, cv));
  float alpha = max(max(cv.r, cv.g), cv.b);
  float3 rgb = col * cv + (alpha - cv) * backdrop;
  return half4(half3(rgb), half(alpha));
}
"""
    }
}
