package com.jarvis.assistant.productivity

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * Transparent 1x1 activity that momentarily gains window focus to read the
 * system clipboard on Android 10+ (API 29+) where background clipboard reading
 * is restricted by the OS.
 */
class ClipboardReaderActivity : Activity() {

    companion object {
        private const val TAG = "ClipboardReader"
        @Volatile var latestClipboardCaptured: String? = null
        @Volatile var isCaptureCompleted = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setDimAmount(0f)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            readAndFinish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Fallback in case onWindowFocusChanged was already called before listener attached
        window.decorView.postDelayed({
            readAndFinish()
        }, 60L)
    }

    private fun readAndFinish() {
        if (isFinishing || isDestroyed) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            var text = ""
            if (clip != null && clip.itemCount > 0) {
                text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
            }
            latestClipboardCaptured = text
            isCaptureCompleted = true
            ProductivityManager.updateLastClipboard(this, text)
            Log.d(TAG, "Captured clipboard successfully: \"$text\"")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read clipboard in activity: ${e.message}")
            isCaptureCompleted = true
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
