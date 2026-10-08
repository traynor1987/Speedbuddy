package uk.co.traynor.speedbuddy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
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
                cancelAudio()
            }
        }, main).build()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var closed = false
    private var pending: QueuedCameraSpeech? = null
    private val pendingTimeout = Runnable {
        val queued = pending
        pending = null
        if (queued != null && !queued.alreadyBeeped && queued.relevant() && queued.voiceAllowed()) fallbackBeep()
    }
    private var utteranceId = 0L
    private var activeUtterance: String? = null
    private var activeHadBeep = false
    private var activeRelevant: () -> Boolean = { true }
    private var activeVoiceAllowed: () -> Boolean = { true }
    private var focusHeld = false
    private var tone: ToneGenerator? = null
    private val beepToken = Any()
    private var beepRelevant: () -> Boolean = { true }
    private val focusTimeout = Runnable { cancelAudio() }
    val busy: Boolean get() = pending != null || activeUtterance != null || tone != null

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
                                    main.post { failUtterance(utteranceId) }
                                }
                                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                    main.post { finishUtterance(utteranceId) }
                                }
                            })
                            ready = true
                            drainPending()
                            return@post
                        }
                    }
                    failed = true
                    drainPending()
                }
            }
        } catch (error: Exception) {
            failed = true
            Log.w("SpeedBuddy", "Speech engine unavailable", error)
        }
    }

    fun say(text: String) = play(CameraAudioCue(text, false))

    fun play(cue: CameraAudioCue, relevant: () -> Boolean = { true }, voiceAllowed: () -> Boolean = { true }) {
        if (closed) return
        if (!relevant()) return
        if (cue.speech == null && !cue.doubleBeep) return
        // A new spoken warning supersedes old speech; a proximity beep does not cut it off.
        if (cue.speech != null) cancelAudio()
        if (!requestFocus()) { fallbackBeep(); return }
        if (cue.doubleBeep) {
            releaseTone()
            try {
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, 75)
                beepRelevant = relevant
                var beepPlayed = tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 160) == true
                main.postAtTime({
                    if (!relevant()) { releaseTone(); if (activeUtterance == null) releaseFocus() }
                    else {
                        val played = runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 160) == true }
                            .onFailure { Log.w("SpeedBuddy", "Second camera beep unavailable", it) }.getOrDefault(false)
                        beepPlayed = beepPlayed || played
                    }
                }, beepToken, SystemClock.uptimeMillis() + 270)
                main.postAtTime({
                    releaseTone()
                    if (cue.speech != null) speakNow(cue.speech, beepPlayed, relevant, voiceAllowed)
                    else if (!beepPlayed && relevant()) { fallbackBeep(); if (activeUtterance == null) releaseFocus() }
                    else if (activeUtterance == null) releaseFocus()
                }, beepToken, SystemClock.uptimeMillis() + 500)
            } catch (error: Exception) {
                Log.w("SpeedBuddy", "Double camera beep unavailable", error)
                releaseTone()
                if (cue.speech != null) speakNow(cue.speech, false, relevant, voiceAllowed)
                else { fallbackBeep(); if (activeUtterance == null) releaseFocus() }
            }
        } else if (cue.speech != null) speakNow(cue.speech, false, relevant, voiceAllowed)
    }

    private fun requestFocus(): Boolean {
        if (!focusHeld) {
            val focus = runCatching { audioManager.requestAudioFocus(focusRequest) }
                .onFailure { Log.w("SpeedBuddy", "Audio focus unavailable", it) }.getOrNull()
            if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            focusHeld = true
        }
        main.removeCallbacks(focusTimeout)
        main.postDelayed(focusTimeout, 15_000)
        return true
    }

    private fun speakNow(text: String, alreadyBeeped: Boolean = false,
        relevant: () -> Boolean = { true }, voiceAllowed: () -> Boolean = { true }) {
        if (closed) return
        if (!relevant() || !voiceAllowed()) { if (tone == null && activeUtterance == null) releaseFocus(); return }
        if (!ready && !failed) {
            pending = QueuedCameraSpeech(text, SystemClock.elapsedRealtime(), alreadyBeeped, relevant, voiceAllowed)
            main.removeCallbacks(pendingTimeout)
            main.postDelayed(pendingTimeout, 10_000)
            if (tone == null && activeUtterance == null) releaseFocus()
            return
        }
        if (failed) {
            if (!alreadyBeeped) fallbackBeep()
            releaseFocus(); return
        }
        if (!requestFocus()) { if (!alreadyBeeped) fallbackBeep(); return }
        val id = "camera-${++utteranceId}"
        activeUtterance = id
        activeHadBeep = alreadyBeeped
        activeRelevant = relevant
        activeVoiceAllowed = voiceAllowed
        val result = runCatching {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }.onFailure { Log.w("SpeedBuddy", "Camera speech unavailable", it) }.getOrNull()
        if (result != TextToSpeech.SUCCESS) { finishUtterance(id); if (!alreadyBeeped) fallbackBeep() }
    }

    private fun failUtterance(id: String?) {
        val allowed=!closed && !activeHadBeep && activeRelevant() && activeVoiceAllowed()
        if(finishUtterance(id) && allowed) fallbackBeep()
    }

    private fun finishUtterance(id: String?): Boolean {
        if (id == null || id != activeUtterance) return false
        activeUtterance = null
        if (tone == null) releaseFocus()
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

    private fun releaseTone() {
        main.removeCallbacksAndMessages(beepToken)
        runCatching { tone?.release() }
        tone = null
    }

    private fun drainPending() {
        val queued = pending
        pending = null
        main.removeCallbacks(pendingTimeout)
        if (queued?.playableAt(SystemClock.elapsedRealtime()) == true)
            speakNow(queued.text, queued.alreadyBeeped, queued.relevant, queued.voiceAllowed)
    }

    fun revalidate() {
        if (pending?.playableAt(SystemClock.elapsedRealtime()) == false) {
            pending = null; main.removeCallbacks(pendingTimeout)
        }
        if (activeUtterance != null && (!activeRelevant() || !activeVoiceAllowed())) {
            activeUtterance = null; engine?.stop()
        }
        if (tone != null && !beepRelevant()) releaseTone()
        if (tone == null && activeUtterance == null) releaseFocus()
    }

    private fun cancelAudio() {
        pending = null
        main.removeCallbacks(pendingTimeout)
        activeUtterance = null
        runCatching { engine?.stop() }.onFailure { Log.w("SpeedBuddy", "Could not stop speech", it) }
        releaseTone()
        releaseFocus()
    }

    fun close() {
        closed = true
        cancelAudio()
        engine?.shutdown()
        engine = null
    }
}
