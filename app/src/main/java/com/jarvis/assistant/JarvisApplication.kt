package com.jarvis.assistant

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log

class JarvisApplication : Application() {

    companion object {
        const val PREFS_NAME = "jarvis_prefs"
        private const val CRASH_PREFS = "jarvis_crash_log"
        private const val TAG = "JarvisApplication"
    }

    override fun onCreate() {
        super.onCreate()
        com.jarvis.assistant.security.SecurityGuard.performSecurityAudit(this)
        com.jarvis.assistant.firebase.FirebaseManager.init(this)
        installCrashHandler()
    }

    /**
     * Installs a global uncaught exception handler that:
     * 1. Saves the full stack trace to SharedPreferences for debugging.
     * 2. Launches CrashRecoveryActivity with a SIMPLE, FRIENDLY message
     *    (no technical jargon) so the user sees "Oops! Jarvis ran into a problem"
     *    instead of the ugly default Android "App has stopped" dialog.
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stackTrace = buildString {
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                    appendLine()
                    for (element in throwable.stackTrace) {
                        appendLine("  at $element")
                    }
                    var cause = throwable.cause
                    while (cause != null) {
                        appendLine()
                        appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
                        for (element in cause.stackTrace) {
                            appendLine("  at $element")
                        }
                        cause = cause.cause
                    }
                }
                // Save to SharedPreferences — lightweight, no disk I/O dependency
                getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", stackTrace)
                    .commit() // commit() not apply() — must be synchronous before process dies
                Log.e(TAG, "Saved crash log to SharedPreferences", throwable)

                // Generate a simple, friendly crash message (no technical jargon)
                val friendlyMessage = generateFriendlyCrashMessage(throwable)

                // Launch CrashRecoveryActivity with the friendly message
                val crashIntent = Intent(applicationContext, com.jarvis.assistant.ui.CrashRecoveryActivity::class.java).apply {
                    putExtra("crash_reason", friendlyMessage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                applicationContext.startActivity(crashIntent)
            } catch (e: Exception) {
                // If we fail to launch the friendly screen, fall back to default handler
                Log.e(TAG, "Failed to launch CrashRecoveryActivity", e)
                defaultHandler?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }

            // Kill the process after launching the recovery activity
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(1)
        }
    }

    /**
     * Converts a technical exception into a simple, easy-to-understand message.
     * No stack traces, no class names — just plain language.
     */
    private fun generateFriendlyCrashMessage(throwable: Throwable): String {
        val exName = throwable.javaClass.simpleName.lowercase()
        val msg = throwable.message?.lowercase() ?: ""

        return when {
            exName.contains("outofmemory") || msg.contains("out of memory") ->
                "Jarvis used too much memory and had to stop. Don't worry — just restart and everything will be fine! 😊"
            
            exName.contains("security") || msg.contains("permission") ->
                "Jarvis needed a permission that wasn't granted. Please restart and check your app permissions in Settings. 🔐"
            
            msg.contains("network") || msg.contains("connection") || msg.contains("socket") ->
                "Jarvis lost internet connection for a moment. Please check your Wi-Fi/data and restart. 📶"
            
            msg.contains("media") || msg.contains("projection") || msg.contains("screen") ->
                "Screen sharing ran into a small issue. Just restart Jarvis and try again! 📱"
            
            msg.contains("camera") || msg.contains("vision") ->
                "The camera had a small hiccup. Restart Jarvis and it should work fine! 📸"
            
            msg.contains("audio") || msg.contains("microphone") || msg.contains("recording") ->
                "There was a small issue with the microphone. Restart Jarvis to fix it! 🎤"
            
            else ->
                "Don't worry, your data is safe! This was just a small glitch. Tap the button below to restart Jarvis. 💪"
        }
    }
}

