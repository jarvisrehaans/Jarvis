package com.jarvis.assistant.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.PatternMatcher
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.service.JarvisAccessibilityService
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Comprehensive controller for all Android device hardware settings:
 * Wi-Fi (connect, toggle, status), Bluetooth (connect, toggle, status),
 * Personal Hotspot (toggle, password), Dual-SIM switching,
 * Developer Options (USB & Wireless Debugging), and System Settings pages.
 */
object DeviceSettingsController {

    private const val TAG = "DeviceSettingsCtrl"

    // ------------------------------------------------------------------------
    // 1. Wi-Fi Management
    // ------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun setWifiEnabled(context: Context, enable: Boolean, onVerifiedResult: ((Boolean, String) -> Unit)? = null): Pair<Boolean, String> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wifiManager != null) {
            try {
                @Suppress("DEPRECATION")
                val ok = wifiManager.setWifiEnabled(enable)
                if (ok) {
                    return Pair(true, if (enable) "Wi-Fi turned on, Sir." else "Wi-Fi turned off, Sir.")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Direct setWifiEnabled failed: ${e.message}")
            }
        }

        // Seamless fallback for Android 10+ / 11 / 12 / 13 / 14 via Accessibility automation
        openWifiSettings(context)
        val acc = JarvisAccessibilityService.instance
        if (acc != null) {
            acc.automateUniversalSettingToggle("wi-fi", enable) { success, message -> onVerifiedResult?.invoke(success, message) }
            return Pair(true, if (enable) "Turning Wi-Fi ON, Sir." else "Turning Wi-Fi OFF, Sir.")
        } else {
            return Pair(false, "I opened Wi-Fi settings, but I need Accessibility permission enabled to flip the switch for you, Sir.")
        }
    }

    @SuppressLint("MissingPermission")
    fun setBluetoothEnabled(context: Context, enable: Boolean, onVerifiedResult: ((Boolean, String) -> Unit)? = null): Pair<Boolean, String> {
        val adapter = getBluetoothAdapter(context)

        if (adapter != null) {
            try {
                if (enable && adapter.isEnabled) {
                    return Pair(true, "Bluetooth is already turned ON.")
                }
                if (!enable && !adapter.isEnabled) {
                    return Pair(true, "Bluetooth is already turned OFF.")
                }
                @Suppress("DEPRECATION")
                val ok = if (enable) adapter.enable() else adapter.disable()
                if (ok) {
                    return Pair(true, if (enable) "Bluetooth turned ON, Sir." else "Bluetooth turned OFF, Sir.")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Direct bluetoothAdapter toggle failed (restricted on Android 13+): ${e.message}")
            }
        }

        // Seamless fallback for Android 12+/13+/14+ via Accessibility automation
        openBluetoothSettings(context)
        val acc = JarvisAccessibilityService.instance
        if (acc != null) {
            acc.automateUniversalSettingToggle("bluetooth", enable) { success, message -> onVerifiedResult?.invoke(success, message) }
            return Pair(true, if (enable) "Turning Bluetooth ON, Sir." else "Turning Bluetooth OFF, Sir.")
        } else {
            return Pair(false, "I opened Bluetooth settings, but I need Accessibility permission enabled to flip the switch for you, Sir.")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToWifi(context: Context, ssid: String, password: String): Pair<Boolean, String> {
        if (ssid.isBlank()) return Pair(false, "Network name (SSID) cannot be empty.")
        val cleanSsid = ssid.trim().removeSurrounding("\"")
        val cleanPassword = password.trim().removeSurrounding("\"")

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return Pair(false, "Wi-Fi hardware not available.")

        // Make sure Wi-Fi is turned on first
        if (!wifiManager.isWifiEnabled) {
            setWifiEnabled(context, true)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 1. Try Network Suggestion API
                val suggestionBuilder = WifiNetworkSuggestion.Builder()
                    .setSsid(cleanSsid)
                if (cleanPassword.isNotEmpty()) {
                    suggestionBuilder.setWpa2Passphrase(cleanPassword)
                }
                val suggestions = listOf(suggestionBuilder.build())
                val status = wifiManager.addNetworkSuggestions(suggestions)
                Log.d(TAG, "addNetworkSuggestions status: $status for SSID: $cleanSsid")

                // 2. Also dispatch Accessibility automation to tap the SSID in Settings and enter password if modal appears
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.automateWifiConnect(cleanSsid, cleanPassword)
                } else {
                    openWifiSettings(context)
                }
                return Pair(true, "Connecting to Wi-Fi network \"$cleanSsid\", Sir.")
            } else {
                @Suppress("DEPRECATION")
                val wifiConfig = WifiConfiguration().apply {
                    this.SSID = "\"$cleanSsid\""
                    if (cleanPassword.isNotEmpty()) {
                        this.preSharedKey = "\"$cleanPassword\""
                    } else {
                        this.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                }
                @Suppress("DEPRECATION")
                val netId = wifiManager.addNetwork(wifiConfig)
                if (netId != -1) {
                    @Suppress("DEPRECATION")
                    wifiManager.disconnect()
                    @Suppress("DEPRECATION")
                    wifiManager.enableNetwork(netId, true)
                    @Suppress("DEPRECATION")
                    wifiManager.reconnect()
                    return Pair(true, "Connected to Wi-Fi network \"$cleanSsid\".")
                } else {
                    openWifiSettings(context)
                    JarvisAccessibilityService.instance?.automateWifiConnect(cleanSsid, cleanPassword)
                    return Pair(true, "Attempting connection to \"$cleanSsid\".")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectToWifi error", e)
            openWifiSettings(context)
            JarvisAccessibilityService.instance?.automateWifiConnect(cleanSsid, cleanPassword)
            return Pair(true, "Connecting to \"$cleanSsid\" via settings automation.")
        }
    }

    @SuppressLint("MissingPermission")
    fun getWifiStatus(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return "Wi-Fi is not available on this device."

        val isEnabled = wifiManager.isWifiEnabled
        if (!isEnabled) return "Wi-Fi is currently turned OFF."

        val wifiInfo: WifiInfo? = wifiManager.connectionInfo
        val ssid = wifiInfo?.ssid?.removeSurrounding("\"") ?: "Unknown"
        val isConnected = ssid.isNotBlank() && ssid != "<unknown ssid>" && wifiInfo?.networkId != -1

        return if (isConnected) {
            val rssi = wifiInfo?.rssi ?: 0
            val ipInt = wifiInfo?.ipAddress ?: 0
            val ipStr = if (ipInt != 0) {
                val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array()
                InetAddress.getByAddress(bytes).hostAddress
            } else "N/A"
            "Wi-Fi is ON and connected to \"$ssid\" (Signal: $rssi dBm, IP: $ipStr)."
        } else {
            "Wi-Fi is ON but currently not connected to any network."
        }
    }

    fun openWifiSettings(context: Context) {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try { context.startActivity(intent) } catch (e: Exception) { Log.e(TAG, "openWifiSettings failed", e) }
    }

    // ------------------------------------------------------------------------
    // 2. Bluetooth Management
    // ------------------------------------------------------------------------

    private fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    @SuppressLint("MissingPermission")
    fun connectToBluetoothDevice(context: Context, deviceNameQuery: String): Pair<Boolean, String> {
        if (deviceNameQuery.isBlank()) return Pair(false, "Device name cannot be empty.")
        val cleanQuery = deviceNameQuery.trim()

        val adapter = getBluetoothAdapter(context) ?: return Pair(false, "Bluetooth hardware not available.")
        try {
            if (!adapter.isEnabled) {
                setBluetoothEnabled(context, true)
            }
        } catch (_: Exception) {}

        try {
            var targetDevice: BluetoothDevice? = null
            var matchedName = cleanQuery

            try {
                val bondedDevices: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
                targetDevice = bondedDevices.firstOrNull { dev ->
                    val name = dev.name ?: ""
                    name.contains(cleanQuery, ignoreCase = true) || cleanQuery.contains(name, ignoreCase = true)
                }
                if (targetDevice != null) {
                    matchedName = targetDevice.name ?: cleanQuery
                }
            } catch (e: Exception) {
                Log.d(TAG, "bondedDevices lookup failed: ${e.message}")
            }

            if (targetDevice != null) {
                Log.d(TAG, "Found bonded Bluetooth device: $matchedName ($targetDevice)")

                // 1. Try HID / A2DP profile connection via reflection
                var reflectionSuccess = false
                try {
                    val connectMethod = targetDevice.javaClass.getMethod("connect")
                    connectMethod.isAccessible = true
                    val res = connectMethod.invoke(targetDevice) as? Boolean
                    reflectionSuccess = (res == true)
                } catch (e2: Exception) {
                    Log.d(TAG, "Direct reflection connect failed: ${e2.message}")
                }

                // 2. Also dispatch accessibility automation to tap device in Bluetooth Settings
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    openBluetoothSettings(context)
                    acc.automateBluetoothConnect(matchedName)
                } else if (!reflectionSuccess) {
                    openBluetoothSettings(context)
                }

                return Pair(true, "Connecting to Bluetooth device \"$matchedName\", Sir.")
            } else {
                // Not in paired devices — open Bluetooth settings and scan/pair via Accessibility
                openBluetoothSettings(context)
                val acc = JarvisAccessibilityService.instance
                if (acc != null) {
                    acc.automateBluetoothConnect(cleanQuery)
                }
                return Pair(true, "Searching and connecting to \"$cleanQuery\" in Bluetooth settings.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectToBluetoothDevice error", e)
            openBluetoothSettings(context)
            JarvisAccessibilityService.instance?.automateBluetoothConnect(cleanQuery)
            return Pair(true, "Opening Bluetooth settings to connect to \"$cleanQuery\".")
        }
    }

    @SuppressLint("MissingPermission")
    fun getBluetoothStatus(context: Context): String {
        val adapter = getBluetoothAdapter(context) ?: return "Bluetooth is not supported on this device."
        if (!adapter.isEnabled) return "Bluetooth is currently turned OFF."

        val bonded = adapter.bondedDevices?.mapNotNull { it.name } ?: emptyList()
        val pairedSummary = if (bonded.isNotEmpty()) {
            "Paired devices: " + bonded.joinToString(", ")
        } else {
            "No paired devices found."
        }
        return "Bluetooth is ON. $pairedSummary"
    }

    fun openBluetoothSettings(context: Context) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try { context.startActivity(intent) } catch (e: Exception) { Log.e(TAG, "openBluetoothSettings failed", e) }
    }

    // ------------------------------------------------------------------------
    // 3. Hotspot (Tethering) Management & Password
    // ------------------------------------------------------------------------

    fun toggleHotspot(context: Context, enable: Boolean): Pair<Boolean, String> {
        openHotspotSettings(context)
        val acc = JarvisAccessibilityService.instance
        if (acc != null) {
            acc.automateHotspotControl(enable)
            return Pair(true, if (enable) "Turning Personal Hotspot ON, Sir." else "Turning Personal Hotspot OFF, Sir.")
        } else {
            return Pair(true, "Opened Hotspot settings. Please enable Accessibility for full background control.")
        }
    }

    fun getHotspotPassword(context: Context): Pair<Boolean, String> {
        val acc = JarvisAccessibilityService.instance
        return if (acc != null) {
            val pwd = acc.automateReadHotspotPassword()
            if (!pwd.isNullOrBlank()) {
                Pair(true, "Your Hotspot password is \"$pwd\", Sir.")
            } else {
                openHotspotSettings(context)
                Pair(true, "Opened Hotspot settings. Please check your screen for the password.")
            }
        } else {
            openHotspotSettings(context)
            Pair(true, "Opened Hotspot settings. Please check your screen for the password.")
        }
    }

    fun openHotspotSettings(context: Context) {
        val intents = listOf(
            Intent().setComponent(android.content.ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent("android.settings.WIFI_AP_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------------
    // 4. Dual-SIM & Mobile Data Switching
    // ------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun switchMobileDataSim(context: Context, slotIndex: Int): Pair<Boolean, String> {
        val targetSlot = if (slotIndex in 1..2) slotIndex - 1 else 0
        val targetSimLabel = "SIM ${targetSlot + 1}"

        try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val activeSubs: List<SubscriptionInfo> = try {
                subManager?.activeSubscriptionInfoList ?: emptyList()
            } catch (e: Exception) { emptyList() }

            val targetSub = activeSubs.firstOrNull { it.simSlotIndex == targetSlot }

            if (targetSub != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // Try setDefaultDataSubId via reflection / direct if available
                    val method = subManager?.javaClass?.getMethod("setDefaultDataSubId", Int::class.javaPrimitiveType)
                    method?.isAccessible = true
                    method?.invoke(subManager, targetSub.subscriptionId)
                    Log.d(TAG, "setDefaultDataSubId called for subId: ${targetSub.subscriptionId}")
                } catch (e: Exception) {
                    Log.d(TAG, "setDefaultDataSubId direct invoke failed: ${e.message}")
                }
            }

            // Also dispatch Accessibility automation to ensure the UI switches the mobile data toggle
            val acc = JarvisAccessibilityService.instance
            if (acc != null) {
                acc.automateSimDataSwitch(targetSlot + 1)
            } else {
                openSimSettings(context)
            }

            return Pair(true, "Switched mobile data network to $targetSimLabel, Sir.")
        } catch (e: Exception) {
            Log.e(TAG, "switchMobileDataSim error", e)
            openSimSettings(context)
            JarvisAccessibilityService.instance?.automateSimDataSwitch(targetSlot + 1)
            return Pair(true, "Opening SIM settings to switch data to $targetSimLabel.")
        }
    }

    fun openSimSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------------
    // 5. Developer Options & Debugging Automation
    // ------------------------------------------------------------------------

    fun openDeveloperOptions(context: Context): Pair<Boolean, String> {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Developer options opened, Sir.")
        } catch (e: Exception) {
            Log.w(TAG, "Developer options not enabled, opening About phone to unlock", e)
            val acc = JarvisAccessibilityService.instance
            if (acc != null) {
                acc.unlockDeveloperOptions()
                Pair(true, "Developer options were locked. Unlocking them now by tapping Build Number 7 times.")
            } else {
                openAboutPhone(context)
                Pair(true, "Developer options not unlocked yet. Please tap Build Number 7 times in About Phone.")
            }
        }
    }

    /**
     * Immediate return is only ever "I'm on it" / "I can't do that" — never a claim that the
     * toggle already succeeded. The real outcome (verified against the actual switch state)
     * arrives later via [onVerifiedResult], which the caller should speak/toast to the user.
     */
    fun controlDeveloperOption(
        context: Context,
        optionName: String,
        enable: Boolean,
        onVerifiedResult: ((Boolean, String) -> Unit)? = null
    ): Pair<Boolean, String> {
        openDeveloperOptions(context)
        val acc = JarvisAccessibilityService.instance
        val stateText = if (enable) "turn on" else "turn off"
        return if (acc != null) {
            acc.automateDeveloperOptionToggle(optionName, enable) { success, message ->
                onVerifiedResult?.invoke(success, message)
            }
            Pair(true, "Working on it, Sir — let me $stateText $optionName.")
        } else {
            Pair(false, "I can't do that automatically, Sir — Accessibility permission for Jarvis isn't enabled. Please turn it on in Jarvis settings, or flip $optionName yourself; I've opened Developer options for you.")
        }
    }

    fun openAboutPhone(context: Context) {
        val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try { context.startActivity(intent) } catch (e: Exception) { Log.e(TAG, "openAboutPhone failed", e) }
    }

    // ------------------------------------------------------------------------
    // 6. Global System Settings Launcher & Toggles
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // 7. Battery Optimization Exemption (so the background voice service isn't killed)
    // ------------------------------------------------------------------------

    /**
     * Requests exemption from Android's battery optimization / Doze restrictions for this app,
     * using the standard system API (same mechanism WhatsApp, Spotify, etc. use). This ALWAYS
     * shows Android's own confirmation dialog — there is no way to grant this silently, by
     * design, since it lets an app keep running in the background and drain battery.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context): Pair<Boolean, String> {
        if (isIgnoringBatteryOptimizations(context)) {
            return Pair(true, "Battery optimization is already disabled for Jarvis, Sir.")
        }
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Pair(true, "I've opened the battery permission screen, Sir — please tap Allow so I keep running in the background.")
        } catch (e: Exception) {
            // Some OEMs (MIUI, ColorOS, etc.) don't support the direct intent — fall back to the settings list.
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
                Pair(true, "Opened the battery optimization list, Sir — please find Jarvis and set it to \"Don't optimize\" / \"Unrestricted\".")
            } catch (e2: Exception) {
                Pair(false, "I couldn't open battery settings, Sir: ${e2.message}")
            }
        }
    }

    fun openSystemSetting(context: Context, settingType: String): Pair<Boolean, String> {
        val cleanSetting = settingType.lowercase().trim()
        val intentAction = when {
            cleanSetting.contains("wifi") || cleanSetting.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
            cleanSetting.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            cleanSetting.contains("developer") -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            cleanSetting.contains("display") || cleanSetting.contains("screen") -> Settings.ACTION_DISPLAY_SETTINGS
            cleanSetting.contains("sound") || cleanSetting.contains("volume") || cleanSetting.contains("audio") -> Settings.ACTION_SOUND_SETTINGS
            cleanSetting.contains("location") || cleanSetting.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            cleanSetting.contains("airplane") || cleanSetting.contains("flight") -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            cleanSetting.contains("nfc") -> Settings.ACTION_NFC_SETTINGS
            cleanSetting.contains("battery") || cleanSetting.contains("power") -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            cleanSetting.contains("storage") || cleanSetting.contains("memory") -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            cleanSetting.contains("accessibility") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            cleanSetting.contains("app") || cleanSetting.contains("application") -> Settings.ACTION_APPLICATION_SETTINGS
            cleanSetting.contains("notification") -> Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS
            cleanSetting.contains("about") || cleanSetting.contains("device_info") -> Settings.ACTION_DEVICE_INFO_SETTINGS
            cleanSetting.contains("sim") || cleanSetting.contains("mobile_network") || cleanSetting.contains("cellular") -> Settings.ACTION_NETWORK_OPERATOR_SETTINGS
            cleanSetting.contains("hotspot") || cleanSetting.contains("tether") -> "android.settings.TETHER_SETTINGS"
            cleanSetting.contains("date") || cleanSetting.contains("time") -> Settings.ACTION_DATE_SETTINGS
            cleanSetting.contains("security") || cleanSetting.contains("lock") -> Settings.ACTION_SECURITY_SETTINGS
            cleanSetting.contains("privacy") -> Settings.ACTION_PRIVACY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        val intent = Intent(intentAction).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opened ${settingType.replace('_', ' ')} settings, Sir.")
        } catch (e: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            try { context.startActivity(fallback); Pair(true, "Opened device settings.") } catch (e2: Exception) {
                Pair(false, "Failed to open settings: ${e2.message}")
            }
        }
    }
}
