package com.voicephone

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

/**
 * In-app settings for carers — visual toggles for cloud TTS and smart mode.
 * Accessible via voice command "app settings" or "voicephone settings".
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_HOME_ROLE = 103
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val switchCloudTts = findViewById<Switch>(R.id.switchCloudTts)
        val switchSmartMode = findViewById<Switch>(R.id.switchSmartMode)
        val btnDone = findViewById<Button>(R.id.btnDone)

        val prefs = getSharedPreferences("voicephone_prefs", MODE_PRIVATE)

        // Load current state
        switchCloudTts.isChecked = prefs.getBoolean("use_cloud_tts", false)
        switchSmartMode.isChecked = prefs.getBoolean("use_smart_mode", false)

        switchCloudTts.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("use_cloud_tts", checked).apply()
            VoiceService.instance?.tts?.useCloudTts = checked
        }

        switchSmartMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("use_smart_mode", checked).apply()
            VoiceService.instance?.let { service ->
                service.useSmartMode = checked
                service.speech.smartMode = checked
            }
        }

        findViewById<Button>(R.id.btnOpenContacts).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                type = "vnd.android.cursor.dir/contact"
            })
        }

        findViewById<Button>(R.id.btnPhoneSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        findViewById<Button>(R.id.btnSetHomeScreen).setOnClickListener {
            openDefaultHomeSettings()
        }

        findViewById<Button>(R.id.btnResetApp).setOnClickListener {
            // Clear all preferences — triggers onboarding on next launch
            getSharedPreferences("voicephone_prefs", MODE_PRIVATE).edit().clear().apply()
            // Delete TTS cache files
            filesDir.listFiles { f -> f.name.startsWith("tts_cache_") }?.forEach { it.delete() }
            // Restart to onboarding
            VoiceService.instance?.let { stopService(Intent(this, VoiceService::class.java)) }
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }

        btnDone.setOnClickListener { finish() }
    }

    private fun openDefaultHomeSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                        REQUEST_HOME_ROLE
                    )
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // No additional action needed — the role change is reflected system-wide immediately
    }
}
