package com.shortstop.app

import android.graphics.Bitmap
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Orange's articulated rig.
 *
 * Every part that can move on its own has its own channel here. A move (or an overlay such as a pet, a
 * press or a drag) only writes TARGETS into [OrangeRig.base] / [OrangeRig.add]; the solver then moves the
 * real values toward those targets with a critically damped spring per channel. That gives three guarantees:
 *   - two layers can never fight over a part: the base pose is rewritten every frame and overlays are
 *     added on top, so a pet or a blink cannot restart or cancel the focus pose underneath;
 *   - changing from one move to another is always a smooth glide, never a jump;
 *   - the result does not depend on the frame rate (the spring is solved in closed form).
 * Angles are degrees. Distances are in layer units (the 640-unit space the artwork was cut in).
 */
object RigCh {
    const val HEAD_ROT = 0; const val HEAD_X = 1; const val HEAD_Y = 2
    // arms: shoulder / elbow / wrist angle, hand openness (1 = open mitt, 0 = tight fist)
    const val ARM_L_SH = 3; const val ARM_L_EL = 4; const val ARM_L_WR = 5; const val ARM_L_HAND = 6
    const val ARM_R_SH = 7; const val ARM_R_EL = 8; const val ARM_R_WR = 9; const val ARM_R_HAND = 10
    // legs: hip / knee / ankle angle offsets on top of the foot-placement solution, plus where the foot goes
    const val LEG_L_HIP = 11; const val LEG_L_KNEE = 12; const val LEG_L_ANKLE = 13
    const val LEG_R_HIP = 14; const val LEG_R_KNEE = 15; const val LEG_R_ANKLE = 16
    const val FOOT_L_X = 17; const val FOOT_L_LIFT = 18; const val FOOT_R_X = 19; const val FOOT_R_LIFT = 20
    // face: each eye separately
    const val EYE_L_OPEN = 21; const val EYE_R_OPEN = 22
    const val LID_L_COVER = 23; const val LID_R_COVER = 24   // 0..1 of the eye covered from above
    const val LID_L_TILT = 25; const val LID_R_TILT = 26     // -1 sad .. +1 angry (slant of the lid line)
    const val PUPIL_X = 27; const val PUPIL_Y = 28; const val PUPIL_SCALE = 29
    const val MOUTH_OPEN = 30; const val MOUTH_SMILE = 31
    const val LEAF_BEND = 32
    const val COUNT = 33

    /** Value of every channel when nothing is happening. */
    val REST = FloatArray(COUNT).also {
        it[ARM_L_HAND] = 1f; it[ARM_R_HAND] = 1f
        it[EYE_L_OPEN] = 1f; it[EYE_R_OPEN] = 1f
        it[PUPIL_SCALE] = 1f; it[MOUTH_OPEN] = 1f
    }

    /** Spring stiffness (rad/s) per channel: heavy things (head, legs) settle slower than light ones (lids). */
    val OMEGA = FloatArray(COUNT) { 18f }.also {
        it[HEAD_ROT] = 14f; it[HEAD_X] = 14f; it[HEAD_Y] = 14f
        for (c in ARM_L_SH..ARM_L_HAND) it[c] = 19f
        for (c in ARM_R_SH..ARM_R_HAND) it[c] = 19f
        for (c in LEG_L_HIP..FOOT_R_LIFT) it[c] = 22f
        it[EYE_L_OPEN] = 30f; it[EYE_R_OPEN] = 30f; it[LID_L_COVER] = 26f; it[LID_R_COVER] = 26f
        it[LID_L_TILT] = 22f; it[LID_R_TILT] = 22f
        it[PUPIL_X] = 20f; it[PUPIL_Y] = 20f; it[PUPIL_SCALE] = 24f
        it[MOUTH_OPEN] = 24f; it[MOUTH_SMILE] = 24f; it[LEAF_BEND] = 8f
    }
}

class OrangeRig {
    /** Where the current move wants every channel. Rewritten each frame by [begin]. */
    val base = FloatArray(RigCh.COUNT)
    /** Overlays (touch, pet, drag, effects) add to the base; also cleared each frame. */
    val add = FloatArray(RigCh.COUNT)
    /** The values that are actually drawn. */
    val cur = FloatArray(RigCh.COUNT)
    private val vel = FloatArray(RigCh.COUNT)
    private var primed = false

    fun begin() {
        System.arraycopy(RigCh.REST, 0, base, 0, RigCh.COUNT)
        java.util.Arrays.fill(add, 0f)
    }

    /** Advances every channel by dt seconds (closed-form critically damped spring: stable at any dt). */
    fun step(dt: Float) {
        if (!primed) {
            primed = true
            for (i in 0 until RigCh.COUNT) { cur[i] = base[i] + add[i]; vel[i] = 0f }
            return
        }
        val h = dt.coerceIn(0.0005f, 0.1f)
        for (i in 0 until RigCh.COUNT) {
            val w = RigCh.OMEGA[i]
            val x = cur[i] - (base[i] + add[i])
            val e = exp(-w * h)
            val c1 = vel[i] + w * x
            cur[i] = (base[i] + add[i]) + (x + c1 * h) * e
            vel[i] = (vel[i] - w * c1 * h) * e
        }
    }

    operator fun get(ch: Int) = cur[ch]
}

/**
 * A three-bone arm (upper arm, forearm, hand) skinned onto one of Orange's own arm lobes. The lobe artwork
 * is cut from the original picture, so it keeps its exact colours and soft shading; bending it only moves
 * the mesh. Weights blend smoothly across each joint so the lobe bends like a soft arm instead of folding.
 * Nothing here allocates after construction.
 */
class RigArm(val bmp: Bitmap, private val ox: Float, private val oy: Float, joints: FloatArray, private val side: Float) {
    val cols = 10
    val rows = 10
    val count = (cols + 1) * (rows + 1)
    private val rest = FloatArray(count * 2)
    private val w0 = FloatArray(count)
    private val w2 = FloatArray(count)
    /** Deformed vertex positions in layer space. */
    val pos = FloatArray(count * 2)
    private val sx = joints[0]; private val sy = joints[1]
    private val ex = joints[2]; private val ey = joints[3]
    private val wx = joints[4]; private val wy = joints[5]
    /** Texture coordinates for drawBitmapMesh are implied by the grid, so only positions are needed. */
    val width get() = bmp.width.toFloat()
    val height get() = bmp.height.toFloat()

    init {
        val tx = joints[6] - sx; val ty = joints[7] - sy
        val len = hypot(tx, ty)
        val ax = tx / len; val ay = ty / len
        val ue = ((ex - sx) * ax + (ey - sy) * ay) / len
        val uw = ((wx - sx) * ax + (wy - sy) * ay) / len
        var n = 0
        for (r in 0..rows) for (c in 0..cols) {
            val px = ox + bmp.width * c / cols.toFloat()
            val py = oy + bmp.height * r / rows.toFloat()
            rest[n * 2] = px; rest[n * 2 + 1] = py
            val u = ((px - sx) * ax + (py - sy) * ay) / len
            w2[n] = smooth((u - (uw - 0.12f)) / 0.24f)
            w0[n] = 1f - smooth((u - (ue - 0.12f)) / 0.24f)
            n++
        }
    }

    private fun smooth(x: Float): Float { val v = x.coerceIn(0f, 1f); return v * v * (3f - 2f * v) }

    /**
     * Bends the arm. [sh], [el], [wr] are degrees: positive always raises the arm, on either side (the
     * right arm is mirrored internally), so the same number means the same gesture on both sides. [hand] scales the mitt.
     */
    fun deform(sh: Float, el: Float, wr: Float, hand: Float) {
        val a0 = Math.toRadians((sh * side).toDouble()); val a1 = Math.toRadians((el * side).toDouble()); val a2 = Math.toRadians((wr * side).toDouble())
        val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat()
        val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
        val c2 = cos(a2).toFloat(); val s2 = sin(a2).toFloat()
        // where the wrist ends up after the first two bones (used to scale the hand about it)
        val wdx = wx - ex; val wdy = wy - ey
        val w1x = ex + (wdx * c1 - wdy * s1); val w1y = ey + (wdx * s1 + wdy * c1)
        val wfx = sx + ((w1x - sx) * c0 - (w1y - sy) * s0); val wfy = sy + ((w1x - sx) * s0 + (w1y - sy) * c0)
        for (i in 0 until count) {
            val px = rest[i * 2]; val py = rest[i * 2 + 1]
            // bone 0: rotate about the shoulder
            val p0x = sx + ((px - sx) * c0 - (py - sy) * s0); val p0y = sy + ((px - sx) * s0 + (py - sy) * c0)
            // bone 1: rotate about the elbow, then bone 0
            var qx = ex + ((px - ex) * c1 - (py - ey) * s1); var qy = ey + ((px - ex) * s1 + (py - ey) * c1)
            val p1x = sx + ((qx - sx) * c0 - (qy - sy) * s0); val p1y = sy + ((qx - sx) * s0 + (qy - sy) * c0)
            // bone 2: rotate about the wrist, then bone 1, then bone 0
            val rx = wx + ((px - wx) * c2 - (py - wy) * s2); val ry = wy + ((px - wx) * s2 + (py - wy) * c2)
            qx = ex + ((rx - ex) * c1 - (ry - ey) * s1); qy = ey + ((rx - ex) * s1 + (ry - ey) * c1)
            val p2x0 = sx + ((qx - sx) * c0 - (qy - sy) * s0); val p2y0 = sy + ((qx - sx) * s0 + (qy - sy) * c0)
            // hand openness: the mitt shrinks a little toward a fist
            val hs = 1f - (1f - hand.coerceIn(0f, 1.2f)) * 0.10f * w2[i]
            val p2x = wfx + (p2x0 - wfx) * hs; val p2y = wfy + (p2y0 - wfy) * hs
            val wa = w0[i]; val wc = w2[i]; val wb = (1f - wa - wc).coerceIn(0f, 1f)
            pos[i * 2] = wa * p0x + wb * p1x + wc * p2x
            pos[i * 2 + 1] = wa * p0y + wb * p1y + wc * p2y
        }
    }
}

/**
 * A two-bone leg (upper leg, lower leg) plus a foot. The foot is PLACED (x offset and lift) and the knee is
 * solved, so the leg always reaches its foot instead of stretching; hip, knee and ankle offsets then bend it
 * further for acting. Legs are drawn behind the body, so only the feet and the lower legs ever show.
 */
class RigLeg(private val hipX: Float, private val hipY: Float, private val side: Float) {
    val thigh = 40f
    val shin = 40f
    var kneeX = 0f; var kneeY = 0f
    var ankleX = 0f; var ankleY = 0f
    var footAngle = 0f

    fun solve(footDx: Float, lift: Float, hip: Float, knee: Float, ankle: Float, groundY: Float) {
        val tx = hipX + footDx
        val ty = groundY - lift
        var dx = tx - hipX; var dy = ty - hipY
        var d = hypot(dx, dy)
        val reach = thigh + shin - 0.5f
        if (d > reach) { dx *= reach / d; dy *= reach / d; d = reach }
        if (d < 8f) d = 8f
        val base = atan2(dy, dx)
        val cosHip = ((thigh * thigh + d * d - shin * shin) / (2f * thigh * d)).coerceIn(-1f, 1f)
        // the knee always bends outward, away from the body's centre line
        val bend = acos(cosHip) * (if (side < 0f) 1f else -1f)
        val thighAng = base + bend + Math.toRadians(hip.toDouble()).toFloat() * side
        kneeX = hipX + cos(thighAng) * thigh
        kneeY = hipY + sin(thighAng) * thigh
        val toAx = hipX + dx - kneeX; val toAy = hipY + dy - kneeY
        val shinAng = atan2(toAy, toAx) + Math.toRadians(knee.toDouble()).toFloat() * side
        ankleX = kneeX + cos(shinAng) * shin
        ankleY = kneeY + sin(shinAng) * shin
        footAngle = ankle * side - lift * 0.35f * side
    }
}
