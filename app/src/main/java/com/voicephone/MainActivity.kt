package com.voicephone

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Single activity. Owns no business logic — it's a pure view layer.
 *
 * State machine:
 *   IDLE → touch anywhere → LISTENING → PROCESSING → IDLE / IN_CALL / …
 *
 * All state transitions are driven by VoiceService callbacks via [stateListener].
 *
 * Connection to VoiceService uses the singleton [VoiceService.instance] — the service
 * is never bound (onBind returns null) so we poll briefly after starting it.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var listeningIndicator: View
    private lateinit var callLayout: View
    private lateinit var tvCallLabel: TextView
    private lateinit var tvCallName: TextView
    private lateinit var tvCallHint: TextView
    private lateinit var btnHangUp: Button
    private lateinit var btnAnswer: Button

    // ── Animation ──────────────────────────────────────────────────────────
    private var pulseAnimator: ValueAnimator? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on — core UX requirement
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        // Check minimum permissions — redirect to setup if missing
        if (!hasMinimumPermissions()) {
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        bindViews()
        setupTouchListener()
        startVoiceService()
    }

    override fun onResume() {
        super.onResume()
        attachToService()
        // Refresh contacts cache in case user added/edited contacts while away
        Thread { VoiceService.instance?.contactsHelper?.loadContacts() }.start()
    }

    override fun onPause() {
        super.onPause()
        // Keep stateListener registered so incoming call can update the UI
        // even when the activity is paused (it will call runOnUiThread)
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
        VoiceService.instance?.stateListener = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View wiring
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        listeningIndicator = findViewById(R.id.listeningIndicator)
        callLayout = findViewById(R.id.callLayout)
        tvCallLabel = findViewById(R.id.tvCallLabel)
        tvCallName = findViewById(R.id.tvCallName)
        tvCallHint = findViewById(R.id.tvCallHint)
        btnHangUp = findViewById(R.id.btnHangUp)
        btnAnswer = findViewById(R.id.btnAnswer)

        btnHangUp.setOnClickListener {
            InCallHandler.instance?.hangUp()
        }
        btnAnswer.setOnClickListener {
            InCallHandler.instance?.answerCall()
        }
    }

    private fun setupTouchListener() {
        findViewById<View>(R.id.root).setOnClickListener {
            Log.d(TAG, "Screen touched")
            val service = VoiceService.instance ?: run {
                startVoiceService()
                mainHandler.postDelayed({ VoiceService.instance?.startListening() }, 600)
                return@setOnClickListener
            }
            // During an active or dialling call, tap = hang up immediately
            if (service.currentAppState == AppState.IN_CALL || service.currentAppState == AppState.DIALLING) {
                InCallHandler.instance?.hangUp()
            } else {
                service.startListening()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service connection
    // ─────────────────────────────────────────────────────────────────────────

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** Attach the state listener to the running VoiceService singleton. */
    private fun attachToService() {
        val service = VoiceService.instance
        if (service != null) {
            service.stateListener = ::onStateChanged
        } else {
            // Service still starting — retry shortly
            mainHandler.postDelayed(::attachToService, 300)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State machine rendering
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by VoiceService whenever app state changes.
     * May arrive on any thread — always dispatches to main thread.
     */
    private fun onStateChanged(state: AppState, extra: String) {
        runOnUiThread {
            Log.d(TAG, "UI state → $state ($extra)")
            when (state) {
                AppState.IDLE -> showIdle()
                AppState.LISTENING -> showListening()
                AppState.PROCESSING -> showProcessing(extra)
                AppState.DIALLING -> showDialling(extra)
                AppState.IN_CALL -> showInCall(extra)
                AppState.INCOMING_CALL -> showIncomingCall(extra)
                AppState.INCOMING_SMS -> showIdle() // audio-only notification
                AppState.COMPOSING_SMS -> showListening() // mic active for message body
                AppState.CONFIRMING_SMS -> showListening() // mic active for yes/no
            }
        }
    }

    // ── State renderers ───────────────────────────────────────────────────

    private fun showIdle() {
        stopPulse()
        callLayout.visibility = View.GONE
        listeningIndicator.visibility = View.GONE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.touch_to_speak)
        startIdlePulse()
    }

    private fun showListening() {
        stopPulse()
        callLayout.visibility = View.GONE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.listening)
        listeningIndicator.visibility = View.VISIBLE
        startListeningPulse()
    }

    private fun showProcessing(name: String) {
        stopPulse()
        callLayout.visibility = View.GONE
        listeningIndicator.visibility = View.GONE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = if (name.isNotEmpty()) getString(R.string.tts_calling, name)
        else getString(R.string.processing)
    }

    private fun showDialling(name: String) {
        showCallScreen(
            label = getString(R.string.calling_label),
            name = name,
            hint = "",
            showHangUp = true,
            showAnswer = false
        )
    }

    private fun showInCall(name: String) {
        showCallScreen(
            label = getString(R.string.in_call_label),
            name = name,
            hint = getString(R.string.in_call_hint),
            showHangUp = true,
            showAnswer = false
        )
    }

    private fun showIncomingCall(callerName: String) {
        showCallScreen(
            label = getString(R.string.incoming_call_label),
            name = callerName,
            hint = getString(R.string.incoming_call_hint),
            showHangUp = true,
            showAnswer = true
        )
    }

    private fun showCallScreen(
        label: String,
        name: String,
        hint: String,
        showHangUp: Boolean,
        showAnswer: Boolean
    ) {
        stopPulse()
        listeningIndicator.visibility = View.GONE
        tvStatus.visibility = View.GONE
        callLayout.visibility = View.VISIBLE
        tvCallLabel.text = label
        tvCallName.text = name
        tvCallHint.text = hint
        btnHangUp.visibility = if (showHangUp) View.VISIBLE else View.GONE
        btnAnswer.visibility = if (showAnswer) View.VISIBLE else View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animations
    // ─────────────────────────────────────────────────────────────────────────

    private fun startIdlePulse() {
        pulseAnimator = ValueAnimator.ofFloat(0.6f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { tvStatus.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun startListeningPulse() {
        val scaleX = ObjectAnimator.ofFloat(listeningIndicator, "scaleX", 0.8f, 1.3f).apply {
            duration = 550
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val scaleY = ObjectAnimator.ofFloat(listeningIndicator, "scaleY", 0.8f, 1.3f).apply {
            duration = 550
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        scaleX.start()
        scaleY.start()
        pulseAnimator = scaleX // keep a reference so we can cancel
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        tvStatus.alpha = 1f
        listeningIndicator.scaleX = 1f
        listeningIndicator.scaleY = 1f
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission check
    // ─────────────────────────────────────────────────────────────────────────

    private fun hasMinimumPermissions(): Boolean {
        val required = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO
        )
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
