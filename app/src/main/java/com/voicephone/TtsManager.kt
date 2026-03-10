package com.voicephone

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * TTS manager supporting both Android on-device TTS and Deepgram cloud TTS.
 *
 * Toggle with [useCloudTts]. Preference is persisted across restarts.
 * Falls back to Android TTS automatically if cloud fails or no network.
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
        private const val PREFS = "voicephone_prefs"
        private const val KEY_CLOUD_TTS = "use_cloud_tts"
        private const val DEEPGRAM_VOICE = "aura-2-thalia-en"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = OkHttpClient()

    /** Toggle cloud TTS on/off. Persists across restarts. */
    var useCloudTts: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_TTS, false)
        set(value) { prefs.edit().putBoolean(KEY_CLOUD_TTS, value).apply() }

    private var tts: TextToSpeech? = null
    var isReady = false
        private set

    /** True while TTS audio is playing. */
    var isSpeaking = false
        private set

    private var pendingOnReady: (() -> Unit)? = null
    private val onDoneCallbacks = mutableMapOf<String, () -> Unit>()
    private var cloudPlayer: MediaPlayer? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                tts?.setSpeechRate(0.85f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { isSpeaking = true }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        mainHandler.post {
                            onDoneCallbacks[utteranceId]?.invoke()
                            onDoneCallbacks.remove(utteranceId)
                        }
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

    /**
     * Speak [text]. Uses Deepgram if [useCloudTts] is true, otherwise Android TTS.
     * Falls back to Android TTS automatically if cloud call fails.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isReady) {
            pendingOnReady = { speak(text, onDone) }
            return
        }
        if (useCloudTts && BuildConfig.DEEPGRAM_API_KEY.isNotEmpty()) {
            speakWithDeepgram(text, onDone)
        } else {
            speakWithAndroid(text, onDone)
        }
    }

    private fun speakWithAndroid(text: String, onDone: (() -> Unit)?) {
        val uid = UUID.randomUUID().toString()
        if (onDone != null) onDoneCallbacks[uid] = onDone
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
    }

    private fun speakWithDeepgram(text: String, onDone: (() -> Unit)?) {
        isSpeaking = true
        val body = """{"text":"${text.replace("\"", "\\\"")}"}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.deepgram.com/v1/speak?model=$DEEPGRAM_VOICE&encoding=mp3")
            .header("Authorization", "Token ${BuildConfig.DEEPGRAM_API_KEY}")
            .post(body)
            .build()

        Thread {
            try {
                val response = http.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Deepgram failed (${response.code}), falling back to Android TTS")
                    mainHandler.post { isSpeaking = false; speakWithAndroid(text, onDone) }
                    return@Thread
                }
                val audioBytes = response.body!!.bytes()
                val tmp = File.createTempFile("dg_tts", ".mp3", context.cacheDir)
                tmp.writeBytes(audioBytes)

                mainHandler.post {
                    cloudPlayer?.release()
                    cloudPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(tmp.absolutePath)
                        setOnCompletionListener {
                            isSpeaking = false
                            tmp.delete()
                            onDone?.invoke()
                        }
                        setOnErrorListener { _, _, _ ->
                            isSpeaking = false
                            Log.e(TAG, "MediaPlayer error — falling back")
                            speakWithAndroid(text, onDone)
                            true
                        }
                        prepare()
                        start()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Deepgram request failed: ${e.message} — falling back")
                mainHandler.post { isSpeaking = false; speakWithAndroid(text, onDone) }
            }
        }.start()
    }

    fun stop() {
        tts?.stop()
        cloudPlayer?.stop()
        cloudPlayer?.release()
        cloudPlayer = null
        isSpeaking = false
    }

    fun cancelAll() {
        stop()
        onDoneCallbacks.clear()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
