package com.jarvis.assistant.util

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Universal helper to safely launch activities from background services
 * (e.g. JarvisVoiceService) across Android 10, 11, 12, 13, 14, 15 without
 * getting blocked by Android's Background Activity Launch (BAL) restrictions.
 */
object ActivityLauncherHelper {

    private const val TAG = "ActivityLauncherHelper"

    fun startActivitySafely(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // 1. If JarvisAccessibilityService is running, use it directly.
        // Android explicitly exempts AccessibilityService from BAL restrictions.
        val a11y = com.jarvis.assistant.service.JarvisAccessibilityService.instance
        if (a11y != null) {
            try {
                a11y.startActivity(intent)
                Log.d(TAG, "Launched activity via AccessibilityService: ${intent.component ?: intent.`package` ?: intent.action}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "AccessibilityService.startActivity failed: ${e.message}")
            }
        }

        // 2. PendingIntent approach with background activity start allowance (Android 14+ / API 34)
        try {
            val requestCode = (System.currentTimeMillis() % 10000).toInt()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getActivity(context, requestCode, intent, flags)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                pendingIntent.send()
            }
            Log.d(TAG, "Launched activity via PendingIntent: ${intent.component ?: intent.`package` ?: intent.action}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent.send failed: ${e.message}")
        }

        // 3. Fallback to direct context.startActivity
        return try {
            context.startActivity(intent)
            Log.d(TAG, "Launched activity via context.startActivity: ${intent.component ?: intent.`package` ?: intent.action}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "context.startActivity failed: ${e.message}", e)
            false
        }
    }
}
