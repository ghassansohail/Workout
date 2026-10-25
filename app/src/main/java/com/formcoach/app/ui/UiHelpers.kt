package com.formcoach.app.ui

import com.formcoach.app.coach.SessionPhase

/** How long an instruction stays on screen before the next one may replace it. */
const val INSTRUCTION_MIN_HOLD_MS = 2_000L

/** True while a set is running and the exercise chips and camera flip must be disabled. */
fun isSetActive(phase: SessionPhase): Boolean =
    phase != SessionPhase.IDLE && phase != SessionPhase.SET_COMPLETE

fun phaseLabel(phase: SessionPhase): String? = when (phase) {
    SessionPhase.IDLE -> null
    SessionPhase.POSITIONING -> "Positioning"
    SessionPhase.COUNTDOWN -> "Get ready"
    SessionPhase.IN_SET -> "In set"
    SessionPhase.PAUSED -> "Paused"
    SessionPhase.SET_COMPLETE -> "Set complete"
}

/** Milliseconds until a text shown at [shownAtMs] may be replaced. Zero or negative means now. */
fun holdRemainingMs(shownAtMs: Long, nowMs: Long, minHoldMs: Long = INSTRUCTION_MIN_HOLD_MS): Long =
    maxOf(0L, shownAtMs + minHoldMs - nowMs)
