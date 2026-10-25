package com.formcoach.app.coach

// Shared contract between the coaching engine (coach/), the ViewModel/UI and the speech policy.
// Change this file only with agreement from every track: see docs/COACH_FIX_PLAN.md section 6.

enum class Exercise(val label: String) {
    SQUAT("Squat"), PUSH_UP("Push-up"), CURL("Biceps curl")
}

enum class BodySide { LEFT, RIGHT }

enum class CameraView { FRONT, SIDE, ANGLED }

enum class SessionPhase { IDLE, POSITIONING, COUNTDOWN, IN_SET, PAUSED, SET_COMPLETE }

sealed interface SessionCommand {
    data object StartSet : SessionCommand
    data object StopSet : SessionCommand
    /** Ignored during POSITIONING, COUNTDOWN, IN_SET and PAUSED. */
    data class SelectExercise(val exercise: Exercise) : SessionCommand
}

data class SetSummary(
    val reps: Int,
    val partialReps: Int,
    /** End-of-set advice for the most frequent fault, e.g. "keep your chest up". Null if none passed 30% of reps. */
    val topFaultAdvice: String?,
)

data class WorkoutUiState(
    val exercise: Exercise = Exercise.SQUAT,
    val phase: SessionPhase = SessionPhase.IDLE,
    val reps: Int = 0,
    val partialReps: Int = 0,
    /** Per-arm counts for front-view curls only; null otherwise. */
    val leftReps: Int? = null,
    val rightReps: Int? = null,
    /** Current setup instruction or latest cue to show as text. The UI keeps each text on screen for at least 2 s. */
    val instruction: String? = null,
    /** Placement hint shown during POSITIONING, e.g. "Phone at hip height, 2-3 m away, side-on or facing you." */
    val placementHint: String? = null,
    val view: CameraView? = null,
    val lockedSide: BodySide? = null,
    /** Label and value of the primary signal for the metric card, e.g. "KNEE ANGLE" / "142°". */
    val primaryLabel: String = "",
    val primaryValue: String = "--",
    /** True when every landmark the exercise needs is present in the latest frame. */
    val tracking: Boolean = false,
    /** Smoothed landmarks for the skeleton overlay, in the same image coordinates as the input frame. */
    val overlay: PoseFrame? = null,
    val summary: SetSummary? = null,
)

data class EngineOutput(val state: WorkoutUiState, val events: List<CoachEvent>)

/**
 * The coaching engine. Pure Kotlin and deterministic: given the same frames and commands it produces
 * the same outputs. Called from one thread only (the camera analysis thread).
 */
interface WorkoutEngine {
    /** Feed every analysed frame, including frames with no person ([PoseFrame.hasPerson] false). */
    fun onFrame(frame: PoseFrame): EngineOutput

    /** [timestampMs] is on the same clock as the frames (use the latest frame time). */
    fun onCommand(command: SessionCommand, timestampMs: Long): EngineOutput
}
