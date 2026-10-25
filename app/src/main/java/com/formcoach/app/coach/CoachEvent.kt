package com.formcoach.app.coach

// Facts the engine reports. The engine never decides whether something is spoken:
// SpeechPolicy owns debouncing, persistence (2 of last 3 reps), cooldowns, "Better." and wording choice.

data class SetupIssue(
    /** Stable id, e.g. "visibility", "view", "start_posture", "stance_narrow", "hands_forward". */
    val id: String,
    /** Spoken and shown text, at most 10 words. */
    val text: String,
)

data class FaultCue(
    /** Stable id, e.g. "forward_lean", "knees_cave", "hips_sag", "elbow_drift". */
    val id: String,
    /** In-set cue, at most 4 words, e.g. "Chest up." */
    val cue: String,
    /** End-of-set advice fragment, e.g. "keep your chest up". */
    val advice: String,
)

enum class PartialKind {
    /** Left the top zone but never reached depth. */
    TOO_SHALLOW,
    /** Reached depth but turned back before reaching the top zone again. */
    NO_LOCKOUT,
}

sealed interface CoachEvent {
    /** Frame clock time. Informational: SpeechPolicy must use its own nowMs for timing rules. */
    val atMs: Long

    /** Start pressed; POSITIONING begins. */
    data class SetStarted(override val atMs: Long, val exercise: Exercise) : CoachEvent

    /** The highest-priority failing setup check changed. Null means no issue. Emitted on every change, not debounced. */
    data class SetupIssueChanged(override val atMs: Long, val issue: SetupIssue?) : CoachEvent

    /** Start posture held and calibrated. Speech: "Ready. Go." */
    data class Ready(override val atMs: Long) : CoachEvent

    /** A valid rep completed. [faults] were detected on this rep only, most important first. */
    data class RepCounted(override val atMs: Long, val count: Int, val faults: List<FaultCue>) : CoachEvent

    /** An attempted rep that does not count. [firstCue] is used for the first partial of a set, [cue] after that. */
    data class PartialRep(
        override val atMs: Long,
        val partialCount: Int,
        val kind: PartialKind,
        /** e.g. "Not counted. Go lower." */
        val firstCue: String,
        /** e.g. "Lower." */
        val cue: String,
    ) : CoachEvent

    /** Tracking missing for 1.5 s; phase is now PAUSED. */
    data class TrackingLost(override val atMs: Long) : CoachEvent

    data class TrackingRestored(override val atMs: Long) : CoachEvent

    data class SetCompleted(override val atMs: Long, val summary: SetSummary) : CoachEvent
}
