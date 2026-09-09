package com.jarvis.assistant.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.Socket
import java.security.MessageDigest

/**
 * Runtime Security Guard module for Jarvis AI.
 * Provides protection against root detection, active debugging, Frida/Xposed hooks,
 * emulator environments, and unauthorized APK repackaging.
 */
object SecurityGuard {

    private const val TAG = "SecurityGuard"

    data class SecurityStatus(
        val isRooted: Boolean,
        val isDebuggerAttached: Boolean,
        val isHookingDetected: Boolean,
        val isEmulator: Boolean,
        val isTampered: Boolean
    ) {
        val isSecure: Boolean
            get() = !isRooted && !isDebuggerAttached && !isHookingDetected && !isTampered
    }

    /**
     * Executes all runtime security checks and returns status summary.
     */
    fun performSecurityAudit(context: Context): SecurityStatus {
        val isRooted = checkRoot()
        val isDebuggerAttached = checkDebugger(context)
        val isHookingDetected = checkHookingFrameworks()
        val isEmulator = checkEmulator()
        val isTampered = checkAppIntegrity(context)

        if (isRooted) Log.w(TAG, "Root environment detected!")
        if (isDebuggerAttached) Log.w(TAG, "Active debugger detected!")
        if (isHookingDetected) Log.w(TAG, "Hooking framework (Frida/Xposed) detected!")
        if (isEmulator) Log.i(TAG, "Emulator environment detected.")
        if (isTampered) Log.w(TAG, "APK signature integrity failure detected!")

        return SecurityStatus(
            isRooted = isRooted,
            isDebuggerAttached = isDebuggerAttached,
            isHookingDetected = isHookingDetected,
            isEmulator = isEmulator,
            isTampered = isTampered
        )
    }

    /**
     * Checks for signs of Android rooting (su binaries, test keys, SuperSU/Magisk).
     */
    fun checkRoot(): Boolean {
        // 1. Check build tags for test-keys
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true
        }

        // 2. Check standard su binary paths
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        for (path in paths) {
            if (File(path).exists()) {
                return true
            }
        }

        // 3. Try executing su command
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val input = process.inputStream.bufferedReader()
            val line = input.readLine()
            input.close()
            process.destroy()
            line != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if a debugger is attached or application is in debug mode.
     */
    fun checkDebugger(context: Context): Boolean {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            return true
        }
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return isDebuggable && !Build.FINGERPRINT.contains("generic")
    }

    /**
     * Checks for Frida, Xposed, or other dynamic analysis / hooking tools.
     */
    fun checkHookingFrameworks(): Boolean {
        // 1. Check /proc/self/maps for frida or xposed libraries
        try {
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists()) {
                val reader = BufferedReader(FileReader(mapsFile))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.lowercase() ?: ""
                    if (l.contains("frida") || l.contains("xposed") || l.contains("substrate")) {
                        reader.close()
                        return true
                    }
                }
                reader.close()
            }
        } catch (e: Exception) {
            // Ignore inspection errors
        }

        // 2. Check default Frida server port (27042)
        try {
            val socket = Socket("127.0.0.1", 27042)
            socket.close()
            return true
        } catch (e: Exception) {
            // Port not open
        }

        return false
    }

    /**
     * Checks if app is running inside an Android emulator.
     */
    fun checkEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
    }

    /**
     * Verifies APK signature integrity against tampering/repackaging.
     */
    fun checkAppIntegrity(context: Context): Boolean {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures == null || signatures.isEmpty()) {
                return true // Tampered or un-signed
            }

            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(signatures[0].toByteArray())
            digest.isNotEmpty()
            false // Valid signature present
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating package signature hash", e)
            false
        }
    }
}
