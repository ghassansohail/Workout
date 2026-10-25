package com.formcoach.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.formcoach.app.coach.Speaker
import java.util.Locale

/** TextToSpeech playback only. All decisions live in SpeechPolicy. */
class VoiceCoach(context: Context) : Speaker {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()

    private class Pending(val text: String, val id: String, val flush: Boolean)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pending? = null
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var speaking = false

    @Volatile
    private var currentId: String? = null

    @Volatile
    var isAvailable: Boolean = false
        private set

    /** Called when availability changes (TTS init finished or failed). May be called on any thread. */
    var onAvailabilityChanged: ((Boolean) -> Unit)? = null

    override val isSpeaking: Boolean get() = speaking

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        tts = TextToSpeech(appContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        val engine = synchronized(lock) { tts } ?: return
        var ok = status == TextToSpeech.SUCCESS
        if (ok) {
            engine.setAudioAttributes(attrs)
            engine.setSpeechRate(1.1f)
            engine.setOnUtteranceProgressListener(listener)
            var r = engine.setLanguage(Locale.getDefault())
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                r = engine.setLanguage(Locale.US)
            }
            ok = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
        val p: Pending?
        synchronized(lock) {
            ready = ok
            p = if (ok) pending else null
            pending = null
        }
        isAvailable = ok
        onAvailabilityChanged?.invoke(ok)
        if (p != null) speak(p.text, p.id, p.flush)
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = finished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
    }

    /** Binder-thread callback. Ignores callbacks for utterances that were already replaced. */
    private fun finished(id: String?) {
        synchronized(lock) {
            if (id == null || id != currentId) return
            currentId = null
            speaking = false
        }
        abandonFocus()
    }

    override fun speak(text: String, utteranceId: String, flush: Boolean) {
        val engine: TextToSpeech
        synchronized(lock) {
            if (!ready) {
                // Before init completes keep only the latest request.
                pending = Pending(text, utteranceId, flush)
                return
            }
            engine = tts ?: return
            currentId = utteranceId
            speaking = true
        }
        requestFocus()
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = engine.speak(text, mode, Bundle(), utteranceId)
        if (result != TextToSpeech.SUCCESS) finished(utteranceId)
    }

    override fun stopAll() {
        val engine: TextToSpeech?
        synchronized(lock) {
            pending = null
            currentId = null
            speaking = false
            engine = tts
        }
        engine?.stop()
        abandonFocus()
    }

    fun shutdown() {
        val engine: TextToSpeech?
        synchronized(lock) {
            pending = null
            currentId = null
            speaking = false
            ready = false
            engine = tts
            tts = null
        }
        engine?.stop()
        engine?.shutdown()
        abandonFocus()
        isAvailable = false
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = synchronized(lock) {
                focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build()
                    .also { focusRequest = it }
            }
            audioManager.requestAudioFocus(req)
        } else {
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = synchronized(lock) { focusRequest } ?: return
            audioManager.abandonAudioFocusRequest(req)
        } else {
            audioManager.abandonAudioFocus(null)
        }
    }
}
