package com.jarvis.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.main.MainActivity

/**
 * Friendly crash recovery screen shown when the app encounters an unhandled exception.
 *
 * Instead of showing the ugly default Android "App has stopped" dialog with a
 * cryptic stack trace, this shows a dark-themed screen with a simple emoji icon
 * and a message in easy-to-understand language ("Oops! Jarvis ran into a problem").
 *
 * The user can tap "Restart Jarvis" to relaunch the app fresh, or "Close App"
 * to just exit. No technical jargon is shown to the user.
 */
class CrashRecoveryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_recovery)

        // Extract the friendly crash reason from the intent (if available)
        val crashReason = intent.getStringExtra("crash_reason") ?: ""
        if (crashReason.isNotBlank()) {
            findViewById<TextView>(R.id.crashMessage)?.text = crashReason
        }

        // Restart button → relaunch MainActivity fresh
        findViewById<MaterialButton>(R.id.btnRestart)?.setOnClickListener {
            val restartIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(restartIntent)
            finish()
        }

        // Close button → just finish and exit
        findViewById<MaterialButton>(R.id.btnClose)?.setOnClickListener {
            finishAffinity()
        }
    }

    override fun onBackPressed() {
        // Prevent going back to the crashed state
        finishAffinity()
    }
}
