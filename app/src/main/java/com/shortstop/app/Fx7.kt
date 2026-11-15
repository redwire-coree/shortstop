package com.shortstop.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * react-bits Grainient, ported to AGSL: a flowing, domain-warped gradient with silk shading and animated grain that
 * you can stir with a finger, plus a swirl on tap. Preset "Ember", light mode, with the exact values from the demo:
 * angle 55, bands 1, softness 1, flow 0.8, scale 1, speed 1, silk 0.35, grain 0.03 at 0.7px, contrast 1, saturation 1,
 * stir 1, intro on. Below Android 13 it falls back to a plain Ember gradient.
 */
class GrainientEngine(private val host: View) {
    // ---- settings (react-bits "Ember" + the values in the demo)
    private val colors = intArrayOf(
        Color.parseColor("#120F17"), Color.parseColor("#2A0F0A"), Color.parseColor("#9A3412"),
        Color.parseColor("#F97316"), Color.parseColor("#FED7AA")
    )
    private val angle = 55f
    private val bands = 1f
    private val speed = 1f
    private val flow = 0.8f
    private val scale = 1f
    private val softness = 1f
    private val silk = 0.35f
    private val grainAmount = 0.03f
    private val grainSize = 0.7f
    private val contrast = 1f
    private val saturation = 1f
    private val stirAmount = 1f
    private val lift = 0.2f

    private val maxStirs = 24
    private val maxTwirls = 4
    private val stirLife = 1.3f
    private val stirStep = 0.06f
    private val twirlLife = 2.6f
    private val introSeconds = 1.6f

    private class Stir(var x: Float, var y: Float, var vx: Float, var vy: Float, var age: Float, var grow: Float, var done: Boolean)
    private class Spin(val x: Float, val y: Float, var age: Float, val direction: Float)

    private val trail = ArrayList<Stir>()
    private val spins = ArrayList<Spin>()
    private var px = 0.5f
    private var py = 0.5f
    private var sx = 0.5f
    private var sy = 0.5f
    private var pvx = 0f
    private var inside = false
    private var fresh = true

    private var time = 0f
    private var introClock = 0f
    private var frameSeed = 0
    private var grainClock = 0f
    private var lastNs = 0L
    private var lastDraw = 0L

    private val ramp = FloatArray(32)
    private val stirs = FloatArray(maxStirs * 4)
    private val stirW = FloatArray(maxStirs)
    private val twirls = FloatArray(maxTwirls * 4)

    private var gpuOk = Build.VERSION.SDK_INT >= 33
    private var shader: Any? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        buildRamp()
    }

    // ---------------------------------------------------------------- colour (OKLab, as in the original)

    private fun toLinear(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun toLab(c: Int): FloatArray {
        val lr = toLinear(Color.red(c) / 255f)
        val lg = toLinear(Color.green(c) / 255f)
        val lb = toLinear(Color.blue(c) / 255f)
        val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
        val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
        val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
        return floatArrayOf(
            0.2104542553f * l + 0.793617785f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.428592205f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.808675766f * s
        )
    }

    /** Light mode: dark ramp entries become white, the tints run in reverse and are lifted. */
    private fun buildRamp() {
        val labs = colors.map { toLab(it) }
        val blank = labs.map { abs(it[0] - labs[0][0]) < 0.1f }
        val tints = ArrayList(labs.filterIndexed { i, _ -> !blank[i] }.reversed())
        for (i in labs.indices) {
            val lab = if (blank[i]) floatArrayOf(1f, 0f, 0f) else tints.removeAt(0)
            ramp[i * 4] = lab[0] + (1f - lab[0]) * lift
            ramp[i * 4 + 1] = lab[1] * (1f - lift)
            ramp[i * 4 + 2] = lab[2] * (1f - lift)
            ramp[i * 4 + 3] = 0f
        }
    }

    // ---------------------------------------------------------------- touch (face coordinates, 0..1, y up)

    fun pointerMove(x: Float, y: Float) {
        px = x; py = y
        inside = x in 0f..1f && y in 0f..1f
        if (fresh || !inside) { sx = x; sy = y; fresh = false }
    }

    fun pointerDown(x: Float, y: Float) {
        pointerMove(x, y)
        if (!inside) return
        spins.add(0, Spin(px - 0.5f, py - 0.5f, 0f, if (pvx < 0f) 1f else -1f))
        if (spins.size > maxTwirls) spins.removeAt(spins.size - 1)
    }

    fun pointerUp() { inside = false }

    fun restartIntro() { introClock = 0f }

    // ---------------------------------------------------------------- simulation

    /** Advances the state. Returns true when something is still moving (always, while the gradient flows). */
    fun step(): Boolean {
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastNs == 0L) 1f / 60f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNs = now
        time += dt * speed * 0.16f
        if (introClock < 1f) introClock = min(1f, introClock + dt / introSeconds)
        grainClock += dt
        if (grainClock > 1f / 24f) { grainClock = 0f; frameSeed = (frameSeed + 1) % 97 }
        updateTouches(dt)
        return true
    }

    fun touching() = trail.isNotEmpty() || spins.isNotEmpty() || inside

    private fun updateTouches(dt: Float) {
        val follow = 1f - exp(-dt / 0.06f)
        val ox = sx
        val oy = sy
        sx += (px - sx) * follow
        sy += (py - sy) * follow
        val vx = (sx - ox) / dt
        val vy = (sy - oy) / dt
        pvx = vx
        val head = trail.firstOrNull()
        for (s in trail) if (s.done) s.age += dt
        if (inside && hypot(vx, vy) > 0.02f) {
            if (head == null || head.done) {
                trail.add(0, Stir(0f, 0f, 0f, 0f, 0f, 0f, false))
                if (trail.size > maxStirs) trail.removeAt(trail.size - 1)
            }
            val live = trail[0]
            live.x = sx - 0.5f
            live.y = sy - 0.5f
            live.vx = vx
            live.vy = vy
            live.grow = min(1f, live.grow + dt / stirStep)
            live.done = live.grow >= 1f
        } else if (head != null) {
            head.done = true
        }
        while (trail.isNotEmpty() && trail[trail.size - 1].age > stirLife) trail.removeAt(trail.size - 1)
        for (i in 0 until maxStirs) {
            val s = trail.getOrNull(i)
            if (s == null) { stirW[i] = 0f; continue }
            val fade = 1f - s.age / stirLife
            val cap = min(1f, 2.2f / max(hypot(s.vx, s.vy), 0.001f))
            stirs[i * 4] = s.x; stirs[i * 4 + 1] = s.y; stirs[i * 4 + 2] = s.vx * cap; stirs[i * 4 + 3] = s.vy * cap
            stirW[i] = fade * fade * s.grow * stirAmount * 0.05f
        }
        for (s in spins) s.age += dt
        while (spins.isNotEmpty() && spins[spins.size - 1].age > twirlLife) spins.removeAt(spins.size - 1)
        for (i in 0 until maxTwirls) {
            val s = spins.getOrNull(i)
            if (s == null) { twirls[i * 4 + 2] = 0f; continue }
            val k = s.age / twirlLife
            val env = min(1f, s.age / 0.18f) * (1f - k) * (1f - k)
            twirls[i * 4] = s.x; twirls[i * 4 + 1] = s.y
            twirls[i * 4 + 2] = s.direction * 2.4f * env
            twirls[i * 4 + 3] = 0.16f + 0.1f * min(1f, s.age / 0.5f)
        }
    }

    // ---------------------------------------------------------------- drawing

    /** Draws the gradient as a disc of the given centre and radius (view pixels). */
    fun drawDisc(c: Canvas, cx: Float, cy: Float, radius: Float, density: Float) {
        if (Build.VERSION.SDK_INT >= 33 && gpuOk && drawGpu(c, cx, cy, radius, density)) return
        // fallback: the same Ember ramp as a plain linear gradient
        val a = Math.toRadians(angle.toDouble())
        val dx = sin(a).toFloat() * radius
        val dy = -cos(a).toFloat() * radius
        val wob = sin(time * 6f) * radius * 0.15f
        val white = Color.WHITE
        val cols = intArrayOf(
            white, white, mixColor(colors[4], white, lift), mixColor(colors[3], white, lift), mixColor(colors[2], white, lift)
        )
        fb.shader = LinearGradient(cx - dx + wob, cy - dy, cx + dx + wob, cy + dy, cols, floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, radius, fb)
    }

    @android.annotation.TargetApi(33)
    private fun drawGpu(c: Canvas, cx: Float, cy: Float, radius: Float, density: Float): Boolean {
        try {
            var s = shader as? android.graphics.RuntimeShader
            if (s == null) {
                s = android.graphics.RuntimeShader(AGSL)
                shader = s
            }
            val size = radius * 2f
            val rad = Math.toRadians(angle.toDouble())
            val dirX = sin(rad).toFloat()
            val dirY = cos(rad).toFloat()
            s.setFloatUniform("uOrigin", cx - radius, cy - radius)
            s.setFloatUniform("uSize", size, size)
            s.setFloatUniform("uRadius", radius)
            s.setFloatUniform("uTime", time)
            s.setFloatUniform("uRamp", ramp)
            s.setFloatUniform("uCount", colors.size.toFloat())
            s.setFloatUniform("uDir", dirX, dirY)
            s.setFloatUniform("uExtent", abs(dirX) + abs(dirY))
            s.setFloatUniform("uBands", bands)
            s.setFloatUniform("uSoftness", softness)
            s.setFloatUniform("uFlow", flow)
            s.setFloatUniform("uScale", scale)
            s.setFloatUniform("uSilk", silk)
            s.setFloatUniform("uStir", stirs)
            s.setFloatUniform("uStirW", stirW)
            s.setFloatUniform("uTwirl", twirls)
            s.setFloatUniform("uSeed", 0f, 0f)
            s.setFloatUniform("uContrast", contrast)
            s.setFloatUniform("uSaturation", saturation)
            s.setFloatUniform("uSettle", introClock * introClock * (3f - 2f * introClock))
            s.setFloatUniform("uReveal", introClock)
            s.setFloatUniform("uGrain", grainAmount)
            s.setFloatUniform("uGrainSize", max(1f, grainSize * density))
            s.setFloatUniform("uFrame", frameSeed.toFloat())
            paint.shader = s
            rect.set(cx - radius - 1f, cy - radius - 1f, cx + radius + 1f, cy + radius + 1f)
            c.drawRect(rect, paint)
            return true
        } catch (e: Throwable) {
            gpuOk = false
            return false
        }
    }

    private val AGSL = """
uniform float2 uOrigin;
uniform float2 uSize;
uniform float uRadius;
uniform float uTime;
uniform float4 uRamp[8];
uniform float uCount;
uniform float2 uDir;
uniform float uExtent;
uniform float uBands;
uniform float uSoftness;
uniform float uFlow;
uniform float uScale;
uniform float uSilk;
uniform float4 uStir[24];
uniform float uStirW[24];
uniform float4 uTwirl[4];
uniform float2 uSeed;
uniform float uContrast;
uniform float uSaturation;
uniform float uSettle;
uniform float uReveal;
uniform float uGrain;
uniform float uGrainSize;
uniform float uFrame;

float2 hash2(float2 p) {
  float3 q = fract(float3(p.xyx) * float3(0.1031, 0.103, 0.0973));
  q += dot(q, q.yzx + 33.33);
  return fract((q.xx + q.yz) * q.zy) * 2.0 - 1.0;
}

float hash(float2 p) {
  float3 q = fract(float3(p.xyx) * 0.1031);
  q += dot(q, q.yzx + 33.33);
  return fract((q.x + q.y) * q.z);
}

float noise(float2 p) {
  float2 i = floor(p);
  float2 f = fract(p);
  float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  float a = dot(hash2(i), f);
  float b = dot(hash2(i + float2(1.0, 0.0)), f - float2(1.0, 0.0));
  float c = dot(hash2(i + float2(0.0, 1.0)), f - float2(0.0, 1.0));
  float d = dot(hash2(i + float2(1.0, 1.0)), f - float2(1.0, 1.0));
  return 0.5 + 0.5 * mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float2 drift(float2 p, float t) {
  float2 w1 = float2(
    noise(p * 0.85 + float2(t * 0.11, t * 0.07)),
    noise(p * 0.85 + float2(4.1, 1.7) - float2(t * 0.08, t * 0.12))
  );
  float2 w2 = float2(
    noise(p * 1.55 + w1 * 1.8 + float2(1.3, 7.1) + t * 0.05),
    noise(p * 1.55 + w1 * 1.8 + float2(6.4, 2.2) - t * 0.06)
  );
  return (w1 - 0.5) * 1.1 + (w2 - 0.5) * 0.55;
}

float2 warp(float2 p) {
  float2 q = p / uScale;
  q += float2(-uDir.y, uDir.x) * uTime * 0.45;
  return p + drift(q + uSeed, uTime) * uFlow * uScale * uSettle;
}

float coord(float2 p) {
  float2 w = warp(p);
  return dot(w, uDir) / uExtent + 0.5;
}

float3 labToRgb(float3 lab) {
  float l = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
  float m = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
  float s = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
  l = l * l * l;
  m = m * m * m;
  s = s * s * s;
  return float3(
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
  );
}

float enc1(float c) {
  c = clamp(c, 0.0, 1.0);
  return c < 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

float clump(float2 p) {
  float2 i = floor(p);
  float2 f = fract(p);
  float2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

half4 main(float2 fc) {
  float2 local = fc - uOrigin;
  float mask = clamp(uRadius - length(local - uSize * 0.5) + 0.5, 0.0, 1.0);
  if (mask <= 0.0) return half4(0.0);
  float2 vUv = float2(local.x / uSize.x, 1.0 - local.y / uSize.y);
  float2 p = vUv - 0.5;
  for (int i = 0; i < 24; i++) {
    float w = uStirW[i];
    if (w <= 0.0) continue;
    float2 dd = p - uStir[i].xy;
    p -= uStir[i].zw * w * exp(-dot(dd, dd) / 0.045);
  }
  for (int i = 0; i < 4; i++) {
    float angle = uTwirl[i].z;
    if (angle == 0.0) continue;
    float2 dd = p - uTwirl[i].xy;
    float a = angle * exp(-dot(dd, dd) / (uTwirl[i].w * uTwirl[i].w));
    float cs = cos(a);
    float sn = sin(a);
    p = uTwirl[i].xy + float2(cs * dd.x + sn * dd.y, -sn * dd.x + cs * dd.y);
  }
  float raw = coord(p);
  float s = raw;
  if (uBands > 1.0) {
    s *= uBands;
    float cycle = floor(s);
    s = fract(s);
    if (mod(cycle, 2.0) > 0.5) s = 1.0 - s;
  }
  s = clamp(s, 0.0, 1.0);
  float scaled = s * (uCount - 1.0);
  float indexF = min(floor(scaled), uCount - 2.0);
  float f = scaled - indexF;
  float edge = max(uSoftness * 0.5, 0.003);
  float shaped = smoothstep(0.5 - edge, 0.5 + edge, f);
  float3 cFrom = uRamp[0].xyz;
  float3 cTo = uRamp[1].xyz;
  for (int i = 0; i < 7; i++) {
    if (float(i) == indexF) {
      cFrom = uRamp[i].xyz;
      cTo = uRamp[i + 1].xyz;
    }
  }
  float3 lab = mix(cFrom, cTo, shaped);
  if (uSilk > 0.0) {
    float2 grad = float2(coord(p + float2(0.004, 0.0)) - raw, coord(p + float2(0.0, 0.004)) - raw) / 0.004;
    float2 slope = grad * sin(3.14159265 * clamp(raw, 0.0, 1.0) * uBands) * uSilk * 4.0;
    float3 normal = normalize(float3(-slope, 1.0));
    float3 light = normalize(float3(-uDir.y, uDir.x, 1.2));
    float diffuse = dot(normal, light) - light.z;
    lab = uRamp[0].xyz + (lab - uRamp[0].xyz) * (1.0 + (diffuse * 0.35 + max(diffuse, 0.0) * diffuse * 0.6) * uSilk);
  }
  lab = mix(uRamp[0].xyz, lab, smoothstep(0.0, 1.0, uReveal * 1.5 - length(vUv - 0.5) * 0.6));
  lab.x = (lab.x - 0.55) * uContrast + 0.55;
  lab.yz *= uSaturation;
  float3 rgb = labToRgb(lab);
  float3 color = float3(enc1(rgb.x), enc1(rgb.y), enc1(rgb.z));

  float2 pixel = fc + uFrame * float2(17.0, 59.0);
  float fine = hash(pixel) + hash(pixel + 7.13) - 1.0;
  float coarse = (clump(pixel / max(uGrainSize, 1.0)) - 0.5) * 2.0;
  float grain = mix(fine, coarse, clamp(uGrainSize - 1.0, 0.0, 1.0) * 0.8);
  float luma = dot(color, float3(0.2126, 0.7152, 0.0722));
  color += grain * uGrain * (0.45 + 2.2 * luma * (1.0 - luma));
  color = clamp(color, 0.0, 1.0);
  return half4(half3(color * mask), half(mask));
}
"""
}
