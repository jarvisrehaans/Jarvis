package com.jarvis.assistant.util

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

/**
 * Manages exact alarms, countdown timers, and reminders.
 */
object AlarmTimerManager {
    private const val TAG = "AlarmTimerManager"

    /**
     * Sets an alarm for exact [hour] (0..23) and [minute] (0..59) with optional [label].
     */
    fun setAlarm(context: Context, hour: Int, minute: Int, label: String = "JARVIS Alarm"): Pair<Boolean, String> {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "JARVIS Alarm" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val timeStr = String.format("%02d:%02d", h, m)
            Pair(true, "Alarm set for $timeStr with label '$label'.")
        } catch (e: Exception) {
            Log.e(TAG, "setAlarm intent failed: ${e.message}", e)
            Pair(false, "Could not set alarm: ${e.localizedMessage}")
        }
    }

    /**
     * Sets a countdown timer for [seconds] duration with optional [label].
     */
    fun setTimer(context: Context, seconds: Int, label: String = "JARVIS Timer"): Pair<Boolean, String> {
        val duration = seconds.coerceAtLeast(1)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, duration)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "JARVIS Timer" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val mins = duration / 60
            val secs = duration % 60
            val durStr = if (mins > 0) "$mins min $secs sec" else "$secs seconds"
            Pair(true, "Timer set for $durStr.")
        } catch (e: Exception) {
            Log.e(TAG, "setTimer intent failed: ${e.message}", e)
            Pair(false, "Could not set timer: ${e.localizedMessage}")
        }
    }

    /**
     * Sets a task reminder alert.
     */
    fun setReminder(context: Context, title: String, delayMinutes: Int): Pair<Boolean, String> {
        val delaySecs = (delayMinutes.coerceAtLeast(1)) * 60
        return setTimer(context, delaySecs, "Reminder: $title")
    }
}
