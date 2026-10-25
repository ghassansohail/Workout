package com.formcoach.app.coach.exercise

import com.formcoach.app.coach.Body
import com.formcoach.app.coach.BodySide
import com.formcoach.app.coach.CameraView
import com.formcoach.app.coach.Exercise
import com.formcoach.app.coach.ExerciseConfig
import com.formcoach.app.coach.FaultCue
import com.formcoach.app.coach.Geometry
import com.formcoach.app.coach.Joint
import com.formcoach.app.coach.PartialKind
import com.formcoach.app.coach.Pt
import com.formcoach.app.coach.RepCounter
import com.formcoach.app.coach.RepCounterEvent
import com.formcoach.app.coach.SetupIssue
import com.formcoach.app.coach.Stats

object CurlFaults {
    val ELBOW_DRIFT = FaultCue("elbow_drift", "Elbows pinned.", "keep your elbows at your sides")
    val SWINGING = FaultCue("swinging", "No swinging.", "keep your body still")
    val FAST_LOWERING = FaultCue("fast_lowering", "Lower slowly.", "lower the weight slowly")
}

/**
 * Biceps curl, side or front view. Primary signal per arm: wrist lift ratio
 * (elbowY - wristY) / calibrated forearm length (-1 hanging, about +1 fully curled).
 * Side view tracks the locked arm. Front view runs one counter per arm; completions of both arms within
 * [com.formcoach.app.coach.SessionConfig.curlMergeMs] are one rep (both arms together), otherwise each
 * arm counts on its own (alternating curls). A merged rep carries both arms' faults, deduplicated: when one
 * arm completes while the other is already well on its way back, the count waits (at most curlMergeMs) for
 * the other arm.
 */
class CurlAnalyzer(config: ExerciseConfig) : ExerciseAnalyzer(Exercise.CURL, config) {
    private val c = config.curl

    override val allowedViews = setOf(CameraView.SIDE, CameraView.FRONT)
    override val requiredJoints = listOf(Joint.SHOULDER, Joint.ELBOW, Joint.WRIST, Joint.HIP)
    override val sideJoints = listOf(Joint.SHOULDER, Joint.ELBOW, Joint.WRIST)
    override val visibilityIssue = SetupIssue("visibility", "Step back so I can see you to your hips.")
    override val viewIssue = SetupIssue("view", "Turn fully sideways, or face the camera.")
    override val placementHint = "Phone at chest height, 1.5-2 m away."
    override val partialTexts = PartialTexts(
        shallowFirst = "Not counted. Curl higher.",
        shallow = "Higher.",
        noLockoutFirst = "Straighten fully.",
        noLockout = "Straighten fully.",
        noLockoutAdvice = "straighten your arm at the bottom",
    )

    private val startPosture = SetupIssue("start_posture", "Arms straight by your sides.")

    private class Arm(val side: BodySide, val counter: RepCounter, var forearm: Double) {
        var reps = 0
        var lastRepAt: Long? = null
        var lastRepMerged = false
        var lastPartialAt: Long? = null
        var lastPartialMerged = false
    }

    private class ArmF(val lift: Double, val upperArm: Double, val elbowRel: Pt)

    private class F(val t: Long, val lean: Double, val arms: Map<BodySide, ArmF>)

    /** A completed arm rep held back for up to curlMergeMs, waiting for the other arm. */
    private class Pending(val arm: Arm, val t: Long, val faults: List<FaultCue>)

    private var pending: Pending? = null
    private var arms: List<Arm> = emptyList()
    private var torsoLength = 0.3
    private val frames = FrameBuffer<F>(c.counter.maxDurationMs + 3000)

    override val maxRatePerSec: Double get() = c.counter.maxRatePerSec
    override val outlierSlack: Double get() = c.counter.outlierSlack

    override fun primaryLabel(view: CameraView) = "WRIST LIFT"

    override fun primaryValue(body: Body, view: CameraView, side: BodySide?): String {
        val sides = trackedSides(body, view, side)
        val values = sides.map { s -> lift(body, s, forearmFor(body, s)) }
        if (values.isEmpty() || values.any { it == null }) return "--"
        return if (values.size == 2) {
            "L ${formatRatio(values[0]!!)} R ${formatRatio(values[1]!!)}"
        } else {
            formatRatio(values[0]!!)
        }
    }

    override fun signalIsAngle(view: CameraView) = false

    private fun trackedSides(body: Body, view: CameraView, side: BodySide?): List<BodySide> = when {
        view == CameraView.FRONT -> listOf(BodySide.LEFT, BodySide.RIGHT)
        side != null -> listOf(side)
        else -> listOfNotNull(chooseSide(body))
    }

    private fun forearmFor(body: Body, s: BodySide): Double? {
        arms.firstOrNull { it.side == s }?.let { return it.forearm }
        val el = body.j(Joint.ELBOW, s) ?: return null
        val wr = body.j(Joint.WRIST, s) ?: return null
        return Geometry.dist(el, wr)
    }

    private fun lift(body: Body, s: BodySide, forearm: Double?): Double? {
        val el = body.j(Joint.ELBOW, s) ?: return null
        val wr = body.j(Joint.WRIST, s) ?: return null
        val f = forearm ?: return null
        if (f < 1e-6) return null
        return (el.y - wr.y) / f
    }

    private fun upperArmAngle(body: Body, s: BodySide): Double? {
        val sh = body.j(Joint.SHOULDER, s) ?: return null
        val el = body.j(Joint.ELBOW, s) ?: return null
        return Geometry.fromDownDeg(sh, el)
    }

    /** Signed torso lean (hip -> shoulder from vertical). */
    private fun lean(body: Body, view: CameraView, s: BodySide?): Double? {
        val (hip, sh) = if (view == CameraView.FRONT || s == null) {
            (body.mid(Joint.HIP) ?: return null) to (body.mid(Joint.SHOULDER) ?: return null)
        } else {
            (body.j(Joint.HIP, s) ?: return null) to (body.j(Joint.SHOULDER, s) ?: return null)
        }
        return Geometry.signedFromUpDeg(hip, sh)
    }

    override fun positioningSignals(body: Body, view: CameraView, side: BodySide?): DoubleArray? {
        val sides = trackedSides(body, view, side)
        if (sides.isEmpty()) return null
        val out = DoubleArray(sides.size)
        for ((i, s) in sides.withIndex()) out[i] = lift(body, s, forearmFor(body, s)) ?: return null
        return out
    }

    override fun setupIssue(body: Body, view: CameraView, side: BodySide?): SetupIssue? {
        val sides = trackedSides(body, view, side)
        if (sides.isEmpty()) return startPosture
        for (s in sides) {
            val l = lift(body, s, forearmFor(body, s)) ?: return startPosture
            val ua = upperArmAngle(body, s) ?: return startPosture
            if (l > c.counter.topFloor || ua > c.startUpperArmMaxDeg) return startPosture
        }
        val ln = lean(body, view, side) ?: return startPosture
        if (kotlin.math.abs(ln) > c.startTorsoLeanMaxDeg) return startPosture
        return null
    }

    override fun calibrateLocked(window: List<Body>) {
        val sides = if (view == CameraView.FRONT) listOf(BodySide.LEFT, BodySide.RIGHT) else listOf(side ?: BodySide.LEFT)
        if (view != CameraView.FRONT) side = sides[0]
        arms = sides.map { s ->
            val forearm = Stats.median(window.mapNotNull { b ->
                val el = b.j(Joint.ELBOW, s) ?: return@mapNotNull null
                val wr = b.j(Joint.WRIST, s) ?: return@mapNotNull null
                Geometry.dist(el, wr)
            }).coerceAtLeast(1e-3)
            val rc = RepCounter(c.counter)
            val top = Stats.median(window.mapNotNull { lift(it, s, forearm) })
            rc.calibrate(top)
            Arm(s, rc, forearm)
        }
        torsoLength = Stats.median(window.mapNotNull { b ->
            if (view == CameraView.FRONT) {
                val sh = b.mid(Joint.SHOULDER) ?: return@mapNotNull null
                val hp = b.mid(Joint.HIP) ?: return@mapNotNull null
                Geometry.dist(sh, hp)
            } else {
                val sh = b.j(Joint.SHOULDER, sides[0]) ?: return@mapNotNull null
                val hp = b.j(Joint.HIP, sides[0]) ?: return@mapNotNull null
                Geometry.dist(sh, hp)
            }
        }).coerceAtLeast(1e-3)
        frames.clear()
    }

    override fun signals(body: Body): DoubleArray? {
        if (arms.isEmpty()) return null
        val out = DoubleArray(arms.size)
        for ((i, a) in arms.withIndex()) out[i] = lift(body, a.side, a.forearm) ?: return null
        return out
    }

    override fun process(body: Body): List<AnalyzerResult> {
        if (arms.isEmpty()) return emptyList()
        val sig = signals(body) ?: return emptyList()
        val ln = lean(body, view, side) ?: return emptyList()
        val armF = HashMap<BodySide, ArmF>()
        for ((i, a) in arms.withIndex()) {
            val sh = body.j(Joint.SHOULDER, a.side) ?: return emptyList()
            val el = body.j(Joint.ELBOW, a.side) ?: return emptyList()
            armF[a.side] = ArmF(sig[i], Geometry.fromDownDeg(sh, el), el - sh)
        }
        val f = F(body.timestampMs, ln, armF)
        frames.add(f.t, f)

        val out = ArrayList<AnalyzerResult>()
        pending?.let { p ->
            if (f.t - p.t > config.session.curlMergeMs) {
                pending = null
                out.add(AnalyzerResult.Rep(f.t, p.faults))
            }
        }
        for ((i, a) in arms.withIndex()) {
            when (val e = a.counter.update(f.t, sig[i])) {
                is RepCounterEvent.Completed -> evaluate(a, e)?.let { faults -> onArmRep(a, e.confirmMs, faults, out) }
                is RepCounterEvent.Partial -> {
                    val rep = frames.between(e.startMs, e.confirmMs)
                    if (gatesPass(a, rep)) onArmPartial(a, e.confirmMs, e.kind, out)
                }
                null -> Unit
            }
        }
        return out
    }

    private fun other(a: Arm): Arm? = arms.firstOrNull { it !== a }

    private fun onArmRep(a: Arm, t: Long, faults: List<FaultCue>, out: MutableList<AnalyzerResult>) {
        a.reps++
        val o = other(a)
        val p = pending
        if (o != null && p != null && p.arm === o && t - p.t <= config.session.curlMergeMs) {
            // Both arms together: one rep carrying both arms' faults, each fault once.
            pending = null
            a.lastRepAt = t
            a.lastRepMerged = true
            o.lastRepMerged = true
            out.add(AnalyzerResult.Rep(t, (p.faults + faults).distinctBy { it.id }))
            return
        }
        val oAt = o?.lastRepAt
        if (o != null && oAt != null && !o.lastRepMerged && t - oAt <= config.session.curlMergeMs) {
            // Both arms together, but the other arm's count already went out (it was not held because this
            // arm was still far from the top): no second count, and this arm's faults cannot be added.
            a.lastRepAt = t
            a.lastRepMerged = true
            o.lastRepMerged = true
            return
        }
        a.lastRepAt = t
        a.lastRepMerged = false
        val oProgress = o?.counter?.returnProgress()
        if (oProgress != null && oProgress >= config.session.curlMergeHoldProgress) {
            pending = Pending(a, t, faults)
        } else {
            out.add(AnalyzerResult.Rep(t, faults))
        }
    }

    override fun flushPending(): List<AnalyzerResult> {
        val p = pending ?: return emptyList()
        pending = null
        return listOf(AnalyzerResult.Rep(p.t, p.faults))
    }

    private fun onArmPartial(a: Arm, t: Long, kind: PartialKind, out: MutableList<AnalyzerResult>) {
        val o = other(a)
        val oAt = o?.lastPartialAt
        if (o != null && oAt != null && !o.lastPartialMerged && t - oAt <= config.session.curlMergeMs) {
            a.lastPartialAt = t
            a.lastPartialMerged = true
            o.lastPartialMerged = true
            return
        }
        a.lastPartialAt = t
        a.lastPartialMerged = false
        out.add(partialTexts.partial(t, kind))
    }

    /** Upper arm near vertical throughout and elbow not travelling relative to the shoulder. */
    private fun gatesPass(a: Arm, rep: List<F>): Boolean {
        val armFrames = rep.mapNotNull { it.arms[a.side] }
        if (armFrames.isEmpty()) return false
        if (robustMax(armFrames.map { it.upperArm }) > c.gateUpperArmDeg) return false
        val e0 = armFrames.first().elbowRel
        val moves = armFrames.map { Geometry.dist(it.elbowRel, e0) }
        if (robustMax(moves) >= c.gateElbowMoveTorso * torsoLength) return false
        return true
    }

    /** Faults for a completed arm rep, or null when a gate fails (not a curl). */
    private fun evaluate(a: Arm, e: RepCounterEvent.Completed): List<FaultCue>? {
        val rep = frames.between(e.startMs, e.endMs)
        if (!gatesPass(a, rep)) return null
        val armFrames = rep.mapNotNull { it.arms[a.side] }
        val faults = ArrayList<FaultCue>()
        if (robustMax(armFrames.map { it.upperArm }) > c.elbowDriftDeg) faults.add(CurlFaults.ELBOW_DRIFT)
        val leans = rep.map { it.lean }
        if (robustMax(leans) - robustMin(leans) > c.swingDeg) faults.add(CurlFaults.SWINGING)
        if (e.upMs < c.fastLoweringMs) faults.add(CurlFaults.FAST_LOWERING)
        return faults
    }

    override fun abandon() {
        arms.forEach { it.counter.abandon() }
    }

    override fun restSinceMs(): Long? {
        if (arms.isEmpty()) return null
        var latest = Long.MIN_VALUE
        for (a in arms) latest = maxOf(latest, a.counter.topSinceMs ?: return null)
        return latest
    }

    override fun inTopZone(body: Body): Boolean {
        val s = signals(body) ?: return false
        return arms.indices.all { arms[it].counter.isInTopZone(s[it]) }
    }

    override fun armCounts(): Pair<Int, Int>? {
        if (view != CameraView.FRONT || arms.size != 2) return null
        val l = arms.first { it.side == BodySide.LEFT }.reps
        val r = arms.first { it.side == BodySide.RIGHT }.reps
        return l to r
    }
}
