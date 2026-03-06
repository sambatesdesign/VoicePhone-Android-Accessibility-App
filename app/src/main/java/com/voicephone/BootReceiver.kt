package com.voicephone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts VoiceService after device reboot.
 * Without this, the foreground service dies on reboot and the app won't respond
 * to touch until the user manually opens it.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.d(TAG, "Boot completed — starting VoiceService")
        context.startForegroundService(Intent(context, VoiceService::class.java))
    }
}
