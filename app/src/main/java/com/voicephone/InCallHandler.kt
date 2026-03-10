package com.voicephone

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log

/**
 * InCallService — required to be registered as the default phone app.
 * Intercepts all call events and routes state changes to [VoiceService].
 *
 * Android routes calls here when VoicePhone is the default dialler.
 */
class InCallHandler : InCallService() {

    companion object {
        private const val TAG = "InCallHandler"
        var instance: InCallHandler? = null
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call?, state: Int) {
            handleCallState(call, state)
        }
    }

    override fun onCallAdded(call: Call?) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded state=${call?.state}")
        call?.registerCallback(callCallback)
        handleCallState(call, call?.state ?: Call.STATE_NEW)
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved")
        call?.unregisterCallback(callCallback)
        VoiceService.instance?.onCallStateChanged(AppCallState.ENDED, "")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private fun handleCallState(call: Call?, state: Int) {
        val details = call?.details
        val number = details?.handle?.schemeSpecificPart ?: ""

        // Attempt to look up a friendly name for the number
        val service = VoiceService.instance
        val name = if (service != null && number.isNotEmpty()) {
            service.contactsHelper.findByNumber(number)?.name ?: number
        } else {
            number
        }

        when (state) {
            Call.STATE_RINGING -> {
                Log.d(TAG, "STATE_RINGING from $name")
                VoiceService.instance?.onCallStateChanged(AppCallState.INCOMING, name)
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                Log.d(TAG, "STATE_DIALING to $name")
                setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                VoiceService.instance?.onCallStateChanged(AppCallState.DIALLING, name)
            }
            Call.STATE_ACTIVE -> {
                Log.d(TAG, "STATE_ACTIVE with $name")
                setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                VoiceService.instance?.onCallStateChanged(AppCallState.IN_CALL, name)
            }
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                Log.d(TAG, "STATE_DISCONNECTED")
                VoiceService.instance?.onCallStateChanged(AppCallState.ENDED, "")
            }
        }
    }

    /** Answer the current ringing call. */
    fun answerCall() {
        calls.firstOrNull { it.state == Call.STATE_RINGING }
            ?.answer(0)
    }

    /** Reject the current ringing call. */
    fun rejectCall() {
        calls.firstOrNull { it.state == Call.STATE_RINGING }
            ?.reject(false, null)
    }

    /** Disconnect the active call. */
    fun hangUp() {
        calls.firstOrNull { it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING }
            ?.disconnect()
    }
}
