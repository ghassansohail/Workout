package com.formcoach.app.coach

// Every threshold the coaching engine uses lives in this file. Values marked "// TUNE" are starting
// guesses from docs/COACH_FIX_PLAN.md (or engine choices where the plan gives none) and must be checked
// against recorded sets (plan Phase 8). Lengths are in units of image height unless a body length is named.

/** Top-level engine configuration. `WorkoutSession()` uses the defaults. */
data class ExerciseConfig(
    val filter: FilterConfig = FilterConfig(),
    val tracking: TrackingConfig = TrackingConfig(),
    val session: SessionConfig = SessionConfig(),
    val squat: SquatConfig = SquatConfig(),
    val pushUp: PushUpConfig = PushUpConfig(),
    val curl: CurlConfig = CurlConfig(),
)

/** One Euro filter on every coordinate (normalised by image height). */
data class FilterConfig(
    // TUNE. Plan start: minCutoff 1.5 Hz, beta 0.3. With coordinates normalised by image height, limb
    // speeds are only ~0.1-1 units/s, so beta 0.3 adds almost nothing to the cutoff and 0.7 s squats lose
    // their top zone (0 of 10 counted with stick figures + 1% body-height noise). Following the plan's
    // rule ("raise beta if counts lag; lower minCutoff if standing wobbles > 2°"): beta 8 counts 10/10 fast
    // reps, and minCutoff 1.0 keeps the standing knee-angle sd at ~1.8°. Checked only on synthetic frames.
    val minCutoffHz: Double = 1.0, // TUNE
    val beta: Double = 8.0, // TUNE
    val dCutoffHz: Double = 1.0, // TUNE
    /** A landmark missing for longer than this restarts its filter instead of gliding in. */
    val resetGapMs: Long = 500, // TUNE
)

data class TrackingConfig(
    /** Landmarks with inFrameLikelihood below this are treated as missing. */
    val minInFrame: Float = 0.5f, // TUNE
    /** Missing or rejected tracking up to this long holds every state silently; longer pauses the set. */
    val dropoutGraceMs: Long = 1500, // TUNE
    /** Side view: a limb chain on the locked side whose mean z is this much farther than the other side is swapped. */
    val sideSwapZMargin: Double = 0.05, // TUNE
    /** Front view: a joint pair whose x order flips by more than this (vs calibration) is swapped. */
    val frontSwapXMargin: Double = 0.02, // TUNE
)

data class SessionConfig(
    /** POSITIONING: the start posture must be held this long, still, before calibrating. */
    val holdMs: Long = 1500, // TUNE
    /** Stillness: standard deviation of the primary signal over the hold, for angle signals (degrees). */
    val stillAngleSdDeg: Double = 3.0, // TUNE
    /** Stillness for ratio signals (hip drop, wrist lift). */
    val stillRatioSd: Double = 0.03, // TUNE
    /** View detection on shoulder width / torso length. */
    val viewFrontAbove: Double = 0.5, // TUNE
    val viewSideBelow: Double = 0.3, // TUNE
    val viewHysteresis: Double = 0.05, // TUNE
    val viewStableMs: Long = 1000, // TUNE
    /** Auto set end (only after >= 1 rep): resting in the top zone this long. */
    val restEndMs: Long = 8000, // TUNE
    /** Auto set end for push-ups (only after >= 1 rep): out of the plank this long. */
    val plankExitEndMs: Long = 3000, // TUNE
    /** PAUSED -> IN_SET needs the primary signal in the top zone for this many consecutive frames. */
    val resumeTopFrames: Int = 2, // TUNE
    /** Front-view curls: both arms completing within this window count as one rep. */
    val curlMergeMs: Long = 400, // TUNE
    /** End-of-set advice only for a fault seen on at least this fraction of reps. */
    val faultAdviceMinFraction: Double = 0.3, // TUNE
    /** "Largest"/"throughout" metrics use this percentile of a rep's frames so one noisy frame cannot decide. */
    val robustPercentile: Double = 0.9, // TUNE
    /** Recalibrate (squat) when the ankles have drifted this far from calibration (in leg lengths). */
    val recalibrateAnkleDrift: Double = 0.10, // TUNE
)

/**
 * Thresholds of one [RepCounter], in the signal's natural units (degrees or ratio).
 * [deeperIsLower] is true for joint angles (the angle falls as you go down) and false for ratios.
 */
data class RepCounterConfig(
    val deeperIsLower: Boolean,
    /** Easiest allowed top-zone boundary ("never easier than the floor value"). */
    val topFloor: Double,
    /** Top-zone boundary relative to the calibrated top value; null = use [topFloor] only. */
    val topRelative: Double?,
    /** Depth that must be reached (and held) for a counted rep. */
    val depth: Double,
    /** Movements that never pass this are not rep attempts and are ignored silently. */
    val attempt: Double,
    /** Turning back by this much from the extreme marks a turn (NO_LOCKOUT after the bottom). */
    val turnBack: Double,
    val minDurationMs: Long,
    val maxDurationMs: Long,
    /** Outlier rejection: maximum believable speed of the raw signal, units per second. */
    val maxRatePerSec: Double,
    val bottomDwellFrames: Int = 2, // TUNE
    val bottomDwellMs: Long = 60, // TUNE
    val topConfirmFrames: Int = 2, // TUNE
    /**
     * Bottom window = frames past this fraction of the top->extreme excursion; "at the bottom" faults are
     * averaged over it. Phase durations are extrapolated from the top-zone edge to this level.
     */
    val bottomFraction: Double = 0.9, // TUNE
)

data class SquatConfig(
    val sideCounter: RepCounterConfig = RepCounterConfig(
        deeperIsLower = true,
        topFloor = 150.0, // TUNE
        topRelative = 15.0, // TUNE
        depth = 100.0, // TUNE
        attempt = 135.0, // TUNE
        turnBack = 12.0, // TUNE
        minDurationMs = 600, // TUNE
        maxDurationMs = 8000, // TUNE
        maxRatePerSec = 600.0, // TUNE
    ),
    val frontCounter: RepCounterConfig = RepCounterConfig(
        deeperIsLower = false,
        topFloor = 0.08, // TUNE
        topRelative = null,
        depth = 0.28, // TUNE
        attempt = 0.12, // TUNE
        turnBack = 0.08, // TUNE
        minDurationMs = 600, // TUNE
        maxDurationMs = 8000, // TUNE
        maxRatePerSec = 4.0, // TUNE: "equivalent" of 600°/s for the hip drop ratio
    ),
    // Movement gates (plan Phase 4).
    val gateAnkleMoveLeg: Double = 0.10, // TUNE: ankles move < 0.10 leg length
    val gateHipDropLeg: Double = 0.15, // TUNE: side view, hip drops >= 0.15 leg length (counted reps only)
    val gateTorsoLeanDeg: Double = 65.0, // TUNE
    // Setup (plan Phase 5).
    val startKneeMinDeg: Double = 150.0, // TUNE: "Stand tall."
    val startTorsoLeanMaxDeg: Double = 30.0, // TUNE: engine choice, also part of "Stand tall."
    val stanceMinRatio: Double = 0.7, // TUNE: ankle gap / shoulder width
    val stanceMaxRatio: Double = 1.9, // TUNE
    // Faults (plan Phase 5).
    val forwardLeanDeg: Double = 50.0, // TUNE
    val forwardLeanOverShinDeg: Double = 15.0, // TUNE
    val heelLiftLeg: Double = 0.04, // TUNE
    val kneeCaveRatio: Double = 0.85, // TUNE: knee gap / ankle gap at the bottom
    val weightShiftHipWidth: Double = 0.15, // TUNE
    val fastDescentMs: Long = 500, // TUNE
)

data class PushUpConfig(
    val counter: RepCounterConfig = RepCounterConfig(
        deeperIsLower = true,
        topFloor = 145.0, // TUNE
        topRelative = 15.0, // TUNE
        depth = 95.0, // TUNE
        attempt = 130.0, // TUNE
        turnBack = 12.0, // TUNE
        minDurationMs = 500, // TUNE
        maxDurationMs = 8000, // TUNE
        maxRatePerSec = 600.0, // TUNE
    ),
    val gatePlankLineDeg: Double = 35.0, // TUNE: shoulder-ankle line within 35° of horizontal throughout
    val gateWristMoveArm: Double = 0.15, // TUNE
    val gateShoulderDropArm: Double = 0.2, // TUNE (counted reps only)
    // Setup.
    val startElbowMinDeg: Double = 145.0, // TUNE: "Get into a high plank."
    val handsForwardArm: Double = 0.35, // TUNE: sideways shoulder-wrist gap in arm lengths
    // Faults.
    val hipSagBody: Double = 0.06, // TUNE
    val hipPikeBody: Double = 0.08, // TUNE
    val fastDescentMs: Long = 400, // TUNE
)

data class CurlConfig(
    val counter: RepCounterConfig = RepCounterConfig(
        deeperIsLower = false,
        topFloor = -0.6, // TUNE: arm hanging
        topRelative = null,
        depth = 0.5, // TUNE
        attempt = 0.0, // TUNE
        turnBack = 0.08, // TUNE
        minDurationMs = 500, // TUNE
        maxDurationMs = 8000, // TUNE
        maxRatePerSec = 12.0, // TUNE: "equivalent" of 600°/s for the wrist lift ratio
    ),
    val gateUpperArmDeg: Double = 40.0, // TUNE: upper arm within 40° of vertical throughout
    // TUNE: elbow moves < this many torso lengths relative to the shoulder. The plan says 0.2, but with an
    // upper arm of ~0.55 torso lengths that only allows ~21° of upper-arm rotation, so every rep with the
    // "elbow drift" fault (> 25°) would be discarded and the fault could never fire. 0.4 ~ the 40° gate.
    val gateElbowMoveTorso: Double = 0.4,
    // Setup.
    val startUpperArmMaxDeg: Double = 25.0, // TUNE: "Arms straight by your sides."
    val startTorsoLeanMaxDeg: Double = 30.0, // TUNE
    // Faults.
    val elbowDriftDeg: Double = 25.0, // TUNE
    val swingDeg: Double = 10.0, // TUNE
    val fastLoweringMs: Long = 500, // TUNE
)
