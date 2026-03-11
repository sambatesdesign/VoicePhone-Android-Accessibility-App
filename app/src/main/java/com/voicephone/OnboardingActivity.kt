package com.voicephone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Multi-screen onboarding flow with 5 pages.
 *
 * Pages:
 *   0 — Welcome
 *   1 — Features
 *   2 — Permissions
 *   3 — Setup (default dialer + home)
 *   4 — All Done
 *
 * Navigation uses a simple show/hide approach on a FrameLayout —
 * no ViewPager2 or RecyclerView required.
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OnboardingActivity"
        private const val TOTAL_PAGES = 8

        // Permissions shown on page 3
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE
        )

        // Permission metadata for building the list rows
        private data class PermInfo(
            val permission: String,
            val emoji: String,
            val label: String,
            val reason: String
        )

        private val PERM_INFO = listOf(
            PermInfo(Manifest.permission.CALL_PHONE,       "📞", "Phone",        "Make and answer calls"),
            PermInfo(Manifest.permission.READ_CONTACTS,    "👤", "Contacts",     "Find people by name"),
            PermInfo(Manifest.permission.READ_SMS,         "💬", "Messages",     "Read and send texts"),
            PermInfo(Manifest.permission.RECORD_AUDIO,     "🎤", "Microphone",   "Hear voice commands"),
            PermInfo(Manifest.permission.READ_CALL_LOG,    "📋", "Call History", "Report missed calls"),
            PermInfo(Manifest.permission.READ_PHONE_STATE, "📱", "Phone State",  "Select the correct SIM")
        )
    }

    // ── State ────────────────────────────────────────────────────────────────
    private var currentPage = 0

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var pages: List<View>
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button
    private lateinit var dotsContainer: LinearLayout

    // Permission status dots (one TextView per permission, same order as PERM_INFO)
    private val permStatusViews = mutableListOf<TextView>()

    // Setup page status views
    private lateinit var tvDialerDone: TextView
    private lateinit var btnSetDialer: Button
    private lateinit var tvHomeDone: TextView
    private lateinit var btnSetHome: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Permission launcher ───────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPermissionStatuses()
        updateNextButton()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        hideSystemNav()

        // Find stable views
        btnBack = findViewById(R.id.btnBack)
        btnNext = findViewById(R.id.btnNext)
        dotsContainer = findViewById(R.id.dotsContainer)

        // Wire up page views in order
        pages = listOf(
            findViewById(R.id.page0),
            findViewById(R.id.page1),
            findViewById(R.id.page2),
            findViewById(R.id.page3),
            findViewById(R.id.page4),
            findViewById(R.id.page5),
            findViewById(R.id.page6),
            findViewById(R.id.page7)
        )

        // Setup page views
        tvDialerDone = findViewById(R.id.tvDialerDone)
        btnSetDialer = findViewById(R.id.btnSetDialer)
        tvHomeDone   = findViewById(R.id.tvHomeDone)
        btnSetHome   = findViewById(R.id.btnSetHome)

        buildPermissionRows()
        buildPageDots()

        // Button listeners
        btnBack.setOnClickListener { goBack() }
        btnNext.setOnClickListener { goNext() }
        findViewById<Button>(R.id.btnGetStarted).setOnClickListener { showPage(1) }
        findViewById<Button>(R.id.btnAllowAll).setOnClickListener { requestAllMissingPermissions() }
        btnSetDialer.setOnClickListener { openDefaultDialerSettings() }
        btnSetHome.setOnClickListener { openDefaultHomeSettings() }
        findViewById<Button>(R.id.btnStartVoicePhone).setOnClickListener { launchMainActivity() }

        // AI upsell page — trial enables both cloud TTS and smart mode
        findViewById<Button>(R.id.btnStartTrial).setOnClickListener {
            getSharedPreferences("voicephone_prefs", MODE_PRIVATE).edit()
                .putBoolean("use_cloud_tts", true)
                .putBoolean("use_smart_mode", true)
                .apply()
            showPage(7)
        }
        findViewById<android.widget.TextView>(R.id.tvSkipAi).setOnClickListener {
            showPage(7)
        }

        showPage(0)
        playWelcomeChime()
    }

    override fun onResume() {
        super.onResume()
        // Refresh permission and setup states whenever we return from a system dialog
        refreshPermissionStatuses()
        refreshSetupStatuses()
        updateNextButton()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Page navigation
    // ─────────────────────────────────────────────────────────────────────────

    private fun showPage(index: Int) {
        currentPage = index.coerceIn(0, TOTAL_PAGES - 1)

        // Show / hide pages
        pages.forEachIndexed { i, view ->
            view.visibility = if (i == currentPage) View.VISIBLE else View.GONE
        }

        // Update dots
        updateDots()

        // Back button: hidden on page 0, invisible on welcome (page 0 uses btnGetStarted)
        btnBack.visibility = if (currentPage == 0) View.INVISIBLE else View.VISIBLE

        // Next button label
        btnNext.text = if (currentPage == TOTAL_PAGES - 1) "Done" else "Next"

        // Hide bottom nav entirely on page 0 (uses full-width Get Started button instead)
        // and on page 4 (uses Start VoicePhone button)
        val hideNavButtons = currentPage == 0 || currentPage == TOTAL_PAGES - 1
        btnNext.visibility = if (hideNavButtons) View.INVISIBLE else View.VISIBLE
        btnBack.visibility = when {
            currentPage == 0 -> View.INVISIBLE
            hideNavButtons   -> View.INVISIBLE
            else             -> View.VISIBLE
        }

        // Per-page side effects
        when (currentPage) {
            4 -> {
                refreshPermissionStatuses()
                updateNextButton()
            }
            5 -> {
                refreshSetupStatuses()
                updateNextButton()
            }
            7 -> {
                playCompletionChime()
            }
        }
    }

    private fun goNext() {
        if (currentPage < TOTAL_PAGES - 1) {
            showPage(currentPage + 1)
        }
    }

    private fun goBack() {
        if (currentPage > 0) {
            showPage(currentPage - 1)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Page dots
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildPageDots() {
        dotsContainer.removeAllViews()
        val sizePx = dpToPx(8)
        val marginPx = dpToPx(5)

        repeat(TOTAL_PAGES) { i ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).also {
                    it.setMargins(marginPx, 0, marginPx, 0)
                }
                background = ContextCompat.getDrawable(
                    this@OnboardingActivity,
                    if (i == 0) R.drawable.dot_filled else R.drawable.dot_hollow
                )
            }
            dotsContainer.addView(dot)
        }
    }

    private fun updateDots() {
        for (i in 0 until dotsContainer.childCount) {
            val dot = dotsContainer.getChildAt(i)
            dot.background = ContextCompat.getDrawable(
                this,
                if (i == currentPage) R.drawable.dot_filled else R.drawable.dot_hollow
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build permission rows programmatically (page 3)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildPermissionRows() {
        val container = findViewById<LinearLayout>(R.id.permissionsList)
        container.removeAllViews()
        permStatusViews.clear()

        val rowMarginPx = dpToPx(10)
        val padding = dpToPx(16)

        PERM_INFO.forEach { info ->
            // Outer row: dark card
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@OnboardingActivity, R.drawable.card_dark_rounded)
                setPadding(padding, padding, padding, padding)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, rowMarginPx) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    permissionLauncher.launch(arrayOf(info.permission))
                }
            }

            // Left: text block
            val textBlock = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val labelView = TextView(this).apply {
                text = "${info.emoji}  ${info.label}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, dpToPx(4)) }
            }

            val reasonView = TextView(this).apply {
                text = info.reason
                setTextColor(Color.parseColor("#AAAAAA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }

            textBlock.addView(labelView)
            textBlock.addView(reasonView)

            // Right: status indicator
            val statusView = TextView(this).apply {
                text = "●"
                setTextColor(Color.parseColor("#555555"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(dpToPx(8), 0, 0, 0) }
            }

            row.addView(textBlock)
            row.addView(statusView)
            container.addView(row)

            // Track the status view so we can update it
            permStatusViews.add(statusView)
        }
    }

    private fun refreshPermissionStatuses() {
        PERM_INFO.forEachIndexed { i, info ->
            val granted = ContextCompat.checkSelfPermission(this, info.permission) ==
                    PackageManager.PERMISSION_GRANTED
            val statusView = permStatusViews.getOrNull(i) ?: return@forEachIndexed
            if (granted) {
                statusView.text = "✓"
                statusView.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                statusView.text = "●"
                statusView.setTextColor(Color.parseColor("#555555"))
            }
        }
        // Update Allow All button to reflect current state
        val allGranted = allPermissionsGranted()
        val btnAllowAll = findViewById<Button>(R.id.btnAllowAll)
        if (allGranted) {
            btnAllowAll.text = "All set — Continue →"
            btnAllowAll.setOnClickListener { goNext() }
        } else {
            btnAllowAll.text = "Allow All"
            btnAllowAll.setOnClickListener { requestAllMissingPermissions() }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllMissingPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup page (page 4) — default dialer & home
    // ─────────────────────────────────────────────────────────────────────────

    private fun refreshSetupStatuses() {
        // Hide the whole phone app row once it's set — no need to keep showing it
        val dialerDone = isDefaultDialer()
        findViewById<android.view.View>(R.id.rowDefaultDialer).visibility =
            if (dialerDone) View.GONE else View.VISIBLE
        tvDialerDone.visibility = View.GONE  // not needed since row hides
        btnSetDialer.visibility = View.VISIBLE

        val homeDone = isDefaultHome()
        tvHomeDone.visibility = if (homeDone) View.VISIBLE else View.GONE
        btnSetHome.visibility = if (homeDone) View.GONE else View.VISIBLE
    }

    private fun isDefaultDialer(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            telecom.defaultDialerPackage == packageName
        } else {
            false
        }
    }

    private fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == packageName
    }

    private fun openDefaultDialerSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            startActivity(intent)
        }
    }

    private fun openDefaultHomeSettings() {
        // ACTION_HOME_SETTINGS is API 21+; fall back to manage default apps
        val intent = try {
            Intent(Settings.ACTION_HOME_SETTINGS)
        } catch (e: Exception) {
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        }
        startActivity(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Next button enable/disable logic
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateNextButton() {
        val enabled = when (currentPage) {
            4 -> allPermissionsGranted()   // must have all permissions to advance
            else -> true
        }
        btnNext.isEnabled = enabled
        btnNext.setTextColor(if (enabled) Color.WHITE else Color.parseColor("#555555"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch main activity
    // ─────────────────────────────────────────────────────────────────────────

    private fun launchMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chimes (background thread, plain Thread + ToneGenerator)
    // ─────────────────────────────────────────────────────────────────────────

    private fun playWelcomeChime() {
        try {
            val afd = resources.openRawResourceFd(R.raw.welcome_chime) ?: return
            android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Ignore audio failures — non-critical
        }
    }

    private fun playCompletionChime() {
        Thread {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
                tg.startTone(ToneGenerator.TONE_CDMA_ANSWER, 300)
                Thread.sleep(400)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
                Thread.sleep(300)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                Thread.sleep(400)
                tg.release()
            } catch (e: Exception) {
                // Ignore audio failures — non-critical
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemNav()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemNav() {
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }
}
