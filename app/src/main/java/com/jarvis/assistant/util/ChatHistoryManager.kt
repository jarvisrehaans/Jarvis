package com.jarvis.assistant.util

import android.content.Context
import com.jarvis.assistant.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages persistent storage of conversation history in local device storage.
 * Ensures all messages are safely serialized and preserved across app sessions.
 */
object ChatHistoryManager {

    private const val FILE_NAME = "chat_history.json"
    private const val MAX_HISTORY_ITEMS = 300
    private val FILE_LOCK = Any()

    fun loadHistory(context: Context): List<ChatMessage> {
        synchronized(FILE_LOCK) {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()

            return try {
                val jsonStr = file.readText()
                val array = JSONArray(jsonStr)
                val list = mutableListOf<ChatMessage>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val text = obj.optString("text", "").trim()
                    val isUser = obj.optBoolean("isUser", false)
                    val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    if (text.isNotBlank()) {
                        list.add(ChatMessage(text, isUser, timestamp))
                    }
                }
                list
            } catch (e: Exception) {
                android.util.Log.e("ChatHistoryManager", "Failed to load chat history", e)
                emptyList()
            }
        }
    }

    fun saveMessage(context: Context, message: ChatMessage) {
        saveMessages(context, listOf(message))
    }

    fun saveMessages(context: Context, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        synchronized(FILE_LOCK) {
            try {
                val history = loadHistory(context).toMutableList()
                for (msg in messages) {
                    val text = msg.text.trim()
                    if (text.isNotBlank()) {
                        history.add(ChatMessage(text, msg.isUser, msg.timestamp))
                    }
                }

                while (history.size > MAX_HISTORY_ITEMS) {
                    history.removeAt(0)
                }

                val array = JSONArray()
                for (msg in history) {
                    val obj = JSONObject().apply {
                        put("text", msg.text)
                        put("isUser", msg.isUser)
                        put("timestamp", msg.timestamp)
                    }
                    array.put(obj)
                }
                val file = File(context.filesDir, FILE_NAME)
                file.writeText(array.toString(2))
            } catch (e: Exception) {
                android.util.Log.e("ChatHistoryManager", "Failed to save chat history", e)
            }
        }
    }

    fun saveAll(context: Context, history: List<ChatMessage>) {
        synchronized(FILE_LOCK) {
            try {
                val array = JSONArray()
                for (msg in history) {
                    val text = msg.text.trim()
                    if (text.isNotBlank()) {
                        val obj = JSONObject().apply {
                            put("text", text)
                            put("isUser", msg.isUser)
                            put("timestamp", msg.timestamp)
                        }
                        array.put(obj)
                    }
                }
                val file = File(context.filesDir, FILE_NAME)
                file.writeText(array.toString(2))
            } catch (e: Exception) {
                android.util.Log.e("ChatHistoryManager", "Failed to saveAll chat history", e)
            }
        }
    }

    fun clearHistory(context: Context) {
        synchronized(FILE_LOCK) {
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (file.exists()) {
                    file.delete()
                }
                Unit
            } catch (e: Exception) {
                android.util.Log.e("ChatHistoryManager", "Failed to clear chat history", e)
            }
        }
    }

    fun cleanToHinglish(input: String): String {
        return input.trim()
    }
}
