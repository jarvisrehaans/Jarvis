package com.jarvis.assistant.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

/**
 * Manages Firebase Remote Config for Jarvis AI.
 */
object FirebaseManager {

    private const val TAG = "FirebaseManager"
    private const val KEY_YOUTUBE_API_KEY = "youtube_api_key"

    private var remoteConfig: FirebaseRemoteConfig? = null

    fun init(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }

            remoteConfig = FirebaseRemoteConfig.getInstance().apply {
                val configSettings = FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(0)
                    .build()
                setConfigSettingsAsync(configSettings)

                val defaults = mapOf<String, Any>(
                    KEY_YOUTUBE_API_KEY to ""
                )
                setDefaultsAsync(defaults)

                fetchAndActivate().addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val fetchedKey = getString(KEY_YOUTUBE_API_KEY)
                        Log.d(TAG, "Firebase Remote Config fetched. Key present? ${fetchedKey.isNotBlank()}")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FirebaseManager", e)
        }
    }

    fun getYoutubeApiKey(): String {
        return try {
            val key = remoteConfig?.getString(KEY_YOUTUBE_API_KEY)?.trim() ?: ""
            if (key != "YOUR_YOUTUBE_DATA_API_KEY") key else ""
        } catch (e: Exception) {
            Log.e(TAG, "Error reading YouTube API key from Firebase Remote Config", e)
            ""
        }
    }
}
