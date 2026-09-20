package com.jarvis.assistant.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.R
import com.jarvis.assistant.util.AnimUtils
import com.jarvis.assistant.util.EnvLoader
import com.jarvis.assistant.util.SimManager
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.util.pressFeedback
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import android.util.Log

class SettingsActivity : AppCompatActivity() {

    private lateinit var geminiKeyContainer: View
    private lateinit var apiKeyLabel: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var apiKeyVisibilityToggle: ImageButton
    private lateinit var userNameInput: EditText
    private lateinit var activeEngineTitleText: TextView
    private lateinit var activeEngineSubtext: TextView
    private lateinit var activeEngineBadge: TextView
    private lateinit var geminiVoiceChipsRow: LinearLayout
    private lateinit var simSpinner: Spinner
    private lateinit var geminiVoiceLabel: View
    private lateinit var permissionsBar: View
    private lateinit var personalitySegmented: SegmentedControl
    private lateinit var personalityDescriptionText: TextView
    private lateinit var homeStyleSegmented: SegmentedControl
    private lateinit var homeStyleDescriptionText: TextView
    private lateinit var themeSegmented: SegmentedControl
    private lateinit var themeDescriptionText: TextView
    private lateinit var appearanceThemeSectionLabel: View
    private lateinit var appearanceThemeCard: View
    private lateinit var saveButton: TextView
    private lateinit var backBtn: View
    private lateinit var headerSettingsIcon: View
    private lateinit var headerUpdateSearchBtn: View
    private lateinit var headerUpdateBadgeDot: View
    private lateinit var updateAppNotificationCard: View
    private lateinit var updateNotificationBadge: TextView
    private lateinit var updateNotificationTitle: TextView
    private lateinit var updateNotificationChangelog: TextView
    private lateinit var updateNotificationActionBtn: View
    private lateinit var settingsCheckUpdatesRow: View
    private lateinit var settingsUpdateStatusBadge: TextView
    private lateinit var settingsUpdateSearchIcon: ImageView

    private var isApiKeyVisible = false
    private var selectedVoiceIndex = 0
    private var selectedModelIndex = 0
    private var selectedPersonalityIndex = 0
    private var selectedHomeStyleIndex = 0 // 0 = Classic 3D Orb, 1 = Cyber HUD Blue
    private var selectedThemeIndex = 0 // 0 = Arc Blue (Default), 1 = Amber Gold
    private var selectedSimIndex = 0 // 0 = "Always ask"

    private val defaultGeminiModel = "models/gemini-3.1-flash-live-preview"

    private val geminiModelLabels = listOf(
        "Gemini 3.1 Flash Live (Native Audio)",
        "Gemini 2.5 Flash Live (Native Audio)"
    )
    private val geminiModelValues = listOf(
        "models/gemini-3.1-flash-live-preview",
        "models/gemini-2.5-flash-native-audio-preview-12-2025"
    )

    private val voiceLabels = listOf(
        "Puck", "Kore", "Charon", "Fenrir", "Zephyr", "Aoede", "Leda", "Orus"
    )
    private val voiceValues = voiceLabels

    private val personalityDescriptions = listOf(
        "JARVIS AI — Warm best friend tone, natural human flow with conversational speed. ⚡",
        "Warm, caring Hinglish companion with expressive replies. 💖",
        "Formal, precise English only. No emojis, straight to the point. 💼",
        "Friendly Hinglish/English mix — balanced and helpful. 🤖"
    )

    private val homeStyleDescriptions = listOf(
        "Arc 3D Preview (Classic Reactor with interactive controls) ⚡",
        "Cyber HUD (3D Telemetry & Real-Time Waveform) 🌐"
    )

    private val themeDescriptions = listOf(
        "Arc Blue Theme (Electric Cyan & Deep Space) ⚡",
        "Amber Gold Theme (Luxury Cyber Gold) 👑"
    )

    private var callCapableSims: List<SimManager.SimOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadPrefs()
        wireInteractions()
    }

    private fun initViews() {
        geminiKeyContainer = findViewById(R.id.geminiKeyContainer)
        apiKeyLabel = findViewById(R.id.apiKeyLabel)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        apiKeyVisibilityToggle = findViewById(R.id.apiKeyVisibilityToggle)
        userNameInput = findViewById(R.id.userNameInput)
        activeEngineTitleText = findViewById(R.id.activeEngineTitleText)
        activeEngineSubtext = findViewById(R.id.activeEngineSubtext)
        activeEngineBadge = findViewById(R.id.activeEngineBadge)
        geminiVoiceChipsRow = findViewById(R.id.geminiVoiceChipsRow)
        simSpinner = findViewById(R.id.simSpinner)
        geminiVoiceLabel = findViewById(R.id.geminiVoiceLabel)
        permissionsBar = findViewById(R.id.permissionsBar)
        permissionsBar.pressFeedback(0.96f)
        permissionsBar.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        personalitySegmented = findViewById(R.id.personalitySegmented)
        personalityDescriptionText = findViewById(R.id.personalityDescriptionText)

        homeStyleSegmented = findViewById(R.id.homeStyleSegmented)
        homeStyleDescriptionText = findViewById(R.id.homeStyleDescriptionText)

        themeSegmented = findViewById(R.id.themeSegmented)
        themeDescriptionText = findViewById(R.id.themeDescriptionText)
        appearanceThemeSectionLabel = findViewById(R.id.appearanceThemeSectionLabel)
        appearanceThemeCard = findViewById(R.id.appearanceThemeCard)

        val appVersionText = findViewById<TextView>(R.id.appVersionText)
        if (appVersionText != null) {
            appVersionText.text = "✨ JARVIS AI v${com.jarvis.assistant.BuildConfig.VERSION_NAME} (Build ${com.jarvis.assistant.BuildConfig.VERSION_CODE})"
        }
        saveButton = findViewById(R.id.saveButton)
        backBtn = findViewById(R.id.backBtn)
        headerSettingsIcon = findViewById(R.id.headerSettingsIcon)
        headerUpdateSearchBtn = findViewById(R.id.headerUpdateSearchBtn)
        headerUpdateBadgeDot = findViewById(R.id.headerUpdateBadgeDot)
        updateAppNotificationCard = findViewById(R.id.updateAppNotificationCard)
        updateNotificationBadge = findViewById(R.id.updateNotificationBadge)
        updateNotificationTitle = findViewById(R.id.updateNotificationTitle)
        updateNotificationChangelog = findViewById(R.id.updateNotificationChangelog)
        updateNotificationActionBtn = findViewById(R.id.updateNotificationActionBtn)
        settingsCheckUpdatesRow = findViewById(R.id.settingsCheckUpdatesRow)
        settingsUpdateStatusBadge = findViewById(R.id.settingsUpdateStatusBadge)
        settingsUpdateSearchIcon = findViewById(R.id.settingsUpdateSearchIcon)
    }

    private fun updateThemeSectionVisibility() {
        val isArc3D = (selectedHomeStyleIndex == 0)
        appearanceThemeSectionLabel.visibility = if (isArc3D) View.VISIBLE else View.GONE
        appearanceThemeCard.visibility = if (isArc3D) View.VISIBLE else View.GONE
    }

    private fun wireInteractions() {
        backBtn.pressFeedback()
        backBtn.setOnClickListener { finish() }

        headerSettingsIcon.pressFeedback()
        headerSettingsIcon.setOnClickListener {
            showAdvancedSettingsDialog()
        }

        apiKeyVisibilityToggle.setOnClickListener {
            isApiKeyVisible = !isApiKeyVisible
            if (isApiKeyVisible) {
                apiKeyInput.transformationMethod = HideReturnsTransformationMethod.getInstance()
                apiKeyVisibilityToggle.setImageResource(R.drawable.ic_visibility_off)
            } else {
                apiKeyInput.transformationMethod = PasswordTransformationMethod.getInstance()
                apiKeyVisibilityToggle.setImageResource(R.drawable.ic_visibility)
            }
            apiKeyInput.setSelection(apiKeyInput.text?.length ?: 0)
        }

        saveButton.pressFeedback(0.95f)
        saveButton.setOnClickListener { saveAndClose() }

        personalitySegmented.onSelectionChange { index ->
            selectedPersonalityIndex = index
            AnimUtils.crossFadeText(personalityDescriptionText, personalityDescriptions[index])
        }

        homeStyleSegmented.onSelectionChange { index ->
            selectedHomeStyleIndex = index
            AnimUtils.crossFadeText(homeStyleDescriptionText, homeStyleDescriptions[index])
            updateThemeSectionVisibility()
        }

        themeSegmented.onSelectionChange { index ->
            selectedThemeIndex = index
            val newTheme = if (index == 1) ThemeManager.THEME_GOLD else ThemeManager.THEME_BLUE
            ThemeManager.setTheme(this, newTheme)
            AnimUtils.crossFadeText(themeDescriptionText, themeDescriptions[index])
            applyThemeVisuals()
        }

        // Update Search button in Header
        headerUpdateSearchBtn.pressFeedback()
        headerUpdateSearchBtn.setOnClickListener {
            com.jarvis.assistant.update.UpdateManager.checkForUpdates(this, manualCheck = true)
        }

        // Check for updates row in APPLICATION UPDATES card
        settingsCheckUpdatesRow.pressFeedback(0.97f)
        settingsCheckUpdatesRow.setOnClickListener {
            com.jarvis.assistant.update.UpdateManager.checkForUpdates(this, manualCheck = true)
        }

        // Action button on Update App notification card
        updateNotificationActionBtn.pressFeedback(0.95f)
        updateNotificationActionBtn.setOnClickListener {
            val cached = com.jarvis.assistant.update.UpdateManager.cachedUpdateInfo
            if (cached != null) {
                com.jarvis.assistant.update.UpdateManager.showUpdateDialog(this, cached)
            } else {
                com.jarvis.assistant.update.UpdateManager.checkForUpdates(this, manualCheck = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndUpdateUI()
    }

    private fun checkAndUpdateUI() {
        com.jarvis.assistant.update.UpdateManager.checkUpdateStatus(this) { hasUpdate, info ->
            if (isFinishing || isDestroyed) return@checkUpdateStatus
            if (hasUpdate && info != null) {
                headerUpdateBadgeDot.visibility = View.VISIBLE
                updateAppNotificationCard.visibility = View.VISIBLE
                updateNotificationBadge.text = "v${info.versionName} NEW"
                updateNotificationTitle.text = "JARVIS AI v${info.versionName} is available!"
                updateNotificationChangelog.text = info.changelog
                settingsUpdateStatusBadge.text = "v${info.versionName} Available"
                settingsUpdateStatusBadge.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
            } else {
                headerUpdateBadgeDot.visibility = View.GONE
                updateAppNotificationCard.visibility = View.GONE
                settingsUpdateStatusBadge.text = "Up to date"
                settingsUpdateStatusBadge.setTextColor(android.graphics.Color.parseColor("#10B981"))
            }
        }
    }

    private fun applyThemeVisuals() {
        val primaryColor = ThemeManager.getPrimaryColorInt(this)
        saveButton.setBackgroundResource(ThemeManager.getSaveButtonDrawable(this))
        findViewById<TextView>(R.id.settingsTitleText)?.setTextColor(primaryColor)
        (headerSettingsIcon as? ImageView)?.setColorFilter(primaryColor)
        (backBtn as? ImageButton)?.setColorFilter(primaryColor)
        (headerUpdateSearchBtn as? ImageButton)?.setColorFilter(primaryColor)
        settingsUpdateSearchIcon.setColorFilter(primaryColor)
        apiKeyLabel.setTextColor(primaryColor)
        (geminiVoiceLabel as? TextView)?.setTextColor(primaryColor)
        personalitySegmented.refreshTheme()
        homeStyleSegmented.refreshTheme()
        themeSegmented.refreshTheme()
        updateVoiceAccessibility()
        updateEngineBannerUI()
    }

    private fun prefs() = getSharedPreferences(JarvisApplication.PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadPrefs() {
        val p = prefs()
        userNameInput.setText(p.getString("user_name", ""))

        val savedVoice = p.getString("gemini_voice", "Aoede") ?: "Aoede"
        val effectiveVoice = if (savedVoice.equals("Puck", ignoreCase = true)) "Aoede" else savedVoice
        selectedVoiceIndex = voiceValues.indexOf(effectiveVoice).coerceAtLeast(0)

        val savedModel = p.getString("gemini_model", geminiModelValues[0]) ?: geminiModelValues[0]
        selectedModelIndex = geminiModelValues.indexOf(savedModel).coerceAtLeast(0)

        val personalityIndex = when (p.getString("personality_mode", "jarvis")) {
            "gf" -> 1
            "professional" -> 2
            "assistant" -> 3
            else -> 0
        }
        selectedPersonalityIndex = personalityIndex
        personalitySegmented.setOptions(listOf("JARVIS ⚡", "GF 💖", "Pro 💼", "Assist 🤖"), personalityIndex)
        personalityDescriptionText.text = personalityDescriptions[personalityIndex]

        val savedHomeStyle = p.getString("home_screen_style", "classic")
        selectedHomeStyleIndex = if (savedHomeStyle == "cyber_hud") 1 else 0
        homeStyleSegmented.setOptions(listOf("Arc 3D ⚡", "Cyber HUD 🌐"), selectedHomeStyleIndex)
        homeStyleDescriptionText.text = homeStyleDescriptions[selectedHomeStyleIndex]

        val currentTheme = ThemeManager.getTheme(this)
        selectedThemeIndex = if (currentTheme == ThemeManager.THEME_GOLD) 1 else 0
        themeSegmented.setOptions(listOf("Arc Blue ⚡", "Amber Gold 👑"), selectedThemeIndex)
        themeDescriptionText.text = themeDescriptions[selectedThemeIndex]

        updateThemeSectionVisibility()
        applyThemeVisuals()
        buildSimToggle()
        updateVoiceAccessibility()
        updateEngineBannerUI()
        updateApiKeyFieldUI()
    }

    private fun buildVoiceChips(
        container: LinearLayout,
        labels: List<String>,
        selectedIndex: Int,
        isEnabled: Boolean = true,
        onSelect: (Int) -> Unit
    ) {
        container.removeAllViews()
        labels.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex

            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = ThemeManager.createChipDrawable(this@SettingsActivity, isSelected)
                alpha = if (isEnabled) 1.0f else 0.5f
                this.isEnabled = isEnabled
                pressFeedback(0.94f)
            }

            val label1 = TextView(this).apply {
                text = label
                textSize = 12f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(
                    if (isSelected) ThemeManager.getPrimaryColorInt(this@SettingsActivity)
                    else ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary)
                )
            }
            chip.addView(label1)

            if (isSelected) {
                val badge = TextView(this).apply {
                    text = "ACTIVE"
                    textSize = 8f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(4), dp(1), dp(4), dp(1))
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_on_accent))
                    background = ThemeManager.createBadgeDrawable(this@SettingsActivity)
                }
                val badgeLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                badgeLp.marginStart = dp(6)
                chip.addView(badge, badgeLp)
            }

            chip.setOnClickListener { onSelect(index) }

            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            container.addView(chip, lp)
        }
    }

    private fun updateVoiceAccessibility() {
        geminiVoiceLabel.alpha = 1.0f
        buildVoiceChips(
            container = geminiVoiceChipsRow,
            labels = voiceLabels,
            selectedIndex = selectedVoiceIndex,
            isEnabled = true
        ) { index -> onGeminiVoiceSelected(index) }
    }

    private fun onGeminiVoiceSelected(index: Int) {
        selectedVoiceIndex = index
        updateVoiceAccessibility()
        updateEngineBannerUI()
    }

    private fun buildSimToggle() {
        callCapableSims = SimManager.getCallCapableSims(this)
        val savedSlotIndex = SimManager.getPreferredSimIndex(this)

        val simOptions = if (callCapableSims.isNotEmpty()) {
            callCapableSims
        } else {
            listOf(
                SimManager.SimOption(PhoneAccountHandle(android.content.ComponentName(packageName, "Sim1"), "0"), "SIM 1", 0, 1),
                SimManager.SimOption(PhoneAccountHandle(android.content.ComponentName(packageName, "Sim2"), "1"), "SIM 2", 1, 2)
            )
        }

        selectedSimIndex = when {
            savedSlotIndex < 0 -> 0
            savedSlotIndex + 1 < (simOptions.size + 1) -> savedSlotIndex + 1
            else -> 0
        }

        val simLabels = listOf("Ask") + simOptions.map { it.label }

        val adapter = object : ArrayAdapter<String>(this, R.layout.sim_spinner_item_selected, simLabels) {
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.spinner_item_dropdown, parent, false)
                (view as TextView).text = simLabels[position]
                view.setTextColor(
                    ContextCompat.getColor(
                        this@SettingsActivity,
                        if (position == selectedSimIndex) R.color.success else R.color.text_primary
                    )
                )
                return view
            }
        }

        simSpinner.adapter = adapter
        simSpinner.setSelection(selectedSimIndex, false)
        simSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (selectedSimIndex != position) {
                    selectedSimIndex = position
                    if (position == 0) {
                        SimManager.savePreferredSim(this@SettingsActivity, null, -1, -1)
                    } else {
                        val option = simOptions[position - 1]
                        SimManager.savePreferredSim(this@SettingsActivity, option.handle, option.slotIndex, option.subId)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateEngineBannerUI() {
        val modelLabel = geminiModelLabels.getOrNull(selectedModelIndex) ?: "Gemini 3.1 Live"
        activeEngineTitleText.text = "Gemini Live ($modelLabel)"
        activeEngineSubtext.text = "Voice: ${voiceValues.getOrNull(selectedVoiceIndex) ?: "Kore"} | Real-time Bidirectional Audio"
        activeEngineBadge.text = "● ACTIVE"
    }

    private fun updateApiKeyFieldUI() {
        geminiKeyContainer.visibility = View.VISIBLE
        val p = prefs()
        val savedGemini = p.getString("api_key", "") ?: ""
        val defaultGemini = EnvLoader.getApiKey(this)
        apiKeyInput.setText(if (savedGemini.isNotBlank()) savedGemini else defaultGemini)
    }

    private fun saveAndClose() {
        val selectedPersonality = when (selectedPersonalityIndex) {
            1 -> "gf"
            2 -> "professional"
            3 -> "assistant"
            else -> "jarvis"
        }

        val newUserName = userNameInput.text.toString().trim()
        val newTheme = if (selectedThemeIndex == 1) ThemeManager.THEME_GOLD else ThemeManager.THEME_BLUE
        val homeStyleValue = if (selectedHomeStyleIndex == 1) "cyber_hud" else "classic"
        val previousTheme = ThemeManager.getTheme(this)

        val selectedVoice = voiceValues.getOrNull(selectedVoiceIndex) ?: "Aoede"
        prefs().edit().apply {
            putString("api_key", apiKeyInput.text.toString().trim())
            putString("user_name", newUserName)
            putString("tts_engine", "gemini")
            putString("gemini_model", geminiModelValues.getOrNull(selectedModelIndex) ?: defaultGeminiModel)
            putString("gemini_voice", selectedVoice)
            putString("cached_voice", selectedVoice)
            putString("personality_mode", selectedPersonality)
            putString("home_screen_style", homeStyleValue)
            putString(ThemeManager.PREF_KEY_THEME, newTheme)
            apply()
        }

        try {
            com.jarvis.assistant.service.JarvisVoiceService.instance?.updateVoice(selectedVoice)
        } catch (_: Exception) {}

        syncUserDataToFirebase(newUserName)
        EnvLoader.resetCache()
        Toast.makeText(this, "Configuration saved successfully", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun syncUserDataToFirebase(newUserName: String) {
        try {
            val auth = FirebaseAuth.getInstance()
            val currentUser = auth.currentUser
            val p = prefs()

            val uid = currentUser?.uid ?: p.getString("user_uid", null)
            val email = currentUser?.email ?: p.getString("user_email", "") ?: ""

            // Sync Display Name to Firebase Authentication Console
            if (currentUser != null) {
                val profileUpdates = UserProfileChangeRequest.Builder()
                    .setDisplayName(newUserName)
                    .build()
                currentUser.updateProfile(profileUpdates).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d("SettingsActivity", "Firebase Auth displayName synced: $newUserName")
                    }
                }
            }

            // Sync Display Name to Cloud Firestore 'users' collection
            if (!uid.isNullOrBlank()) {
                com.jarvis.assistant.firebase.UserFirestoreHelper.updateName(uid, newUserName)
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error updating profile name", e)
        }
    }

    private fun showAdvancedSettingsDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_advanced_settings)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val primaryColor = ThemeManager.getPrimaryColorInt(this)
        dialog.findViewById<TextView>(R.id.dialogTitleText)?.setTextColor(primaryColor)
        dialog.findViewById<ImageView>(R.id.notifReaderIcon)?.setColorFilter(primaryColor)
        dialog.findViewById<ImageView>(R.id.webBuilderIcon)?.setColorFilter(primaryColor)

        val switchNotifReader = dialog.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchNotificationReader)
        val isEnabled = prefs().getBoolean("notification_reader_enabled", true)
        switchNotifReader?.isChecked = isEnabled

        switchNotifReader?.setOnCheckedChangeListener { _, isChecked ->
            prefs().edit().putBoolean("notification_reader_enabled", isChecked).apply()
            val stateText = if (isChecked) "ON (Enabled)" else "OFF (Disabled)"
            android.widget.Toast.makeText(this, "Notification Reader: $stateText", android.widget.Toast.LENGTH_SHORT).show()
        }

        dialog.findViewById<View>(R.id.rowWebsiteBuilder)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, WebsiteBuilderActivity::class.java))
        }

        dialog.findViewById<View>(R.id.dialogCloseBtn)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
