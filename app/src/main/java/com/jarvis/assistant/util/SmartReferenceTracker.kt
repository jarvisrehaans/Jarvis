package com.jarvis.assistant.util

import android.util.Log

/**
 * SmartReferenceTracker: Contextual Anaphora & Entity Tracker for JARVIS.
 * Keeps track of the most recently mentioned person, app, website, and action.
 * Allows JARVIS to understand pronouns and references ("call him", "usko message karo",
 * "open it", "wahan navigate karo") without asking "who?".
 */
object SmartReferenceTracker {

    private const val TAG = "SmartReferenceTracker"

    @Volatile private var lastContactName: String? = null
    @Volatile private var lastContactNumber: String? = null
    @Volatile private var lastContactChannel: String? = null // "phone", "whatsapp"
    @Volatile private var lastAppOpened: String? = null
    @Volatile private var lastUrlVisited: String? = null
    @Volatile private var lastActionDescription: String? = null
    @Volatile private var lastActionTimestamp: Long = 0L

    fun setLastContact(name: String, number: String? = null, channel: String = "contact") {
        if (name.isBlank()) return
        lastContactName = name.trim()
        if (!number.isNullOrBlank()) lastContactNumber = number.trim()
        lastContactChannel = channel
        lastActionTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Reference updated: Contact=$name, Number=$number, Channel=$channel")
    }

    fun setLastApp(appName: String) {
        if (appName.isBlank()) return
        lastAppOpened = appName.trim()
        lastActionTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Reference updated: App=$appName")
    }

    fun setLastUrl(url: String) {
        if (url.isBlank()) return
        lastUrlVisited = url.trim()
        lastActionTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Reference updated: Url=$url")
    }

    fun setLastAction(description: String) {
        if (description.isBlank()) return
        lastActionDescription = description.trim()
        lastActionTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Reference updated: Action=$description")
    }

    fun getLastContactName(): String? = lastContactName
    fun getLastContactNumber(): String? = lastContactNumber
    fun getLastAppOpened(): String? = lastAppOpened
    fun getLastUrlVisited(): String? = lastUrlVisited

    /**
     * Formats recent context into a prompt block so Gemini Live knows what "him/her/it/usko" refers to.
     */
    fun getFormattedContextForPrompt(): String {
        val lines = mutableListOf<String>()
        lastContactName?.let { contact ->
            val numStr = if (!lastContactNumber.isNullOrBlank()) " ($lastContactNumber)" else ""
            val chStr = if (!lastContactChannel.isNullOrBlank()) " via $lastContactChannel" else ""
            lines.add("- Last Contact: $contact$numStr$chStr")
        }
        lastAppOpened?.let { lines.add("- Last App Opened: $it") }
        lastUrlVisited?.let { lines.add("- Last Website: $it") }
        lastActionDescription?.let { lines.add("- Last Action: $it") }

        if (lines.isEmpty()) {
            return "No recent conversational entities yet."
        }

        val sb = StringBuilder()
        for (line in lines) {
            sb.append(line).append("\n")
        }
        sb.append("COREFERENCE RESOLUTION RULE:\n")
        sb.append("If the user says 'call him/her', 'usko message karo', 'send it to him', 'open it', or 'wahan jao', resolve 'him/her/usko' to the Last Contact ($lastContactName) and 'it' to the Last App/Website ($lastAppOpened) naturally without asking 'who?' unless ambiguous.")
        return sb.toString().trim()
    }

    fun clear() {
        lastContactName = null
        lastContactNumber = null
        lastContactChannel = null
        lastAppOpened = null
        lastUrlVisited = null
        lastActionDescription = null
        lastActionTimestamp = 0L
    }
}
