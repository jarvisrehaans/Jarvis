package com.jarvis.assistant

import android.app.Application
import android.content.Context
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
     * Installs a global uncaught exception handler that saves the full
     * stack trace to SharedPreferences so the error overlay in MainActivity
     * can display it on next launch. After saving, the default handler
     * runs (to let the OS show the crash dialog / kill the process).
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
            } catch (e: Exception) {
                // If we fail to save, still let the default handler run
                Log.e(TAG, "Failed to save crash log", e)
            }
            // Forward to default handler (shows crash dialog, kills process)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
