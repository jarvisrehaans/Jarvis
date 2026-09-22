package com.jarvis.assistant.util

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SmartMemoryManager: Persistent Long-Term Memory Engine for JARVIS.
 * Stores user preferences, parking spots, birthdays, personal facts, and habits.
 * Memories are injected directly into Gemini Live system prompt for instant 0ms recall.
 */
object SmartMemoryManager {

    private const val TAG = "SmartMemoryManager"
    private const val MEMORY_FILE_NAME = "jarvis_smart_memories.json"

    data class MemoryItem(
        val id: String = UUID.randomUUID().toString(),
        val key: String,
        val content: String,
        val category: String = "general",
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun toJsonObject(): JSONObject = JSONObject().apply {
            put("id", id)
            put("key", key)
            put("content", content)
            put("category", category)
            put("timestamp", timestamp)
        }

        companion object {
            fun fromJsonObject(json: JSONObject): MemoryItem {
                return MemoryItem(
                    id = json.optString("id", UUID.randomUUID().toString()),
                    key = json.optString("key", ""),
                    content = json.optString("content", ""),
                    category = json.optString("category", "general"),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            }
        }
    }

    private val memoryCache = ConcurrentHashMap<String, MemoryItem>()
    private var isInitialized = false
    private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (isInitialized && appContext != null) return
        appContext = context.applicationContext
        loadMemoriesFromDisk()
        isInitialized = true
        Log.i(TAG, "SmartMemoryManager initialized with ${memoryCache.size} memories.")
    }

    private fun getMemoryFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, MEMORY_FILE_NAME)
    }

    @Synchronized
    private fun loadMemoriesFromDisk() {
        try {
            val file = getMemoryFile() ?: return
            if (!file.exists()) return

            val jsonStr = file.readText(Charsets.UTF_8)
            if (jsonStr.isBlank()) return

            val array = JSONArray(jsonStr)
            memoryCache.clear()
            for (i in 0 until array.length()) {
                val itemJson = array.getJSONObject(i)
                val item = MemoryItem.fromJsonObject(itemJson)
                if (item.key.isNotBlank()) {
                    memoryCache[item.key.lowercase().trim()] = item
                }
            }
            consolidateSongMemories()
            Log.d(TAG, "Loaded ${memoryCache.size} memories from disk.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading memories: ${e.message}", e)
        }
    }

    private fun consolidateSongMemories() {
        val hasSongList = memoryCache.keys.any { (it.contains("top") || it.contains("songs")) && it.contains("song") }
        if (hasSongList) {
            val toRemove = memoryCache.keys.filter { it == "favorite_song" || it == "fav_song" }
            if (toRemove.isNotEmpty()) {
                for (k in toRemove) {
                    memoryCache.remove(k)
                    Log.i(TAG, "Consolidated and removed obsolete singular key: $k")
                }
                saveMemoriesToDisk()
            }
        }
    }

    @Synchronized
    private fun saveMemoriesToDisk() {
        try {
            val file = getMemoryFile() ?: return
            val array = JSONArray()
            for (item in memoryCache.values) {
                array.put(item.toJsonObject())
            }
            file.writeText(array.toString(), Charsets.UTF_8)
            Log.d(TAG, "Saved ${memoryCache.size} memories to disk.")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving memories: ${e.message}", e)
        }
    }

    /**
     * Store or update a memory fact.
     * E.g. key="car_parking", content="Parked at B-2 slot 44", category="place"
     */
    fun saveMemory(key: String, content: String, category: String = "general"): Boolean {
        if (key.isBlank() || content.isBlank()) return false
        val cleanKey = key.lowercase().trim()

        // Smart consolidation: if storing a list or top songs, purge singular favorite_song
        if (cleanKey.contains("song")) {
            val isList = cleanKey.contains("top") || cleanKey.contains("songs") || content.contains(",")
            if (isList) {
                val keysToRemove = memoryCache.keys.filter { it != cleanKey && (it == "favorite_song" || it == "fav_song") }
                for (k in keysToRemove) {
                    memoryCache.remove(k)
                    Log.i(TAG, "Consolidated and purged singular key: $k in favor of $cleanKey")
                }
            }
        }

        val item = MemoryItem(
            key = cleanKey,
            content = content.trim(),
            category = category.lowercase().trim(),
            timestamp = System.currentTimeMillis()
        )
        memoryCache[cleanKey] = item
        saveMemoriesToDisk()
        Log.i(TAG, "Remembered: [$cleanKey] -> '$content' ($category)")
        return true
    }

    /**
     * Retrieve a memory fact by key, or fuzzy match if exact key not found.
     */
    fun getMemory(keyOrQuery: String): String? {
        if (keyOrQuery.isBlank()) return null
        val clean = keyOrQuery.lowercase().trim()
        
        // Exact match
        memoryCache[clean]?.let { return it.content }

        // Partial match
        for ((k, item) in memoryCache) {
            if (clean.contains(k) || k.contains(clean)) {
                return item.content
            }
        }
        return null
    }

    /**
     * Delete a memory fact by key.
     */
    fun deleteMemory(keyOrQuery: String): Boolean {
        if (keyOrQuery.isBlank()) return false
        val clean = keyOrQuery.lowercase().trim()
        val removed = memoryCache.remove(clean) != null
        if (!removed) {
            val toRemove = memoryCache.keys.firstOrNull { clean.contains(it) || it.contains(clean) }
            if (toRemove != null) {
                memoryCache.remove(toRemove)
                saveMemoriesToDisk()
                Log.i(TAG, "Deleted memory for: $toRemove")
                return true
            }
        } else {
            saveMemoriesToDisk()
            Log.i(TAG, "Deleted memory for: $clean")
            return true
        }
        return false
    }

    /**
     * List all active memories as structured objects.
     */
    fun listAllMemories(): List<MemoryItem> {
        return memoryCache.values.sortedByDescending { it.timestamp }
    }

    /**
     * Formats all memories into a clean, concise bulleted list for system prompt injection.
     * This provides Gemini Live with instant 0ms recall without needing an API roundtrip!
     */
    fun getFormattedMemoriesForPrompt(): String {
        if (memoryCache.isEmpty()) {
            return "No stored memories yet."
        }
        val sb = java.lang.StringBuilder()
        val hasSongList = memoryCache.keys.any { (it.contains("top") || it.contains("songs")) && it.contains("song") }
        for ((k, item) in memoryCache) {
            if (hasSongList && (k == "favorite_song" || k == "fav_song")) {
                continue // Suppress single song entry when full top songs list is present
            }
            sb.append("- ").append(item.key.replace('_', ' ').capitalizeFirstLetter())
                .append(": ").append(item.content).append("\n")
        }
        return sb.toString().trim()
    }

    private fun String.capitalizeFirstLetter(): String {
        if (isEmpty()) return this
        return substring(0, 1).uppercase() + substring(1)
    }
}
