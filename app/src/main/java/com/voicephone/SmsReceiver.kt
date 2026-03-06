package com.voicephone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Wakes the app when an SMS arrives.
 * Extracts sender + body and routes to VoiceService for TTS announcement.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group multi-part messages by sender
        val byAddress = messages.groupBy { it.originatingAddress ?: "" }
        byAddress.forEach { (address, parts) ->
            val body = parts.joinToString("") { it.messageBody }
            Log.d(TAG, "SMS from $address: $body")

            val serviceIntent = Intent(context, VoiceService::class.java).apply {
                action = VoiceService.ACTION_INCOMING_SMS
                putExtra(VoiceService.EXTRA_SMS_SENDER, address)
                putExtra(VoiceService.EXTRA_SMS_BODY, body)
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
