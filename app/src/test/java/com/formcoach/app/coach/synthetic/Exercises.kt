package com.formcoach.app.coach.synthetic

import kotlin.math.asin
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Per-rep overrides: "reps 4 and 7 only reach 125 degrees". Shared by the three exercise specs. */
internal class RepOverrides<R> {
    private class Entry<R>(val reps: Set<Int>, val side: Side?, val block: R.() -> Unit)

    private val entries = mutableListOf<Entry<R>>()
    val hasSideSpecific: Boolean get() = entries.any { it.side != null }

    fun add(reps: Set<Int>, side: Side?, block: R.() -> Unit) { entries += Entry(reps, side, block) }

    fun applyTo(target: R, index: Int, side: Side?) {
        for (e in entries) if (index in e.reps && (e.side == null || e.side == side)) e.block(target)
    }
}

private fun ms(x: Double): Long = x.roundToLong()

// =====================================================================================================================
// Squat
// =====================================================================================================================

/**
 * Parameters of one squat repetition. The same fields are the defaults of [SquatSpec] and the receiver of per-rep
 * overrides. Angles are degrees, `heelLift` is a fraction of leg length, `lateralShift` is in hip widths.
 */
open class SquatRep {
    /** Time for descent plus ascent (not counting [bottomHoldMs] and [pauseTopMs]). */
    var repDurationMs: Int = 2000
    /** Share of [repDurationMs] spent going down. */
    var descentFraction: Double = 0.5
    var bottomHoldMs: Int = 0
    /** Pause standing at the top after this rep (none after the last rep). */
    var pauseTopMs: Int = 300
    /** Knee angle (hip-knee-ankle interior angle) standing at the top of this rep. */
    var topKneeDeg: Double = 175.0
    /** Knee angle at the bottom: the depth. About 90 is a deep squat, 125 is a shallow one. */
    var bottomKneeDeg: Double = 90.0
    /** Torso lean from vertical at the bottom (forward positive). */
    var torsoLeanDeg: Double = 35.0
    /** Shin lean from vertical at the bottom (knees ahead of ankles positive). */
    var shinLeanDeg: Double = 25.0
    /** Heel rise at the bottom as a fraction of leg length. */
    var heelLift: Double = 0.0
    /** Front view: how far each knee moves toward the midline at the bottom, as a fraction of the ankle half-gap. */
    var kneeCave: Double = 0.0
    /** Front view: sideways hip shift at the bottom in hip widths, positive toward the person's left. */
    var lateralShift: Double = 0.0
    /** Arms raised forward at the bottom (counterbalance), degrees from hanging. 0 keeps them down. */
    var armsForwardDeg: Double = 60.0

    internal fun copyFrom(o: SquatRep) {
        repDurationMs = o.repDurationMs; descentFraction = o.descentFraction; bottomHoldMs = o.bottomHoldMs
        pauseTopMs = o.pauseTopMs; topKneeDeg = o.topKneeDeg; bottomKneeDeg = o.bottomKneeDeg
        torsoLeanDeg = o.torsoLeanDeg; shinLeanDeg = o.shinLeanDeg; heelLift = o.heelLift
        kneeCave = o.kneeCave; lateralShift = o.lateralShift; armsForwardDeg = o.armsForwardDeg
    }
}

/** Squat script: set-level options plus the default [SquatRep] values. */
class SquatSpec : SquatRep() {
    var reps: Int = 10
    /** Standing still before the first rep. */
    var leadInMs: Int = 1500
    /** Standing still after the last rep. */
    var tailMs: Int = 1500
    private val overrides = RepOverrides<SquatRep>()

    /** Change parameters of the given 1-based rep attempts only: `override(4, 7) { bottomKneeDeg = 125.0 }`. */
    fun override(vararg rep: Int, block: SquatRep.() -> Unit) = overrides.add(rep.toSet(), null, block)

    /** Same for a range of reps: `override(3..10) { torsoLeanDeg = 60.0 }`. */
    fun override(reps: IntRange, block: SquatRep.() -> Unit) = overrides.add(reps.toSet(), null, block)

    internal fun repParams(index: Int): SquatRep = SquatRep().also { it.copyFrom(this); overrides.applyTo(it, index, null) }
}

internal fun squatPose(p: Double, topKnee: Double, r: SquatRep): Pose {
    val knee = topKnee + (r.bottomKneeDeg - topKnee) * p
    val pose = basePose(knee, r.shinLeanDeg * p, r.torsoLeanDeg * p, r.heelLift * Body.LEG * p)
    val upper = 176.0 + ((180.0 - r.armsForwardDeg) - 176.0) * p
    pose.setArms(upper, upper - 8.0)
    pose.kneeGapScale = 1.0 - r.kneeCave * p
    pose.hipLat = r.lateralShift * Body.HIP_WIDTH * p
    return pose
}

internal fun squatFaults(r: SquatRep, view: View): Set<Fault> = buildSet {
    val downMs = r.repDurationMs * r.descentFraction
    if (view == View.SIDE) {
        if (r.torsoLeanDeg > Rules.SQUAT_LEAN_FAULT_DEG && r.torsoLeanDeg > r.shinLeanDeg + Rules.SQUAT_LEAN_OVER_SHIN_DEG) add(Fault.FORWARD_LEAN)
        if (r.heelLift > Rules.HEEL_LIFT_FAULT) add(Fault.HEELS_LIFT)
    } else {
        if (1.0 - r.kneeCave < Rules.KNEE_GAP_RATIO_FAULT) add(Fault.KNEES_CAVE)
        if (abs(r.lateralShift) > Rules.WEIGHT_SHIFT_FAULT) add(Fault.WEIGHT_SHIFT)
    }
    if (downMs < Rules.SQUAT_FAST_DESCENT_MS) add(Fault.FAST_DESCENT)
}

internal fun buildSquat(spec: SquatSpec, camera: CameraSpec, fps: Int, startMs: Long): SyntheticClip {
    val reps = (1..spec.reps).map { spec.repParams(it) }
    val timings = reps.mapIndexed { i, r ->
        val down = (r.repDurationMs * r.descentFraction).coerceAtLeast(1.0)
        doubleArrayOf(down, r.bottomHoldMs.toDouble(), (r.repDurationMs - down).coerceAtLeast(1.0), if (i == reps.lastIndex) 0.0 else r.pauseTopMs.toDouble())
    }
    val schedule = RepSchedule.build(spec.leadInMs.toDouble(), timings, spec.tailMs.toDouble())
    val tops = reps.map { it.topKneeDeg }
    val track = FnTrack(schedule.totalMs) { t ->
        val loc = schedule.locate(t)
        squatPose(loc.p, topFor(loc, tops), reps[loc.rep])
    }
    val truth = reps.mapIndexed { i, r ->
        val slot = schedule.slots[i]
        val faults = squatFaults(r, camera.view).toMutableSet()
        val gate = r.torsoLeanDeg >= Rules.SQUAT_TORSO_GATE_DEG
        val counted = !gate && r.bottomKneeDeg <= Rules.SQUAT_COUNTED_KNEE_DEG
        val partial = !gate && !counted && r.bottomKneeDeg <= Rules.SQUAT_ATTEMPT_KNEE_DEG
        RepTruth(i + 1, null, startMs + ms(slot.startMs), startMs + ms(slot.extremeMs), startMs + ms(slot.endMs), counted, partial, faults, r.bottomKneeDeg)
    }
    return finishClip(track, camera, fps, startMs, Truth(Kind.SQUAT, truth, "squat ${camera.view}"))
}

// =====================================================================================================================
// Push-up
// =====================================================================================================================

/**
 * Parameters of one push-up repetition. `hipSag` is a fraction of body length: positive sags (hips below the
 * shoulder-ankle line), negative piked (hips above it). `lockoutCompleteness` 1.0 means arms fully straight at the top,
 * 0.5 means the elbows only reach 150 degrees.
 */
open class PushUpRep {
    var repDurationMs: Int = 2400
    var descentFraction: Double = 0.5
    var bottomHoldMs: Int = 0
    var pauseTopMs: Int = 200
    var bottomElbowDeg: Double = 80.0
    var hipSag: Double = 0.0
    var lockoutCompleteness: Double = 0.97

    internal val topElbowDeg: Double get() = 180.0 - (1.0 - lockoutCompleteness) * 60.0

    internal fun copyFrom(o: PushUpRep) {
        repDurationMs = o.repDurationMs; descentFraction = o.descentFraction; bottomHoldMs = o.bottomHoldMs
        pauseTopMs = o.pauseTopMs; bottomElbowDeg = o.bottomElbowDeg; hipSag = o.hipSag; lockoutCompleteness = o.lockoutCompleteness
    }
}

/** Push-up script (side view, normally landscape). */
class PushUpSpec : PushUpRep() {
    var reps: Int = 10
    var leadInMs: Int = 1500
    var tailMs: Int = 1500
    private val overrides = RepOverrides<PushUpRep>()

    /** Change parameters of the given 1-based rep attempts only. */
    fun override(vararg rep: Int, block: PushUpRep.() -> Unit) = overrides.add(rep.toSet(), null, block)

    /** Same for a range of reps. */
    fun override(reps: IntRange, block: PushUpRep.() -> Unit) = overrides.add(reps.toSet(), null, block)

    internal fun repParams(index: Int): PushUpRep = PushUpRep().also { it.copyFrom(this); overrides.applyTo(it, index, null) }
}

private const val PU_FOOT = 150.0
private const val PU_WRIST_UP = 0.022
private val PU_ANKLE_F = -Body.FOOT_LEN * fwdOf(PU_FOOT)
private val PU_ANKLE_U = -Body.FOOT_LEN * upOf(PU_FOOT)

/** Where the hands are planted: directly under the shoulders of a straight-body full lockout. */
private val PU_WRIST_F: Double = run {
    val beta = asin((PU_WRIST_UP + Body.ARM - PU_ANKLE_U) / Body.BODY_LENGTH)
    PU_ANKLE_F + Body.BODY_LENGTH * cos(beta)
}

/** Plank-position centre, in body heights from the ball of the foot, so the figure sits in the middle of the image. */
private const val PU_CENTER_FWD = -0.38

/**
 * Plank pose: ball of foot at the origin, hands planted, elbow angle [elbowDeg], hips [sag] (fraction of body length)
 * below (+) or above (-) the shoulder-ankle line. Legs straight; arms bend with the elbows going backward.
 */
internal fun pushUpPose(elbowDeg: Double, sag: Double): Pose {
    val delta = sag * Body.BODY_LENGTH
    val a1 = asin((delta / Body.LEG).coerceIn(-0.99, 0.99))
    val a2 = asin((delta / Body.TORSO).coerceIn(-0.99, 0.99))
    val chord = Body.LEG * cos(a1) + Body.TORSO * cos(a2)
    val e = Body.rad(elbowDeg)
    val d = sqrt(Body.UPPER_ARM * Body.UPPER_ARM + Body.FORE_ARM * Body.FORE_ARM - 2 * Body.UPPER_ARM * Body.FORE_ARM * cos(e))
    fun shoulderF(beta: Double) = PU_ANKLE_F + chord * cos(beta)
    fun shoulderU(beta: Double) = PU_ANKLE_U + chord * sin(beta)
    var lo = Body.rad(-15.0)
    var hi = Body.rad(65.0)
    repeat(60) {
        val mid = (lo + hi) / 2
        if (hypot(shoulderF(mid) - PU_WRIST_F, shoulderU(mid) - PU_WRIST_UP) - d < 0) lo = mid else hi = mid
    }
    val beta = (lo + hi) / 2
    val psi1 = beta - a1
    val psi2 = beta + a2
    val hipF = PU_ANKLE_F + Body.LEG * cos(psi1)
    val hipU = PU_ANKLE_U + Body.LEG * sin(psi1)
    val sf = shoulderF(beta)
    val su = shoulderU(beta)

    // Two-bone IK for the arm; the elbow goes to the rear.
    val dx = PU_WRIST_F - sf
    val dy = PU_WRIST_UP - su
    val dist = hypot(dx, dy)
    val a = (Body.UPPER_ARM * Body.UPPER_ARM - Body.FORE_ARM * Body.FORE_ARM + dist * dist) / (2 * dist)
    val h = sqrt(maxOf(0.0, Body.UPPER_ARM * Body.UPPER_ARM - a * a))
    val bf = sf + a * dx / dist
    val bu = su + a * dy / dist
    val c1 = Pair(bf - h * dy / dist, bu + h * dx / dist) // (fwd, up)
    val c2 = Pair(bf + h * dy / dist, bu - h * dx / dist)
    val el = if (c1.first < c2.first) c1 else c2

    val pose = Pose(DoubleArray(Pose.SIZE))
    pose.worldFwd = PU_CENTER_FWD
    pose.hipFwd = hipF
    pose.hipUp = hipU
    val legDir = 270.0 - Body.deg(psi1)
    pose.setLegs(legDir, legDir, PU_FOOT)
    pose.torso = 90.0 - Body.deg(psi2)
    pose.head = pose.torso - 25.0
    pose.kneeGapScale = 1.0
    val upper = Body.deg(atan2(el.first - sf, el.second - su))
    val fore = Body.deg(atan2(PU_WRIST_F - el.first, PU_WRIST_UP - el.second))
    pose.setArms(upper, fore, 90.0, abdUpper = 0.0)
    return pose
}

internal fun pushUpFaults(r: PushUpRep): Set<Fault> = buildSet {
    if (r.hipSag > Rules.HIP_SAG_FAULT) add(Fault.HIPS_SAG)
    if (-r.hipSag > Rules.HIP_PIKE_FAULT) add(Fault.HIPS_PIKE)
    if (r.topElbowDeg < Rules.PUSHUP_LOCKOUT_MIN_DEG) add(Fault.NO_LOCKOUT)
    if (r.repDurationMs * r.descentFraction < Rules.PUSHUP_FAST_DESCENT_MS) add(Fault.FAST_DESCENT)
}

internal fun buildPushUp(spec: PushUpSpec, camera: CameraSpec, fps: Int, startMs: Long): SyntheticClip {
    require(camera.view == View.SIDE) { "push-ups are generated for side view only" }
    val reps = (1..spec.reps).map { spec.repParams(it) }
    val timings = reps.mapIndexed { i, r ->
        val down = (r.repDurationMs * r.descentFraction).coerceAtLeast(1.0)
        doubleArrayOf(down, r.bottomHoldMs.toDouble(), (r.repDurationMs - down).coerceAtLeast(1.0), if (i == reps.lastIndex) 0.0 else r.pauseTopMs.toDouble())
    }
    val schedule = RepSchedule.build(spec.leadInMs.toDouble(), timings, spec.tailMs.toDouble())
    val tops = reps.map { it.topElbowDeg }
    val track = FnTrack(schedule.totalMs) { t ->
        val loc = schedule.locate(t)
        val r = reps[loc.rep]
        val top = topFor(loc, tops)
        pushUpPose(top + (r.bottomElbowDeg - top) * loc.p, r.hipSag)
    }
    val truth = reps.mapIndexed { i, r ->
        val slot = schedule.slots[i]
        val lockoutOk = r.topElbowDeg >= Rules.PUSHUP_LOCKOUT_MIN_DEG
        val deepEnough = r.bottomElbowDeg <= Rules.PUSHUP_COUNTED_ELBOW_DEG
        val attempt = r.bottomElbowDeg <= Rules.PUSHUP_ATTEMPT_ELBOW_DEG
        val counted = deepEnough && lockoutOk
        val partial = attempt && !counted
        RepTruth(i + 1, null, startMs + ms(slot.startMs), startMs + ms(slot.extremeMs), startMs + ms(slot.endMs), counted, partial, pushUpFaults(r), r.bottomElbowDeg)
    }
    return finishClip(track, camera, fps, startMs, Truth(Kind.PUSH_UP, truth, "push-up"))
}

// =====================================================================================================================
// Biceps curl
// =====================================================================================================================

/** Both arms curl together, or one after the other. */
enum class CurlMode { BOTH, ALTERNATING }

/**
 * Parameters of one curl repetition of one arm. [loweringMs] is the eccentric phase, [curlMs] the concentric one.
 * [elbowDriftDeg] is how far the upper arm swings forward of vertical at the peak, [torsoSwingDeg] how far the
 * torso leans back at the peak (momentum).
 */
open class CurlRep {
    var curlMs: Int = 1200
    var peakHoldMs: Int = 0
    var loweringMs: Int = 1800
    /** Pause with the arm extended after this rep (none after the last rep). */
    var pauseMs: Int = 300
    /** Elbow angle at the curled peak. About 40 is a full curl; 100 is a half curl. */
    var topElbowDeg: Double = 40.0
    /** Elbow angle at the bottom (extension). 165 is nearly straight; 120 is an incomplete extension. */
    var bottomElbowDeg: Double = 165.0
    var elbowDriftDeg: Double = 5.0
    var torsoSwingDeg: Double = 0.0

    internal fun copyFrom(o: CurlRep) {
        curlMs = o.curlMs; peakHoldMs = o.peakHoldMs; loweringMs = o.loweringMs; pauseMs = o.pauseMs
        topElbowDeg = o.topElbowDeg; bottomElbowDeg = o.bottomElbowDeg; elbowDriftDeg = o.elbowDriftDeg; torsoSwingDeg = o.torsoSwingDeg
    }
}

/** Curl script. [reps] is per arm. */
class CurlSpec : CurlRep() {
    var reps: Int = 10
    var mode: CurlMode = CurlMode.BOTH
    var leadInMs: Int = 1500
    var tailMs: Int = 1500
    private val overrides = RepOverrides<CurlRep>()

    /** Change parameters of the given 1-based rep attempts, for both arms. */
    fun override(vararg rep: Int, block: CurlRep.() -> Unit) = overrides.add(rep.toSet(), null, block)

    /** Same for a range of reps, for both arms. */
    fun override(reps: IntRange, block: CurlRep.() -> Unit) = overrides.add(reps.toSet(), null, block)

    /** Change parameters of the given reps of one arm only (alternating mode). */
    fun overrideArm(side: Side, vararg rep: Int, block: CurlRep.() -> Unit) = overrides.add(rep.toSet(), side, block)

    internal val hasArmOverrides: Boolean get() = overrides.hasSideSpecific

    internal fun repParams(index: Int, side: Side): CurlRep = CurlRep().also { it.copyFrom(this); overrides.applyTo(it, index, side) }
}

private class ArmPlan(val side: Side, val reps: List<CurlRep>, val schedule: RepSchedule, val tops: List<Double>, val rests: List<Double>)

internal fun curlFaults(r: CurlRep): Set<Fault> = buildSet {
    if (r.elbowDriftDeg > Rules.CURL_DRIFT_FAULT_DEG) add(Fault.ELBOW_DRIFT)
    if (r.torsoSwingDeg > Rules.CURL_SWING_FAULT_DEG) add(Fault.SWINGING)
    if (cos(Body.rad(r.bottomElbowDeg)) > Rules.CURL_EXTENDED_LIFT) add(Fault.NO_FULL_EXTENSION)
    if (r.loweringMs < Rules.CURL_FAST_LOWERING_MS) add(Fault.FAST_LOWERING)
}

internal fun buildCurl(spec: CurlSpec, camera: CameraSpec, fps: Int, startMs: Long): SyntheticClip {
    require(!(spec.mode == CurlMode.BOTH && spec.hasArmOverrides)) { "overrideArm needs mode = ALTERNATING" }
    fun plan(side: Side, offsetMs: Double): ArmPlan {
        val reps = (1..spec.reps).map { spec.repParams(it, side) }
        val timings = reps.mapIndexed { i, r ->
            doubleArrayOf(r.curlMs.toDouble(), r.peakHoldMs.toDouble(), r.loweringMs.toDouble(), if (i == reps.lastIndex) 0.0 else r.pauseMs.toDouble())
        }
        return ArmPlan(side, reps, RepSchedule.build(spec.leadInMs + offsetMs, timings, spec.tailMs.toDouble()), reps.map { it.topElbowDeg }, reps.map { it.bottomElbowDeg })
    }
    val first = spec.repParams(1, Side.LEFT)
    val halfCycle = (first.curlMs + first.peakHoldMs + first.loweringMs + first.pauseMs) / 2.0
    val left = plan(Side.LEFT, 0.0)
    val right = plan(Side.RIGHT, if (spec.mode == CurlMode.ALTERNATING) halfCycle else 0.0)
    val total = maxOf(left.schedule.totalMs, right.schedule.totalMs)

    val track = FnTrack(total) { t ->
        var swing = 0.0
        val states = listOf(left, right).map { arm ->
            val loc = arm.schedule.locate(t)
            val r = arm.reps[loc.rep]
            val rest = topFor(loc, arm.rests)
            swing = maxOf(swing, r.torsoSwingDeg * loc.p)
            Triple(rest + (r.topElbowDeg - rest) * loc.p, r.elbowDriftDeg * loc.p, loc.p)
        }
        val pose = basePose(180.0, 0.0, -swing, 0.0)
        for ((k, arm) in listOf(left, right).withIndex()) {
            val (elbow, drift, c) = states[k]
            val upper = 180.0 - drift
            val fore = upper - 180.0 + elbow
            pose.setArm(arm.side, upper, fore, fore, abdUpper = 6.0)
            if (arm.side == Side.LEFT) pose.wristInL = 0.025 * c else pose.wristInR = 0.025 * c
        }
        pose
    }

    fun repTruth(arm: ArmPlan, sideTag: Side?): List<RepTruth> = arm.reps.mapIndexed { i, r ->
        val slot = arm.schedule.slots[i]
        val faults = curlFaults(r)
        val gate = r.elbowDriftDeg > Rules.CURL_DRIFT_GATE_DEG
        val topLift = cos(Body.rad(r.topElbowDeg - r.elbowDriftDeg))
        val extended = Fault.NO_FULL_EXTENSION !in faults
        val counted = !gate && topLift >= Rules.CURL_COUNTED_LIFT && extended
        val partial = !gate && !counted && topLift >= Rules.CURL_ATTEMPT_LIFT
        RepTruth(i + 1, sideTag, startMs + ms(slot.startMs), startMs + ms(slot.extremeMs), startMs + ms(slot.endMs), counted, partial, faults, r.topElbowDeg)
    }
    val reps = if (spec.mode == CurlMode.BOTH) repTruth(left, null) else repTruth(left, Side.LEFT) + repTruth(right, Side.RIGHT)
    return finishClip(track, camera, fps, startMs, Truth(Kind.CURL, reps, "curl ${spec.mode} ${camera.view}"))
}
