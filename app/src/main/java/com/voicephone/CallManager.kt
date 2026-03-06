package com.voicephone

import android.net.Uri
import android.os.Build
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * ConnectionService implementation — registers VoicePhone as the default
 * phone app and owns the call lifecycle.
 *
 * This is the most complex part of the stack. It does NOT handle UI directly;
 * it signals state changes to [VoiceService] via the singleton accessor
 * [VoiceService.instance].
 */
class CallManager : ConnectionService() {

    companion object {
        private const val TAG = "CallManager"

        /** Currently active connection, if any. */
        var activeConnection: VoiceConnection? = null
            private set

        fun disconnect() {
            activeConnection?.apply {
                setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                destroy()
            }
            activeConnection = null
        }
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateOutgoingConnection: ${request?.address}")
        val connection = VoiceConnection(request?.address?.schemeSpecificPart ?: "")
        connection.setDialing()
        activeConnection = connection

        VoiceService.instance?.onCallStateChanged(AppCallState.DIALLING, connection.callerName)
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateIncomingConnection: ${request?.address}")
        val number = request?.address?.schemeSpecificPart ?: ""
        val connection = VoiceConnection(number)
        connection.setRinging()
        activeConnection = connection

        VoiceService.instance?.onCallStateChanged(AppCallState.INCOMING, connection.callerName)
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "Outgoing connection failed")
        VoiceService.instance?.onCallFailed()
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single call connection. Tracks caller name for TTS announcements.
 */
class VoiceConnection(val number: String) : Connection() {

    var callerName: String = number  // overwritten by ContactsHelper lookup if possible

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = false
    }

    override fun onAnswer() {
        super.onAnswer()
        Log.d(TAG_CONN, "onAnswer")
        setActive()
        VoiceService.instance?.onCallStateChanged(AppCallState.IN_CALL, callerName)
    }

    override fun onReject() {
        super.onReject()
        Log.d(TAG_CONN, "onReject")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        CallManager.activeConnection = null
        VoiceService.instance?.onCallStateChanged(AppCallState.IDLE, "")
    }

    override fun onDisconnect() {
        super.onDisconnect()
        Log.d(TAG_CONN, "onDisconnect")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        CallManager.activeConnection = null
        VoiceService.instance?.onCallStateChanged(AppCallState.ENDED, "")
    }

    override fun onAbort() {
        super.onAbort()
        setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
        destroy()
        CallManager.activeConnection = null
    }

    companion object {
        private const val TAG_CONN = "VoiceConnection"
    }
}

/** Call states used throughout the app. */
enum class AppCallState {
    IDLE,
    DIALLING,
    INCOMING,
    IN_CALL,
    ENDED
}
