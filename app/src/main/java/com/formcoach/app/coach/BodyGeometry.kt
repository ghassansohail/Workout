package com.formcoach.app.coach

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/** A point normalised by image height: x and y in image units / height (y grows downward), z on the same scale. */
data class Pt(val x: Double, val y: Double, val z: Double = 0.0) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Pt(x * s, y * s, z * s)
}

/** Paired body joints; [left]/[right] are [LandmarkId] indices. */
enum class Joint(val left: Int, val right: Int) {
    SHOULDER(LandmarkId.LEFT_SHOULDER, LandmarkId.RIGHT_SHOULDER),
    ELBOW(LandmarkId.LEFT_ELBOW, LandmarkId.RIGHT_ELBOW),
    WRIST(LandmarkId.LEFT_WRIST, LandmarkId.RIGHT_WRIST),
    HIP(LandmarkId.LEFT_HIP, LandmarkId.RIGHT_HIP),
    KNEE(LandmarkId.LEFT_KNEE, LandmarkId.RIGHT_KNEE),
    ANKLE(LandmarkId.LEFT_ANKLE, LandmarkId.RIGHT_ANKLE),
    HEEL(LandmarkId.LEFT_HEEL, LandmarkId.RIGHT_HEEL),
    FOOT(LandmarkId.LEFT_FOOT_INDEX, LandmarkId.RIGHT_FOOT_INDEX);

    fun id(side: BodySide) = if (side == BodySide.LEFT) left else right
}

fun BodySide.other() = if (this == BodySide.LEFT) BodySide.RIGHT else BodySide.LEFT

/** Landmark index of the same point on the other side of the body (the nose maps to itself). */
fun mirrorLandmarkId(id: Int): Int = when (id) {
    LandmarkId.NOSE -> id
    LandmarkId.LEFT_EYE_INNER -> LandmarkId.RIGHT_EYE_INNER
    LandmarkId.LEFT_EYE -> LandmarkId.RIGHT_EYE
    LandmarkId.LEFT_EYE_OUTER -> LandmarkId.RIGHT_EYE_OUTER
    LandmarkId.RIGHT_EYE_INNER -> LandmarkId.LEFT_EYE_INNER
    LandmarkId.RIGHT_EYE -> LandmarkId.LEFT_EYE
    LandmarkId.RIGHT_EYE_OUTER -> LandmarkId.LEFT_EYE_OUTER
    LandmarkId.LEFT_EAR -> LandmarkId.RIGHT_EAR
    LandmarkId.RIGHT_EAR -> LandmarkId.LEFT_EAR
    LandmarkId.LEFT_MOUTH -> LandmarkId.RIGHT_MOUTH
    LandmarkId.RIGHT_MOUTH -> LandmarkId.LEFT_MOUTH
    else -> if (id % 2 == 1) id + 1 else id - 1 // 11..32: left odd, right even
}

/**
 * A gated, height-normalised pose. Missing (or gated: inFrame below the threshold) landmarks are null.
 */
class Body(val timestampMs: Long, val imageHeight: Int, val points: Array<Pt?>) {
    init {
        require(points.size == LandmarkId.COUNT)
    }

    operator fun get(id: Int): Pt? = points[id]

    fun j(joint: Joint, side: BodySide): Pt? = points[joint.id(side)]

    fun has(joints: List<Joint>, side: BodySide) = joints.all { j(it, side) != null }

    fun hasBoth(joints: List<Joint>) = has(joints, BodySide.LEFT) && has(joints, BodySide.RIGHT)

    /** Midpoint of the left and right [joint], or null if either is missing. */
    fun mid(joint: Joint): Pt? {
        val l = j(joint, BodySide.LEFT) ?: return null
        val r = j(joint, BodySide.RIGHT) ?: return null
        return Geometry.mid(l, r)
    }

    companion object {
        fun fromFrame(frame: PoseFrame, minInFrame: Float): Body = fromLandmarks(
            frame.timestampMs, frame.imageHeight, frame.landmarks, minInFrame,
        )

        fun fromLandmarks(timestampMs: Long, imageHeight: Int, landmarks: Array<Landmark?>, minInFrame: Float): Body {
            val h = imageHeight.coerceAtLeast(1).toDouble()
            val pts = arrayOfNulls<Pt>(LandmarkId.COUNT)
            for (i in 0 until LandmarkId.COUNT) {
                val l = landmarks[i] ?: continue
                if (l.inFrame < minInFrame) continue
                if (!l.x.isFinite() || !l.y.isFinite()) continue
                val z = if (l.z.isFinite()) l.z / h else 0.0
                pts[i] = Pt(l.x / h, l.y / h, z)
            }
            return Body(timestampMs, imageHeight, pts)
        }
    }
}

object Geometry {
    fun dist(a: Pt, b: Pt): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    fun mid(a: Pt, b: Pt) = Pt((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2)

    /** 2D angle at [b] between b->a and b->c, in degrees (0..180). Null when a segment is degenerate. */
    fun angleDeg(a: Pt, b: Pt, c: Pt): Double? {
        val abx = a.x - b.x
        val aby = a.y - b.y
        val cbx = c.x - b.x
        val cby = c.y - b.y
        val denom = sqrt((abx * abx + aby * aby) * (cbx * cbx + cby * cby))
        if (denom < 1e-9) return null
        val cos = ((abx * cbx + aby * cby) / denom).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    /**
     * Signed angle of the segment [from] -> [to] from straight up, in degrees (-180..180).
     * Positive when [to] is to the right (+x) of [from]. Example: hip -> shoulder gives the torso lean.
     */
    fun signedFromUpDeg(from: Pt, to: Pt): Double = Math.toDegrees(atan2(to.x - from.x, -(to.y - from.y)))

    /** Unsigned angle of the segment [from] -> [to] from straight up (0..180). */
    fun fromUpDeg(from: Pt, to: Pt): Double = abs(signedFromUpDeg(from, to))

    /** Unsigned angle of the segment [from] -> [to] from straight down (0..180). Example: shoulder -> elbow. */
    fun fromDownDeg(from: Pt, to: Pt): Double = 180.0 - fromUpDeg(from, to)

    /** Angle of the line through [a] and [b] from horizontal, 0..90 degrees. */
    fun fromHorizontalDeg(a: Pt, b: Pt): Double {
        val deg = Math.toDegrees(atan2(abs(b.y - a.y), abs(b.x - a.x)))
        return deg
    }

    /**
     * Perpendicular distance of [p] from the line [a] -> [b]. Positive when [p] lies below the line in the
     * image (larger y), negative above. Uses the line's direction so it works facing left or right.
     */
    fun signedBelowLine(a: Pt, b: Pt, p: Pt): Double {
        val ux = b.x - a.x
        val uy = b.y - a.y
        val len = sqrt(ux * ux + uy * uy)
        if (len < 1e-9) return 0.0
        val cross = ux * (p.y - a.y) - uy * (p.x - a.x)
        val sign = if (ux >= 0) 1.0 else -1.0
        return cross / len * sign
    }

    /** Ratio shoulder width / torso length used for view detection; null without both shoulders and hips. */
    fun viewRatio(body: Body): Double? {
        val ls = body.j(Joint.SHOULDER, BodySide.LEFT) ?: return null
        val rs = body.j(Joint.SHOULDER, BodySide.RIGHT) ?: return null
        val lh = body.j(Joint.HIP, BodySide.LEFT) ?: return null
        val rh = body.j(Joint.HIP, BodySide.RIGHT) ?: return null
        val torso = dist(mid(ls, rs), mid(lh, rh))
        if (torso < 1e-6) return null
        return dist(ls, rs) / torso
    }

    /** Mean z of [joints] on [side], or null if any is missing. Smaller means closer to the camera. */
    fun meanZ(body: Body, joints: List<Joint>, side: BodySide): Double? {
        var sum = 0.0
        for (jt in joints) sum += (body.j(jt, side) ?: return null).z
        return sum / joints.size
    }

    /**
     * Near side: among sides that have all [joints], the one with the lower mean z.
     * Null when neither side is complete.
     */
    fun nearSide(body: Body, joints: List<Joint>): BodySide? {
        val l = meanZ(body, joints, BodySide.LEFT)
        val r = meanZ(body, joints, BodySide.RIGHT)
        return when {
            l != null && r != null -> if (l <= r) BodySide.LEFT else BodySide.RIGHT
            l != null -> BodySide.LEFT
            r != null -> BodySide.RIGHT
            else -> null
        }
    }

    /** Near side over several frames (mean z over all frames where the side is complete). */
    fun nearSide(bodies: List<Body>, joints: List<Joint>): BodySide? {
        val lz = bodies.mapNotNull { meanZ(it, joints, BodySide.LEFT) }
        val rz = bodies.mapNotNull { meanZ(it, joints, BodySide.RIGHT) }
        return when {
            lz.isNotEmpty() && rz.isNotEmpty() -> if (lz.average() <= rz.average()) BodySide.LEFT else BodySide.RIGHT
            lz.isNotEmpty() -> BodySide.LEFT
            rz.isNotEmpty() -> BodySide.RIGHT
            else -> null
        }
    }
}

object Stats {
    fun mean(v: List<Double>): Double = if (v.isEmpty()) 0.0 else v.sum() / v.size

    fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = mean(v)
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    fun median(v: List<Double>): Double = percentile(v, 0.5)

    /** Linear-interpolated percentile, [q] in 0..1. Returns 0 for an empty list. */
    fun percentile(v: List<Double>, q: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val pos = q.coerceIn(0.0, 1.0) * (s.size - 1)
        val lo = floor(pos).toInt()
        val hi = minOf(lo + 1, s.size - 1)
        val f = pos - lo
        return s[lo] * (1 - f) + s[hi] * f
    }
}

/**
 * Classifies the camera view from shoulder width / torso length with hysteresis, and reports a view as
 * stable once the classification has not changed for [SessionConfig.viewStableMs].
 */
class ViewDetector(private val config: SessionConfig) {
    var candidate: CameraView? = null
        private set
    var stable: CameraView? = null
        private set
    private var candidateSinceMs = 0L

    fun reset() {
        candidate = null
        stable = null
    }

    fun classify(ratio: Double, current: CameraView?): CameraView {
        val h = config.viewHysteresis
        return when {
            current == CameraView.FRONT && ratio > config.viewFrontAbove - h -> CameraView.FRONT
            current == CameraView.SIDE && ratio < config.viewSideBelow + h -> CameraView.SIDE
            ratio > config.viewFrontAbove -> CameraView.FRONT
            ratio < config.viewSideBelow -> CameraView.SIDE
            else -> CameraView.ANGLED
        }
    }

    /**
     * [ratio] null means the far shoulder or a hip is missing, which in practice means a side view
     * (the far shoulder is hidden), so it is classified as SIDE.
     */
    fun update(ratio: Double?, timestampMs: Long): CameraView? {
        val view = if (ratio == null) CameraView.SIDE else classify(ratio, candidate)
        if (view != candidate) {
            candidate = view
            candidateSinceMs = timestampMs
        }
        if (timestampMs - candidateSinceMs >= config.viewStableMs) stable = view
        return stable
    }
}

/**
 * Undoes left/right label swaps in raw landmarks once a reference has been locked at calibration.
 * Front view: each joint pair's x order is compared with the calibrated order.
 * Side view: the locked (near) side must stay closer to the camera; a limb chain whose locked side is
 * clearly farther (by [TrackingConfig.sideSwapZMargin]) is swapped.
 */
class SwapCorrector(private val config: TrackingConfig) {
    private var view: CameraView? = null
    private var lockedSide: BodySide? = null
    /** Per joint pair: sign of (left.x - right.x) at calibration, or 0 when unknown. */
    private val pairSign = IntArray(PAIRS.size)

    // POSITIONING: recent votes (+1/-1) per front-view pair and per side-view chain.
    private val pairVotes = List(PAIRS.size) { ArrayDeque<Int>() }
    private val chainVotes = List(CHAINS.size) { ArrayDeque<Int>() }

    fun reset() {
        view = null
        lockedSide = null
        pairSign.fill(0)
        pairVotes.forEach { it.clear() }
        chainVotes.forEach { it.clear() }
    }

    /**
     * Before calibration there is no locked reference, but a detector's left/right swaps are brief and rare,
     * so the majority of the last [TrackingConfig.provisionalSwapWindow] frames is the reference: in a front
     * view the x order of each joint pair, in a side view which side of each limb chain is nearer (z).
     * A frame that clearly disagrees with the majority is swapped back. Swaps must be undone before the
     * One Euro filter, or each swapped frame drags the smoothed joints across the body.
     */
    fun correctProvisional(frame: PoseFrame, view: CameraView?): Array<Landmark?> {
        val src = frame.landmarks
        val h = frame.imageHeight.coerceAtLeast(1).toDouble()
        val out = src.copyOf()
        when (view) {
            CameraView.FRONT -> for ((k, group) in PAIRS.withIndex()) {
                val l = src[group.first()] ?: continue
                val r = src[mirrorLandmarkId(group.first())] ?: continue
                val dx = (l.x - r.x) / h
                if (kotlin.math.abs(dx) < config.frontSwapXMargin) continue
                val vote = if (dx > 0) 1 else -1
                val majority = vote(pairVotes[k], vote)
                if (majority != 0 && vote != majority) group.forEach { swap(out, src, it) }
            }
            CameraView.SIDE -> for ((k, chain) in CHAINS.withIndex()) {
                val lz = chainZ(src, chain, BodySide.LEFT, h) ?: continue
                val rz = chainZ(src, chain, BodySide.RIGHT, h) ?: continue
                if (kotlin.math.abs(lz - rz) < config.sideSwapZMargin) continue
                val vote = if (lz < rz) 1 else -1 // +1: left is nearer
                val majority = vote(chainVotes[k], vote)
                if (majority != 0 && vote != majority) chain.forEach { swap(out, src, it) }
            }
            else -> Unit
        }
        return out
    }

    /** Adds [vote] to [window] and returns the majority before it (0 while too few votes or tied). */
    private fun vote(window: ArrayDeque<Int>, vote: Int): Int {
        val sum = window.sum()
        val majority = if (window.size < config.provisionalSwapMinVotes || sum == 0) 0 else if (sum > 0) 1 else -1
        window.addLast(vote)
        while (window.size > config.provisionalSwapWindow) window.removeFirst()
        return majority
    }

    fun lockFront(bodies: List<Body>) {
        view = CameraView.FRONT
        lockedSide = null
        for ((k, group) in PAIRS.withIndex()) {
            val main = group.first()
            val dx = bodies.mapNotNull { b ->
                val l = b[main] ?: return@mapNotNull null
                val r = b[mirrorLandmarkId(main)] ?: return@mapNotNull null
                l.x - r.x
            }
            val m = Stats.median(dx)
            pairSign[k] = when {
                dx.isEmpty() || abs(m) < config.frontSwapXMargin -> 0
                m > 0 -> 1
                else -> -1
            }
        }
    }

    fun lockSide(side: BodySide) {
        view = CameraView.SIDE
        lockedSide = side
    }

    fun correct(frame: PoseFrame): Array<Landmark?> {
        val src = frame.landmarks
        val h = frame.imageHeight.coerceAtLeast(1).toDouble()
        val out = src.copyOf()
        when (view) {
            CameraView.FRONT -> for ((k, group) in PAIRS.withIndex()) {
                val sign = pairSign[k]
                if (sign == 0) continue
                val l = src[group.first()] ?: continue
                val r = src[mirrorLandmarkId(group.first())] ?: continue
                val dx = (l.x - r.x) / h
                if (dx * sign < -config.frontSwapXMargin) group.forEach { swap(out, src, it) }
            }
            CameraView.SIDE -> {
                val side = lockedSide ?: return out
                for (chain in CHAINS) {
                    val near = chainZ(src, chain, side, h) ?: continue
                    val far = chainZ(src, chain, side.other(), h) ?: continue
                    if (near > far + config.sideSwapZMargin) chain.forEach { swap(out, src, it) }
                }
            }
            else -> Unit
        }
        return out
    }

    private fun swap(out: Array<Landmark?>, src: Array<Landmark?>, leftId: Int) {
        val rightId = mirrorLandmarkId(leftId)
        out[leftId] = src[rightId]
        out[rightId] = src[leftId]
    }

    private fun chainZ(src: Array<Landmark?>, chain: List<Int>, side: BodySide, h: Double): Double? {
        var sum = 0.0
        var n = 0
        for (leftId in chain.take(3)) { // the three main joints of the chain
            val id = if (side == BodySide.LEFT) leftId else mirrorLandmarkId(leftId)
            val l = src[id] ?: return null
            sum += l.z / h
            n++
        }
        return sum / n
    }

    companion object {
        /** Joint pairs checked in front view (left ids). Hand and foot points follow their wrist/ankle. */
        private val PAIRS: List<List<Int>> = listOf(
            listOf(LandmarkId.LEFT_SHOULDER),
            listOf(LandmarkId.LEFT_ELBOW),
            listOf(LandmarkId.LEFT_WRIST, LandmarkId.LEFT_PINKY, LandmarkId.LEFT_INDEX, LandmarkId.LEFT_THUMB),
            listOf(LandmarkId.LEFT_HIP),
            listOf(LandmarkId.LEFT_KNEE),
            listOf(LandmarkId.LEFT_ANKLE, LandmarkId.LEFT_HEEL, LandmarkId.LEFT_FOOT_INDEX),
        )

        /** Limb chains checked in side view (left ids); the first three decide, the rest follow. */
        private val CHAINS: List<List<Int>> = listOf(
            listOf(
                LandmarkId.LEFT_SHOULDER, LandmarkId.LEFT_ELBOW, LandmarkId.LEFT_WRIST,
                LandmarkId.LEFT_PINKY, LandmarkId.LEFT_INDEX, LandmarkId.LEFT_THUMB,
            ),
            listOf(
                LandmarkId.LEFT_HIP, LandmarkId.LEFT_KNEE, LandmarkId.LEFT_ANKLE,
                LandmarkId.LEFT_HEEL, LandmarkId.LEFT_FOOT_INDEX,
            ),
        )
    }
}
