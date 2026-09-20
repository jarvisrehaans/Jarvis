package com.jarvis.assistant.productivity

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Manages JARVIS productivity features:
 * - Voice Notes (saved to SharedPreferences)
 * - Todo List (add, remove, list, mark done)
 * - Clipboard AI (read/write clipboard)
 * - Calendar Events (create, read)
 * - Alarms & Timers
 * - Daily Briefing (aggregates weather, calendar, todos)
 */
class ProductivityManager(private val context: Context) {

    companion object {
        private const val TAG = "ProductivityManager"
        private const val PREFS_NAME = "jarvis_productivity"
        private const val KEY_NOTES = "voice_notes"
        private const val KEY_TODOS = "todo_list"
        private const val KEY_LAST_CLIPBOARD = "last_clipboard_text"

        fun updateLastClipboard(context: Context, text: String) {
            if (text.isNotBlank()) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST_CLIPBOARD, text).apply()
            }
        }
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // ---------------------------------------------------------------
    // VOICE NOTES
    // ---------------------------------------------------------------

    /**
     * Saves a voice note with timestamp.
     */
    fun saveNote(content: String): JSONObject {
        val result = JSONObject()
        if (content.isBlank()) {
            result.put("success", false)
            result.put("message", "Note content is empty.")
            return result
        }

        val notes = getNotesList()
        val note = JSONObject().apply {
            put("id", System.currentTimeMillis())
            put("content", content)
            put("timestamp", SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date()))
        }
        notes.put(note)
        prefs.edit().putString(KEY_NOTES, notes.toString()).apply()

        result.put("success", true)
        result.put("message", "Note saved: \"$content\"")
        return result
    }

    /**
     * Returns all saved notes.
     */
    fun listNotes(): JSONObject {
        val result = JSONObject()
        val notes = getNotesList()
        if (notes.length() == 0) {
            result.put("success", true)
            result.put("message", "Aapka koi note saved nahi hai. (No notes saved yet.)")
            result.put("count", 0)
            result.put("notes", "")
            return result
        }

        val notesList = mutableListOf<String>()
        for (i in 0 until notes.length()) {
            val note = notes.getJSONObject(i)
            notesList.add("${i + 1}. ${note.optString("content")} (${note.optString("timestamp")})")
        }
        val formatted = notesList.joinToString("\n")
        result.put("success", true)
        result.put("count", notes.length())
        result.put("notes", formatted)
        result.put("message", "Aapke ${notes.length()} saved notes hain:\n$formatted")
        return result
    }

    /**
     * Deletes a note by index (1-based).
     */
    fun deleteNote(index: Int): JSONObject {
        val result = JSONObject()
        val notes = getNotesList()
        val idx = index - 1
        if (idx < 0 || idx >= notes.length()) {
            result.put("success", false)
            result.put("message", "Note number $index not found. You have ${notes.length()} notes.")
            return result
        }
        val deleted = notes.getJSONObject(idx).optString("content")
        notes.remove(idx)
        prefs.edit().putString(KEY_NOTES, notes.toString()).apply()
        result.put("success", true)
        result.put("message", "Deleted note: \"$deleted\"")
        return result
    }

    private fun getNotesList(): JSONArray {
        val raw = prefs.getString(KEY_NOTES, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    // ---------------------------------------------------------------
    // TODO LIST
    // ---------------------------------------------------------------

    fun addTodo(task: String): JSONObject {
        val result = JSONObject()
        if (task.isBlank()) {
            result.put("success", false)
            result.put("message", "Task description is empty.")
            return result
        }

        val todos = getTodoList()
        val todo = JSONObject().apply {
            put("id", System.currentTimeMillis())
            put("task", task)
            put("done", false)
            put("created", SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date()))
        }
        todos.put(todo)
        prefs.edit().putString(KEY_TODOS, todos.toString()).apply()

        result.put("success", true)
        result.put("message", "Added to-do: \"$task\"")
        result.put("total_todos", todos.length())
        return result
    }

    fun listTodos(): JSONObject {
        val result = JSONObject()
        val todos = getTodoList()
        if (todos.length() == 0) {
            result.put("success", true)
            result.put("message", "Your to-do list is empty. Nothing pending!")
            result.put("count", 0)
            return result
        }

        val todoStrings = mutableListOf<String>()
        var pendingCount = 0
        var doneCount = 0
        for (i in 0 until todos.length()) {
            val todo = todos.getJSONObject(i)
            val isDone = todo.optBoolean("done", false)
            val status = if (isDone) "✅" else "⬜"
            todoStrings.add("${i + 1}. $status ${todo.optString("task")}")
            if (isDone) doneCount++ else pendingCount++
        }
        val formatted = todoStrings.joinToString("\n")
        result.put("success", true)
        result.put("count", todos.length())
        result.put("pending", pendingCount)
        result.put("completed", doneCount)
        result.put("todos", formatted)
        result.put("message", "Aapki to-do list me ${todos.length()} items hain ($pendingCount pending):\n$formatted")
        return result
    }

    fun completeTodo(index: Int): JSONObject {
        val result = JSONObject()
        val todos = getTodoList()
        val idx = index - 1
        if (idx < 0 || idx >= todos.length()) {
            result.put("success", false)
            result.put("message", "To-do number $index not found.")
            return result
        }
        val todo = todos.getJSONObject(idx)
        todo.put("done", true)
        prefs.edit().putString(KEY_TODOS, todos.toString()).apply()
        result.put("success", true)
        result.put("message", "Marked as done: \"${todo.optString("task")}\" ✅")
        return result
    }

    fun removeTodo(index: Int): JSONObject {
        val result = JSONObject()
        val todos = getTodoList()
        val idx = index - 1
        if (idx < 0 || idx >= todos.length()) {
            result.put("success", false)
            result.put("message", "To-do number $index not found.")
            return result
        }
        val removed = todos.getJSONObject(idx).optString("task")
        todos.remove(idx)
        prefs.edit().putString(KEY_TODOS, todos.toString()).apply()
        result.put("success", true)
        result.put("message", "Removed to-do: \"$removed\"")
        return result
    }

    private fun getTodoList(): JSONArray {
        val raw = prefs.getString(KEY_TODOS, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    // ---------------------------------------------------------------
    // CLIPBOARD AI
    // ---------------------------------------------------------------

    /**
     * Reads the current clipboard text.
     */
    suspend fun readClipboard(): JSONObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val result = JSONObject()
        var text = ""
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct clipboard read error: ${e.message}")
        }

        // On Android 10+ (API 29+), background apps cannot read clipboard directly.
        // Momentarily launch transparent 1x1 activity to gain focus and capture clipboard without flicker.
        if (text.isBlank() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                ClipboardReaderActivity.latestClipboardCaptured = null
                ClipboardReaderActivity.isCaptureCompleted = false
                val intent = android.content.Intent(context, ClipboardReaderActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                context.startActivity(intent)

                var elapsed = 0
                while (!ClipboardReaderActivity.isCaptureCompleted && elapsed < 450) {
                    kotlinx.coroutines.delay(25L)
                    elapsed += 25
                }
                val captured = ClipboardReaderActivity.latestClipboardCaptured
                if (!captured.isNullOrBlank()) {
                    text = captured
                }
            } catch (e: Exception) {
                Log.w(TAG, "Transparent clipboard reader launch error: ${e.message}")
            }
        }

        if (text.isBlank()) {
            text = prefs.getString(KEY_LAST_CLIPBOARD, "") ?: ""
        }
        if (text.isNotBlank()) {
            result.put("success", true)
            result.put("content", text)
            result.put("clipboard_text", text)
            result.put("message", "Clipboard me ye text copy hai: \"$text\"")
        } else {
            result.put("success", true)
            result.put("content", "")
            result.put("clipboard_text", "")
            result.put("message", "Clipboard khali hai, abhi kuch copy nahi hai. (Clipboard is empty.)")
        }
        return@withContext result
    }

    /**
     * Writes text to the clipboard.
     */
    fun writeClipboard(text: String): JSONObject {
        val result = JSONObject()
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Jarvis Clipboard", text)
            cm?.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.d(TAG, "Direct clipboard write error: ${e.message}")
        }
        prefs.edit().putString(KEY_LAST_CLIPBOARD, text).apply()
        result.put("success", true)
        result.put("content", text)
        result.put("clipboard_text", text)
        result.put("message", "Clipboard me copy kar diya hai: \"${text.take(100)}\"")
        return result
    }

    // ---------------------------------------------------------------
    // ALARMS & TIMERS
    // ---------------------------------------------------------------

    /**
     * Sets an alarm using Android's AlarmClock intent.
     */
    fun setAlarm(hour: Int, minute: Int, label: String = "Jarvis Alarm"): JSONObject {
        val result = JSONObject()
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            result.put("success", true)
            result.put("message", "Alarm set for ${String.format("%02d:%02d", hour, minute)} — $label")
        } catch (e: Exception) {
            result.put("success", false)
            result.put("message", "Could not set alarm: ${e.message}")
        }
        return result
    }

    /**
     * Sets a countdown timer using Android's AlarmClock intent.
     */
    fun setTimer(seconds: Int, label: String = "Jarvis Timer"): JSONObject {
        val result = JSONObject()
        try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val mins = seconds / 60
            val secs = seconds % 60
            val timeStr = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
            result.put("success", true)
            result.put("message", "Timer set for $timeStr — $label")
        } catch (e: Exception) {
            result.put("success", false)
            result.put("message", "Could not set timer: ${e.message}")
        }
        return result
    }

    // ---------------------------------------------------------------
    // CALENDAR EVENTS
    // ---------------------------------------------------------------

    /**
     * Creates a calendar event using the system Calendar provider.
     */
    fun createCalendarEvent(title: String, description: String = "", startTimeMillis: Long = 0, durationMinutes: Int = 60): JSONObject {
        val result = JSONObject()
        try {
            val startTime = if (startTimeMillis > 0) startTimeMillis else System.currentTimeMillis() + 3600_000L
            val endTime = startTime + (durationMinutes * 60_000L)

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, description)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            result.put("success", true)
            result.put("message", "Creating calendar event: \"$title\"")
        } catch (e: Exception) {
            result.put("success", false)
            result.put("message", "Could not create calendar event: ${e.message}")
        }
        return result
    }

    /**
     * Reads today's calendar events.
     */
    fun getTodayEvents(): JSONObject {
        val result = JSONObject()
        try {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            val dayStart = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val dayEnd = cal.timeInMillis

            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION
            )
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?"
            val selArgs = arrayOf(dayStart.toString(), dayEnd.toString())
            val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection, selection, selArgs, sortOrder
            )

            val events = mutableListOf<String>()
            val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

            cursor?.use {
                while (it.moveToNext()) {
                    val title = it.getString(0) ?: "Untitled"
                    val start = it.getLong(1)
                    val location = it.getString(3) ?: ""
                    val timeStr = timeFormat.format(Date(start))
                    val locationStr = if (location.isNotBlank()) " at $location" else ""
                    events.add("• $timeStr — $title$locationStr")
                }
            }

            if (events.isEmpty()) {
                result.put("success", true)
                result.put("message", "No events scheduled for today. Your day is free!")
                result.put("count", 0)
            } else {
                result.put("success", true)
                result.put("count", events.size)
                result.put("events", events.joinToString("\n"))
            }
        } catch (e: Exception) {
            result.put("success", false)
            result.put("message", "Could not read calendar: ${e.message}. Calendar permission may be needed.")
        }
        return result
    }

    // ---------------------------------------------------------------
    // DAILY BRIEFING
    // ---------------------------------------------------------------

    /**
     * Generates a daily briefing combining calendar events, pending todos,
     * and current date/time information.
     */
    fun getDailyBriefing(): JSONObject {
        val result = JSONObject()
        try {
            val dateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val now = Date()

            val briefing = StringBuilder()
            briefing.appendLine("📅 Today is ${dateFormat.format(now)}")
            briefing.appendLine("⏰ Current time: ${timeFormat.format(now)}")
            briefing.appendLine()

            // Calendar events
            val calendarResult = getTodayEvents()
            val eventCount = calendarResult.optInt("count", 0)
            if (eventCount > 0) {
                briefing.appendLine("📋 Today's Schedule ($eventCount events):")
                briefing.appendLine(calendarResult.optString("events"))
            } else {
                briefing.appendLine("📋 No events scheduled for today — your day is free!")
            }
            briefing.appendLine()

            // Pending todos
            val todosResult = listTodos()
            val pendingCount = todosResult.optInt("pending", 0)
            val totalCount = todosResult.optInt("count", 0)
            if (totalCount > 0) {
                briefing.appendLine("✅ To-Do List: $pendingCount pending, ${todosResult.optInt("completed", 0)} completed")
                briefing.appendLine(todosResult.optString("todos"))
            } else {
                briefing.appendLine("✅ No pending to-dos. All caught up!")
            }

            result.put("success", true)
            result.put("briefing", briefing.toString())
            result.put("event_count", eventCount)
            result.put("pending_todos", pendingCount)
        } catch (e: Exception) {
            result.put("success", false)
            result.put("message", "Could not generate daily briefing: ${e.message}")
        }
        return result
    }
}
