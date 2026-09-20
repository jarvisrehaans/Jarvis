package com.jarvis.assistant.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jarvis.assistant.R
import com.jarvis.assistant.service.FloatingOrbService
import com.jarvis.assistant.service.JarvisAccessibilityService
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.util.pressFeedback

class PermissionsActivity : AppCompatActivity() {

    private lateinit var backBtn: ImageView
    private lateinit var recheckPermissionsBtn: TextView
    private lateinit var permissionStatusCounter: TextView
    private lateinit var grantAllPermissionsBtn: View

    private lateinit var micPermissionRow: View
    private lateinit var micPermissionBadge: TextView

    private lateinit var cameraPermissionRow: View
    private lateinit var cameraPermissionBadge: TextView

    private lateinit var contactsPermissionRow: View
    private lateinit var contactsPermissionBadge: TextView

    private lateinit var phonePermissionRow: View
    private lateinit var phonePermissionBadge: TextView

    private lateinit var notifPermissionRow: View
    private lateinit var notifPermissionBadge: TextView

    private lateinit var accessibilityRow: View
    private lateinit var accessibilityPermissionBadge: TextView

    private lateinit var batteryPermissionRow: View
    private lateinit var batteryPermissionBadge: TextView

    private lateinit var overlayPermissionRow: View
    private lateinit var overlayPermissionBadge: TextView

    private lateinit var overlaySwitch: SwitchMaterial
    private lateinit var openAppDetailsBtn: View

    companion object {
        private const val REQUEST_CODE_GRANT_ALL = 500
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        initViews()
        wireInteractions()
    }

    override fun onResume() {
        super.onResume()
        applyThemeVisuals()
        checkAllPermissionsStatus(showToast = false)
    }

    private fun applyThemeVisuals() {
        grantAllPermissionsBtn.setBackgroundResource(ThemeManager.getSaveButtonDrawable(this))
        val primaryColor = ThemeManager.getPrimaryColorInt(this)
        recheckPermissionsBtn.setTextColor(primaryColor)
        permissionStatusCounter.setTextColor(primaryColor)
    }

    private fun initViews() {
        backBtn = findViewById(R.id.backBtn)
        recheckPermissionsBtn = findViewById(R.id.recheckPermissionsBtn)
        permissionStatusCounter = findViewById(R.id.permissionStatusCounter)
        grantAllPermissionsBtn = findViewById(R.id.grantAllPermissionsBtn)
        applyThemeVisuals()

        micPermissionRow = findViewById(R.id.micPermissionRow)
        micPermissionBadge = findViewById(R.id.micPermissionBadge)

        cameraPermissionRow = findViewById(R.id.cameraPermissionRow)
        cameraPermissionBadge = findViewById(R.id.cameraPermissionBadge)

        contactsPermissionRow = findViewById(R.id.contactsPermissionRow)
        contactsPermissionBadge = findViewById(R.id.contactsPermissionBadge)

        phonePermissionRow = findViewById(R.id.phonePermissionRow)
        phonePermissionBadge = findViewById(R.id.phonePermissionBadge)

        notifPermissionRow = findViewById(R.id.notifPermissionRow)
        notifPermissionBadge = findViewById(R.id.notifPermissionBadge)

        accessibilityRow = findViewById(R.id.accessibilityRow)
        accessibilityPermissionBadge = findViewById(R.id.accessibilityPermissionBadge)

        batteryPermissionRow = findViewById(R.id.batteryPermissionRow)
        batteryPermissionBadge = findViewById(R.id.batteryPermissionBadge)

        overlayPermissionRow = findViewById(R.id.overlayPermissionRow)
        overlayPermissionBadge = findViewById(R.id.overlayPermissionBadge)

        overlaySwitch = findViewById(R.id.overlaySwitch)
        openAppDetailsBtn = findViewById(R.id.openAppDetailsBtn)

        val prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
        val overlayEnabled = prefs.getBoolean("enable_floating_overlay", false)
        overlaySwitch.isChecked = overlayEnabled
    }

    private fun wireInteractions() {
        backBtn.pressFeedback()
        backBtn.setOnClickListener { finish() }

        recheckPermissionsBtn.pressFeedback(0.95f)
        recheckPermissionsBtn.setOnClickListener {
            checkAllPermissionsStatus(showToast = true)
        }

        grantAllPermissionsBtn.pressFeedback(0.96f)
        grantAllPermissionsBtn.setOnClickListener {
            grantAllPermissions()
        }

        micPermissionRow.pressFeedback(0.98f)
        micPermissionRow.setOnClickListener {
            if (!isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), 201
                )
            } else {
                Toast.makeText(this, "Microphone permission is already granted", Toast.LENGTH_SHORT).show()
            }
        }

        cameraPermissionRow.pressFeedback(0.98f)
        cameraPermissionRow.setOnClickListener {
            if (!isPermissionGranted(Manifest.permission.CAMERA)) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.CAMERA), 202
                )
            } else {
                Toast.makeText(this, "Camera permission is already granted", Toast.LENGTH_SHORT).show()
            }
        }

        contactsPermissionRow.pressFeedback(0.98f)
        contactsPermissionRow.setOnClickListener {
            if (!isPermissionGranted(Manifest.permission.READ_CONTACTS)) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.READ_CONTACTS), 203
                )
            } else {
                Toast.makeText(this, "Contacts permission is already granted", Toast.LENGTH_SHORT).show()
            }
        }

        phonePermissionRow.pressFeedback(0.98f)
        phonePermissionRow.setOnClickListener {
            if (!isPermissionGranted(Manifest.permission.CALL_PHONE) ||
                !isPermissionGranted(Manifest.permission.READ_PHONE_STATE)) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_PHONE_STATE
                    ), 204
                )
            } else {
                Toast.makeText(this, "Phone & SIM permissions are already granted", Toast.LENGTH_SHORT).show()
            }
        }

        notifPermissionRow.pressFeedback(0.98f)
        notifPermissionRow.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 205
                    )
                } else {
                    Toast.makeText(this, "Notifications permission is already granted", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Notifications permission is automatically granted", Toast.LENGTH_SHORT).show()
            }
        }

        accessibilityRow.pressFeedback(0.97f)
        accessibilityRow.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open Accessibility settings", Toast.LENGTH_SHORT).show()
            }
        }

        batteryPermissionRow.pressFeedback(0.97f)
        batteryPermissionRow.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                if (pm?.isIgnoringBatteryOptimizations(packageName) != true) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (e2: Exception) {
                            Toast.makeText(this, "Please set Battery to 'Unrestricted' in App Info > Battery", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Battery is already Unrestricted (Never killed in background)", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Battery optimization is not restricted on this Android version", Toast.LENGTH_SHORT).show()
            }
        }

        overlayPermissionRow.pressFeedback(0.98f)
        overlayPermissionRow.setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Display Over Other Apps is already enabled", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open Overlay settings", Toast.LENGTH_SHORT).show()
            }
        }

        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    overlaySwitch.isChecked = false
                    Toast.makeText(this, "Please enable 'Display Over Other Apps' permission first!", Toast.LENGTH_LONG).show()
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Couldn't open Overlay settings", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    prefs.edit().putBoolean("enable_floating_overlay", true).apply()
                    Toast.makeText(this, "Floating Overlay Orb Enabled!", Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.edit().putBoolean("enable_floating_overlay", false).apply()
                FloatingOrbService.stopService(this)
                Toast.makeText(this, "Floating Overlay Orb Disabled.", Toast.LENGTH_SHORT).show()
            }
        }

        openAppDetailsBtn.pressFeedback(0.96f)
        openAppDetailsBtn.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open App Settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun grantAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (!isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (!isPermissionGranted(Manifest.permission.CAMERA)) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (!isPermissionGranted(Manifest.permission.READ_CONTACTS)) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }
        if (!isPermissionGranted(Manifest.permission.CALL_PHONE)) {
            permissionsToRequest.add(Manifest.permission.CALL_PHONE)
        }
        if (!isPermissionGranted(Manifest.permission.READ_PHONE_STATE)) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (!isPermissionGranted(Manifest.permission.READ_CALENDAR)) {
            permissionsToRequest.add(Manifest.permission.READ_CALENDAR)
        }
        if (!isPermissionGranted(Manifest.permission.WRITE_CALENDAR)) {
            permissionsToRequest.add(Manifest.permission.WRITE_CALENDAR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                REQUEST_CODE_GRANT_ALL
            )
        } else {
            // Check Accessibility, Battery Unrestricted and Overlay
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Standard permissions granted! Please enable Accessibility Automation in Settings.", Toast.LENGTH_LONG).show()
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) {}
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                (getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)?.isIgnoringBatteryOptimizations(packageName) != true) {
                Toast.makeText(this, "Please set Battery to 'Unrestricted' so JARVIS & Accessibility stay active.", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Standard permissions granted! Enable Display Over Apps to use overlay.", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {}
            } else {
                Toast.makeText(this, "All permissions are already granted!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAllPermissionsStatus(showToast: Boolean = false) {
        var grantedCount = 0
        val totalCount = 8

        // 1. Mic
        val micGranted = isPermissionGranted(Manifest.permission.RECORD_AUDIO)
        updateBadge(micPermissionBadge, micGranted, "GRANTED", "NOT GRANTED")
        if (micGranted) grantedCount++

        // 2. Camera
        val cameraGranted = isPermissionGranted(Manifest.permission.CAMERA)
        updateBadge(cameraPermissionBadge, cameraGranted, "GRANTED", "NOT GRANTED")
        if (cameraGranted) grantedCount++

        // 3. Contacts
        val contactsGranted = isPermissionGranted(Manifest.permission.READ_CONTACTS)
        updateBadge(contactsPermissionBadge, contactsGranted, "GRANTED", "NOT GRANTED")
        if (contactsGranted) grantedCount++

        // 4. Phone
        val phoneGranted = isPermissionGranted(Manifest.permission.CALL_PHONE) &&
                isPermissionGranted(Manifest.permission.READ_PHONE_STATE)
        updateBadge(phonePermissionBadge, phoneGranted, "GRANTED", "NOT GRANTED")
        if (phoneGranted) grantedCount++

        // 5. Notifications
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        updateBadge(notifPermissionBadge, notifGranted, "GRANTED", "NOT GRANTED")
        if (notifGranted) grantedCount++

        // 6. Accessibility
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        updateBadge(accessibilityPermissionBadge, accessibilityEnabled, "ENABLED", "DISABLED")
        if (accessibilityEnabled) grantedCount++

        // 7. Battery Unrestricted
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        val batteryUnrestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        } else {
            true
        }
        updateBadge(batteryPermissionBadge, batteryUnrestricted, "UNRESTRICTED", "OPTIMIZED")
        if (batteryUnrestricted) grantedCount++

        // 8. Overlay
        val overlayEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        updateBadge(overlayPermissionBadge, overlayEnabled, "ENABLED", "DISABLED")
        if (overlayEnabled) grantedCount++

        permissionStatusCounter.text = "$grantedCount of $totalCount Granted"

        if (showToast) {
            Toast.makeText(
                this,
                "Checked $totalCount permissions: $grantedCount Active / ${totalCount - grantedCount} Pending",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = "$packageName/${JarvisAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedService, ignoreCase = true) ||
                componentName.equals("$packageName/.service.JarvisAccessibilityService", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun updateBadge(badge: TextView, isOk: Boolean, okText: String, failText: String) {
        if (isOk) {
            badge.text = "● $okText"
            badge.setTextColor(ContextCompat.getColor(this, R.color.success))
            badge.setBackgroundResource(R.drawable.bg_chip_pill_success)
        } else {
            badge.text = "● $failText"
            badge.setTextColor(ContextCompat.getColor(this, R.color.danger))
            badge.setBackgroundResource(R.drawable.bg_chip_pill)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkAllPermissionsStatus(showToast = false)
    }
}
