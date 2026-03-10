package com.voicephone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Wraps Android's on-device SpeechRecognizer and converts recognised text
 * into a typed [VoiceIntent] via simple keyword matching.
 *
 * Design principle: this class is the seam for Phase 3 LLM integration.
 * To plug in Claude API, replace [parseIntent] or wrap it:
 *
 *   fun handleUtterance(text: String) {
 *       if (networkAvailable()) claudeHandler.process(text)
 *       else parseIntent(text)
 *   }
 */
class SpeechHandler(
    private val context: Context,
    private val onIntent: (VoiceIntent) -> Unit,
    private val onListeningStarted: () -> Unit,
    private val onListeningEnded: () -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val TAG = "SpeechHandler"
        private const val LISTEN_TIMEOUT_MS = 30000L
    }

    private var recognizer: SpeechRecognizer? = null
    var isListening = false
        private set
    /** When true, skip parseIntent and return raw text as Unknown. */
    var rawMode = false

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(buildListener())
        }
    }

    private fun buildListener() = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    onListeningStarted()
                    Log.d(TAG, "Ready for speech")
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    onListeningEnded()
                }

                override fun onError(error: Int) {
                    isListening = false
                    onListeningEnded()
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_NO_MATCH -> "timeout"
                        SpeechRecognizer.ERROR_AUDIO -> "audio_error"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                        else -> "error_$error"
                    }
                    Log.w(TAG, "STT error: $msg")
                    onIntent(VoiceIntent.Timeout)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    onListeningEnded()
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: return
                    Log.d(TAG, "Recognised: $text")
                    onIntent(if (rawMode) VoiceIntent.Unknown(text) else parseIntent(text))
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }

    fun startListening() {
        if (isListening) return
        val sr = recognizer ?: run {
            onError("Speech recognition not available.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, LISTEN_TIMEOUT_MS)
        }
        sr.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        isListening = false
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        isListening = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intent parsing — keyword matching, simple + forgiving
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse a free-text utterance into a [VoiceIntent].
     * Normalise to lowercase, then match keywords in priority order.
     *
     * This is the Phase 3 LLM integration point — replace or wrap this method.
     */
    fun parseIntent(raw: String): VoiceIntent {
        val text = raw.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim()
        Log.d(TAG, "Parsing: '$text'")

        return when {
            // ── Answer incoming call ──
            text.contains("answer") || text == "yes" || text.contains("yeah") -> VoiceIntent.Answer

            // ── Hang up ──
            text.contains("hang up") || text.contains("hang-up")
                    || text.contains("end call") || text.contains("goodbye")
                    || text.contains("bye") -> VoiceIntent.HangUp

            // ── Reject / ignore ──
            text.contains("ignore") || text.contains("reject")
                    || text.contains("not now") || text == "no" || text.contains("nope") -> VoiceIntent.Reject

            // ── Call intent ──
            text.contains("call") || text.contains("phone")
                    || text.contains("ring") || text.contains("dial") -> {
                val target = extractCallTarget(text)
                VoiceIntent.Call(target)
            }

            // ── SMS read ──
            (text.contains("read") && (text.contains("message") || text.contains("text")))
                    || text.contains("any messages")
                    || text.contains("read it") -> VoiceIntent.ReadSms

            // ── SMS send ──
            text.startsWith("text ") || text.startsWith("message ") ||
            ((text.contains("send") || text.contains("reply"))
                    && (text.contains("message") || text.contains("text"))) -> {
                val name = extractSendTarget(text)
                VoiceIntent.SendSms(name)
            }

            // ── Time ──
            text.contains("time") -> VoiceIntent.Time

            // ── Date ──
            text.contains("date") || text.contains("day") || text.contains("today") -> VoiceIntent.Date

            // ── Missed calls ──
            text.contains("missed") || (text.contains("who") && text.contains("call")) -> VoiceIntent.MissedCalls

            // ── Help ──
            text.contains("help") || text.contains("what can you do") -> VoiceIntent.Help

            // ── Open contacts (carer use) ──
            text.contains("contacts") || text.contains("manage contacts") || text.contains("add contact") -> VoiceIntent.OpenContacts

            // ── Open settings (carer use) ──
            text.contains("settings") || text.contains("open settings") -> VoiceIntent.OpenSettings

            else -> VoiceIntent.Unknown(raw)
        }
    }

    /** Extract the contact name/number from a call utterance. */
    private fun extractCallTarget(text: String): String {
        val triggers = listOf("call", "phone", "ring", "dial")
        var result = text
        for (trigger in triggers) {
            val idx = result.indexOf(trigger)
            if (idx >= 0) {
                result = result.substring(idx + trigger.length).trim()
                break
            }
        }
        // Strip common filler words
        result = result
            .removePrefix("my ")
            .removePrefix("the ")
            .trim()
        return result
    }

    private fun extractSendTarget(text: String): String {
        // "send a message to NAME" / "reply to NAME"
        val toIdx = text.indexOf(" to ")
        if (toIdx >= 0) return text.substring(toIdx + 4).trim()
        // "text NAME" / "message NAME"
        for (trigger in listOf("text ", "message ")) {
            if (text.startsWith(trigger)) return text.removePrefix(trigger).trim()
        }
        return ""
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Voice intents — sealed class for exhaustive when() handling
// ─────────────────────────────────────────────────────────────────────────────

sealed class VoiceIntent {
    data class Call(val target: String) : VoiceIntent()
    object Answer : VoiceIntent()
    object HangUp : VoiceIntent()
    object Reject : VoiceIntent()
    object ReadSms : VoiceIntent()
    data class SendSms(val contactName: String) : VoiceIntent()
    object Time : VoiceIntent()
    object Date : VoiceIntent()
    object MissedCalls : VoiceIntent()
    object Help : VoiceIntent()
    object OpenContacts : VoiceIntent()
    object OpenSettings : VoiceIntent()
    object Timeout : VoiceIntent()
    data class Unknown(val raw: String) : VoiceIntent()
}
