package com.jarvis.assistant.youtube

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import com.jarvis.assistant.util.EnvLoader

/**
 * Everything JARVIS needs to actually control YouTube:
 *  - search + jump straight to a video (deep link, skips typing in-app)
 *  - play/pause/skip via standard Android media key events (works because
 *    YouTube registers a MediaSession while a video is playing, the same
 *    mechanism headset buttons use)
 *  - volume control
 */
object YouTubeController {

    data class PlayResult(val success: Boolean, val title: String?, val message: String?)

    /**
     * Cleans noise and command words from the spoken query (e.g. "play 30 songs on YouTube" -> "30 songs").
     */
    fun cleanQuery(rawQuery: String): String {
        var clean = rawQuery.trim()
        val patterns = listOf(
            Regex("""\b(on|in|from)\s+youtube\b""", RegexOption.IGNORE_CASE),
            Regex("""\byoutube\s+(pe|par|mein|video|song|songs)\b""", RegexOption.IGNORE_CASE),
            Regex("""\byoutube\b""", RegexOption.IGNORE_CASE),
            Regex("""^\s*(play|open|search|listen\s+to)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(chalao|chala\s+do|karo|kar\s+do|lagao|laga\s+do|sunao|suna\s+do|bajao|baja\s+do|dikhao|dikha\s+do)\b""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            clean = pattern.replace(clean, " ")
        }
        val result = clean.replace(Regex("""\s+"""), " ").trim()
        return if (result.isNotBlank()) result else rawQuery.trim()
    }

    /**
     * Searches YouTube for [query] and opens the top result directly in the
     * player (via the `vnd.youtube:` deep link, which YouTube's app handles
     * natively). Falls back to zero-quota direct search, then native
     * MEDIA_PLAY_FROM_SEARCH intent, so videos ALWAYS play directly.
     */
    fun searchAndPlay(context: Context, query: String): PlayResult {
        val cleanQ = cleanQuery(query)
        val apiKey = EnvLoader.getYoutubeApiKey(context)
        android.util.Log.d("YouTubeController", "searchAndPlay for: \"$query\" -> cleaned: \"$cleanQ\" (apiKey present: ${apiKey.isNotBlank()})")

        // 1. Try search via YouTubeApiClient (checks Data API if key present, else direct zero-quota web scraper)
        val result = YouTubeApiClient.searchTopVideo(apiKey, cleanQ)
        if (result != null && result.videoId.isNotBlank()) {
            val played = playVideoId(context, result.videoId)
            if (played) {
                return PlayResult(true, result.title, "Playing \"${result.title}\" on YouTube.")
            }
        }

        // 2. Native Android Media Play from Search (supported by YouTube app to auto-play top match)
        try {
            val mediaPlayIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra(android.app.SearchManager.QUERY, cleanQ)
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(context, mediaPlayIntent)
            if (launched) {
                return PlayResult(true, cleanQ, "Playing \"$cleanQ\" on YouTube.")
            }
        } catch (e: Exception) {
            android.util.Log.w("YouTubeController", "MEDIA_PLAY_FROM_SEARCH intent failed, trying web fallback", e)
        }

        // 3. Last fallback: open YouTube search screen with accessibility auto-click attempt
        return try {
            val encoded = Uri.encode(cleanQ)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(context, intent)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                com.jarvis.assistant.service.JarvisAccessibilityService.instance?.clickFirstVideoResult()
            }, 1800)
            PlayResult(true, null, "Opened YouTube for \"$cleanQ\".")
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "Fallback search-intent failed", e)
            PlayResult(false, null, "Couldn't open YouTube.")
        }
    }

    /**
     * Searches YouTube for [query] and presents the search results screen ONLY.
     * Does NOT auto-click or play the top result.
     */
    fun searchYouTube(context: Context, query: String): PlayResult {
        val cleanQ = cleanQuery(query)
        return try {
            val encoded = Uri.encode(cleanQ)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ok = com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(context, intent)
            if (ok) {
                PlayResult(true, null, "Showing YouTube search results for \"$cleanQ\".")
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(context, webIntent)
                PlayResult(true, null, "Showing YouTube search results for \"$cleanQ\".")
            }
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "YouTube search intent failed", e)
            PlayResult(false, null, "Couldn't open YouTube.")
        }
    }

    /** Opens a specific video ID directly in the YouTube app's player. */
    fun playVideoId(context: Context, videoId: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(context, intent)
            if (launched) {
                true
            } else {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v=$videoId")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(context, webIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "Deep link play failed, trying web fallback", e)
            try {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v=$videoId")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(context, webIntent)
            } catch (e2: Exception) {
                android.util.Log.e("YouTubeController", "Web fallback also failed", e2)
                false
            }
        }
    }

    /**
     * Sends a standard media key event (play/pause/next/previous/stop).
     * Controls whatever app currently holds the active media session —
     * in practice this is YouTube whenever a video is actively playing.
     */
    fun sendMediaKey(context: Context, action: String): Boolean {
        val keyCode = when (action.lowercase()) {
            "play", "pause", "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip", "skip_next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "back", "skip_previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return false
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "Media key dispatch failed", e)
            false
        }
    }

    /** direction: "up" or "down". Adjusts the media/music volume stream. */
    fun adjustVolume(context: Context, direction: String): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val adjust = when (direction.lowercase()) {
            "up", "increase", "louder" -> AudioManager.ADJUST_RAISE
            "down", "decrease", "lower", "quieter" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            "unmute" -> AudioManager.ADJUST_UNMUTE
            else -> return false
        }
        return try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "Volume adjust failed", e)
            false
        }
    }
}