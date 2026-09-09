package com.jarvis.assistant.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/**
 * System Overlay Service that renders a draggable Live Code Window Overlay for website generation.
 * Features:
 *  - 3 Top Buttons: "into" (close/hide), "over" (expand/fullscreen), "minimize" (minimize to floating pill).
 *  - 3 Code Tabs: HTML, CSS, JS.
 *  - Live Code Viewer showing real-time code writing.
 *  - Background code generation continuation when closed or minimized.
 */
class WebsiteOverlayService : Service() {

    companion object {
        private const val TAG = "WebsiteOverlayService"

        const val ACTION_SHOW = "com.jarvis.assistant.action.SHOW_WEBSITE_OVERLAY"
        const val ACTION_UPDATE_CODE = "com.jarvis.assistant.action.UPDATE_WEBSITE_CODE"
        const val ACTION_COMPLETE = "com.jarvis.assistant.action.COMPLETE_WEBSITE_CODE"
        const val ACTION_HIDE = "com.jarvis.assistant.action.HIDE_WEBSITE_OVERLAY"

        const val EXTRA_WEBSITE_NAME = "extra_website_name"
        const val EXTRA_HTML = "extra_html"
        const val EXTRA_CSS = "extra_css"
        const val EXTRA_JS = "extra_js"
        const val EXTRA_FOLDER_PATH = "extra_folder_path"

        private const val NOTIFICATION_ID = 303
        private const val CHANNEL_ID = "jarvis_website_overlay_channel"

        private var instance: WebsiteOverlayService? = null

        fun isRunning(): Boolean = instance != null

        fun updateProgress(context: Context, websiteName: String, html: String, css: String, js: String) {
            val inst = instance
            if (inst != null) {
                Handler(Looper.getMainLooper()).post {
                    inst.applyCodeUpdate(websiteName, html, css, js)
                }
            }
        }

        fun markComplete(context: Context, websiteName: String, folderPath: String, html: String, css: String, js: String) {
            val inst = instance
            if (inst != null) {
                Handler(Looper.getMainLooper()).post {
                    inst.applyCompletion(websiteName, folderPath, html, css, js)
                }
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)) {
                val intent = Intent(context, WebsiteOverlayService::class.java).apply {
                    action = ACTION_COMPLETE
                    putExtra(EXTRA_WEBSITE_NAME, websiteName)
                    putExtra(EXTRA_FOLDER_PATH, folderPath)
                    putExtra(EXTRA_HTML, html)
                    putExtra(EXTRA_CSS, css)
                    putExtra(EXTRA_JS, js)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (e: Exception) { Log.e(TAG, "markComplete startService failed", e) }
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var rootOverlayView: FrameLayout? = null
    private var windowContainer: LinearLayout? = null
    private var minimizedPillView: LinearLayout? = null

    private var layoutParams: WindowManager.LayoutParams? = null
    private var pillLayoutParams: WindowManager.LayoutParams? = null

    private var statusTextView: TextView? = null
    private var codeTextView: TextView? = null
    private var htmlTabBtn: Button? = null
    private var cssTabBtn: Button? = null
    private var jsTabBtn: Button? = null
    private var openWebBtn: Button? = null

    private var currentTab = "HTML" // "HTML", "CSS", "JS"
    private var htmlCode = "<!-- Generating HTML code... -->"
    private var cssCode = "/* Generating CSS code... */"
    private var jsCode = "// Generating JavaScript code..."
    private var currentFolderPath: String? = null
    private var websiteName: String = "Website"

    private var isExpanded = false
    private var isMinimized = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else 0
                startForeground(NOTIFICATION_ID, buildNotification(), serviceType)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlayViews()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "JARVIS Website Overlay",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        createNotificationChannel()
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Website Builder")
            .setContentText("Generating live website code...")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val name = intent?.getStringExtra(EXTRA_WEBSITE_NAME) ?: "Website"
        this.websiteName = name

        val html = intent?.getStringExtra(EXTRA_HTML)
        val css = intent?.getStringExtra(EXTRA_CSS)
        val js = intent?.getStringExtra(EXTRA_JS)
        val folder = intent?.getStringExtra(EXTRA_FOLDER_PATH)

        if (html != null) htmlCode = html
        if (css != null) cssCode = css
        if (js != null) jsCode = js
        if (folder != null) currentFolderPath = folder

        when (action) {
            ACTION_SHOW, ACTION_UPDATE_CODE -> {
                showWindow()
                updateCodeDisplay()
                statusTextView?.text = "Writing code for $websiteName..."
            }
            ACTION_COMPLETE -> {
                showWindow()
                updateCodeDisplay()
                statusTextView?.text = "Website created successfully!"
                openWebBtn?.visibility = View.VISIBLE
            }
            ACTION_HIDE -> {
                hideOverlayCompletely()
            }
        }

        return START_STICKY
    }

    fun applyCodeUpdate(name: String, html: String, css: String, js: String) {
        this.websiteName = name
        this.htmlCode = html
        this.cssCode = css
        this.jsCode = js
        showWindow()
        updateCodeDisplay()
        statusTextView?.text = "Writing code for $websiteName..."
    }

    fun applyCompletion(name: String, folder: String, html: String, css: String, js: String) {
        this.websiteName = name
        this.currentFolderPath = folder
        this.htmlCode = html
        this.cssCode = css
        this.jsCode = js
        showWindow()
        updateCodeDisplay()
        statusTextView?.text = "Website created successfully!"
        openWebBtn?.visibility = View.VISIBLE
    }

    private fun setupOverlayViews() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot setup WebsiteOverlayService: overlay permission missing")
            return
        }

        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Layout parameters for main code window (default small popup style: 90% width, 55% height)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            (screenWidth * 0.90f).toInt(),
            (screenHeight * 0.55f).toInt(),
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (screenHeight * 0.15f).toInt()
        }

        // Layout parameters for minimized floating pill badge
        pillLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 30
            y = 120
        }

        buildWindowContainer()
        buildMinimizedPill()
    }

    private fun buildWindowContainer() {
        val windowBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 32f
            setColor(Color.parseColor("#E60D1117")) // Dark sleek glassmorphism background
            setStroke(3, Color.parseColor("#30363D"))
        }

        windowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = windowBg
            setPadding(24, 20, 24, 20)
            elevation = 20f
        }

        // --- TOP HEADER BAR with 3 Buttons ("into", "over", "minimize") ---
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        // Title & status
        statusTextView = TextView(this).apply {
            text = "JARVIS Website Generator"
            setTextColor(Color.parseColor("#58A6FF"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(statusTextView)

        // Button 1: "into" (Close / X button - disappears overlay, generation continues in background)
        val intoBtn = createHeaderButton("into (X)", "#FF5555") {
            hideOverlayCompletely()
        }

        // Button 2: "over" (Fullscreen / Expand toggle)
        val overBtn = createHeaderButton("over (Fullscreen)", "#F1FA8C") {
            toggleExpandWindow()
        }

        // Button 3: "minimize" (Minimize to floating pill)
        val minimizeBtn = createHeaderButton("minimize", "#50FA7B") {
            minimizeToPill()
        }

        headerLayout.addView(intoBtn)
        headerLayout.addView(overBtn)
        headerLayout.addView(minimizeBtn)

        windowContainer?.addView(headerLayout)

        // --- CODE TAB BUTTONS BAR (HTML, CSS, JS/Java) ---
        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }

        htmlTabBtn = createTabButton("HTML", true) { selectTab("HTML") }
        cssTabBtn = createTabButton("CSS", false) { selectTab("CSS") }
        jsTabBtn = createTabButton("JS / Java", false) { selectTab("JS") }

        tabsLayout.addView(htmlTabBtn)
        tabsLayout.addView(cssTabBtn)
        tabsLayout.addView(jsTabBtn)

        windowContainer?.addView(tabsLayout)

        // --- SCROLLABLE CODE CONTAINER ---
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            val codeBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor(Color.parseColor("#090D16"))
                setStroke(2, Color.parseColor("#21262D"))
            }
            background = codeBg
            setPadding(16, 16, 16, 16)
        }
        codeScrollView = scrollView

        codeTextView = TextView(this).apply {
            text = htmlCode
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(codeTextView)

        windowContainer?.addView(scrollView)

        // --- ACTION BUTTON: View Generated Website ---
        openWebBtn = Button(this).apply {
            text = "🌐 Open Website in Browser"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(Color.parseColor("#238636"))
            }
            background = btnBg
            visibility = View.GONE
            setOnClickListener { openGeneratedWebsite() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }
        windowContainer?.addView(openWebBtn)

        // Draggable touch listener for window header
        headerLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                val lp = layoutParams ?: return false
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = lp.x
                        initialY = lp.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = initialX + (event.rawX - initialTouchX).toInt()
                        lp.y = initialY + (event.rawY - initialTouchY).toInt()
                        try { windowManager?.updateViewLayout(windowContainer, lp) } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun buildMinimizedPill() {
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(Color.parseColor("#EE0D1117"))
            setStroke(3, Color.parseColor("#58A6FF"))
        }

        minimizedPillView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg
            setPadding(24, 16, 24, 16)
            elevation = 16f
            visibility = View.GONE

            val iconTv = TextView(context).apply {
                text = "⚡ JARVIS Web Gen"
                setTextColor(Color.parseColor("#58A6FF"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
            }
            addView(iconTv)

            setOnClickListener {
                restoreFromPill()
            }
        }
    }

    private fun createHeaderButton(label: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.parseColor(colorHex))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setPadding(12, 4, 12, 4)
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(Color.parseColor("#21262D"))
                setStroke(2, Color.parseColor(colorHex))
            }
            background = btnBg
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8 }
        }
    }

    private fun createTabButton(title: String, active: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6
            }
            updateTabStyle(this, active)
            setOnClickListener { onClick() }
        }
    }

    private fun updateTabStyle(btn: Button, active: Boolean) {
        btn.setTextColor(if (active) Color.WHITE else Color.parseColor("#8B949E"))
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f
            setColor(if (active) Color.parseColor("#1F6FEB") else Color.parseColor("#161B22"))
        }
        btn.background = bg
    }

    private var codeScrollView: ScrollView? = null

    private fun selectTab(tab: String) {
        currentTab = tab
        updateTabStyle(htmlTabBtn!!, tab == "HTML")
        updateTabStyle(cssTabBtn!!, tab == "CSS")
        updateTabStyle(jsTabBtn!!, tab == "JS")
        updateCodeDisplay()
    }

    private fun updateCodeDisplay() {
        val codeText = when (currentTab) {
            "CSS" -> cssCode
            "JS" -> jsCode
            else -> htmlCode
        }
        codeTextView?.text = codeText
        codeScrollView?.post {
            codeScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showWindow() {
        if (windowContainer != null && windowContainer?.parent == null) {
            try { windowManager?.addView(windowContainer, layoutParams) } catch (e: Exception) { Log.e(TAG, "addView window failed", e) }
        }
        windowContainer?.visibility = View.VISIBLE
        minimizedPillView?.visibility = View.GONE
        isMinimized = false
    }

    private fun hideOverlayCompletely() {
        windowContainer?.visibility = View.GONE
        minimizedPillView?.visibility = View.GONE
    }

    private fun minimizeToPill() {
        windowContainer?.visibility = View.GONE
        if (minimizedPillView != null && minimizedPillView?.parent == null) {
            try { windowManager?.addView(minimizedPillView, pillLayoutParams) } catch (e: Exception) { Log.e(TAG, "addView pill failed", e) }
        }
        minimizedPillView?.visibility = View.VISIBLE
        isMinimized = true
    }

    private fun restoreFromPill() {
        minimizedPillView?.visibility = View.GONE
        showWindow()
    }

    private fun toggleExpandWindow() {
        val lp = layoutParams ?: return
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)

        if (isExpanded) {
            lp.width = (displayMetrics.widthPixels * 0.90f).toInt()
            lp.height = (displayMetrics.heightPixels * 0.55f).toInt()
            isExpanded = false
        } else {
            lp.width = (displayMetrics.widthPixels * 0.98f).toInt()
            lp.height = (displayMetrics.heightPixels * 0.85f).toInt()
            isExpanded = true
        }
        try { windowManager?.updateViewLayout(windowContainer, lp) } catch (_: Exception) {}
    }

    private fun openGeneratedWebsite() {
        val folder = currentFolderPath ?: return
        val indexFile = File(folder, "index.html")
        if (!indexFile.exists()) return

        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                indexFile
            )
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(viewIntent)
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider open failed: ${e.message}", e)
            try {
                val editorIntent = Intent(this, com.jarvis.assistant.ui.settings.CodeEditorActivity::class.java).apply {
                    putExtra(com.jarvis.assistant.ui.settings.CodeEditorActivity.EXTRA_FOLDER_PATH, folder)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(editorIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "CodeEditorActivity fallback failed: ${e2.message}", e2)
            }
        }
    }

    override fun onDestroy() {
        instance = null
        try {
            if (windowContainer?.parent != null) windowManager?.removeView(windowContainer)
            if (minimizedPillView?.parent != null) windowManager?.removeView(minimizedPillView)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay views", e)
        }
        super.onDestroy()
    }
}
