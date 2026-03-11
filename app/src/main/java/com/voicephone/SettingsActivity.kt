package com.voicephone

import android.content.Intent
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

        btnDone.setOnClickListener { finish() }
    }
}
