package com.formcoach.app.coach.synthetic

import com.formcoach.app.coach.LandmarkId
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.reflect.KProperty

/*
 * Stick-figure model.
 *
 * Units: body heights (H = 1.0 is the standing height). Each point is (lat, up, fwd):
 *  - lat: sideways, +1 toward the person's left,
 *  - up: height above the floor,
 *  - fwd: in the direction the person faces.
 * Segment directions are absolute angles in degrees in the (fwd, up) plane, measured from "up" toward "fwd":
 * 0 = straight up, 90 = forward, 180 = straight down, 270 = backward. The unit vector of angle a is (sin a, cos a).
 */

/** Segment lengths and widths (fractions of body height). */
internal object Body {
    const val THIGH = 0.24
    const val SHIN = 0.24
    const val TORSO = 0.30
    const val UPPER_ARM = 0.17
    const val FORE_ARM = 0.15
    const val NECK = 0.115
    const val LEG = THIGH + SHIN
    const val ARM = UPPER_ARM + FORE_ARM
    const val HIP_HALF = 0.055
    const val SHOULDER_HALF = 0.115
    const val ANKLE_HALF = 0.10
    const val HIP_WIDTH = 2 * HIP_HALF
    const val BODY_LENGTH = LEG + TORSO

    /** Foot: ankle to ball is (0.11 forward, 0.04 down) when flat; heel is (0.02 back, 0.04 down). */
    val FOOT_LEN = hypot(0.11, 0.04)
    val FOOT0 = deg(atan2(0.11, -0.04))
    val HEEL_LEN = hypot(0.02, 0.04)
    val HEEL0 = deg(atan2(-0.02, -0.04))

    fun deg(rad: Double) = rad * 180.0 / PI
    fun rad(deg: Double) = deg * PI / 180.0
}

internal fun fwdOf(deg: Double) = sin(Body.rad(deg))
internal fun upOf(deg: Double) = cos(Body.rad(deg))

/** A point of the figure: lateral, height, forward (see the file comment). */
internal data class P3(val lat: Double, val up: Double, val fwd: Double)

private class Slot(private val i: Int) {
    operator fun getValue(p: Pose, prop: KProperty<*>): Double = p.a[i]
    operator fun setValue(p: Pose, prop: KProperty<*>, v: Double) { p.a[i] = v }
}

/**
 * A full body configuration, kept as a flat array of numbers so poses can be blended. The hip is the anchor; every
 * limb is a chain of absolute segment angles (see the file comment).
 */
class Pose internal constructor(internal val a: DoubleArray) {
    /** Shift of the whole figure, forward (along the facing direction) / sideways (+ person's left). */
    var worldFwd by Slot(0)
    var worldLat by Slot(1)

    /** Hip joint centre: forward and height. [hipLat] shifts hips (and, through [shoulderLat], the upper body) sideways. */
    var hipFwd by Slot(2)
    var hipUp by Slot(3)
    var hipLat by Slot(4)

    /** Extra sideways shift of shoulders, head and arms on top of [hipLat] (trunk sway). */
    var shoulderLat by Slot(5)

    /** Direction hip to shoulder. For a torso lean of x degrees from vertical this equals x (forward positive). */
    var torso by Slot(6)
    var head by Slot(7)

    /** Direction hip to knee, knee to ankle, ankle to ball of the foot. */
    var thighL by Slot(8)
    var thighR by Slot(9)
    var shinL by Slot(10)
    var shinR by Slot(11)
    var footL by Slot(12)
    var footR by Slot(13)

    /** Knee gap relative to the ankle gap (1.0 = knees above ankles, 0.7 = caved in). Front view only. */
    var kneeGapScale by Slot(14)

    /** Direction shoulder to elbow, elbow to wrist, wrist to fingertips. */
    var uArmL by Slot(15)
    var uArmR by Slot(16)
    var fArmL by Slot(17)
    var fArmR by Slot(18)
    var handL by Slot(19)
    var handR by Slot(20)

    /** Sideways abduction (degrees, away from the body) of upper arm and forearm. */
    var abdUL by Slot(21)
    var abdUR by Slot(22)
    var abdFL by Slot(23)
    var abdFR by Slot(24)

    /** Wrist moved toward the body midline by this many body heights (curls in front view). */
    var wristInL by Slot(25)
    var wristInR by Slot(26)

    fun copy() = Pose(a.copyOf())

    fun thigh(s: Side) = if (s == Side.LEFT) thighL else thighR
    fun shin(s: Side) = if (s == Side.LEFT) shinL else shinR
    fun foot(s: Side) = if (s == Side.LEFT) footL else footR
    fun uArm(s: Side) = if (s == Side.LEFT) uArmL else uArmR
    fun fArm(s: Side) = if (s == Side.LEFT) fArmL else fArmR
    fun hand(s: Side) = if (s == Side.LEFT) handL else handR

    fun setLegs(thigh: Double, shin: Double, foot: Double) {
        thighL = thigh; thighR = thigh; shinL = shin; shinR = shin; footL = foot; footR = foot
    }

    fun setArm(s: Side, upper: Double, fore: Double, hand: Double = fore, abdUpper: Double = 6.0, abdFore: Double = abdUpper) {
        if (s == Side.LEFT) { uArmL = upper; fArmL = fore; handL = hand; abdUL = abdUpper; abdFL = abdFore }
        else { uArmR = upper; fArmR = fore; handR = hand; abdUR = abdUpper; abdFR = abdFore }
    }

    fun setArms(upper: Double, fore: Double, hand: Double = fore, abdUpper: Double = 6.0, abdFore: Double = abdUpper) {
        setArm(Side.LEFT, upper, fore, hand, abdUpper, abdFore)
        setArm(Side.RIGHT, upper, fore, hand, abdUpper, abdFore)
    }

    companion object {
        internal const val SIZE = 27

        /** Standing, arms hanging slightly forward, feet planted with the ankles at the image centre. */
        fun standing(worldFwd: Double = 0.11): Pose = basePose(180.0, 0.0, 0.0, 0.0, worldFwd)

        /** Blend two poses with a cosine ease, [u] in 0..1. */
        fun blend(from: Pose, to: Pose, u: Double): Pose {
            val k = 0.5 - 0.5 * cos(PI * u.coerceIn(0.0, 1.0))
            return linear(from, to, k)
        }

        /** Linear blend of every number, [k] in 0..1. */
        fun linear(from: Pose, to: Pose, k: Double): Pose =
            Pose(DoubleArray(SIZE) { from.a[it] + (to.a[it] - from.a[it]) * k })
    }
}

/** Both legs planted: result of [plantLeg]. */
internal class PlantedLeg(val thigh: Double, val shin: Double, val foot: Double, val hipFwd: Double, val hipUp: Double)

/** Foot direction (ankle to ball) so that the heel is [lift] body heights above the floor, ball on the floor. */
internal fun footDirForHeelLift(lift: Double): Double {
    if (lift <= 0.0) return Body.FOOT0
    fun heelUp(fd: Double): Double {
        val ankleUp = -Body.FOOT_LEN * upOf(fd)
        return ankleUp + Body.HEEL_LEN * upOf(Body.HEEL0 + fd - Body.FOOT0)
    }
    var lo = Body.FOOT0
    var hi = Body.FOOT0 + 70.0
    repeat(50) {
        val mid = (lo + hi) / 2
        if (heelUp(mid) < lift) lo = mid else hi = mid
    }
    return (lo + hi) / 2
}

/**
 * Solve a leg whose ball of foot sits at the origin, floor level. [kneeDeg] is the interior hip-knee-ankle angle,
 * [shinLeanDeg] the shin's lean from vertical (knee ahead of the ankle is positive), [heelLift] in body heights.
 */
internal fun plantLeg(kneeDeg: Double, shinLeanDeg: Double, heelLift: Double): PlantedLeg {
    val foot = footDirForHeelLift(heelLift)
    val ankleF = -Body.FOOT_LEN * fwdOf(foot)
    val ankleU = -Body.FOOT_LEN * upOf(foot)
    val kneeF = ankleF + Body.SHIN * fwdOf(shinLeanDeg)
    val kneeU = ankleU + Body.SHIN * upOf(shinLeanDeg)
    val thigh = shinLeanDeg + kneeDeg
    return PlantedLeg(
        thigh = thigh,
        shin = shinLeanDeg + 180.0,
        foot = foot,
        hipFwd = kneeF - Body.THIGH * fwdOf(thigh),
        hipUp = kneeU - Body.THIGH * upOf(thigh),
    )
}

/**
 * Standing-style pose with both feet planted, arms hanging slightly forward.
 * [kneeDeg] 180 = straight legs; [torsoDeg] lean from vertical, forward positive.
 */
internal fun basePose(kneeDeg: Double, shinLeanDeg: Double, torsoDeg: Double, heelLift: Double, worldFwd: Double = 0.11): Pose {
    val leg = plantLeg(kneeDeg, shinLeanDeg, heelLift)
    val p = Pose(DoubleArray(Pose.SIZE))
    p.worldFwd = worldFwd
    p.hipFwd = leg.hipFwd
    p.hipUp = leg.hipUp
    p.setLegs(leg.thigh, leg.shin, leg.foot)
    p.torso = torsoDeg
    p.head = torsoDeg * 0.5
    p.kneeGapScale = 1.0
    p.setArms(176.0, 168.0)
    return p
}

/** Lowest point of the feet is moved to the floor by adjusting [Pose.hipUp] (for walking and similar). */
internal fun Pose.groundFeet(): Pose {
    val pts = points()
    val low = listOf(LandmarkId.LEFT_HEEL, LandmarkId.RIGHT_HEEL, LandmarkId.LEFT_FOOT_INDEX, LandmarkId.RIGHT_FOOT_INDEX)
        .minOf { pts[it].up }
    hipUp -= low
    return this
}

/** Forward kinematics: all 33 landmark positions in body-height units, world shift applied. */
internal fun Pose.points(): Array<P3> {
    val out = arrayOfNulls<P3>(LandmarkId.COUNT)
    fun put(id: Int, lat: Double, up: Double, fwd: Double) {
        out[id] = P3(lat + worldLat, up, fwd + worldFwd)
    }

    // Torso and head (midline)
    val shF = hipFwd + Body.TORSO * fwdOf(torso)
    val shU = hipUp + Body.TORSO * upOf(torso)
    val hcF = shF + Body.NECK * fwdOf(head)
    val hcU = shU + Body.NECK * upOf(head)
    val headLat = hipLat + shoulderLat
    // Head-local axes: "perp" points the way the face looks, "ax" points up the head.
    fun headPt(id: Int, perp: Double, ax: Double, lat: Double) =
        put(id, headLat + lat, hcU + perp * upOf(head + 90.0) + ax * upOf(head), hcF + perp * fwdOf(head + 90.0) + ax * fwdOf(head))
    headPt(LandmarkId.NOSE, 0.065, 0.0, 0.0)
    for ((s, sgn) in listOf(Side.LEFT to 1.0, Side.RIGHT to -1.0)) {
        val l = s == Side.LEFT
        fun id(left: Int, right: Int) = if (l) left else right
        headPt(id(LandmarkId.LEFT_EYE_INNER, LandmarkId.RIGHT_EYE_INNER), 0.045, 0.02, sgn * 0.012)
        headPt(id(LandmarkId.LEFT_EYE, LandmarkId.RIGHT_EYE), 0.040, 0.02, sgn * 0.025)
        headPt(id(LandmarkId.LEFT_EYE_OUTER, LandmarkId.RIGHT_EYE_OUTER), 0.030, 0.02, sgn * 0.040)
        headPt(id(LandmarkId.LEFT_EAR, LandmarkId.RIGHT_EAR), -0.010, 0.0, sgn * 0.055)
        headPt(id(LandmarkId.LEFT_MOUTH, LandmarkId.RIGHT_MOUTH), 0.050, -0.035, sgn * 0.015)

        // Legs
        val thigh = thigh(s)
        val shin = shin(s)
        val foot = foot(s)
        put(id(LandmarkId.LEFT_HIP, LandmarkId.RIGHT_HIP), sgn * Body.HIP_HALF + hipLat, hipUp, hipFwd)
        val kF = hipFwd + Body.THIGH * fwdOf(thigh)
        val kU = hipUp + Body.THIGH * upOf(thigh)
        put(id(LandmarkId.LEFT_KNEE, LandmarkId.RIGHT_KNEE), sgn * Body.ANKLE_HALF * kneeGapScale + hipLat * 0.5, kU, kF)
        val aF = kF + Body.SHIN * fwdOf(shin)
        val aU = kU + Body.SHIN * upOf(shin)
        put(id(LandmarkId.LEFT_ANKLE, LandmarkId.RIGHT_ANKLE), sgn * Body.ANKLE_HALF, aU, aF)
        put(
            id(LandmarkId.LEFT_FOOT_INDEX, LandmarkId.RIGHT_FOOT_INDEX), sgn * (Body.ANKLE_HALF + 0.02),
            aU + Body.FOOT_LEN * upOf(foot), aF + Body.FOOT_LEN * fwdOf(foot),
        )
        val heelDir = Body.HEEL0 + foot - Body.FOOT0
        put(
            id(LandmarkId.LEFT_HEEL, LandmarkId.RIGHT_HEEL), sgn * Body.ANKLE_HALF,
            aU + Body.HEEL_LEN * upOf(heelDir), aF + Body.HEEL_LEN * fwdOf(heelDir),
        )

        // Arms
        val shLat = sgn * Body.SHOULDER_HALF + hipLat + shoulderLat
        put(id(LandmarkId.LEFT_SHOULDER, LandmarkId.RIGHT_SHOULDER), shLat, shU, shF)
        val uDir = uArm(s)
        val fDir = fArm(s)
        val abdU = Body.rad(if (l) abdUL else abdUR)
        val abdF = Body.rad(if (l) abdFL else abdFR)
        val wristIn = if (l) wristInL else wristInR
        val eLat = shLat + sgn * Body.UPPER_ARM * sin(abdU)
        val eU = shU + Body.UPPER_ARM * cos(abdU) * upOf(uDir)
        val eF = shF + Body.UPPER_ARM * cos(abdU) * fwdOf(uDir)
        put(id(LandmarkId.LEFT_ELBOW, LandmarkId.RIGHT_ELBOW), eLat, eU, eF)
        val wLat = eLat + sgn * Body.FORE_ARM * sin(abdF) - sgn * wristIn
        val wU = eU + Body.FORE_ARM * cos(abdF) * upOf(fDir)
        val wF = eF + Body.FORE_ARM * cos(abdF) * fwdOf(fDir)
        put(id(LandmarkId.LEFT_WRIST, LandmarkId.RIGHT_WRIST), wLat, wU, wF)
        val hDir = hand(s)
        fun handPt(id: Int, len: Double, dir: Double, lat: Double) =
            put(id, wLat + lat, wU + len * upOf(dir), wF + len * fwdOf(dir))
        handPt(id(LandmarkId.LEFT_PINKY, LandmarkId.RIGHT_PINKY), 0.070, hDir, sgn * 0.010)
        handPt(id(LandmarkId.LEFT_INDEX, LandmarkId.RIGHT_INDEX), 0.085, hDir, -sgn * 0.010)
        handPt(id(LandmarkId.LEFT_THUMB, LandmarkId.RIGHT_THUMB), 0.060, hDir - 25.0, -sgn * 0.012)
    }
    return Array(LandmarkId.COUNT) { out[it] ?: error("landmark $it not filled") }
}
