package com.formcoach.app.coach.synthetic

import com.formcoach.app.coach.PoseFrame

/** Camera view of the person. */
enum class View { SIDE, FRONT }

/**
 * A body side, from the person's own point of view. [sign] is +1 for the person's left and -1 for the right;
 * it is the sign of the lateral coordinate used by the stick figure.
 */
enum class Side(val sign: Int) { LEFT(1), RIGHT(-1) }

/** What a clip shows. */
enum class Kind { SQUAT, PUSH_UP, CURL, DISTRACTOR }

/** Form faults a generated rep can carry. Thresholds are in [Rules] and mirror docs/COACH_FIX_PLAN.md Phase 5. */
enum class Fault {
    FORWARD_LEAN, HEELS_LIFT, KNEES_CAVE, WEIGHT_SHIFT, FAST_DESCENT,
    HIPS_SAG, HIPS_PIKE, NO_LOCKOUT,
    ELBOW_DRIFT, SWINGING, NO_FULL_EXTENSION, FAST_LOWERING,
}

/**
 * Thresholds used to derive the ground truth of a generated rep from the numbers that were injected.
 * They follow the starting values of the plan (Phase 4 and 5). Change them here if the plan changes.
 */
object Rules {
    // Squat
    const val SQUAT_COUNTED_KNEE_DEG = 100.0
    const val SQUAT_ATTEMPT_KNEE_DEG = 135.0
    const val SQUAT_TORSO_GATE_DEG = 65.0
    const val SQUAT_LEAN_FAULT_DEG = 50.0
    const val SQUAT_LEAN_OVER_SHIN_DEG = 15.0
    const val HEEL_LIFT_FAULT = 0.04 // fraction of leg length
    const val KNEE_GAP_RATIO_FAULT = 0.85
    const val WEIGHT_SHIFT_FAULT = 0.15 // hip widths
    const val SQUAT_FAST_DESCENT_MS = 500

    // Push-up
    const val PUSHUP_COUNTED_ELBOW_DEG = 95.0
    const val PUSHUP_ATTEMPT_ELBOW_DEG = 130.0
    const val PUSHUP_LOCKOUT_MIN_DEG = 145.0
    const val HIP_SAG_FAULT = 0.06 // fraction of body length
    const val HIP_PIKE_FAULT = 0.08
    const val PUSHUP_FAST_DESCENT_MS = 400

    // Curl (wrist lift ratio = (elbowY - wristY) / forearm length)
    const val CURL_COUNTED_LIFT = 0.5
    const val CURL_ATTEMPT_LIFT = 0.0
    const val CURL_EXTENDED_LIFT = -0.6
    const val CURL_DRIFT_FAULT_DEG = 25.0
    const val CURL_DRIFT_GATE_DEG = 40.0
    const val CURL_SWING_FAULT_DEG = 10.0
    const val CURL_FAST_LOWERING_MS = 500
}

/**
 * Ground truth of one injected rep attempt.
 *
 * @property index 1-based attempt number within its clip (and within its arm for curls). Partial attempts count.
 * @property side the arm for curls, null otherwise.
 * @property startMs frame timestamp at which the rep starts moving.
 * @property extremeMs timestamp of the deepest point (squat, push-up) or the curled peak (curl).
 * @property endMs timestamp at which the rep is back at the top.
 * @property counted the rep is deep enough and complete: an engine with the plan's thresholds should count it.
 * @property partial an attempt that should be reported as a partial rep (not counted).
 * @property faults injected faults that exceed the plan's start thresholds.
 * @property extremeDeg the injected extreme angle: knee (squat), elbow (push-up, curl).
 */
data class RepTruth(
    val index: Int,
    val side: Side?,
    val startMs: Long,
    val extremeMs: Long,
    val endMs: Long,
    val counted: Boolean,
    val partial: Boolean,
    val faults: Set<Fault>,
    val extremeDeg: Double,
)

/** Ground truth for a clip or a composed sequence. Distractor clips have no reps. */
data class Truth(
    val kind: Kind?,
    val reps: List<RepTruth> = emptyList(),
    val description: String = "",
) {
    /** Reps an engine should count, in total (both arms for curls). */
    val expectedReps: Int get() = reps.count { it.counted }

    /** Attempts that should be reported as partial reps. */
    val expectedPartialReps: Int get() = reps.count { it.partial }

    /** Counted reps of the person's left arm (curls only, otherwise 0). Both-arms curls count for both arms. */
    val leftReps: Int get() = reps.count { it.counted && (it.side == Side.LEFT || (it.side == null && kind == Kind.CURL)) }

    /** Counted reps of the person's right arm (curls only, otherwise 0). */
    val rightReps: Int get() = reps.count { it.counted && (it.side == Side.RIGHT || (it.side == null && kind == Kind.CURL)) }

    /** Injected faults by 1-based attempt index. For curls the faults of both arms are merged. */
    val faultsByRep: Map<Int, Set<Fault>>
        get() = reps.groupBy { it.index }.mapValues { (_, v) -> v.flatMap { it.faults }.toSet() }

    /** Number of reps carrying each fault. */
    val faultCounts: Map<Fault, Int>
        get() = reps.flatMap { it.faults }.groupingBy { it }.eachCount()

    internal fun shifted(deltaMs: Long): Truth = copy(
        reps = reps.map { it.copy(startMs = it.startMs + deltaMs, extremeMs = it.extremeMs + deltaMs, endMs = it.endMs + deltaMs) },
    )

    internal operator fun plus(other: Truth): Truth = Truth(
        kind = if (kind == null || kind == Kind.DISTRACTOR) other.kind ?: kind else kind,
        reps = reps + other.reps,
        description = listOf(description, other.description).filter { it.isNotEmpty() }.joinToString(" + "),
    )
}

/**
 * Where the camera is and how big the person is in the image. All sizes are pixels.
 *
 * @property view SIDE: the person stands side-on and faces image-right when [nearSide] is RIGHT (their right side
 * is then nearest the camera), image-left when it is LEFT. FRONT: the person faces the camera, unmirrored, so the
 * person's left body side appears on the image's right.
 * @property bodyHeightPx standing body height in pixels. Noise sigma is a fraction of this.
 * @property centerX image x of the figure's reference point (the ankles while standing, the body middle for a plank).
 * @property floorY image y of the floor.
 */
data class CameraSpec(
    val view: View = View.SIDE,
    val nearSide: Side = Side.RIGHT,
    val width: Int = 480,
    val height: Int = 640,
    val bodyHeightPx: Double = 0.78 * height,
    val centerX: Double = width / 2.0,
    val floorY: Double = 0.93 * height,
) {
    companion object {
        /** 480x640 portrait, person about 78% of the image height. */
        fun portrait(view: View = View.SIDE, nearSide: Side = Side.RIGHT, width: Int = 480, height: Int = 640) =
            CameraSpec(view, nearSide, width, height)

        /** 640x480 landscape (push-ups), person about 80% of the image height. */
        fun landscape(view: View = View.SIDE, nearSide: Side = Side.RIGHT, width: Int = 640, height: Int = 480) =
            CameraSpec(view, nearSide, width, height, bodyHeightPx = 0.8 * height, floorY = 0.9 * height)
    }
}

/** Everything needed to re-render a clip at pose level; kept so clips can be composed with smooth transitions. */
class ClipSource internal constructor(
    internal val track: PoseTrack,
    internal val camera: CameraSpec,
    internal val fps: Int,
    internal val startMs: Long,
)

/**
 * A generated sequence of frames with its ground truth.
 *
 * Clips straight from a builder (`Synthetic.squat { }` ...) also remember their pose-level script so
 * [Synthetic.sequence] can join them smoothly. Clips returned by a modifier (noise, dropouts, swaps, ...) have no
 * such script: compose first, then apply modifiers.
 *
 * Modifiers are deterministic for a given seed. They never change [truth].
 */
data class SyntheticClip(
    val frames: List<PoseFrame>,
    val truth: Truth,
    /** Standing body height in pixels, the unit for noise sigma. */
    val bodyHeightPx: Double = 0.0,
    internal val source: ClipSource? = null,
) {
    val durationMs: Long get() = if (frames.isEmpty()) 0 else frames.last().timestampMs - frames.first().timestampMs
}
