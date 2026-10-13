package com.formcoach.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Speaks changed cues locally and rate-limits them to keep coaching calm during movement. */
class VoiceCoach(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pendingCue: String? = null
    private var lastSpokenCue: String? = null
    private var lastSpokenAt = 0L
    private var scheduled: Runnable? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.getDefault()
                ready = true
                deliverPendingCue()
            }
        }
    }

    fun speak(cue: String) {
        if (cue == "Position yourself in frame" || cue == "Get in position" || cue == "--°") return
        pendingCue = cue
        scheduled?.let(handler::removeCallbacks)
        val wait = (800L - (SystemClock.elapsedRealtime() - lastSpokenAt)).coerceAtLeast(0L)
        scheduled = Runnable { deliverPendingCue() }
        handler.postDelayed(scheduled!!, wait)
    }

    private fun deliverPendingCue() {
        val cue = pendingCue ?: return
        if (!ready) return
        pendingCue = null
        if (cue == lastSpokenCue && SystemClock.elapsedRealtime() - lastSpokenAt < 2_000L) return
        lastSpokenCue = cue
        lastSpokenAt = SystemClock.elapsedRealtime()
        engine?.speak(cue, TextToSpeech.QUEUE_FLUSH, Bundle(), "form-cue-${lastSpokenAt}")
    }

    fun stop() {
        pendingCue = null
        scheduled?.let(handler::removeCallbacks)
        scheduled = null
        engine?.stop()
    }

    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
    }
}
