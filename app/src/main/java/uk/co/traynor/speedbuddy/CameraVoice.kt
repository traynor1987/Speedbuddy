package uk.co.traynor.speedbuddy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                engine?.stop(); activeUtterance = null; releaseFocus()
            }
        }, main).build()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var closed = false
    private var pending: Pair<String, Long>? = null
    private var utteranceId = 0L
    private var activeUtterance: String? = null
    private var focusHeld = false
    private val focusTimeout = Runnable { activeUtterance = null; releaseFocus() }

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
                            tts.setAudioAttributes(audioAttributes)
                            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) = Unit
                                override fun onDone(utteranceId: String?) {
                                    main.post { finishUtterance(utteranceId) }
                                }
                                override fun onError(utteranceId: String?) {
                                    main.post {
                                        if (finishUtterance(utteranceId) && !closed) fallbackBeep()
                                    }
                                }
                                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                    main.post { finishUtterance(utteranceId) }
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
        if (!focusHeld) {
            val focus = runCatching { audioManager.requestAudioFocus(focusRequest) }
                .onFailure { Log.w("SpeedBuddy", "Audio focus unavailable", it) }.getOrNull()
            if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { fallbackBeep(); return }
            focusHeld = true
        }
        val id = "camera-${++utteranceId}"
        activeUtterance = id
        main.removeCallbacks(focusTimeout)
        main.postDelayed(focusTimeout, 15_000)
        val result = runCatching {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }.onFailure { Log.w("SpeedBuddy", "Camera speech unavailable", it) }.getOrNull()
        if (result != TextToSpeech.SUCCESS) { finishUtterance(id); fallbackBeep() }
    }

    private fun finishUtterance(id: String?): Boolean {
        if (id == null || id != activeUtterance) return false
        activeUtterance = null
        releaseFocus()
        return true
    }

    private fun releaseFocus() {
        main.removeCallbacks(focusTimeout)
        if (focusHeld) {
            focusHeld = false
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
                .onFailure { Log.w("SpeedBuddy", "Could not release audio focus", it) }
        }
    }

    fun close() {
        closed = true
        pending = null
        engine?.stop()
        activeUtterance = null
        releaseFocus()
        engine?.shutdown()
        engine = null
    }
}
