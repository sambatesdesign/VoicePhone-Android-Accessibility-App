package com.voicephone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import androidx.core.app.NotificationCompat
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.telecom.TelecomManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service — the engine of VoicePhone.
 *
 * Responsibilities:
 * - Keep alive always (foreground notification, PARTIAL_WAKE_LOCK)
 * - Own TTS, STT, ContactsHelper, SmsHelper instances
 * - Receive events from MainActivity (touch) and broadcast receivers (calls, SMS)
 * - Drive the app state machine
 * - Notify MainActivity of state changes via [stateListener]
 */
class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "voicephone_service"

        const val ACTION_START_LISTENING = "com.voicephone.START_LISTENING"
        const val ACTION_INCOMING_CALL = "com.voicephone.INCOMING_CALL"
        const val ACTION_INCOMING_SMS = "com.voicephone.INCOMING_SMS"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_SMS_SENDER = "sms_sender"
        const val EXTRA_SMS_BODY = "sms_body"

        /** Singleton reference for cross-component callbacks. */
        var instance: VoiceService? = null
            private set
    }

    // ── Core components ──────────────────────────────────────────────────────
    lateinit var tts: TtsManager
        private set
    lateinit var speech: SpeechHandler
    lateinit var contactsHelper: ContactsHelper
        private set
    private lateinit var smsHelper: SmsHelper

    // ── State ────────────────────────────────────────────────────────────────
    var currentAppState: AppState = AppState.IDLE
        private set
    private var appState: AppState
        get() = currentAppState
        set(value) { currentAppState = value }
    private var pendingSmsSender: String = ""
    private var pendingSmsBody: String = ""
    private var lastIncomingCaller: String = ""
    private var pendingSmsContact: Contact? = null
    private var pendingSmsOutbodyDraft: String = ""

    private val prefs by lazy {
        getSharedPreferences("voicephone_prefs", MODE_PRIVATE)
    }
    var useSmartMode: Boolean
        get() = prefs.getBoolean("use_smart_mode", false)
        set(value) { prefs.edit().putBoolean("use_smart_mode", value).apply() }
    private val claudeParser by lazy {
        if (BuildConfig.ANTHROPIC_API_KEY.isNotEmpty())
            ClaudeIntentParser(BuildConfig.ANTHROPIC_API_KEY)
        else null
    }

    /** Activity registers here to receive state updates for the UI. */
    var stateListener: ((AppState, String) -> Unit)? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtonePlayer: MediaPlayer? = null
    private var savedRingVolume: Int = -1

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "VoiceService created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        acquireWakeLock()

        tts = TtsManager(this).also { t ->
            t.onSpeakingChanged = { speaking ->
                // IDLE or PROCESSING → SPEAKING when TTS starts (not during calls)
                if (speaking && (appState == AppState.IDLE || appState == AppState.PROCESSING)) {
                    updateState(AppState.SPEAKING, "")
                } else if (!speaking && appState == AppState.SPEAKING) {
                    updateState(AppState.IDLE, "")
                }
            }
        }
        contactsHelper = ContactsHelper(this)
        smsHelper = SmsHelper(this)

        speech = SpeechHandler(
            context = this,
            onIntent = ::handleIntent,
            onListeningStarted = {
                if (appState != AppState.INCOMING_CALL &&
                    appState != AppState.IN_CALL &&
                    appState != AppState.COMPOSING_SMS &&
                    appState != AppState.CONFIRMING_SMS &&
                    appState != AppState.CONFIRMING_SETTINGS) {
                    updateState(AppState.LISTENING, "")
                }
                playListeningBeep()
            },
            onListeningEnded = { /* state will change via onIntent */ },
            onError = { msg ->
                Log.e(TAG, "STT error: $msg")
                tts.speak(getString(R.string.tts_sorry))
                updateState(AppState.IDLE, "")
            }
        )

        // Load contacts in background
        Thread { contactsHelper.loadContacts() }.start()

        // Restore smart mode flag after service restart
        speech.smartMode = useSmartMode

        // First-launch welcome — plays bundled Deepgram audio, no network needed
        if (!prefs.getBoolean("welcome_spoken", false)) {
            prefs.edit().putBoolean("welcome_spoken", true).apply()
            playBundledWelcome()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> startListening()
            ACTION_INCOMING_CALL -> {
                val caller = intent.getStringExtra(EXTRA_CALLER_NAME) ?: ""
                onCallStateChanged(AppCallState.INCOMING, caller)
            }
            ACTION_INCOMING_SMS -> {
                val sender = intent.getStringExtra(EXTRA_SMS_SENDER) ?: ""
                val body = intent.getStringExtra(EXTRA_SMS_BODY) ?: ""
                onIncomingSms(sender, body)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        speech.destroy()
        tts.shutdown()
        stopRinging()
        wakeLock?.release()
        instance = null
        Log.d(TAG, "VoiceService destroyed — will restart via START_STICKY")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listening
    // ─────────────────────────────────────────────────────────────────────────

    fun startListening() {
        // Second tap while listening or greeting TTS = cancel
        if (speech.isListening || appState == AppState.LISTENING) {
            speech.stopListening()
            tts.cancelAll()
            updateState(AppState.IDLE, "")
            return
        }
        // Tap while speaking = cancel
        if (tts.isSpeaking || appState == AppState.SPEAKING) {
            tts.cancelAll()
            updateState(AppState.IDLE, "")
            return
        }

        if (appState == AppState.IN_CALL) {
            tts.speak(getString(R.string.tts_in_call_options)) {
                speech.startListening()
            }
            return
        }
        if (appState == AppState.INCOMING_CALL) {
            tts.speak(getString(R.string.tts_incoming_call, lastIncomingCaller)) {
                speech.startListening()
            }
            return
        }

        updateState(AppState.LISTENING, "")
        val greeting = if (useSmartMode) getString(R.string.tts_greeting_smart)
                       else getString(R.string.tts_greeting)
        tts.speak(greeting) {
            speech.startListening()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intent dispatch
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleIntent(intent: VoiceIntent) {
        Log.d(TAG, "handleIntent: $intent")
        // If composing an SMS, treat speech as the draft message body then confirm
        if (appState == AppState.COMPOSING_SMS) {
            val body = when (intent) {
                is VoiceIntent.Timeout -> {
                    // Re-prompt
                    tts.speak(getString(R.string.tts_say_your_message)) { speech.startListening() }
                    return
                }
                is VoiceIntent.Unknown -> intent.raw
                else -> (intent as? VoiceIntent.Unknown)?.raw ?: intent.javaClass.simpleName
            }
            val contact = pendingSmsContact ?: run {
                updateState(AppState.IDLE, ""); return
            }
            speech.rawMode = false
            speech.smartModeSuspended = true  // yes/no needs keyword matching, not Claude
            pendingSmsOutbodyDraft = body
            updateState(AppState.CONFIRMING_SMS, contact.name)
            tts.speak("Your message to ${contact.name} says: $body. Ready to send it?") {
                speech.startListening()
            }
            return
        }

        // If confirming an SMS, handle yes/no
        if (appState == AppState.CONFIRMING_SMS) {
            val contact = pendingSmsContact
            when (intent) {
                is VoiceIntent.Answer -> { // "yes"
                    speech.smartModeSuspended = false
                    if (contact != null) {
                        smsHelper.sendSms(contact.number, pendingSmsOutbodyDraft)
                        tts.speak(getString(R.string.tts_message_sent))
                    }
                    pendingSmsContact = null
                    pendingSmsOutbodyDraft = ""
                    updateState(AppState.IDLE, "")
                }
                is VoiceIntent.Reject -> { // "no" — re-ask for message
                    speech.smartModeSuspended = false
                    speech.rawMode = true
                    updateState(AppState.COMPOSING_SMS, contact?.name ?: "")
                    tts.speak(getString(R.string.tts_say_your_message)) { speech.startListening() }
                }
                is VoiceIntent.Timeout -> {
                    // Re-read the confirmation
                    tts.speak("Your message to ${contact?.name} says: $pendingSmsOutbodyDraft. Ready to send it?") {
                        speech.startListening()
                    }
                }
                else -> {
                    tts.speak("Say yes to send or no to re-record.") { speech.startListening() }
                }
            }
            return
        }
        when (intent) {
            is VoiceIntent.Call -> handleCallIntent(intent.target)
            is VoiceIntent.Answer -> handleAnswer()
            is VoiceIntent.HangUp -> handleHangUp()
            is VoiceIntent.Reject -> handleReject()
            is VoiceIntent.ReadSms -> handleReadSms()
            is VoiceIntent.SendSms -> handleSendSms(intent.contactName)
            is VoiceIntent.Time -> handleTime()
            is VoiceIntent.Date -> handleDate()
            is VoiceIntent.MissedCalls -> handleMissedCalls()
            is VoiceIntent.Help -> handleHelp()
            is VoiceIntent.OpenContacts -> {
                tts.speak("Opening contacts.")
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    type = "vnd.android.cursor.dir/contact"
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(i)
                updateState(AppState.IDLE, "")
            }
            is VoiceIntent.OpenSettings -> {
                updateState(AppState.CONFIRMING_SETTINGS, "")
                speech.smartModeSuspended = true
                tts.speak("This screen requires your carer to operate. Are you sure you want to continue? Say yes or no.") {
                    speech.startListening()
                }
            }
            is VoiceIntent.EnableCloudTts -> {
                tts.useCloudTts = true
                tts.speak("Cloud voice is now on.")
                updateState(AppState.IDLE, "")
            }
            is VoiceIntent.DisableCloudTts -> {
                tts.useCloudTts = false
                tts.speak("Switched back to local voice.")
                updateState(AppState.IDLE, "")
            }
            is VoiceIntent.Timeout -> {
                when (appState) {
                    AppState.INCOMING_CALL -> announceIncomingCall(lastIncomingCaller)
                    AppState.LISTENING -> updateState(AppState.IDLE, "")
                    else -> updateState(AppState.IDLE, "")
                }
            }
            is VoiceIntent.DirectAnswer -> {
                // Stay in PROCESSING — onSpeakingChanged will transition to SPEAKING then IDLE
                tts.speak(intent.response)
            }
            is VoiceIntent.EnableSmartMode -> {
                useSmartMode = true
                speech.smartMode = true
                tts.speak("Smart mode is now on. I'll use AI to understand natural speech.")
                updateState(AppState.IDLE, "")
            }
            is VoiceIntent.DisableSmartMode -> {
                useSmartMode = false
                speech.smartMode = false
                tts.speak("Switched back to basic commands.")
                updateState(AppState.IDLE, "")
            }
            is VoiceIntent.Cancel -> {
                // Also handles cancelling settings confirmation
                if (appState == AppState.CONFIRMING_SETTINGS) {
                    speech.smartModeSuspended = false
                    tts.speak("Cancelled.")
                    updateState(AppState.IDLE, "")
                    return
                }
                // Universal escape — clean up any active flow and return to idle
                pendingSmsContact = null
                pendingSmsOutbodyDraft = ""
                speech.rawMode = false
                speech.smartModeSuspended = false
                tts.cancelAll()
                speech.stopListening()
                tts.speak("Cancelled.")
                updateState(AppState.IDLE, "")
            }
            is VoiceIntent.Unknown -> {
                if (appState == AppState.CONFIRMING_SETTINGS) {
                    // Didn't say yes or no clearly — re-ask
                    tts.speak("Say yes to open settings with your carer, or no to cancel.") {
                        speech.startListening()
                    }
                    return
                }
                if (appState == AppState.INCOMING_CALL) {
                    announceIncomingCall(lastIncomingCaller)
                } else if (useSmartMode && claudeParser != null) {
                    // Show thinking indicator while Claude processes (screen shows "Thinking…")
                    updateState(AppState.PROCESSING, "")
                    val raw = intent.raw
                    val contacts = contactsHelper.getContactNames()
                    Thread {
                        val claudeIntent = claudeParser?.parse(raw, contacts)
                            ?: VoiceIntent.Unknown(raw)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (claudeIntent is VoiceIntent.Unknown) {
                                tts.speak(getString(R.string.tts_sorry))
                                updateState(AppState.IDLE, "")
                            } else {
                                handleIntent(claudeIntent)
                            }
                        }
                    }.start()
                } else {
                    tts.speak(getString(R.string.tts_sorry))
                    updateState(AppState.IDLE, "")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Call handling
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleCallIntent(target: String) {
        if (target.isEmpty()) {
            tts.speak(getString(R.string.tts_sorry))
            updateState(AppState.IDLE, "")
            return
        }

        // Direct number?
        if (contactsHelper.isPhoneNumber(target)) {
            dialNumber(target, target)
            return
        }

        val matches = contactsHelper.findContacts(target)
        when {
            matches.isEmpty() -> {
                tts.speak(getString(R.string.tts_contact_not_found, target))
                updateState(AppState.IDLE, "")
            }
            matches.size == 1 -> {
                dialNumber(matches.first().number, matches.first().name)
            }
            else -> {
                // MVP: call first match; Phase 2 adds disambiguation
                val first = matches.first()
                dialNumber(first.number, first.name)
            }
        }
    }

    private fun dialNumber(number: String, displayName: String) {
        updateState(AppState.PROCESSING, displayName)
        tts.speak(getString(R.string.tts_calling, displayName))
        try {
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            val uri = Uri.fromParts("tel", number, null)
            telecom.placeCall(uri, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to place call: ${e.message}")
            tts.speak(getString(R.string.tts_call_failed))
            updateState(AppState.IDLE, "")
        }
    }

    private fun handleAnswer() {
        if (appState == AppState.CONFIRMING_SETTINGS) {
            speech.smartModeSuspended = false
            tts.speak("Opening settings. Ask your carer to help.")
            val i = android.content.Intent(this, SettingsActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(i)
            updateState(AppState.IDLE, "")
            return
        }
        if (appState != AppState.INCOMING_CALL) {
            tts.speak(getString(R.string.tts_sorry))
            return
        }
        tts.speak(getString(R.string.tts_call_answered))
        InCallHandler.instance?.answerCall()
    }

    private fun handleReject() {
        if (appState == AppState.CONFIRMING_SETTINGS) {
            speech.smartModeSuspended = false
            tts.speak("Cancelled.")
            updateState(AppState.IDLE, "")
            return
        }
        if (appState != AppState.INCOMING_CALL) {
            tts.speak(getString(R.string.tts_sorry))
            return
        }
        tts.speak(getString(R.string.tts_call_rejected))
        InCallHandler.instance?.rejectCall()
        updateState(AppState.IDLE, "")
    }

    private fun handleHangUp() {
        if (appState != AppState.IN_CALL && appState != AppState.DIALLING) {
            tts.speak(getString(R.string.tts_sorry))
            return
        }
        InCallHandler.instance?.hangUp()
    }

    /** Called by InCallHandler / CallManager when call state changes. */
    fun onCallStateChanged(state: AppCallState, callerName: String) {
        Log.d(TAG, "onCallStateChanged: $state ($callerName)")
        when (state) {
            AppCallState.INCOMING -> {
                // Ignore while already in a call
                if (appState == AppState.IN_CALL || appState == AppState.DIALLING) return
                if (appState == AppState.INCOMING_CALL) {
                    // Second fire (InCallHandler has better name) — update if improved
                    if (!contactsHelper.isPhoneNumber(callerName) && callerName != lastIncomingCaller) {
                        lastIncomingCaller = callerName
                        updateState(AppState.INCOMING_CALL, callerName)
                        tts.cancelAll()
                        announceIncomingCall(callerName)
                    }
                    return
                }
                lastIncomingCaller = callerName
                updateState(AppState.INCOMING_CALL, callerName)
                speech.smartModeSuspended = true
                startRinging()
                announceIncomingCall(callerName)
            }
            AppCallState.DIALLING -> {
                updateState(AppState.DIALLING, callerName)
                tts.speak(getString(R.string.tts_calling, callerName))
            }
            AppCallState.IN_CALL -> {
                speech.smartModeSuspended = false
                stopRinging()
                playConnectedTone()
                updateState(AppState.IN_CALL, callerName)
                tts.speak(getString(R.string.tts_call_connected))
            }
            AppCallState.ENDED -> {
                if (appState == AppState.IDLE) return  // already handled
                speech.smartModeSuspended = false
                stopRinging()
                tts.speak(getString(R.string.tts_call_ended))
                updateState(AppState.IDLE, "")
            }
            AppCallState.IDLE -> {
                if (appState == AppState.IDLE) return  // already idle
                stopRinging()
                updateState(AppState.IDLE, "")
            }
        }
    }

    fun onCallFailed() {
        tts.speak(getString(R.string.tts_call_failed))
        updateState(AppState.IDLE, "")
    }

    private fun startRinging() {
        stopRinging() // ensure any previous player is released before starting a new one
        // Mute system ring stream to suppress OEM ringtones, then play our custom one
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        savedRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        try {
            val afd = resources.openRawResourceFd(R.raw.ringtone)
            ringtonePlayer = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(0.1f, 0.1f)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ringtone: ${e.message}")
        }
    }

    private fun stopRinging() {
        ringtonePlayer?.stop()
        ringtonePlayer?.release()
        ringtonePlayer = null
        // Restore system ring volume
        if (savedRingVolume >= 0) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_RING, savedRingVolume, 0)
            savedRingVolume = -1
        }
    }

    /** Plays the pre-generated welcome MP3 bundled in res/raw — instant, no network needed. */
    private fun playBundledWelcome() {
        try {
            val afd = resources.openRawResourceFd(R.raw.welcome) ?: return
            val player = android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play bundled welcome: ${e.message}")
        }
    }

    private fun playListeningBeep() {
        try {
            val afd = resources.openRawResourceFd(R.raw.mic_beep) ?: return
            android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play mic beep: ${e.message}")
        }
    }

    private fun playConnectedTone() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 400)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ toneGen.release() }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play connected tone: ${e.message}")
        }
    }


    private fun announceIncomingCall(callerName: String) {
        // Stop any in-progress TTS (e.g. Deepgram cloud player) before announcing
        tts.stop()
        tts.speak(getString(R.string.tts_incoming_call, callerName)) {
            speech.startListening()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMS handling
    // ─────────────────────────────────────────────────────────────────────────

    fun onIncomingSms(sender: String, body: String) {
        pendingSmsSender = sender
        pendingSmsBody = body
        updateState(AppState.INCOMING_SMS, sender)
        val friendlyName = contactsHelper.findByNumber(sender)?.name ?: sender
        tts.speak("You have a message from $friendlyName. Say read it to hear it.") {
            speech.startListening()
        }
    }

    private fun handleReadSms() {
        if (pendingSmsBody.isNotEmpty()) {
            val friendlyName = contactsHelper.findByNumber(pendingSmsSender)?.name ?: pendingSmsSender
            tts.speak(getString(R.string.tts_message_from, friendlyName, pendingSmsBody))
            pendingSmsSender = ""
            pendingSmsBody = ""
            updateState(AppState.IDLE, "")
            return
        }

        val messages = smsHelper.getUnreadMessages()
        if (messages.isEmpty()) {
            tts.speak(getString(R.string.tts_no_unread_messages))
            updateState(AppState.IDLE, "")
        } else {
            val total = messages.size
            val recent = messages.takeLast(3)
            val intro = if (total > 3) "You have $total unread messages. Here are the 3 most recent. "
                        else ""
            val text = intro + recent.joinToString(". Next message. ") { msg ->
                val name = contactsHelper.findByNumber(msg.sender)?.name ?: msg.sender
                "From $name. ${msg.body}"
            }
            tts.speak(text)  // onSpeakingChanged handles → SPEAKING → IDLE
        }
    }

    private fun handleSendSms(contactName: String) {
        if (contactName.isEmpty()) {
            tts.speak(getString(R.string.tts_sorry))
            updateState(AppState.IDLE, "")
            return
        }
        val contact = contactsHelper.findBestContact(contactName)
        if (contact == null) {
            tts.speak(getString(R.string.tts_contact_not_found, contactName))
            updateState(AppState.IDLE, "")
            return
        }
        pendingSmsContact = contact
        updateState(AppState.COMPOSING_SMS, contact.name)
        speech.rawMode = true  // next STT result = raw message body, not a command
        tts.speak(getString(R.string.tts_say_your_message)) {
            speech.startListening()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility intents
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleTime() {
        val time = SimpleDateFormat("h:mm a", Locale.UK).format(Date())
        tts.speak(getString(R.string.tts_time, time))
        updateState(AppState.IDLE, "")
    }

    private fun handleDate() {
        val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.UK).format(Date())
        tts.speak(getString(R.string.tts_date, date))
        updateState(AppState.IDLE, "")
    }

    private fun handleMissedCalls() {
        // Simple call log query
        val projection = arrayOf(
            android.provider.CallLog.Calls.CACHED_NAME,
            android.provider.CallLog.Calls.NUMBER,
            android.provider.CallLog.Calls.DATE
        )
        val cursor = contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            projection,
            "${android.provider.CallLog.Calls.TYPE} = ${android.provider.CallLog.Calls.MISSED_TYPE} AND ${android.provider.CallLog.Calls.NEW} = 1",
            null,
            "${android.provider.CallLog.Calls.DATE} DESC"
        )
        val missedCount = cursor?.count ?: 0
        val mostRecent = if (cursor != null && cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
            val numIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
            cursor.getString(nameIdx)?.takeIf { it.isNotEmpty() }
                ?: cursor.getString(numIdx)
                ?: "Unknown"
        } else null
        cursor?.close()

        when {
            missedCount == 0 -> tts.speak(getString(R.string.tts_no_missed_calls))
            missedCount == 1 -> tts.speak(getString(R.string.tts_missed_calls, missedCount, mostRecent ?: ""))
            else -> tts.speak(getString(R.string.tts_missed_calls_plural, missedCount, mostRecent ?: ""))
        }
        updateState(AppState.IDLE, "")
    }

    private fun handleHelp() {
        // Don't set IDLE here — onSpeakingChanged handles PROCESSING → SPEAKING → IDLE
        // This prevents showing the default screen while Deepgram downloads the long help text
        tts.speak(getString(R.string.tts_help))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State machine
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateState(newState: AppState, extra: String) {
        appState = newState
        stateListener?.invoke(newState, extra)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification / Wake lock
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoicePhone:WakeLock")
        wakeLock?.acquire(Long.MAX_VALUE)
    }
}

/** App UI states — drives what MainActivity shows. */
enum class AppState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    IN_CALL,
    DIALLING,
    INCOMING_CALL,
    INCOMING_SMS,
    COMPOSING_SMS,
    CONFIRMING_SMS,
    CONFIRMING_SETTINGS
}
