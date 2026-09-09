package com.jarvis.assistant.util

import android.content.Context
import com.jarvis.assistant.JarvisApplication
import java.io.InputStream
import java.util.Properties

/**
 * Utility to load variables from SharedPreferences and assets/env.properties file.
 */
object EnvLoader {
    private var cachedApiKey: String? = null
    private var cachedYoutubeKey: String? = null
    fun resetCache() {
        cachedApiKey = null
        cachedYoutubeKey = null
    }

    fun getApiKey(context: Context): String {
        cachedApiKey?.takeIf { it.isNotBlank() }?.let { return it }

        val prefsKey = context.getSharedPreferences(JarvisApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("api_key", "")?.trim() ?: ""
        if (prefsKey.isNotEmpty()) {
            cachedApiKey = prefsKey
            return prefsKey
        }

        return try {
            val assetManager = context.assets
            val inputStream = assetManager.open("env.properties")
            val properties = Properties()
            properties.load(inputStream)
            val apiKey = properties.getProperty("GEMINI_API_KEY")?.trim() ?: ""
            if (apiKey.isNotEmpty() && !apiKey.contains("YOUR_GEMINI_API_KEY")) {
                cachedApiKey = apiKey
                apiKey
            } else {
                ""
            }
        } catch (e: Exception) {
            android.util.Log.e("EnvLoader", "Failed to load API key from env.properties", e)
            ""
        }
    }

    fun getYoutubeApiKey(context: Context): String {
        cachedYoutubeKey?.let { return it }

        // 1. Check Firebase Remote Config backend first for secure key
        val firebaseKey = com.jarvis.assistant.firebase.FirebaseManager.getYoutubeApiKey()
        if (firebaseKey.isNotBlank()) {
            cachedYoutubeKey = firebaseKey
            return firebaseKey
        }

        // 2. Fallback to local env.properties if Firebase key is not configured
        return try {
            val properties = Properties()
            properties.load(context.assets.open("env.properties"))
            val key = properties.getProperty("YOUTUBE_API_KEY")?.trim() ?: ""
            if (key.isNotEmpty() && !key.contains("YOUR_YOUTUBE_DATA_API_KEY")) {
                cachedYoutubeKey = key
                key
            } else {
                ""
            }
        } catch (e: Exception) {
            android.util.Log.e("EnvLoader", "Failed to load YouTube API key from env.properties", e)
            ""
        }
    }
}