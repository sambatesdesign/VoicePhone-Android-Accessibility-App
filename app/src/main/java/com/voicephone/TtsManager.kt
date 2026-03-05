package com.voicephone

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Thin wrapper around Android TextToSpeech.
 *
 * Key behaviours:
 * - Speech rate 0.85x (easier for elderly users)
 * - Queues speech; provides onDone callback per utterance
 * - Prevents STT from starting while TTS is speaking (via [isSpeaking])
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    private var tts: TextToSpeech? = null
    var isReady = false
        private set

    /** True while TTS audio is playing. Check before starting STT. */
    var isSpeaking = false
        private set

    private var pendingOnReady: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                tts?.setSpeechRate(0.85f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        onDoneCallbacks[utteranceId]?.invoke()
                        onDoneCallbacks.remove(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        Log.e(TAG, "TTS error for utterance: $utteranceId")
                    }
                })
                isReady = true
                Log.d(TAG, "TTS ready")
                pendingOnReady?.invoke()
                pendingOnReady = null
            } else {
                Log.e(TAG, "TTS init failed, status=$status")
            }
        }
    }

    private val onDoneCallbacks = mutableMapOf<String, () -> Unit>()

    /**
     * Speak [text]. Flushes any currently speaking utterance.
     * [onDone] is called when playback completes.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready, queuing: $text")
            pendingOnReady = { speak(text, onDone) }
            return
        }
        val uid = UUID.randomUUID().toString()
        if (onDone != null) onDoneCallbacks[uid] = onDone

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
    }

    /** Stop any current speech immediately. */
    fun stop() {
        tts?.stop()
        isSpeaking = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
