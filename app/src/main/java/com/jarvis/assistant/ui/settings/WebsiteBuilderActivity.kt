package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.util.OpenRouterWebsiteGenerator
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.util.pressFeedback
import kotlinx.coroutines.launch
import java.io.File

class WebsiteBuilderActivity : AppCompatActivity() {

    private lateinit var backBtn: View
    private lateinit var apiKeyInput: EditText
    private lateinit var keyToggleBtn: ImageButton
    private lateinit var modelSpinner: Spinner
    private lateinit var saveConfigBtn: TextView
    private lateinit var testWebsiteNameInput: EditText
    private lateinit var testWebsiteDescInput: EditText
    private lateinit var generateWebsiteBtn: TextView
    private lateinit var genProgressBar: ProgressBar
    private lateinit var storagePathText: TextView
    private lateinit var websitesContainer: LinearLayout

    private var isKeyVisible = false
    private var availableFreeModels = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_website_builder)

        initViews()
        loadSavedConfig()
        wireInteractions()
        refreshFreeModelsList()
        refreshWebsitesList()
    }

    override fun onResume() {
        super.onResume()
        applyThemeVisuals()
    }

    private fun initViews() {
        backBtn = findViewById(R.id.backBtn)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        keyToggleBtn = findViewById(R.id.keyToggleBtn)
        modelSpinner = findViewById(R.id.modelSpinner)
        saveConfigBtn = findViewById(R.id.saveConfigBtn)
        testWebsiteNameInput = findViewById(R.id.testWebsiteNameInput)
        testWebsiteDescInput = findViewById(R.id.testWebsiteDescInput)
        generateWebsiteBtn = findViewById(R.id.generateWebsiteBtn)
        genProgressBar = findViewById(R.id.genProgressBar)
        storagePathText = findViewById(R.id.storagePathText)
        websitesContainer = findViewById(R.id.websitesContainer)

        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val baseFolder = File(documentsDir, "JarvisWebsites")
        storagePathText.text = "Saved in: ${baseFolder.absolutePath}"

        availableFreeModels.clear()
        availableFreeModels.addAll(OpenRouterWebsiteGenerator.FALLBACK_FREE_MODELS)
        val initAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            availableFreeModels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modelSpinner.adapter = initAdapter
        val savedModel = OpenRouterWebsiteGenerator.getSelectedModel(this)
        val initIdx = availableFreeModels.indexOf(savedModel)
        modelSpinner.setSelection(if (initIdx >= 0) initIdx else 0)

        applyThemeVisuals()
    }

    private fun applyThemeVisuals() {
        val primaryColor = ThemeManager.getPrimaryColorInt(this)
        val saveDrawable = ThemeManager.getSaveButtonDrawable(this)
        saveConfigBtn.setBackgroundResource(saveDrawable)
        generateWebsiteBtn.setBackgroundResource(saveDrawable)
        findViewById<TextView>(R.id.websiteBuilderTitleText)?.setTextColor(primaryColor)
        findViewById<ImageView>(R.id.headerBuilderIcon)?.setColorFilter(primaryColor)
        (backBtn as? ImageButton)?.setColorFilter(primaryColor)
        genProgressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(primaryColor)
        refreshWebsitesList()
    }

    private fun loadSavedConfig() {
        val apiKey = OpenRouterWebsiteGenerator.getApiKey(this)
        apiKeyInput.setText(apiKey)
    }

    private fun wireInteractions() {
        backBtn.pressFeedback()
        backBtn.setOnClickListener { finish() }

        keyToggleBtn.setOnClickListener {
            isKeyVisible = !isKeyVisible
            if (isKeyVisible) {
                apiKeyInput.transformationMethod = HideReturnsTransformationMethod.getInstance()
                keyToggleBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            } else {
                apiKeyInput.transformationMethod = PasswordTransformationMethod.getInstance()
                keyToggleBtn.setImageResource(android.R.drawable.ic_menu_view)
            }
            apiKeyInput.setSelection(apiKeyInput.text.length)
        }

        saveConfigBtn.pressFeedback()
        saveConfigBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            val selectedModel = modelSpinner.selectedItem?.toString() ?: OpenRouterWebsiteGenerator.DEFAULT_MODEL

            OpenRouterWebsiteGenerator.saveApiKey(this, key)
            OpenRouterWebsiteGenerator.saveSelectedModel(this, selectedModel)

            Toast.makeText(this, "OpenRouter configuration saved!", Toast.LENGTH_SHORT).show()
            refreshFreeModelsList()
        }

        generateWebsiteBtn.pressFeedback()
        generateWebsiteBtn.setOnClickListener {
            val key = OpenRouterWebsiteGenerator.getApiKey(this)
            val geminiKey = com.jarvis.assistant.util.EnvLoader.getApiKey(this)
            if (key.isBlank() && geminiKey.isBlank()) {
                Toast.makeText(this, "Please enter and save your OpenRouter API key above, or configure Gemini API key in Settings!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val name = testWebsiteNameInput.text.toString().trim()
            val desc = testWebsiteDescInput.text.toString().trim()

            if (name.isBlank()) {
                Toast.makeText(this, "Please enter a website name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            genProgressBar.visibility = View.VISIBLE
            generateWebsiteBtn.isEnabled = false

            lifecycleScope.launch {
                val result = OpenRouterWebsiteGenerator.generateWebsite(
                    context = this@WebsiteBuilderActivity,
                    websiteName = name,
                    businessDescription = desc.ifBlank { name }
                )

                genProgressBar.visibility = View.GONE
                generateWebsiteBtn.isEnabled = true

                if (result.success) {
                    Toast.makeText(this@WebsiteBuilderActivity, result.message, Toast.LENGTH_LONG).show()
                    testWebsiteNameInput.setText("")
                    testWebsiteDescInput.setText("")
                    refreshWebsitesList()

                    result.folderPath?.let { path ->
                        openCodeEditor(path)
                    }
                } else {
                    Toast.makeText(this@WebsiteBuilderActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openCodeEditor(folderPath: String) {
        val intent = Intent(this, CodeEditorActivity::class.java).apply {
            putExtra(CodeEditorActivity.EXTRA_FOLDER_PATH, folderPath)
        }
        startActivity(intent)
    }

    private fun refreshFreeModelsList() {
        val apiKey = apiKeyInput.text.toString().trim()
        lifecycleScope.launch {
            val models = OpenRouterWebsiteGenerator.fetchFreeModels(apiKey)
            availableFreeModels.clear()
            availableFreeModels.addAll(models)

            val adapter = ArrayAdapter(
                this@WebsiteBuilderActivity,
                android.R.layout.simple_spinner_item,
                availableFreeModels
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            modelSpinner.adapter = adapter

            val savedModel = OpenRouterWebsiteGenerator.getSelectedModel(this@WebsiteBuilderActivity)
            val index = availableFreeModels.indexOf(savedModel)
            if (index >= 0) {
                modelSpinner.setSelection(index)
            } else {
                modelSpinner.setSelection(0)
            }
        }
    }

    private fun refreshWebsitesList() {
        websitesContainer.removeAllViews()

        val folders = OpenRouterWebsiteGenerator.getAllWebsites(this)
        if (folders.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No websites generated yet. Use voice command or Test Generator above."
                setTextColor(resources.getColor(R.color.orb_text_muted, theme))
                textSize = 13f
                setPadding(0, 12, 0, 12)
            }
            websitesContainer.addView(emptyTv)
            return
        }

        for (folder in folders) {
            val rowView = layoutInflater.inflate(android.R.layout.simple_list_item_2, websitesContainer, false)
            val text1 = rowView.findViewById<TextView>(android.R.id.text1)
            val text2 = rowView.findViewById<TextView>(android.R.id.text2)

            val htmlFile = File(folder, "index.html")
            val hasHtml = htmlFile.exists()

            text1.text = "📁 ${folder.name}"
            text1.setTextColor(ThemeManager.getPrimaryColorInt(this))
            text1.textSize = 15f

            text2.text = if (hasHtml) "Tap to Edit Code 📝 | Tap & Hold to View Browser 🌐" else "Empty directory"
            text2.setTextColor(resources.getColor(R.color.orb_text_muted, theme))
            text2.textSize = 12f

            rowView.setPadding(12, 16, 12, 16)
            rowView.setOnClickListener {
                openCodeEditor(folder.absolutePath)
            }

            rowView.setOnLongClickListener {
                if (hasHtml) {
                    openHtmlInBrowser(htmlFile)
                } else {
                    Toast.makeText(this, "Folder path: ${folder.absolutePath}", Toast.LENGTH_SHORT).show()
                }
                true
            }

            websitesContainer.addView(rowView)
        }
    }

    private fun openHtmlInBrowser(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            val plainUri = Uri.fromFile(file)
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(plainUri, "text/html")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Saved at: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
