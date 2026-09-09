package com.jarvis.assistant.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Built-in Chrome background search engine for JARVIS.
 * Allows JARVIS to conduct invisible background web research on topics, questions,
 * or websites without interrupting the user's active screen.
 */
object BuiltInChromeEngine {

    private const val TAG = "BuiltInChromeEngine"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun searchAndExtract(query: String): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext "Empty search query provided."

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // Try DuckDuckGo Lite / HTML API for fast, reliable search results
            val searchUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: ""

            val snippets = parseDuckDuckGoHtml(html)
            if (snippets.isNotEmpty()) {
                return@withContext "Search Results for '$query':\n\n" + snippets.joinToString("\n\n")
            }

            // Fallback: Google HTML Search parsing
            val googleUrl = "https://www.google.com/search?q=$encodedQuery&hl=en"
            val googleReq = Request.Builder()
                .url(googleUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            val googleResp = httpClient.newCall(googleReq).execute()
            val googleHtml = googleResp.body?.string() ?: ""
            val googleSnippets = parseGoogleHtml(googleHtml)

            if (googleSnippets.isNotEmpty()) {
                return@withContext "Search Results for '$query':\n\n" + googleSnippets.joinToString("\n\n")
            }

            return@withContext "Could not extract direct answer snippets for '$query'. Try asking with specific details."
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$query'", e)
            return@withContext "Web research failed: ${e.message}"
        }
    }

    private fun parseDuckDuckGoHtml(html: String): List<String> {
        val results = mutableListOf<String>()
        try {
            val resultPattern = Pattern.compile("<a class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL)
            val matcher = resultPattern.matcher(html)
            var count = 0
            while (matcher.find() && count < 4) {
                val rawText = matcher.group(1) ?: ""
                val cleanText = stripHtmlTags(rawText).trim()
                if (cleanText.length > 20) {
                    results.add("${count + 1}. $cleanText")
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing DDG HTML", e)
        }
        return results
    }

    private fun parseGoogleHtml(html: String): List<String> {
        val results = mutableListOf<String>()
        try {
            val snippetPattern = Pattern.compile("<div[^>]*class=\"[^\"]*VwiC3b[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL)
            val matcher = snippetPattern.matcher(html)
            var count = 0
            while (matcher.find() && count < 4) {
                val rawText = matcher.group(1) ?: ""
                val cleanText = stripHtmlTags(rawText).trim()
                if (cleanText.length > 20) {
                    results.add("${count + 1}. $cleanText")
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Google HTML", e)
        }
        return results
    }

    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
    }
}
