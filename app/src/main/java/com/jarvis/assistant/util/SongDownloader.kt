package com.jarvis.assistant.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Handles automated song research and direct background downloading on Android mobile.
 * Uses OkHttp & BuiltInChromeEngine for research and Android DownloadManager for file download.
 */
object SongDownloader {

    private const val TAG = "SongDownloader"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class SongResult(
        val isAvailable: Boolean,
        val isCached: Boolean = false,
        val downloadUrl: String? = null,
        val message: String = ""
    )

    /**
     * Checks pagalnew.com for song availability. If available, downloads it to 'Songs Jarvis'.
     * If not available, returns truthful isAvailable = false result with exact apology message.
     */
    suspend fun checkAndDownloadPagalNew(context: Context, songQuery: String): SongResult = withContext(Dispatchers.IO) {
        if (songQuery.isBlank()) {
            return@withContext SongResult(isAvailable = false, message = "Sorry sir, you asked me to download a song, but it is not available so please I am sorry.")
        }

        try {
            val fileName = sanitizeFileName(songQuery) + ".mp3"
            val songsFolder = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Songs Jarvis")
            if (!songsFolder.exists()) {
                songsFolder.mkdirs()
            }

            val cachedFile = java.io.File(songsFolder, fileName)
            if (cachedFile.exists() && cachedFile.length() > 0) {
                Log.d(TAG, "Song '$songQuery' already exists in Songs Jarvis folder: ${cachedFile.absolutePath}")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "Song already in Songs Jarvis folder", Toast.LENGTH_SHORT).show()
                }
                return@withContext SongResult(isAvailable = true, isCached = true, message = "Song is already available in your Songs Jarvis folder.")
            }

            Log.d(TAG, "Searching pagalnew.com for song: $songQuery")
            val downloadUrl = findAudioDownloadUrl(songQuery)

            if (!downloadUrl.isNullOrBlank()) {
                Log.d(TAG, "Found direct download link on pagalnew.com: $downloadUrl")
                val enqueued = enqueueDownloadManager(context, downloadUrl, fileName, songQuery)
                return@withContext SongResult(isAvailable = true, downloadUrl = downloadUrl, message = "Found on pagalnew.com, downloading now...")
            }

            Log.w(TAG, "Song '$songQuery' is not available on pagalnew.com")
            return@withContext SongResult(isAvailable = false, message = "Sorry sir, you asked me to download $songQuery. It is not available so please I am sorry.")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking pagalnew.com for $songQuery", e)
            return@withContext SongResult(isAvailable = false, message = "Sorry sir, you asked me to download $songQuery. It is not available so please I am sorry.")
        }
    }

    /**
     * Researches downloadable MP3 link for the given song query and triggers Android DownloadManager.
     */
    suspend fun researchAndDownload(context: Context, songQuery: String): Boolean = withContext(Dispatchers.IO) {
        val result = checkAndDownloadPagalNew(context, songQuery)
        return@withContext result.isAvailable
    }

    private fun findAudioDownloadUrl(songQuery: String): String? {
        try {
            // 1. Target pagalnew.com directly for song download
            val pagalnewQuery = "site:pagalnew.com $songQuery"
            val encodedPagal = URLEncoder.encode(pagalnewQuery, "UTF-8")
            val searchUrl = "https://html.duckduckgo.com/html/?q=$encodedPagal"

            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: ""

            // Extract top pagalnew.com webpage URL from search results
            val targetUrls = mutableListOf<String>()
            val linkPattern = Pattern.compile("uddg=([^&\"'\\s]+)", Pattern.CASE_INSENSITIVE)
            val linkMatcher = linkPattern.matcher(html)
            while (linkMatcher.find() && targetUrls.size < 3) {
                val rawUrl = java.net.URLDecoder.decode(linkMatcher.group(1) ?: "", "UTF-8")
                if (rawUrl.contains("pagalnew.com") && !rawUrl.contains("duckduckgo")) {
                    targetUrls.add(rawUrl)
                }
            }

            // Fallback: search pagalnew.com without site: prefix
            if (targetUrls.isEmpty()) {
                val altQuery = "pagalnew.com $songQuery"
                val encodedAlt = URLEncoder.encode(altQuery, "UTF-8")
                val altReq = Request.Builder()
                    .url("https://html.duckduckgo.com/html/?q=$encodedAlt")
                    .header("User-Agent", USER_AGENT)
                    .build()
                val altResp = httpClient.newCall(altReq).execute()
                val altHtml = altResp.body?.string() ?: ""
                val altMatcher = linkPattern.matcher(altHtml)
                while (altMatcher.find() && targetUrls.size < 3) {
                    val rawUrl = java.net.URLDecoder.decode(altMatcher.group(1) ?: "", "UTF-8")
                    if (rawUrl.contains("pagalnew.com") && !rawUrl.contains("duckduckgo")) {
                        targetUrls.add(rawUrl)
                    }
                }
            }

            // 2. Visit top pagalnew.com song webpage and extract 320kbps or 128kbps MP3 link
            for (targetUrl in targetUrls) {
                try {
                    val pageReq = Request.Builder()
                        .url(targetUrl)
                        .header("User-Agent", USER_AGENT)
                        .build()
                    val pageResp = httpClient.newCall(pageReq).execute()
                    val pageHtml = pageResp.body?.string() ?: ""

                    // pagalnew.com uses links like <a href="128-..." class="d-btn"> or direct mp3 download buttons
                    val mp3Pattern = Pattern.compile("https?://[^\"'\\s]+\\.mp3", Pattern.CASE_INSENSITIVE)
                    val mp3Matcher = mp3Pattern.matcher(pageHtml)
                    if (mp3Matcher.find()) {
                        return mp3Matcher.group(0)
                    }

                    // Extract download button hrefs (e.g. href="https://pagalnew.com/download/...")
                    val hrefPattern = Pattern.compile("href=[\"']([^\"']*(?:320|128|download|mp3)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
                    val hrefMatcher = hrefPattern.matcher(pageHtml)
                    while (hrefMatcher.find()) {
                        val href = hrefMatcher.group(1) ?: ""
                        if (!href.contains("javascript") && (href.contains("320") || href.contains("128") || href.endsWith(".mp3") || href.contains("download"))) {
                            val absoluteUrl = if (href.startsWith("http")) href else resolveRelativeUrl(targetUrl, href)
                            if (absoluteUrl.startsWith("http")) {
                                return absoluteUrl
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing pagalnew page $targetUrl", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering pagalnew MP3 link", e)
        }
        return null
    }

    private fun resolveRelativeUrl(baseUrl: String, relativePath: String): String {
        return try {
            java.net.URL(java.net.URL(baseUrl), relativePath).toString()
        } catch (e: Exception) {
            relativePath
        }
    }

    /**
     * Enqueues song download into Android's native DownloadManager.
     */
    fun enqueueDownloadManager(context: Context, downloadUrl: String, fileName: String, title: String): Boolean {
        return try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return false

            val uri = Uri.parse(downloadUrl)
            val request = DownloadManager.Request(uri).apply {
                setTitle("Downloading $title")
                setDescription("Jarvis AI Song Downloader")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Songs Jarvis/$fileName")
                setAllowedOverRoaming(true)
                setAllowedOverMetered(true)
            }

            downloadManager.enqueue(request)
            
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Downloading $title...", Toast.LENGTH_LONG).show()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download", e)
            false
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(50)
    }
}
