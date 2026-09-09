package com.jarvis.assistant.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.jarvis.assistant.JarvisApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenRouter-Powered Website Generator for Jarvis AI with Gemini Resilience Fallback.
 * Generates modern, responsive multi-file websites (index.html, style.css, script.js).
 * Includes robust JSON extraction, fallback model cascading across active free models,
 * Gemini fallback when OpenRouter is rate-limited, and dual-directory storage.
 */
object OpenRouterWebsiteGenerator {

    private const val TAG = "OpenRouterWebGen"
    private const val OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models"

    const val PREF_OPENROUTER_KEY = "openrouter_api_key"
    const val PREF_OPENROUTER_MODEL = "openrouter_selected_model"
    const val DEFAULT_MODEL = "openrouter/free"

    val FALLBACK_FREE_MODELS = listOf(
        "openrouter/free",
        "cohere/north-mini-code:free",
        "google/gemma-4-26b-a4b-it:free",
        "google/gemma-4-31b-it:free",
        "nvidia/nemotron-3.5-lightning:free",
        "minimax/minimax-m3:free",
        "z-ai/glm-5.2:free",
        "liquid/lfm-2.5-2.6b:free",
        "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
        "minimax/minimax-m2.7:free"
    )

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(JarvisApplication.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_OPENROUTER_KEY, "") ?: ""
    }

    fun saveApiKey(context: Context, apiKey: String) {
        val prefs = context.getSharedPreferences(JarvisApplication.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_OPENROUTER_KEY, apiKey.trim()).apply()
    }

    fun getSelectedModel(context: Context): String {
        val prefs = context.getSharedPreferences(JarvisApplication.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_OPENROUTER_MODEL, null)
        if (saved.isNullOrBlank() || !FALLBACK_FREE_MODELS.contains(saved)) {
            return DEFAULT_MODEL
        }
        return saved
    }

    fun saveSelectedModel(context: Context, modelId: String) {
        val prefs = context.getSharedPreferences(JarvisApplication.PREFS_NAME, Context.MODE_PRIVATE)
        val toSave = if (modelId.isBlank()) DEFAULT_MODEL else modelId.trim()
        prefs.edit().putString(PREF_OPENROUTER_MODEL, toSave).apply()
    }

    fun getWebsitesPrimaryDir(): File {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(documentsDir, "JarvisWebsites")
    }

    fun getWebsitesFallbackDir(context: Context): File {
        val appDocs = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(appDocs, "JarvisWebsites")
    }

    /**
     * Lists all website directories across both primary public Documents and app-scoped Documents.
     */
    fun getAllWebsites(context: Context): List<File> {
        val folders = mutableListOf<File>()
        val primaryDir = getWebsitesPrimaryDir()
        if (primaryDir.exists()) {
            primaryDir.listFiles()?.filter { it.isDirectory }?.let { folders.addAll(it) }
        }
        val fallbackDir = getWebsitesFallbackDir(context)
        if (fallbackDir.exists()) {
            fallbackDir.listFiles()?.filter { it.isDirectory }?.forEach { fFolder ->
                if (folders.none { it.name.equals(fFolder.name, ignoreCase = true) }) {
                    folders.add(fFolder)
                }
            }
        }
        return folders.sortedByDescending { it.lastModified() }
    }

    /**
     * Fetches currently active free models from OpenRouter API.
     */
    suspend fun fetchFreeModels(apiKey: String = ""): List<String> = withContext(Dispatchers.IO) {
        val freeModels = mutableListOf<String>()
        try {
            val url = URL(OPENROUTER_MODELS_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val data = json.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val id = obj.optString("id", "")
                    val pricing = obj.optJSONObject("pricing")
                    val promptPrice = pricing?.optString("prompt", "0") ?: "0"
                    val completionPrice = pricing?.optString("completion", "0") ?: "0"

                    val isFreeByPricing = promptPrice == "0" && completionPrice == "0"
                    val isFreeById = id.endsWith(":free") || id == "openrouter/free"

                    if ((isFreeByPricing || isFreeById) && id.isNotBlank()) {
                        freeModels.add(id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching free models from OpenRouter", e)
        }

        if (freeModels.isEmpty()) {
            return@withContext FALLBACK_FREE_MODELS
        }

        val sorted = freeModels.distinct().toMutableList()
        if (sorted.contains(DEFAULT_MODEL)) {
            sorted.remove(DEFAULT_MODEL)
        }
        sorted.add(0, DEFAULT_MODEL)
        if (sorted.contains("cohere/north-mini-code:free")) {
            sorted.remove("cohere/north-mini-code:free")
            sorted.add(1, "cohere/north-mini-code:free")
        }
        return@withContext sorted
    }

    data class GenerationResult(
        val success: Boolean,
        val message: String,
        val folderPath: String? = null
    )

    /**
     * Generates HTML, CSS, and JS website files.
     * Tries selected and fallback OpenRouter models, and gracefully falls back to Gemini API
     * if OpenRouter free endpoints are rate-limited or unavailable.
     */
    suspend fun generateWebsite(
        context: Context,
        websiteName: String,
        businessDescription: String
    ): GenerationResult = withContext(Dispatchers.IO) {
        val openRouterKey = getApiKey(context)
        val geminiKey = EnvLoader.getApiKey(context)

        if (openRouterKey.isBlank() && geminiKey.isBlank()) {
            return@withContext GenerationResult(
                success = false,
                message = "Please configure your OpenRouter API key in Settings -> Website Builder or Gemini API Key in Settings."
            )
        }

        val cleanName = websiteName.ifBlank { "JarvisWebsite" }
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")

        val systemPrompt = """
            You are a world-class senior web designer and developer. Build an ULTRA-PROFESSIONAL, STUNNING, HIGH-CONVERTING, fully responsive website based strictly on the user's business request.

            CRITICAL TECHNICAL REQUIREMENTS:
            1. Write pure HTML5, CSS3, and JavaScript (Vanilla JS). Do NOT use React, Vue, Python, or external build tools.
            2. LINKING & ASSETS:
               - Link style.css via `<link rel="stylesheet" href="style.css">`.
               - Link script.js via `<script src="script.js"></script>`.
               - Import Google Fonts (e.g. Outfit, Inter, Poppins, or Playfair Display) in `<head>`.
               - Use high-resolution Unsplash image URLs (`https://images.unsplash.com/photo-...`) matching the exact business topic.
            3. SECTIONS & CONTENT (Tailored specifically to the requested business niche):
               - Header & Sticky Nav with logo, links, and Call-To-Action button.
               - Hero Section: Full-bleed hero banner with dark gradient overlay, headline, subheadline, and primary/secondary CTA buttons.
               - Features / Highlights Grid: 3-4 feature cards with modern icons/badges.
               - Products / Services / Membership Grid: Relevant items, prices, descriptions, and Action buttons.
               - About / Story Section: Brand background and image showcase.
               - Testimonials: Review cards with 5-star ratings.
               - Interactive Contact / Booking Form.
               - Footer: Brand details, social media links, and copyright line.
            4. STYLING & RESPONSIVENESS:
               - Use CSS variables (`:root`) for color palette.
               - Full Mobile Responsiveness with `@media (max-width: 768px)`.
            5. OUTPUT FORMAT:
               Respond ONLY with a valid JSON object containing 3 keys: "html", "css", and "js".
               JSON Structure:
               {
                 "html": "<!DOCTYPE html>...",
                 "css": "/* CSS */...",
                 "js": "// JS..."
               }
        """.trimIndent()

        val userPrompt = "Create a modern, complete, responsive, ultra-professional website for: \"$websiteName\". Business description & category: \"$businessDescription\"."

        var rawHtml = ""
        var rawCss = ""
        var rawJs = ""
        var lastErrorMessage = ""

        // Strategy 1: Attempt generation via OpenRouter if key is available
        if (openRouterKey.isNotBlank()) {
            val primaryModel = getSelectedModel(context)
            val modelsToTry = mutableListOf<String>()
            if (primaryModel.isNotBlank()) modelsToTry.add(primaryModel)
            for (m in FALLBACK_FREE_MODELS) {
                if (!modelsToTry.contains(m)) modelsToTry.add(m)
            }

            for (modelCandidate in modelsToTry) {
                Log.d(TAG, "Attempting website generation with OpenRouter model: $modelCandidate")
                try {
                    val payload = JSONObject().apply {
                        put("model", modelCandidate)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "system")
                                put("content", systemPrompt)
                            })
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", userPrompt)
                            })
                        })
                        put("temperature", 0.7)
                    }

                    val connection = (URL(OPENROUTER_API_URL).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Authorization", "Bearer $openRouterKey")
                        setRequestProperty("HTTP-Referer", "https://github.com/jarvis-ai")
                        setRequestProperty("X-Title", "JARVIS AI Assistant")
                        doOutput = true
                        connectTimeout = 30000
                        readTimeout = 60000
                    }

                    connection.outputStream.use { os ->
                        os.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }

                    val responseCode = connection.responseCode
                    if (responseCode == 200) {
                        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                        val choices = JSONObject(responseText).optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val rawContent = choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim()
                            val parsed = parseJsonCodeResponse(rawContent)
                            if (parsed.first.isNotBlank()) {
                                rawHtml = parsed.first
                                rawCss = parsed.second
                                rawJs = parsed.third
                                Log.d(TAG, "Successfully generated code with OpenRouter model: $modelCandidate")
                                break
                            }
                        }
                    } else {
                        val errText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        Log.w(TAG, "OpenRouter model $modelCandidate returned HTTP $responseCode: $errText")
                        lastErrorMessage = "HTTP $responseCode: $errText"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OpenRouter exception with model $modelCandidate: ${e.message}")
                    lastErrorMessage = e.message ?: "Network error"
                }
            }
        }

        // Strategy 2: Graceful Gemini Fallback if OpenRouter failed or was rate-limited
        if (rawHtml.isBlank() && geminiKey.isNotBlank()) {
            Log.d(TAG, "Falling back to Gemini API for website generation...")
            val geminiResult = generateWithGemini(context, systemPrompt, userPrompt)
            if (geminiResult != null && geminiResult.first.isNotBlank()) {
                rawHtml = geminiResult.first
                rawCss = geminiResult.second
                rawJs = geminiResult.third
                Log.d(TAG, "Successfully generated code via Gemini fallback")
            }
        }

        if (rawHtml.isBlank()) {
            return@withContext GenerationResult(
                success = false,
                message = "Website generation failed ($lastErrorMessage). Please check your internet connection or try another OpenRouter Model in Settings -> Website Builder."
            )
        }

        val htmlContent = cleanCodeString(rawHtml)
        val cssContent = cleanCodeString(rawCss)
        val jsContent = cleanCodeString(rawJs)

        // Save files with dual storage fallback
        val targetFolder = saveWebsiteFiles(context, cleanName, htmlContent, cssContent, jsContent)

        // Update overlay if currently running
        if (com.jarvis.assistant.service.WebsiteOverlayService.isRunning()) {
            com.jarvis.assistant.service.WebsiteOverlayService.updateProgress(
                context, websiteName, htmlContent, cssContent, jsContent
            )
        }
        com.jarvis.assistant.service.WebsiteOverlayService.markComplete(
            context, websiteName, targetFolder.absolutePath, htmlContent, cssContent, jsContent
        )

        Log.d(TAG, "Website successfully saved to ${targetFolder.absolutePath}")

        return@withContext GenerationResult(
            success = true,
            message = "Website created successfully in ${targetFolder.absolutePath}!",
            folderPath = targetFolder.absolutePath
        )
    }

    /**
     * Fallback generation directly using Google Gemini REST API.
     */
    private suspend fun generateWithGemini(
        context: Context,
        systemPrompt: String,
        userPrompt: String
    ): Triple<String, String, String>? = withContext(Dispatchers.IO) {
        val geminiKey = EnvLoader.getApiKey(context)
        if (geminiKey.isBlank()) return@withContext null

        val geminiModels = listOf("gemini-2.0-flash", "gemini-1.5-flash")
        for (modelName in geminiModels) {
            try {
                Log.d(TAG, "Calling Gemini REST API with model: $modelName")
                val urlStr = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$geminiKey"
                val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 30000
                    readTimeout = 60000
                    doOutput = true
                }

                val payload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "$systemPrompt\n\nUser Request: $userPrompt\nRespond strictly with JSON containing html, css, js.")
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.7)
                        put("responseMimeType", "application/json")
                    })
                }

                connection.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                if (code == 200) {
                    val respText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(respText)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val text = parts.getJSONObject(0).optString("text", "")
                            val parsed = parseJsonCodeResponse(text)
                            if (parsed.first.isNotBlank()) {
                                return@withContext parsed
                            }
                        }
                    }
                } else {
                    val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.w(TAG, "Gemini $modelName returned HTTP $code: $err")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini exception on $modelName: ${e.message}")
            }
        }
        return@withContext null
    }

    /**
     * Saves index.html, style.css, script.js to primary Documents directory,
     * falling back to app external documents directory if permissions require.
     */
    private fun saveWebsiteFiles(
        context: Context,
        cleanName: String,
        html: String,
        css: String,
        js: String
    ): File {
        var targetFolder = File(getWebsitesPrimaryDir(), cleanName)
        var writeSuccess = false

        try {
            if (!targetFolder.exists()) {
                targetFolder.mkdirs()
            }
            if (targetFolder.exists()) {
                File(targetFolder, "index.html").writeText(html, Charsets.UTF_8)
                File(targetFolder, "style.css").writeText(css, Charsets.UTF_8)
                File(targetFolder, "script.js").writeText(js, Charsets.UTF_8)
                writeSuccess = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write to primary Documents folder: ${e.message}")
        }

        if (!writeSuccess) {
            targetFolder = File(getWebsitesFallbackDir(context), cleanName)
            if (!targetFolder.exists()) {
                targetFolder.mkdirs()
            }
            File(targetFolder, "index.html").writeText(html, Charsets.UTF_8)
            File(targetFolder, "style.css").writeText(css, Charsets.UTF_8)
            File(targetFolder, "script.js").writeText(js, Charsets.UTF_8)
        }

        return targetFolder
    }

    /**
     * Bulletproof parser for LLM responses. Extracts JSON substrings or falls back to Markdown code blocks.
     */
    private fun parseJsonCodeResponse(rawContent: String): Triple<String, String, String> {
        val cleanContent = rawContent.trim()

        // Strategy 1: Find first '{' and last '}' substring to extract pure JSON object
        val firstBrace = cleanContent.indexOf('{')
        val lastBrace = cleanContent.lastIndexOf('}')

        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            val jsonSubstring = cleanContent.substring(firstBrace, lastBrace + 1)
            try {
                val parsedCode = JSONObject(jsonSubstring)
                val html = parsedCode.optString("html", "").trim()
                val css = parsedCode.optString("css", "").trim()
                val js = parsedCode.optString("js", "").trim()

                if (html.isNotBlank()) {
                    return Triple(html, css, js)
                }
            } catch (e: Exception) {
                Log.w(TAG, "JSON substring extraction failed: ${e.message}")
            }
        }

        // Strategy 2: Extract Markdown code blocks directly using Regex
        var html = ""
        var css = ""
        var js = ""

        val htmlMatch = Regex("```(?:html)?\\s*(<!DOCTYPE html[\\s\\S]*?|\\<html[\\s\\S]*?\\</html\\>)\\s*```", RegexOption.IGNORE_CASE).find(cleanContent)
        if (htmlMatch != null) {
            html = htmlMatch.groupValues[1].trim()
        }

        val cssMatch = Regex("```(?:css)?\\s*([\\s\\S]*?:root[\\s\\S]*?|\\*\\s*\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE).find(cleanContent)
        if (cssMatch != null) {
            css = cssMatch.groupValues[1].trim()
        }

        val jsMatch = Regex("```(?:js|javascript)?\\s*([\\s\\S]*?console\\.log[\\s\\S]*?|[\\s\\S]*?function[\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE).find(cleanContent)
        if (jsMatch != null) {
            js = jsMatch.groupValues[1].trim()
        }

        return Triple(html, css, js)
    }

    private fun cleanCodeString(str: String): String {
        var cleaned = str
        cleaned = cleaned.replace("\\n", "\n")
        cleaned = cleaned.replace("\\r", "")
        cleaned = cleaned.replace("\\t", "    ")
        cleaned = cleaned.replace("\\\"", "\"")
        cleaned = cleaned.replace("\\'", "'")
        return cleaned.trim()
    }
}
