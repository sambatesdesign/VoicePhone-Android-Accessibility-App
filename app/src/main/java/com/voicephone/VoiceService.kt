package com.voicephone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
    private lateinit var speech: SpeechHandler
    lateinit var contactsHelper: ContactsHelper
        private set
    private lateinit var smsHelper: SmsHelper

    // ── State ────────────────────────────────────────────────────────────────
    private var appState: AppState = AppState.IDLE
    private var pendingSmsSender: String = ""
    private var pendingSmsBody: String = ""
    private var lastIncomingCaller: String = ""

    /** Activity registers here to receive state updates for the UI. */
    var stateListener: ((AppState, String) -> Unit)? = null

    private var wakeLock: PowerManager.WakeLock? = null

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

        tts = TtsManager(this)
        contactsHelper = ContactsHelper(this)
        smsHelper = SmsHelper(this)

        speech = SpeechHandler(
            context = this,
            onIntent = ::handleIntent,
            onListeningStarted = { updateState(AppState.LISTENING, "") },
            onListeningEnded = { /* state will change via onIntent */ },
            onError = { msg ->
                Log.e(TAG, "STT error: $msg")
                tts.speak(getString(R.string.tts_sorry))
                updateState(AppState.IDLE, "")
            }
        )

        // Load contacts in background
        Thread { contactsHelper.loadContacts() }.start()
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
        wakeLock?.release()
        instance = null
        Log.d(TAG, "VoiceService destroyed — will restart via START_STICKY")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listening
    // ─────────────────────────────────────────────────────────────────────────

    fun startListening() {
        if (appState == AppState.IN_CALL) {
            // In-call: just remind the user of options
            tts.speak(getString(R.string.tts_in_call_options))
            return
        }
        if (appState == AppState.INCOMING_CALL) {
            tts.speak(getString(R.string.tts_incoming_call, lastIncomingCaller))
            return
        }
        if (speech.isListening || tts.isSpeaking) return

        updateState(AppState.LISTENING, "")
        val greeting = if (appState == AppState.IDLE) getString(R.string.tts_greeting) else getString(R.string.tts_greeting)
        tts.speak(greeting) {
            speech.startListening()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intent dispatch
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleIntent(intent: VoiceIntent) {
        Log.d(TAG, "handleIntent: $intent")
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
            is VoiceIntent.Timeout -> {
                tts.speak(getString(R.string.tts_timeout))
                updateState(AppState.IDLE, "")
            }
            is VoiceIntent.Unknown -> {
                tts.speak(getString(R.string.tts_sorry))
                updateState(AppState.IDLE, "")
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
        if (appState != AppState.INCOMING_CALL) {
            tts.speak(getString(R.string.tts_sorry))
            return
        }
        tts.speak(getString(R.string.tts_call_answered))
        InCallHandler.instance?.answerCall()
    }

    private fun handleReject() {
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
                lastIncomingCaller = callerName
                updateState(AppState.INCOMING_CALL, callerName)
                announceIncomingCall(callerName)
            }
            AppCallState.DIALLING -> {
                updateState(AppState.DIALLING, callerName)
            }
            AppCallState.IN_CALL -> {
                updateState(AppState.IN_CALL, callerName)
                tts.speak(getString(R.string.tts_call_connected))
            }
            AppCallState.ENDED -> {
                tts.speak(getString(R.string.tts_call_ended))
                updateState(AppState.IDLE, "")
            }
            AppCallState.IDLE -> {
                updateState(AppState.IDLE, "")
            }
        }
    }

    fun onCallFailed() {
        tts.speak(getString(R.string.tts_call_failed))
        updateState(AppState.IDLE, "")
    }

    private fun announceIncomingCall(callerName: String) {
        tts.speak(getString(R.string.tts_incoming_call, callerName))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMS handling
    // ─────────────────────────────────────────────────────────────────────────

    fun onIncomingSms(sender: String, body: String) {
        pendingSmsSender = sender
        pendingSmsBody = body
        updateState(AppState.INCOMING_SMS, sender)
        val friendlyName = contactsHelper.findBestContact(sender)?.name ?: sender
        tts.speak("You have a message from $friendlyName. Say read it to hear it.") {
            speech.startListening()
        }
    }

    private fun handleReadSms() {
        if (pendingSmsBody.isNotEmpty()) {
            val friendlyName = contactsHelper.findBestContact(pendingSmsSender)?.name ?: pendingSmsSender
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
            val text = messages.joinToString(". Next message. ") { msg ->
                val name = contactsHelper.findBestContact(msg.sender)?.name ?: msg.sender
                "From $name. ${msg.body}"
            }
            tts.speak(text)
            updateState(AppState.IDLE, "")
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
        tts.speak(getString(R.string.tts_say_your_message)) {
            // Listen for the message content
            speech.startListening()
        }
        // After speech is captured, next intent will be an Unknown/raw utterance
        // This is handled as a two-step: a simple state flag would extend this in Phase 2
        // For MVP: the next raw utterance is treated as the message body
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
        tts.speak(getString(R.string.tts_help))
        updateState(AppState.IDLE, "")
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

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
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
    IN_CALL,
    DIALLING,
    INCOMING_CALL,
    INCOMING_SMS
}
