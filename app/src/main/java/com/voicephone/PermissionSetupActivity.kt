package com.voicephone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * First-launch permission setup flow.
 * Walks the user through each permission with TTS explanation before each dialog.
 * After all permissions granted, transitions to MainActivity permanently.
 */
class PermissionSetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PermissionSetupActivity"

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    private lateinit var tts: TtsManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d(TAG, "Permission results: $results")
        continueSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TtsManager(this)
        tts.speak(getString(R.string.tts_welcome)) {
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            continueSetup()
        } else {
            tts.speak(getString(R.string.tts_permission_phone)) {
                permissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun continueSetup() {
        when {
            !isBatteryOptimisationExempt() -> requestBatteryExemption()
            !isDefaultDialer() -> promptSetDefaultDialer()
            else -> finishSetup()
        }
    }

    private fun isBatteryOptimisationExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        tts.speak(getString(R.string.tts_setup_battery)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            continueSetup()
        }
    }

    private fun isDefaultDialer(): Boolean {
        val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        return telecom.defaultDialerPackage == packageName
    }

    private fun promptSetDefaultDialer() {
        tts.speak(getString(R.string.tts_setup_phone_app)) {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            startActivity(intent)
            finishSetup()
        }
    }

    private fun finishSetup() {
        tts.speak(getString(R.string.tts_setup_complete)) {
            tts.shutdown()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }
}
