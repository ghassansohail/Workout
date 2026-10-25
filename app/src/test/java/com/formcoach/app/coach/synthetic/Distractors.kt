package com.formcoach.app.coach.synthetic

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Movements that are not reps. Every builder returns a clip whose Truth has no reps.

private const val STRIDE = 0.8 // body heights per full gait cycle (two steps)

private fun distractorTruth(description: String) = Truth(Kind.DISTRACTOR, emptyList(), description)

private fun smooth01(x: Double): Double { val u = x.coerceIn(0.0, 1.0); return u * u * (3 - 2 * u) }

/** Gait pose at horizontal displacement [x] (body heights, relative to the standing position) with amplitude [amp] 0..1. */
private fun walkPose(x: Double, amp: Double, camera: CameraSpec): Pose {
    val phase = 2 * PI * x / STRIDE
    val p = basePose(180.0, 0.0, 3.0 * amp, 0.0)
    for (side in Side.values()) {
        val ph = phase + if (side == Side.RIGHT) PI else 0.0
        val thigh = 180.0 - amp * 28.0 * sin(ph)
        val c = max(0.0, cos(ph - 0.3))
        val flex = amp * (5.0 + 50.0 * c * c)
        if (side == Side.LEFT) { p.thighL = thigh; p.shinL = thigh + flex } else { p.thighR = thigh; p.shinR = thigh + flex }
        val upper = 176.0 + amp * 22.0 * sin(ph)
        p.setArm(side, upper, upper - 8.0 - 22.0 * amp)
    }
    p.head = 1.5 * amp
    if (camera.view == View.SIDE) p.worldFwd += x else p.worldLat += x
    return p.groundFeet()
}

private fun walkTrack(from: Double, to: Double, durationMs: Int, camera: CameraSpec): PoseTrack =
    FnTrack(durationMs.toDouble()) { t ->
        val u = t / durationMs
        val x = from + (to - from) * cosEase(u)
        val speed = abs(to - from) * (PI / 2) * sin(PI * u) / (durationMs / 1000.0) // body heights per second
        walkPose(x, smooth01(speed / 0.35), camera)
    }

internal fun buildWalkIn(durationMs: Int, camera: CameraSpec, fps: Int, startMs: Long) =
    finishClip(walkTrack(-1.0, 0.0, durationMs, camera), camera, fps, startMs, distractorTruth("walk into frame"))

internal fun buildStepOut(durationMs: Int, camera: CameraSpec, fps: Int, startMs: Long) =
    finishClip(walkTrack(0.0, 1.0, durationMs, camera), camera, fps, startMs, distractorTruth("step out of frame"))

internal fun buildStandStill(durationMs: Int, swayDeg: Double, seed: Long, camera: CameraSpec, fps: Int, startMs: Long): SyntheticClip {
    val rnd = Random(seed)
    val ph = DoubleArray(6) { rnd.nextDouble() * 2 * PI }
    fun wave(i: Int, hz: Double, s: Double) = sin(2 * PI * hz * s + ph[i])
    val track = FnTrack(durationMs.toDouble()) { t ->
        val s = t / 1000.0
        val knee = 175.0 + swayDeg * (0.6 * wave(0, 0.31, s) + 0.4 * wave(1, 0.83, s))
        val torso = swayDeg * (0.6 * wave(2, 0.23, s) + 0.4 * wave(3, 0.61, s))
        val p = basePose(knee, 0.0, torso, 0.0)
        p.hipLat = 0.004 * wave(4, 0.27, s)
        val upper = 176.0 + swayDeg * 0.5 * wave(5, 0.4, s)
        p.setArms(upper, upper - 8.0)
        p
    }
    return finishClip(track, camera, fps, startMs, distractorTruth("standing still, sway $swayDeg deg"))
}

internal fun buildBendOver(camera: CameraSpec, fps: Int, startMs: Long): SyntheticClip {
    val stand = basePose(178.0, 0.0, 0.0, 0.0)
    val bent = basePose(158.0, 6.0, 82.0, 0.0)
    bent.head = 70.0
    bent.setArms(168.0, 152.0)
    val track = KeyframeTrack(listOf(0.0 to stand, 600.0 to stand, 2200.0 to bent, 3400.0 to bent, 5000.0 to stand, 5600.0 to stand))
    return finishClip(track, camera, fps, startMs, distractorTruth("bend over to touch the phone"))
}

internal fun buildScratchHead(side: Side, camera: CameraSpec, fps: Int, startMs: Long): SyntheticClip {
    val stand = basePose(178.0, 0.0, 0.0, 0.0)
    val raised = stand.copy().also { it.setArm(side, 8.0, 235.0, 235.0, abdUpper = 15.0) }
    val track = FnTrack(5200.0) { t ->
        val k = when {
            t < 600 -> 0.0
            t < 1600 -> (t - 600) / 1000
            t < 3600 -> 1.0
            t < 4600 -> 1.0 - (t - 3600) / 1000
            else -> 0.0
        }
        val p = Pose.blend(stand, raised, k)
        if (t in 1600.0..3600.0) {
            val osc = 14.0 * sin(2 * PI * 3.0 * (t - 1600) / 1000)
            if (side == Side.LEFT) p.fArmL += osc else p.fArmR += osc
        }
        p
    }
    return finishClip(track, camera, fps, startMs, distractorTruth("raise ${side.name.lowercase()} arm and scratch the head"))
}

internal fun buildReachToPhone(camera: CameraSpec, fps: Int, startMs: Long): SyntheticClip {
    val stand = basePose(178.0, 0.0, 0.0, 0.0)
    val reach = basePose(178.0, 0.0, 8.0, 0.0)
    val arm = camera.nearSide
    if (camera.view == View.SIDE) {
        // The camera is to the person's side: reaching toward it is a sideways raise toward the lens.
        reach.shoulderLat = arm.sign * 0.07
        reach.setArm(arm, 150.0, 135.0, 135.0, abdUpper = 70.0, abdFore = 78.0)
    } else {
        reach.setArm(arm, 85.0, 80.0, 80.0, abdUpper = 5.0)
    }
    val track = KeyframeTrack(listOf(0.0 to stand, 500.0 to stand, 1600.0 to reach, 3100.0 to reach, 4200.0 to stand, 4700.0 to stand))
    return finishClip(track, camera, fps, startMs, distractorTruth("reach toward the camera to adjust the phone"))
}

/** Plank on the knees, hands where the push-up left them. */
private fun kneelPlankPose(): Pose {
    val p = Pose(DoubleArray(Pose.SIZE))
    p.worldFwd = -0.38
    p.hipFwd = 0.434
    p.hipUp = 0.171
    p.setLegs(234.0, 270.0, 260.0)
    p.torso = 54.0
    p.head = 29.0
    p.kneeGapScale = 1.0
    p.setArms(180.0, 180.0, 90.0, abdUpper = 0.0)
    return p
}

/** Upright on the knees. */
private fun kneelUprightPose(): Pose {
    val p = Pose(DoubleArray(Pose.SIZE))
    p.worldFwd = -0.38
    p.hipFwd = 0.24
    p.hipUp = 0.27
    p.setLegs(180.0, 270.0, 260.0)
    p.kneeGapScale = 1.0
    p.setArms(176.0, 168.0)
    return p
}

internal fun buildKneelAndStand(camera: CameraSpec, fps: Int, startMs: Long): SyntheticClip {
    val plank = pushUpPose(178.0, 0.0)
    val stand = basePose(178.0, 0.0, 0.0, 0.0, worldFwd = -0.03)
    val track = KeyframeTrack(
        listOf(
            0.0 to plank, 600.0 to plank,
            1800.0 to kneelPlankPose(), 2600.0 to kneelPlankPose(),
            3800.0 to kneelUprightPose(), 4600.0 to kneelUprightPose(),
            6000.0 to stand, 6600.0 to stand,
        ),
    )
    return finishClip(track, camera, fps, startMs, distractorTruth("kneel down after push-ups, then stand up"))
}
