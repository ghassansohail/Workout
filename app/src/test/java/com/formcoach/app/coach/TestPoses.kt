package com.formcoach.app.coach

import java.util.Random
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small hand-built stick figures for engine tests (track A). Coordinates are built normalised by image
 * height (y down) and converted to pixels. The near side in side views is LEFT unless stated (smaller z).
 */
object TestPoses {
    const val PORTRAIT_W = 480
    const val PORTRAIT_H = 640
    const val LANDSCAPE_W = 640
    const val LANDSCAPE_H = 480
    const val FRAME_MS = 33L

    private fun rad(deg: Double) = Math.toRadians(deg)

    /** Linear interpolation through (time, value) keyframes; clamps outside. */
    fun interp(keys: List<Pair<Long, Double>>, t: Long): Double {
        if (t <= keys.first().first) return keys.first().second
        for (i in 1 until keys.size) {
            val (t1, v1) = keys[i]
            val (t0, v0) = keys[i - 1]
            if (t <= t1) return if (t1 == t0) v1 else v0 + (v1 - v0) * (t - t0).toDouble() / (t1 - t0)
        }
        return keys.last().second
    }

    /**
     * Keyframes for [n] reps starting at [start]: top -> bottom in [downMs], hold [holdMs], back up in
     * [upMs], then [restMs] at the top. Returns keys and the end time.
     */
    fun repKeys(
        start: Long, n: Int, top: Double, bottom: Double,
        downMs: Long = 1000, upMs: Long = 1000, holdMs: Long = 100, restMs: Long = 500,
        bottoms: List<Double>? = null,
    ): Pair<List<Pair<Long, Double>>, Long> {
        val keys = ArrayList<Pair<Long, Double>>()
        var t = start
        keys.add(t to top)
        for (i in 0 until n) {
            val b = bottoms?.getOrNull(i) ?: bottom
            t += downMs; keys.add(t to b)
            t += holdMs; keys.add(t to b)
            t += upMs; keys.add(t to top)
            t += restMs; keys.add(t to top)
        }
        return keys to t
    }

    class Noise(seed: Long, private val sigma: Double) {
        private val rnd = Random(seed)
        fun next() = if (sigma == 0.0) 0.0 else rnd.nextGaussian() * sigma
    }

    /** Builds a frame from normalised points. Missing face/hand/foot points are derived. */
    fun frame(
        t: Long, w: Int, h: Int, pts: MutableMap<Int, Pt>,
        noise: Noise? = null, inFrame: Float = 0.95f, hide: Set<Int> = emptySet(),
    ): PoseFrame {
        fillDerived(pts)
        val lms = arrayOfNulls<Landmark>(LandmarkId.COUNT)
        for ((id, p) in pts) {
            if (id in hide) continue
            val nx = noise?.next() ?: 0.0
            val ny = noise?.next() ?: 0.0
            lms[id] = Landmark(((p.x + nx) * h).toFloat(), ((p.y + ny) * h).toFloat(), (p.z * h).toFloat(), inFrame)
        }
        return PoseFrame(t, w, h, lms)
    }

    private fun fillDerived(pts: MutableMap<Int, Pt>) {
        val head = pts[LandmarkId.NOSE] ?: return
        for (id in 1..10) pts.putIfAbsent(id, head + Pt(0.0, if (id >= 9) 0.02 else -0.01, 0.0))
        for ((wrist, ids) in listOf(
            LandmarkId.LEFT_WRIST to listOf(LandmarkId.LEFT_PINKY, LandmarkId.LEFT_INDEX, LandmarkId.LEFT_THUMB),
            LandmarkId.RIGHT_WRIST to listOf(LandmarkId.RIGHT_PINKY, LandmarkId.RIGHT_INDEX, LandmarkId.RIGHT_THUMB),
        )) {
            val wp = pts[wrist] ?: continue
            ids.forEach { pts.putIfAbsent(it, wp) }
        }
    }

    private fun put(pts: MutableMap<Int, Pt>, joint: Joint, side: BodySide, p: Pt) {
        pts[joint.id(side)] = p
    }

    /** Copies the near (LEFT) side to the far side with a small x offset and larger z. */
    private fun mirrorFar(pts: MutableMap<Int, Pt>, near: BodySide, dx: Double = 0.006, dz: Double = 0.12) {
        val nearPts = pts.toMap()
        for (jt in Joint.values()) {
            val p = nearPts[jt.id(BodySide.LEFT)] ?: continue
            put(pts, jt, BodySide.RIGHT, Pt(p.x + dx, p.y, p.z + dz))
        }
        if (near == BodySide.RIGHT) swapSides(pts)
    }

    fun swapSides(pts: MutableMap<Int, Pt>) {
        for (jt in Joint.values()) {
            val l = pts[jt.left]
            val r = pts[jt.right]
            if (l != null) pts[jt.right] = l else pts.remove(jt.right)
            if (r != null) pts[jt.left] = r else pts.remove(jt.left)
        }
    }

    // ---- squat, side view (facing +x) ---------------------------------------------------------

    fun squatSidePts(
        kneeDeg: Double, leanDeg: Double? = null, heelRise: Double = 0.0, ankleX: Double = 0.35,
        near: BodySide = BodySide.LEFT, torsoBend: Double = 0.0,
    ): MutableMap<Int, Pt> {
        val shin = 0.21
        val thigh = 0.21
        val torso = 0.27
        val bend = 180.0 - kneeDeg
        val phi = rad(0.35 * bend)
        val psi = rad(0.65 * bend)
        val lam = rad((leanDeg ?: (0.55 * Math.toDegrees(psi))) + torsoBend)
        val a = Pt(ankleX, 0.92, -0.06)
        val k = a + Pt(shin * sin(phi), -shin * cos(phi))
        val hp = k + Pt(-thigh * sin(psi), -thigh * cos(psi))
        val s = hp + Pt(torso * sin(lam), -torso * cos(lam))
        val pts = HashMap<Int, Pt>()
        val L = BodySide.LEFT
        put(pts, Joint.ANKLE, L, a)
        put(pts, Joint.KNEE, L, k)
        put(pts, Joint.HIP, L, hp)
        put(pts, Joint.SHOULDER, L, s)
        put(pts, Joint.ELBOW, L, s + Pt(0.14, 0.0))
        put(pts, Joint.WRIST, L, s + Pt(0.26, 0.0))
        put(pts, Joint.HEEL, L, a + Pt(-0.04, 0.02 - heelRise))
        put(pts, Joint.FOOT, L, a + Pt(0.10, 0.03))
        mirrorFar(pts, near)
        pts[LandmarkId.NOSE] = s + Pt(0.05 + 0.1 * sin(lam), -0.1 * cos(lam), -0.0)
        return pts
    }

    fun squatSide(t: Long, kneeDeg: Double, noise: Noise? = null, leanDeg: Double? = null, heelRise: Double = 0.0,
                  ankleX: Double = 0.35, near: BodySide = BodySide.LEFT, torsoBend: Double = 0.0,
                  hide: Set<Int> = emptySet()): PoseFrame =
        frame(t, PORTRAIT_W, PORTRAIT_H, squatSidePts(kneeDeg, leanDeg, heelRise, ankleX, near, torsoBend), noise, hide = hide)

    // ---- squat, front view (person's left at larger x) ---------------------------------------

    fun squatFrontPts(drop: Double, caveRatio: Double = 1.0, hipShift: Double = 0.0, ankleHalf: Double = 0.09, cx: Double = 0.375): MutableMap<Int, Pt> {
        val hipAnkle = 0.42
        val maxDrop = 0.35
        val prog = (drop / maxDrop).coerceIn(0.0, 1.0)
        val hipY = 0.50 + drop * hipAnkle
        val kneeY = 0.71 + 0.08 * prog
        // Standing: knee on the straight hip-ankle line. Bottom: knees over the toes, or caved in.
        val kneeHalf0 = 0.07 + (ankleHalf - 0.07) * 0.5
        val kneeTarget = if (caveRatio < 1.0) ankleHalf * caveRatio else ankleHalf * 1.05
        val kneeHalf = kneeHalf0 + (kneeTarget - kneeHalf0) * prog
        val torsoProj = 0.27 * cos(rad(40.0 * prog))
        val hx = cx + hipShift * prog
        val pts = HashMap<Int, Pt>()
        for ((side, sign) in listOf(BodySide.LEFT to 1.0, BodySide.RIGHT to -1.0)) {
            put(pts, Joint.ANKLE, side, Pt(cx + sign * ankleHalf, 0.92))
            put(pts, Joint.HEEL, side, Pt(cx + sign * ankleHalf, 0.94))
            put(pts, Joint.FOOT, side, Pt(cx + sign * (ankleHalf + 0.01), 0.95))
            put(pts, Joint.KNEE, side, Pt(cx + sign * kneeHalf, kneeY))
            put(pts, Joint.HIP, side, Pt(hx + sign * 0.07, hipY))
            val sh = Pt(hx + sign * 0.10, hipY - torsoProj)
            put(pts, Joint.SHOULDER, side, sh)
            put(pts, Joint.ELBOW, side, sh + Pt(sign * 0.01, 0.14))
            put(pts, Joint.WRIST, side, sh + Pt(sign * 0.02, 0.26))
        }
        pts[LandmarkId.NOSE] = Pt(hx, hipY - torsoProj - 0.1)
        return pts
    }

    fun squatFront(t: Long, drop: Double, noise: Noise? = null, caveRatio: Double = 1.0, hipShift: Double = 0.0,
                   ankleHalf: Double = 0.09): PoseFrame =
        frame(t, PORTRAIT_W, PORTRAIT_H, squatFrontPts(drop, caveRatio, hipShift, ankleHalf), noise)

    // ---- push-up, side view, landscape (head toward +x) ---------------------------------------

    fun pushUpPts(elbowDeg: Double, sag: Double = 0.0, wristX: Double = 1.0, near: BodySide = BodySide.LEFT): MutableMap<Int, Pt> {
        val u = 0.2
        val f = 0.18
        val wr = Pt(wristX, 0.85, -0.06)
        val d = sqrt(u * u + f * f - 2 * u * f * cos(rad(elbowDeg)))
        val s = wr + Pt(0.0, -d)
        val beta = acos(((f * f + d * d - u * u) / (2 * f * d)).coerceIn(-1.0, 1.0))
        val el = wr + Pt(-f * sin(beta), -f * cos(beta))
        val an = Pt(wristX - 0.8, 0.84, -0.06)
        val hipOnLine = s + (an - s) * 0.45
        val hp = hipOnLine + Pt(0.0, sag)
        val kn = hp + (an - hp) * 0.5
        val pts = HashMap<Int, Pt>()
        val L = BodySide.LEFT
        put(pts, Joint.WRIST, L, wr)
        put(pts, Joint.ELBOW, L, el)
        put(pts, Joint.SHOULDER, L, s)
        put(pts, Joint.HIP, L, hp)
        put(pts, Joint.KNEE, L, kn)
        put(pts, Joint.ANKLE, L, an)
        put(pts, Joint.HEEL, L, an + Pt(-0.02, -0.03))
        put(pts, Joint.FOOT, L, an + Pt(-0.01, 0.03))
        mirrorFar(pts, near)
        pts[LandmarkId.NOSE] = s + Pt(0.12, -0.02)
        return pts
    }

    fun pushUp(t: Long, elbowDeg: Double, noise: Noise? = null, sag: Double = 0.0): PoseFrame =
        frame(t, LANDSCAPE_W, LANDSCAPE_H, pushUpPts(elbowDeg, sag), noise)

    /** Standing upright, side view, in a landscape image (push-up distractor). */
    fun standingLandscape(t: Long): PoseFrame {
        val pts = squatSidePts(178.0, leanDeg = 0.0)
        val scaled = HashMap<Int, Pt>()
        for ((id, p) in pts) scaled[id] = Pt(p.x + 0.3, p.y * 0.95, p.z)
        // arms hanging
        for (side in BodySide.values()) {
            val s = scaled[Joint.SHOULDER.id(side)]!!
            scaled[Joint.ELBOW.id(side)] = s + Pt(0.0, 0.14)
            scaled[Joint.WRIST.id(side)] = s + Pt(0.0, 0.26)
        }
        return frame(t, LANDSCAPE_W, LANDSCAPE_H, scaled)
    }

    // ---- curls --------------------------------------------------------------------------------

    /** Side view, standing, facing +x. [forearmDeg] from straight down (0 hanging, 150 curled). */
    fun curlSidePts(forearmDeg: Double, upperArmDeg: Double = 0.0, leanDeg: Double = 0.0, hipX: Double = 0.35,
                    near: BodySide = BodySide.LEFT): MutableMap<Int, Pt> {
        val pts = HashMap<Int, Pt>()
        val L = BodySide.LEFT
        val hp = Pt(hipX, 0.50, -0.06)
        val s = hp + Pt(0.27 * sin(rad(leanDeg)), -0.27 * cos(rad(leanDeg)))
        val el = s + Pt(0.14 * sin(rad(upperArmDeg)), 0.14 * cos(rad(upperArmDeg)))
        val wr = el + Pt(0.12 * sin(rad(forearmDeg)), 0.12 * cos(rad(forearmDeg)))
        put(pts, Joint.HIP, L, hp)
        put(pts, Joint.SHOULDER, L, s)
        put(pts, Joint.ELBOW, L, el)
        put(pts, Joint.WRIST, L, wr)
        put(pts, Joint.KNEE, L, Pt(hipX, 0.71, -0.06))
        put(pts, Joint.ANKLE, L, Pt(hipX, 0.92, -0.06))
        put(pts, Joint.HEEL, L, Pt(hipX - 0.04, 0.94, -0.06))
        put(pts, Joint.FOOT, L, Pt(hipX + 0.1, 0.95, -0.06))
        mirrorFar(pts, near)
        pts[LandmarkId.NOSE] = s + Pt(0.05, -0.1)
        return pts
    }

    fun curlSide(t: Long, forearmDeg: Double, noise: Noise? = null, upperArmDeg: Double = 0.0, leanDeg: Double = 0.0,
                 hipX: Double = 0.35): PoseFrame =
        frame(t, PORTRAIT_W, PORTRAIT_H, curlSidePts(forearmDeg, upperArmDeg, leanDeg, hipX), noise)

    /** Front view. Forearm angles from straight down for each arm; the forearm swings toward the camera. */
    fun curlFrontPts(leftDeg: Double, rightDeg: Double, upperArmDeg: Double = 0.0, cx: Double = 0.375): MutableMap<Int, Pt> {
        val pts = HashMap<Int, Pt>()
        for ((side, sign) in listOf(BodySide.LEFT to 1.0, BodySide.RIGHT to -1.0)) {
            val fa = if (side == BodySide.LEFT) leftDeg else rightDeg
            val ua = if (side == BodySide.LEFT) upperArmDeg else 0.0
            val s = Pt(cx + sign * 0.10, 0.23)
            val el = s + Pt(sign * 0.14 * sin(rad(ua)), 0.14 * cos(rad(ua)))
            val wr = Pt(el.x, el.y + 0.12 * cos(rad(fa)), el.z - 0.12 * sin(rad(fa)))
            put(pts, Joint.SHOULDER, side, s)
            put(pts, Joint.ELBOW, side, el)
            put(pts, Joint.WRIST, side, wr)
            put(pts, Joint.HIP, side, Pt(cx + sign * 0.07, 0.50))
            put(pts, Joint.KNEE, side, Pt(cx + sign * 0.08, 0.71))
            put(pts, Joint.ANKLE, side, Pt(cx + sign * 0.09, 0.92))
            put(pts, Joint.HEEL, side, Pt(cx + sign * 0.09, 0.94))
            put(pts, Joint.FOOT, side, Pt(cx + sign * 0.10, 0.95))
        }
        pts[LandmarkId.NOSE] = Pt(cx, 0.13)
        return pts
    }

    fun curlFront(t: Long, leftDeg: Double, rightDeg: Double, noise: Noise? = null, upperArmDeg: Double = 0.0): PoseFrame =
        frame(t, PORTRAIT_W, PORTRAIT_H, curlFrontPts(leftDeg, rightDeg, upperArmDeg), noise)
}

/** Feeds frames and commands to a session on a 30 fps clock and collects every event. */
class Driver(val session: WorkoutSession = WorkoutSession()) {
    var t = 0L
        private set
    val events = ArrayList<CoachEvent>()
    var state: WorkoutUiState = session.state
        private set

    fun command(c: SessionCommand) {
        val out = session.onCommand(c, t)
        events.addAll(out.events)
        state = out.state
    }

    /** Runs frames from now for [durationMs]; [make] receives the absolute time and the time since this call. */
    fun run(durationMs: Long, make: (t: Long, rel: Long) -> PoseFrame?) {
        val start = t
        while (t - start < durationMs) {
            val f = make(t, t - start) ?: PoseFrame.empty(t, TestPoses.PORTRAIT_W, TestPoses.PORTRAIT_H)
            val out = session.onFrame(f)
            events.addAll(out.events)
            state = out.state
            t += TestPoses.FRAME_MS
        }
    }

    inline fun <reified T : CoachEvent> eventsOf(): List<T> = events.filterIsInstance<T>()

    fun reps() = eventsOf<CoachEvent.RepCounted>()
}
