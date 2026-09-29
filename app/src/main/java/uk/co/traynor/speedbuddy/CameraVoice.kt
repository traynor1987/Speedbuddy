package uk.co.traynor.speedbuddy

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/** Owns the system speech engine only while driving; a short beep covers unavailable speech. */
class CameraVoice(context: Context, private val fallbackBeep: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var closed = false
    private var pending: Pair<String, Long>? = null
    private var utteranceId = 0L

    init {
        try {
            engine = TextToSpeech(context.applicationContext) { status ->
                main.post {
                    if (closed) return@post
                    val tts = engine
                    if (status == TextToSpeech.SUCCESS && tts != null) {
                        val language = tts.setLanguage(Locale.UK).let { result ->
                            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
                                tts.setLanguage(Locale.ENGLISH) else result
                        }
                        if (language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setAudioAttributes(AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build())
                            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) = Unit
                                override fun onDone(utteranceId: String?) = Unit
                                override fun onError(utteranceId: String?) {
                                    main.post { if (!closed) fallbackBeep() }
                                }
                            })
                            ready = true
                            pending?.let { (text, queuedAt) ->
                                if (SystemClock.elapsedRealtime() - queuedAt <= 10_000) speakNow(text)
                            }
                            pending = null
                            return@post
                        }
                    }
                    failed = true
                    if (pending != null) fallbackBeep()
                    pending = null
                }
            }
        } catch (error: Exception) {
            failed = true
            Log.w("SpeedBuddy", "Speech engine unavailable", error)
        }
    }

    fun say(text: String) {
        if (closed) return
        if (failed) { fallbackBeep(); return }
        if (!ready) { pending = text to SystemClock.elapsedRealtime(); return }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        val result = runCatching {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "camera-${++utteranceId}")
        }.onFailure { Log.w("SpeedBuddy", "Camera speech unavailable", it) }.getOrNull()
        if (result != TextToSpeech.SUCCESS) fallbackBeep()
    }

    fun close() {
        closed = true
        pending = null
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
