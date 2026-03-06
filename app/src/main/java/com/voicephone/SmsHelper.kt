package com.voicephone

import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

data class SmsMessage(val sender: String, val body: String, val timestamp: Long)

/**
 * Reads unread SMS and sends messages.
 * Phase 1 MVP: reading unread messages, sending to a contact.
 */
class SmsHelper(private val context: Context) {

    companion object {
        private const val TAG = "SmsHelper"
    }

    /** Fetch unread SMS, oldest first. */
    fun getUnreadMessages(): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.READ} = 0",
            null,
            "${Telephony.Sms.DATE} ASC"
        )
        cursor?.use {
            val addrIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                messages += SmsMessage(
                    sender = it.getString(addrIdx) ?: "Unknown",
                    body = it.getString(bodyIdx) ?: "",
                    timestamp = it.getLong(dateIdx)
                )
            }
        }
        Log.d(TAG, "Found ${messages.size} unread messages")
        return messages
    }

    /** Send an SMS. Returns true on success. */
    fun sendSms(toNumber: String, message: String): Boolean {
        return try {
            val manager = SmsManager.getDefault()
            val parts = manager.divideMessage(message)
            if (parts.size == 1) {
                manager.sendTextMessage(toNumber, null, message, null, null)
            } else {
                manager.sendMultipartTextMessage(toNumber, null, parts, null, null)
            }
            Log.d(TAG, "SMS sent to $toNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS: ${e.message}")
            false
        }
    }
}
