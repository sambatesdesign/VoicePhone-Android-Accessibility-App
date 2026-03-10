package com.voicephone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Wakes the app on incoming call state changes.
 *
 * Note: When VoicePhone is set as the default phone app, InCallHandler
 * also receives these events. This receiver is a belt-and-braces fallback
 * to ensure VoiceService is started even if InCallHandler hasn't bound yet.
 */
class IncomingCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IncomingCallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        Log.d(TAG, "Phone state: $state, number: $incomingNumber")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Look up a friendly name if VoiceService is already running
                val name = VoiceService.instance
                    ?.contactsHelper
                    ?.findBestContact(incomingNumber)
                    ?.name
                    ?: incomingNumber

                // Ensure VoiceService is running and notify it
                val serviceIntent = Intent(context, VoiceService::class.java).apply {
                    action = VoiceService.ACTION_INCOMING_CALL
                    putExtra(VoiceService.EXTRA_CALLER_NAME, name)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // Call ended / rejected — VoiceService will also hear this via InCallHandler
            }
        }
    }
}
