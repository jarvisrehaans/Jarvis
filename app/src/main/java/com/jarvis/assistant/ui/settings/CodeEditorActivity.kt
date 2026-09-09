package com.jarvis.assistant.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.util.pressFeedback
import java.io.File

class CodeEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
    }

    private lateinit var backBtn: ImageButton
    private lateinit var folderTitleText: TextView
    private lateinit var previewBtn: TextView
    private lateinit var saveBtn: TextView
    private lateinit var tabHtml: TextView
    private lateinit var tabCss: TextView
    private lateinit var tabJs: TextView
    private lateinit var editorWebView: WebView
    private lateinit var livePreviewContainer: FrameLayout
    private lateinit var previewWebView: WebView
    private lateinit var closePreviewBtn: TextView

    private var targetFolder: File? = null
    private var activeTab = "html" // "html", "css", "js"

    private var htmlContent = ""
    private var cssContent = ""
    private var jsContent = ""
    private var isEditorReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_code_editor)

        val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH) ?: ""
        if (folderPath.isBlank()) {
            Toast.makeText(this, "Invalid website folder path.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        targetFolder = File(folderPath)
        if (!targetFolder!!.exists() || !targetFolder!!.isDirectory) {
            Toast.makeText(this, "Folder does not exist: $folderPath", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        applyThemeVisuals()
        readFilesFromDisk()
        setupEditorWebView()
        setupPreviewWebView()
        wireInteractions()
    }

    override fun onResume() {
        super.onResume()
        applyThemeVisuals()
    }

    private fun applyThemeVisuals() {
        val primaryColor = ThemeManager.getPrimaryColorInt(this)
        val saveDrawable = ThemeManager.getSaveButtonDrawable(this)
        saveBtn.setBackgroundResource(saveDrawable)
        previewBtn.setBackgroundResource(saveDrawable)
        folderTitleText.setTextColor(primaryColor)
        backBtn.setColorFilter(primaryColor)
        updateTabStyles()
    }

    private fun initViews() {
        backBtn = findViewById(R.id.backBtn)
        folderTitleText = findViewById(R.id.folderTitleText)
        previewBtn = findViewById(R.id.previewBtn)
        saveBtn = findViewById(R.id.saveBtn)
        tabHtml = findViewById(R.id.tabHtml)
        tabCss = findViewById(R.id.tabCss)
        tabJs = findViewById(R.id.tabJs)
        editorWebView = findViewById(R.id.editorWebView)
        livePreviewContainer = findViewById(R.id.livePreviewContainer)
        previewWebView = findViewById(R.id.previewWebView)
        closePreviewBtn = findViewById(R.id.closePreviewBtn)

        folderTitleText.text = "📁 ${targetFolder?.name}"
    }

    private fun readFilesFromDisk() {
        val folder = targetFolder ?: return
        val htmlFile = File(folder, "index.html")
        val cssFile = File(folder, "style.css")
        val jsFile = File(folder, "script.js")

        htmlContent = if (htmlFile.exists()) htmlFile.readText(Charsets.UTF_8) else "<!-- index.html -->\n<!DOCTYPE html>\n<html>\n<head>\n    <title>Website</title>\n    <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n    <h1>Welcome</h1>\n    <script src=\"script.js\"></script>\n</body>\n</html>"
        cssContent = if (cssFile.exists()) cssFile.readText(Charsets.UTF_8) else "/* style.css */\nbody {\n    font-family: sans-serif;\n    background: #111;\n    color: #fff;\n}"
        jsContent = if (jsFile.exists()) jsFile.readText(Charsets.UTF_8) else "// script.js\nconsole.log('Website initialized');"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupEditorWebView() {
        editorWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        editorWebView.webChromeClient = WebChromeClient()
        editorWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isEditorReady = true
                loadActiveTabContentIntoEditor()
            }
        }

        editorWebView.addJavascriptInterface(AndroidCodeBridge(), "AndroidCodeEditor")
        editorWebView.loadUrl("file:///android_asset/code_editor.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupPreviewWebView() {
        previewWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        previewWebView.webChromeClient = WebChromeClient()
        previewWebView.webViewClient = WebViewClient()
    }

    private fun wireInteractions() {
        backBtn.pressFeedback()
        backBtn.setOnClickListener { finish() }

        tabHtml.pressFeedback()
        tabHtml.setOnClickListener { switchTab("html") }

        tabCss.pressFeedback()
        tabCss.setOnClickListener { switchTab("css") }

        tabJs.pressFeedback()
        tabJs.setOnClickListener { switchTab("js") }

        saveBtn.pressFeedback()
        saveBtn.setOnClickListener {
            saveCurrentEditorContent()
            writeFilesToDisk()
            Toast.makeText(this, "💾 Changes saved to ${targetFolder?.name}!", Toast.LENGTH_SHORT).show()
        }

        previewBtn.pressFeedback()
        previewBtn.setOnClickListener {
            saveCurrentEditorContent()
            writeFilesToDisk()
            showLivePreview()
        }

        closePreviewBtn.pressFeedback()
        closePreviewBtn.setOnClickListener {
            livePreviewContainer.visibility = FrameLayout.GONE
        }
    }

    private fun switchTab(tab: String) {
        if (activeTab == tab) return
        saveCurrentEditorContent()

        activeTab = tab
        updateTabStyles()
        loadActiveTabContentIntoEditor()
    }

    private fun updateTabStyles() {
        val activeBg = ThemeManager.createChipDrawable(this, true)
        val inactiveBg = ThemeManager.createChipDrawable(this, false)

        val activeColor = ThemeManager.getPrimaryColorInt(this)
        val inactiveColor = ContextCompat.getColor(this, R.color.orb_text_muted)

        tabHtml.background = if (activeTab == "html") activeBg else inactiveBg
        tabHtml.setTextColor(if (activeTab == "html") activeColor else inactiveColor)

        tabCss.background = if (activeTab == "css") activeBg else inactiveBg
        tabCss.setTextColor(if (activeTab == "css") activeColor else inactiveColor)

        tabJs.background = if (activeTab == "js") activeBg else inactiveBg
        tabJs.setTextColor(if (activeTab == "js") activeColor else inactiveColor)
    }

    private fun loadActiveTabContentIntoEditor() {
        if (!isEditorReady) return
        val content = when (activeTab) {
            "css" -> cssContent
            "js" -> jsContent
            else -> htmlContent
        }
        val fileType = activeTab

        val escapedCode = content
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

        val jsCall = "javascript:setEditorContent('$escapedCode', '$fileType');"
        editorWebView.evaluateJavascript(jsCall, null)
    }

    private fun saveCurrentEditorContent() {
        if (!isEditorReady) return
        editorWebView.evaluateJavascript("javascript:getEditorContent();") { result ->
            if (result != null && result != "null") {
                var unquoted = result
                if (unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
                    unquoted = unquoted.substring(1, unquoted.length - 1)
                }
                val cleaned = unquoted
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")

                when (activeTab) {
                    "html" -> htmlContent = cleaned
                    "css" -> cssContent = cleaned
                    "js" -> jsContent = cleaned
                }
            }
        }
    }

    private fun writeFilesToDisk() {
        val folder = targetFolder ?: return
        val htmlFile = File(folder, "index.html")
        val cssFile = File(folder, "style.css")
        val jsFile = File(folder, "script.js")

        htmlFile.writeText(htmlContent, Charsets.UTF_8)
        cssFile.writeText(cssContent, Charsets.UTF_8)
        jsFile.writeText(jsContent, Charsets.UTF_8)
    }

    private fun showLivePreview() {
        val folder = targetFolder ?: return
        val htmlFile = File(folder, "index.html")

        if (htmlFile.exists()) {
            val htmlUri = Uri.fromFile(htmlFile).toString()
            previewWebView.loadUrl(htmlUri)
            livePreviewContainer.visibility = FrameLayout.VISIBLE
        } else {
            Toast.makeText(this, "index.html not found.", Toast.LENGTH_SHORT).show()
        }
    }

    inner class AndroidCodeBridge {
        @JavascriptInterface
        fun onEditorReady() {
            runOnUiThread {
                isEditorReady = true
                loadActiveTabContentIntoEditor()
            }
        }

        @JavascriptInterface
        fun onContentChanged(newContent: String) {
            runOnUiThread {
                when (activeTab) {
                    "html" -> htmlContent = newContent
                    "css" -> cssContent = newContent
                    "js" -> jsContent = newContent
                }
            }
        }
    }
}
