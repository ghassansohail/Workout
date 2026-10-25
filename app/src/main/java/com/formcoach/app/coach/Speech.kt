package com.formcoach.app.coach

/** Highest priority first. */
enum class SpeechPriority { COUNT, STATE, FORM, SETUP, PRAISE }

/** Audio output, implemented by voice/VoiceCoach (TextToSpeech) and by fakes in tests. */
interface Speaker {
    /** True from the moment speak() is called until the utterance finishes or fails. */
    val isSpeaking: Boolean

    /** [flush] true cuts off whatever is playing; false waits behind it. */
    fun speak(text: String, utteranceId: String, flush: Boolean)

    fun stopAll()
}

/**
 * Decides what is said and when. Implemented by coach/SpeechPolicy. Enforces the rules in
 * docs/COACH_FIX_PLAN.md section 3.2 and the script in section 3.3.
 * Uses only the nowMs arguments for timing (never CoachEvent.atMs, which is on the camera clock).
 */
interface SpeechScheduler {
    /** When true, nothing is spoken and pending utterances are dropped. */
    var muted: Boolean

    fun onEvents(events: List<CoachEvent>, nowMs: Long)

    /** Call at least every 100 ms. Starts the next utterance on [speaker] when the rules allow. */
    fun onTick(nowMs: Long, speaker: Speaker)
}
