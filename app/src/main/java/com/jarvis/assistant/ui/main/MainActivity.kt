package com.jarvis.assistant.ui.main

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.R
import com.jarvis.assistant.model.ChatMessage
import com.jarvis.assistant.service.JarvisVoiceService
import com.jarvis.assistant.ui.settings.SettingsActivity
import com.jarvis.assistant.util.AnimUtils
import com.jarvis.assistant.util.EnvLoader
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.util.pressFeedback
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import android.content.ClipData
import android.content.ClipboardManager
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.webkit.WebViewClient
import com.jarvis.assistant.firebase.GroupChatManager
import com.jarvis.assistant.model.GroupChatMessage

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_JARVIS_SHUTDOWN = "com.jarvis.assistant.ACTION_SHUTDOWN"
        private const val ALL_PERMISSIONS_REQUEST_CODE = 1010
        var instance: MainActivity? = null
    }

    enum class OrbState { IDLE, LISTENING, SPEAKING, THINKING, ACTIVE }

    private lateinit var orbWebView: android.webkit.WebView
    private lateinit var standbyBarWebView: android.webkit.WebView
    private lateinit var classicHomeLayout: View
    private lateinit var cyberHudWebView: android.webkit.WebView
    private var cyberHudLoaded = false
    private var currentHomeScreenStyle: String = ""
    private lateinit var timeText: android.widget.TextView
    private lateinit var batteryText: android.widget.TextView
    private lateinit var ramText: android.widget.TextView
    private lateinit var redOverlay: View
    private lateinit var chatRecycler: androidx.recyclerview.widget.RecyclerView
    private lateinit var topBarCard: View
    private lateinit var bottomSection: View

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var chatInput: android.widget.EditText
    private lateinit var sendBtn: android.widget.ImageButton
    private lateinit var clearHistoryBtn: android.widget.ImageButton
    private lateinit var closeDrawerBtn: android.widget.ImageButton
    private lateinit var gestureDetector: android.view.GestureDetector

    // Group chat views
    private lateinit var groupChatRecycler: androidx.recyclerview.widget.RecyclerView
    private lateinit var groupChatAdapter: GroupChatAdapter
    private lateinit var groupChatBtn: android.widget.ImageButton
    private lateinit var chatDrawerTitleText: android.widget.TextView
    private lateinit var chatDrawerSubtitleText: android.widget.TextView
    private var isGroupChatMode = false

    private lateinit var cameraPreviewCard: View
    private lateinit var cameraTextureView: android.view.TextureView
    private lateinit var cameraFlipBtn: View

    // Error overlay views
    private lateinit var errorOverlay: View
    private lateinit var errorDetailText: android.widget.TextView
    private lateinit var errorTimestamp: android.widget.TextView

    // Skeleton loader views
    private lateinit var orbSkeletonView: View
    private lateinit var footerSkeletonView: View
    private var orbSkeletonAnimator: ObjectAnimator? = null
    private var footerSkeletonAnimator: ObjectAnimator? = null
    private var orbLoaded = false
    private var footerLoaded = false

    // Active session configuration tracking
    private var activePersonality: String = ""
    private var activeVoice: String = ""
    private var activeUserName: String = ""
    private var activeApiKey: String = ""
    private var activeModelString: String = ""
    private var currentAppliedTheme: String = ""

    /** Keeps a handle on the mic ring's infinite breathing animator so it can
     *  be cancelled in onDestroy — previously this ran forever with no owner,
     *  leaking the view/Activity and risking duplicate overlapping animators
     *  if the Activity was ever recreated. */
    private var micRingBreathingAnimator: android.animation.ValueAnimator? = null

    /** True once the user has long-pressed the mic to fully shut JARVIS down
     *  (mic + WebSocket + foreground service all torn down). A plain tap on
     *  the mic button while this is true restarts everything from scratch. */
    private var isShutDown = false

    /** Bound reference to the always-on voice service; null until onServiceConnected fires. */
    private var voiceService: JarvisVoiceService? = null
    private var isBound = false

    private var isMuted = false
    private var isActiveMode = false

    private val inputBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()

    private val statusHandler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null

    private val screenCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            voiceService?.startScreenShare(result.resultCode, result.data!!)
            Toast.makeText(this, "Vision Screen Share Started", Toast.LENGTH_SHORT).show()
        } else {
            voiceService?.ensureMicrophoneForegroundService()
            Toast.makeText(this, "Screen Share permission canceled", Toast.LENGTH_SHORT).show()
            updateVisionVisuals(false)
        }
    }

    /** Receiver for graceful shutdown (triggered when removed from recent apps). */
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            performGracefulShutdown()
        }
    }

    /**
     * Binds MainActivity's UI to JarvisVoiceService. The service keeps running
     * (mic + WebSocket) even while unbound, e.g. while YouTube is in the
     * foreground — this connection only exists to push UI updates.
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as JarvisVoiceService.LocalBinder
            voiceService = localBinder.getService()
            voiceService?.addListener(voiceListener)
            voiceService?.setAppForeground(true)
            voiceService?.ensureMicrophoneForegroundService()

            val currentVoice = prefs().getString("gemini_voice", "Puck") ?: "Puck"
            if (voiceService?.isSessionRunning() == true) {
                // Rejoining an already-running session (e.g. returned from YouTube)
                if (voiceService?.getCurrentVoice() != currentVoice) {
                    voiceService?.updateVoice(currentVoice)
                }
                runOnUiThread {
                    updateMicVisuals()
                    setOrbState(if (isMuted) OrbState.IDLE else OrbState.LISTENING)
                }
            } else {
                startVoiceSession()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
        }
    }

    private val voiceListener = object : JarvisVoiceService.JarvisVoiceListener {
        override fun onConnected() {
            runOnUiThread {
                dismissErrorOverlay()
                setOrbState(OrbState.THINKING)
            }
        }

        override fun onSetupComplete() {
            runOnUiThread {
                dismissErrorOverlay()
                setOrbState(OrbState.LISTENING)
            }
        }

        override fun onCommandIgnored() {
            // Someone was heard but never said "Jarvis" — stay quiet, no UI change needed.
            // Left as a hook in case a subtle indicator is wanted later.
        }

        override fun onDisconnected() {
            runOnUiThread { setOrbState(OrbState.THINKING) }
        }

        override fun onError(msg: String) {
            runOnUiThread {
                showErrorOverlay(msg)
            }
        }

        override fun onInputTranscript(text: String) {
            if (!containsForbiddenScript(text)) inputBuffer.append(text)
        }

        override fun onOutputTranscript(text: String) {
            if (!containsForbiddenScript(text)) outputBuffer.append(text)
        }

        override fun onTurnComplete() {
            runOnUiThread { flushTranscriptBuffers() }
        }

        override fun onAmplitudeChanged(rms: Float) {
            runOnUiThread {
                val speaking = voiceService?.isCurrentlySpeaking() == true
                val amplified = if (speaking) (rms * 12f).coerceIn(0.12f, 1f) else (rms * 8f).coerceIn(0f, 1f)
                updateOrbAudioLevel(amplified)
                updateBarAudioLevel(amplified)
            }
        }

        override fun onSpeakingStarted() {
            runOnUiThread {
                setOrbState(OrbState.SPEAKING)
                setActiveMode(true)
            }
        }

        override fun onSpeakingStopped() {
            runOnUiThread {
                setOrbState(if (isMuted) OrbState.IDLE else OrbState.LISTENING)
                setActiveMode(false)
            }
        }

        override fun onStandbyStateChanged(isStandby: Boolean) {
            runOnUiThread {
                if (isStandby) {
                    setOrbState(OrbState.IDLE)
                    setActiveMode(false)
                } else {
                    setOrbState(if (isMuted) OrbState.IDLE else OrbState.LISTENING)
                }
            }
        }

        override fun onToolCall(name: String, args: JSONObject, callId: String) {
            // Execution happens in JarvisVoiceService itself (so it still works
            // even if this Activity isn't bound, e.g. mid-YouTube-video).
            // This callback is just a hook for optional UI feedback.
        }

        override fun onScreenShareStateChanged(isSharing: Boolean) {
            updateVisionVisuals(isSharing)
        }

        override fun onCameraVisionStateChanged(isActive: Boolean, isFront: Boolean) {
            updateCameraVisuals(isActive, isFront)
        }

        override fun onResearchStateChanged(isSearching: Boolean, query: String) {
            updateResearchVisuals(isSearching, query)
        }

        override fun onShutdownRequested() {
            runOnUiThread { shutdownJarvis() }
        }
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    private var systemStatusBarTopDp: Int = 0
    private var lastSettingsOpenTime: Long = 0L

    fun openSettingsSafely() {
        val now = System.currentTimeMillis()
        if (now - lastSettingsOpenTime < 1000L) return
        lastSettingsOpenTime = now
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)?.let { controller ->
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
        super.onCreate(savedInstanceState)

        val isAuth = prefs().getBoolean("is_authenticated", false)
        if (!isAuth) {
            val loginIntent = Intent(this, com.jarvis.assistant.ui.auth.LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(loginIntent)
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        currentAppliedTheme = ThemeManager.getTheme(this)

        // Start the foreground service before WebViews and the rest of the dashboard are built.
        // Its onStartCommand pre-connects the persisted Live session in parallel with UI work.
        ContextCompat.startForegroundService(this, Intent(this, JarvisVoiceService::class.java))

        // Non-blocking update check for direct APK installations
        com.jarvis.assistant.update.UpdateManager.checkForUpdates(this)

        initViews()
        applyWindowInsets()
        startStatusUpdates()

        drawerLayout = findViewById(R.id.drawerLayout)
        gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && Math.abs(velocityX) > 200 && Math.abs(e2.y - e1.y) < 300) {
                    if (e2.x - e1.x > 100) { // Swipe Right -> Open Chat Drawer
                        openChatDrawer()
                        return true
                    }
                }
                return false
            }
        })

        instance = this
        ContextCompat.registerReceiver(
            this, shutdownReceiver, IntentFilter(ACTION_JARVIS_SHUTDOWN),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ThemeManager.addListener(themeChangeListener)
        startAndBindVoiceService()
        checkStoragePermissionOnLaunch()
        checkPermissions()

        // Check for crash log from previous session
        checkAndDisplayCrashLog()
    }

    private val themeChangeListener: (String) -> Unit = { newTheme ->
        runOnUiThread {
            currentAppliedTheme = newTheme
            applyThemeVisuals()
            orbWebView.evaluateJavascript("if (window.setAppTheme) window.setAppTheme('$newTheme');", null)
            standbyBarWebView.evaluateJavascript("if (window.setAppTheme) window.setAppTheme('$newTheme');", null)
        }
    }

    private fun checkStoragePermissionOnLaunch() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } else {
                val permsToRequest = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                if (permsToRequest.isNotEmpty()) {
                    ActivityCompat.requestPermissions(this, permsToRequest.toTypedArray(), 301)
                }
            }
        } catch (_: Exception) {}
    }

    fun applyBrightness(percentage: Int) {
        runOnUiThread {
            try {
                val lp = window.attributes
                val floatVal = (percentage.coerceIn(1, 100) / 100f)
                lp.screenBrightness = floatVal
                window.attributes = lp
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setWindowBrightness(brightness255: Int) {
        applyBrightness(((brightness255 / 255f) * 100).toInt())
    }

    fun resetWindowBrightnessOverride() {
        runOnUiThread {
            try {
                val lp = window.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Starts the foreground voice service (if not already running) and binds
     * to it for UI updates. Called once from onCreate, and again after the
     * user long-presses the mic to fully shut JARVIS down and then taps it to
     * bring her back.
     */
    private fun startAndBindVoiceService() {
        val serviceIntent = Intent(this, JarvisVoiceService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        isBound = true
    }

    /**
     * Pads the top bar and bottom section by the real system bar insets
     * (status bar height, gesture/navigation bar height) instead of relying
     * on hardcoded dp margins. Theme uses edge-to-edge (transparent status
     * bar), so without this the top pill and mic button can end up drawn
     * under the status bar / gesture bar on many devices.
     */
    private fun applyWindowInsets() {
        val topBarDefaultMarginPx = (14 * resources.displayMetrics.density).toInt()
        val bottomDefaultPaddingPx = (28 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.drawerLayout)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemStatusBarTopDp = (bars.top / resources.displayMetrics.density).toInt()
            if (cyberHudLoaded) {
                cyberHudWebView.evaluateJavascript("if (window.setTopInset) window.setTopInset($systemStatusBarTopDp);", null)
            }
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(topBarCard) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val lp = view.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.topMargin = bars.top + topBarDefaultMarginPx
                view.layoutParams = lp
            }
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomSection) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom + bottomDefaultPaddingPx)
            insets
        }

        val chatDrawerPanel = findViewById<View>(R.id.chatDrawerPanel)
        val drawerDefaultPaddingPx = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(chatDrawerPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                drawerDefaultPaddingPx,
                bars.top + drawerDefaultPaddingPx,
                drawerDefaultPaddingPx,
                bars.bottom + drawerDefaultPaddingPx
            )
            insets
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        voiceService?.setAppForeground(true)
        voiceService?.ensureMicrophoneForegroundService()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)) {
            com.jarvis.assistant.service.FloatingOrbService.stopService(this)
        }
    }

    override fun onStop() {
        super.onStop()
        voiceService?.setAppForeground(false)
        val enableOverlay = prefs().getBoolean("enable_floating_overlay", false)
        if (enableOverlay && (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this))) {
            com.jarvis.assistant.service.FloatingOrbService.startService(this)
        }
    }

    override fun onPause() {
        super.onPause()
        // Intentionally NOT muting the mic here — JARVIS keeps listening in background
        // but gates responses until wake word ("jarvis", "hi jarvis", "hello jarvis", "hey jarvis") is spoken
        voiceService?.setAppForeground(false)
        orbWebView.onPause()
        standbyBarWebView.onPause()
        cyberHudWebView.onPause()
        val enableOverlay = prefs().getBoolean("enable_floating_overlay", false)
        if (enableOverlay && (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this))) {
            com.jarvis.assistant.service.FloatingOrbService.startService(this)
        }
    }

    override fun onResume() {
        super.onResume()
        instance = this

        // Check for updates every time app is opened or brought back from recents
        com.jarvis.assistant.update.UpdateManager.checkForUpdates(this)

        val activeTheme = ThemeManager.getTheme(this)
        if (currentAppliedTheme != activeTheme) {
            currentAppliedTheme = activeTheme
            applyThemeVisuals()
            orbWebView.evaluateJavascript("if (window.setAppTheme) window.setAppTheme('$activeTheme');", null)
            standbyBarWebView.evaluateJavascript("if (window.setAppTheme) window.setAppTheme('$activeTheme');", null)
        }

        voiceService?.setAppForeground(true)
        voiceService?.ensureMicrophoneForegroundService()
        if (isBound && voiceService != null) {
            voiceService?.uiListener = voiceListener
        }
        orbWebView.onResume()
        standbyBarWebView.onResume()
        cyberHudWebView.onResume()
        applyHomeScreenStyle()

        orbWebView.evaluateJavascript("if (window.setAppTheme) window.setAppTheme('$activeTheme');", null)
        standbyBarWebView.evaluateJavascript("if (window.setAppTheme) window.setAppTheme('$activeTheme');", null)
        applyThemeVisuals()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)) {
            com.jarvis.assistant.service.FloatingOrbService.stopService(this)
        }

        // Reload latest chat history
        val latestHistory = com.jarvis.assistant.util.ChatHistoryManager.loadHistory(this)
        chatAdapter.setMessages(latestHistory)
        if (latestHistory.isNotEmpty()) {
            chatRecycler.scrollToPosition(latestHistory.size - 1)
        }

        val hasKey = isApiKeyConfigured()
        var currentKey = EnvLoader.getApiKey(this)
        if (currentKey.isBlank()) currentKey = prefs().getString("api_key", "") ?: ""
        val currentUserName = prefs().getString("user_name", "Sir") ?: "Sir"
        val currentVoice = prefs().getString("gemini_voice", "Puck") ?: "Puck"
        val currentPersonality = prefs().getString("personality_mode", "jarvis") ?: "jarvis"
        val currentModel = prefs().getString("gemini_model", "models/gemini-3.1-flash-live-preview")
            ?: "models/gemini-3.1-flash-live-preview"

        if (hasKey) {
            val sessionRunning = voiceService?.isSessionRunning() == true
            val serviceVoiceMismatch = voiceService != null && voiceService?.getCurrentVoice() != currentVoice
            val settingsChanged = currentKey != activeApiKey ||
                    currentUserName != activeUserName ||
                    currentVoice != activeVoice ||
                    currentPersonality != activePersonality ||
                    currentModel != activeModelString ||
                    serviceVoiceMismatch

            if (!sessionRunning || settingsChanged || isShutDown) {
                isShutDown = false
                isMuted = false
                if (isBound && voiceService != null) {
                    startVoiceSession(forceRestart = true)
                } else {
                    startAndBindVoiceService()
                }
            } else {
                val isSpeaking = voiceService?.isCurrentlySpeaking() == true
                val targetState = if (isSpeaking) OrbState.SPEAKING else if (isMuted) OrbState.IDLE else OrbState.LISTENING
                setOrbState(targetState)
            }
        } else {
            isShutDown = true
            isMuted = true
            setOrbState(OrbState.IDLE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ThemeManager.removeListener(themeChangeListener)
        if (instance == this) instance = null
        micRingBreathingAnimator?.cancel()
        orbSkeletonAnimator?.cancel()
        footerSkeletonAnimator?.cancel()
        if (isBound) {
            voiceService?.removeListener(voiceListener)
            unbindService(serviceConnection)
            isBound = false
        }
        try {
            unregisterReceiver(shutdownReceiver)
        } catch (e: Exception) {
            // already unregistered
        }
        statusRunnable?.let { statusHandler.removeCallbacks(it) }
    }

    /**
     * Gracefully cleans up activity UI bindings when removed from recent apps
     * while keeping JarvisVoiceService actively listening in the background.
     */
    private fun performGracefulShutdown() {
        if (isBound) {
            voiceService?.removeListener(voiceListener)
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            isBound = false
        }
        statusRunnable?.let { statusHandler.removeCallbacks(it) }
        finishAndRemoveTask()
    }

    // ---------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------

    private fun initViews() {
        classicHomeLayout = findViewById(R.id.classicHomeLayout)
        orbWebView = findViewById(R.id.orbWebView)
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        orbWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        orbWebView.webChromeClient = android.webkit.WebChromeClient()
        orbWebView.setBackgroundColor(Color.TRANSPARENT)
        orbWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        orbWebView.addJavascriptInterface(OrbBridge(), "AndroidInterface")
        orbWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                val activeTheme = ThemeManager.getTheme(this@MainActivity)
                orbWebView.evaluateJavascript("if (window.setAppTheme) window.setAppTheme('$activeTheme');", null)
                if (!orbLoaded) {
                    orbLoaded = true
                    runOnUiThread { hideSkeletonView(orbSkeletonView, orbSkeletonAnimator) }
                }
            }
        }
        val activeTheme = ThemeManager.getTheme(this)
        orbWebView.loadUrl("file:///android_asset/index.html?theme=$activeTheme")
        standbyBarWebView = findViewById(R.id.standbyBarWebView)
        standbyBarWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        standbyBarWebView.webChromeClient = android.webkit.WebChromeClient()
        standbyBarWebView.setBackgroundColor(Color.TRANSPARENT)
        standbyBarWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        standbyBarWebView.isFocusable = false
        standbyBarWebView.isFocusableInTouchMode = false
        standbyBarWebView.isHapticFeedbackEnabled = true
        standbyBarWebView.addJavascriptInterface(StandbyBarBridge(), "AndroidInterface")
        standbyBarWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                val currentTheme = ThemeManager.getTheme(this@MainActivity)
                standbyBarWebView.evaluateJavascript("if (window.setAppTheme) window.setAppTheme('$currentTheme');", null)
                if (!footerLoaded) {
                    footerLoaded = true
                    runOnUiThread { hideSkeletonView(footerSkeletonView, footerSkeletonAnimator) }
                }
            }
        }
        standbyBarWebView.loadUrl("file:///android_asset/standby-bar.html?theme=$activeTheme")

        cyberHudWebView = findViewById(R.id.cyberHudWebView)
        cyberHudWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        cyberHudWebView.webChromeClient = android.webkit.WebChromeClient()
        cyberHudWebView.setBackgroundColor(Color.TRANSPARENT)
        cyberHudWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        cyberHudWebView.addJavascriptInterface(CyberHudBridge(), "AndroidInterface")
        cyberHudWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                cyberHudLoaded = true
                syncCyberHudInitialState()
            }
        }
        cyberHudWebView.loadUrl("file:///android_asset/cyber-hud.html?theme=$activeTheme")

        timeText = findViewById(R.id.timeText)
        batteryText = findViewById(R.id.batteryText)
        ramText = findViewById(R.id.ramText)
        redOverlay = findViewById(R.id.redOverlay)
        chatRecycler = findViewById(R.id.chatRecycler)
        topBarCard = findViewById(R.id.topBarCard)
        bottomSection = findViewById(R.id.bottomSection)

        chatAdapter = ChatAdapter()
        chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatRecycler.adapter = chatAdapter
        chatRecycler.itemAnimator?.apply {
            addDuration = 320
            removeDuration = 220
            changeDuration = 220
            moveDuration = 260
        }

        val topBarCard = findViewById<View>(R.id.topBarCard)
        topBarCard?.let { card ->
            val pulse = ObjectAnimator.ofFloat(card, "alpha", 0.92f, 1.0f).apply {
                duration = 3200
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
            }
            pulse.start()
        }

        val settingsBtn = findViewById<View>(R.id.settingsBtn)
        settingsBtn.pressFeedback()
        settingsBtn.setOnClickListener {
            openSettingsSafely()
        }

        closeDrawerBtn = findViewById(R.id.closeDrawerBtn)
        closeDrawerBtn.pressFeedback()
        closeDrawerBtn.setOnClickListener { closeChatDrawer() }

        clearHistoryBtn = findViewById(R.id.clearHistoryBtn)
        clearHistoryBtn.pressFeedback()
        clearHistoryBtn.setOnClickListener {
            com.jarvis.assistant.util.ChatHistoryManager.clearHistory(this)
            chatAdapter.clear()
            if (cyberHudLoaded) {
                cyberHudWebView.evaluateJavascript("if (window.clearChatHistory) window.clearChatHistory();", null)
            }
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
        }

        chatInput = findViewById(R.id.chatInput)
        sendBtn = findViewById(R.id.sendBtn)
        sendBtn.pressFeedback()
        sendBtn.setOnClickListener {
            sendTextMessage()
        }

        // Group chat setup
        chatDrawerTitleText = findViewById(R.id.chatDrawerTitleText)
        chatDrawerSubtitleText = findViewById(R.id.chatDrawerSubtitleText)
        groupChatBtn = findViewById(R.id.groupChatBtn)
        groupChatBtn.pressFeedback()
        groupChatBtn.setOnClickListener { toggleGroupChat() }

        groupChatRecycler = findViewById(R.id.groupChatRecycler)
        val userEmail = GroupChatManager.getUserEmail(this)
        groupChatAdapter = GroupChatAdapter(userEmail)
        groupChatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        groupChatRecycler.adapter = groupChatAdapter
        groupChatRecycler.itemAnimator?.apply {
            addDuration = 320
            removeDuration = 220
            changeDuration = 220
            moveDuration = 260
        }

        cameraPreviewCard = findViewById(R.id.cameraPreviewCard)
        cameraTextureView = findViewById(R.id.cameraTextureView)
        cameraFlipBtn = findViewById(R.id.cameraFlipBtn)
        cameraFlipBtn.pressFeedback()
        cameraFlipBtn.setOnClickListener {
            voiceService?.switchCameraLens()
        }

        // Error overlay
        errorOverlay = findViewById(R.id.errorOverlay)
        errorDetailText = findViewById(R.id.errorDetailText)
        errorTimestamp = findViewById(R.id.errorTimestamp)
        findViewById<View>(R.id.dismissErrorBtn).setOnClickListener { dismissErrorOverlay() }
        findViewById<View>(R.id.dismissErrorBtn2).setOnClickListener { dismissErrorOverlay() }
        findViewById<View>(R.id.copyErrorBtn).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS Error", errorDetailText.text))
            Toast.makeText(this, "Error copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        // Skeleton loaders — start pulse animation
        orbSkeletonView = findViewById(R.id.orbSkeletonView)
        footerSkeletonView = findViewById(R.id.footerSkeletonView)
        orbSkeletonAnimator = startSkeletonPulse(orbSkeletonView)
        footerSkeletonAnimator = startSkeletonPulse(footerSkeletonView)

        val loadedHistory = com.jarvis.assistant.util.ChatHistoryManager.loadHistory(this)
        chatAdapter.setMessages(loadedHistory)
        if (loadedHistory.isNotEmpty()) {
            chatRecycler.scrollToPosition(loadedHistory.size - 1)
        }

        applyThemeVisuals()
        updateMicVisuals()
    }

    private fun applyThemeVisuals() {
        val primaryColor = ThemeManager.getPrimaryColorInt(this)
        topBarCard.setBackgroundResource(ThemeManager.getTopHeaderDrawable(this))
        sendBtn.setBackgroundResource(ThemeManager.getSaveButtonDrawable(this))
        findViewById<android.widget.TextView>(R.id.chatDrawerTitleText)?.setTextColor(primaryColor)
        chatAdapter.notifyDataSetChanged()
    }

    private fun applyHomeScreenStyle() {
        val style = prefs().getString("home_screen_style", "classic") ?: "classic"
        currentHomeScreenStyle = style
        if (style == "cyber_hud") {
            classicHomeLayout.visibility = View.GONE
            cyberHudWebView.visibility = View.VISIBLE
            if (cyberHudLoaded) {
                syncCyberHudInitialState()
            }
        } else {
            cyberHudWebView.visibility = View.GONE
            classicHomeLayout.visibility = View.VISIBLE
        }
    }

    private fun syncCyberHudInitialState() {
        val hasKey = isApiKeyConfigured()
        val currentPwr = batteryText.text.toString().replace("%", "").trim()
        val pwrInt = currentPwr.toIntOrNull() ?: 92
        val latestHistory = com.jarvis.assistant.util.ChatHistoryManager.loadHistory(this)
        val historyJsonArray = org.json.JSONArray()
        latestHistory.takeLast(10).forEach { msg ->
            val obj = org.json.JSONObject()
            obj.put("isUser", msg.isUser)
            obj.put("message", msg.text)
            historyJsonArray.put(obj)
        }
        val historyJsonStr = historyJsonArray.toString().replace("'", "\\'")

        val stateIndex = when {
            voiceService?.isCurrentlySpeaking() == true -> 3
            isMuted || isShutDown || !hasKey -> 0
            else -> 1
        }

        cyberHudWebView.evaluateJavascript("if (window.setApiKeyConfigured) window.setApiKeyConfigured($hasKey);", null)
        cyberHudWebView.evaluateJavascript("if (window.updateBattery) window.updateBattery($pwrInt);", null)
        cyberHudWebView.evaluateJavascript("if (window.loadChatHistory) window.loadChatHistory('$historyJsonStr');", null)
        cyberHudWebView.evaluateJavascript("if (window.setOrbState) window.setOrbState($stateIndex);", null)
        if (systemStatusBarTopDp > 0) {
            cyberHudWebView.evaluateJavascript("if (window.setTopInset) window.setTopInset($systemStatusBarTopDp);", null)
        }
    }

    private fun toggleScreenShare() {
        val service = voiceService
        if (service == null) {
            Toast.makeText(this, "JARVIS Voice session is starting...", Toast.LENGTH_SHORT).show()
            return
        }
        if (service.isScreenSharing()) {
            service.stopScreenShare()
            Toast.makeText(this, "Vision Screen Share Stopped", Toast.LENGTH_SHORT).show()
        } else {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    private fun updateVisionVisuals(isSharing: Boolean) {
        runOnUiThread {
            val js = "if (window.setVisionState) window.setVisionState($isSharing);"
            standbyBarWebView.evaluateJavascript(js, null)
        }
    }

    private fun toggleCameraVision(useFront: Boolean = false) {
        val service = voiceService
        if (service == null) {
            Toast.makeText(this, "JARVIS Voice session is starting...", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), ALL_PERMISSIONS_REQUEST_CODE)
            Toast.makeText(this, "Camera permission needed for vision", Toast.LENGTH_SHORT).show()
            return
        }

        if (service.isCameraVisionActive()) {
            if (service.isCameraFrontLens() == useFront) {
                service.stopCameraVision()
                Toast.makeText(this, "Camera Vision Stopped", Toast.LENGTH_SHORT).show()
            } else {
                service.switchCameraLens()
            }
        } else {
            cameraPreviewCard.visibility = View.VISIBLE
            if (cameraTextureView.isAvailable) {
                service.startCameraVision(useFront, cameraTextureView)
            } else {
                cameraTextureView.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        service.startCameraVision(useFront, cameraTextureView)
                    }
                    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                }
            }
            Toast.makeText(this, if (useFront) "Front Camera Vision Active" else "Back Camera Vision Active", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun updateCameraVisuals(isActive: Boolean, isFront: Boolean) {
        runOnUiThread {
            cameraPreviewCard.visibility = if (isActive) View.VISIBLE else View.GONE
            if (isActive && cameraTextureView.isAvailable) {
                voiceService?.updateCameraPreviewTarget(cameraTextureView)
            }
            val js = "if (window.setCameraState) window.setCameraState($isActive);"
            standbyBarWebView.evaluateJavascript(js, null)
        }
    }

    fun showResearchHud(query: String) {
        updateResearchVisuals(true, query)
    }

    fun hideResearchHud() {
        updateResearchVisuals(false, "")
    }

    private fun updateResearchVisuals(isSearching: Boolean, query: String) {
        runOnUiThread {
            val safeQuery = query.replace("'", "\\'")
            val js = "if (window.setResearchState) window.setResearchState($isSearching, '$safeQuery');"
            standbyBarWebView.evaluateJavascript(js, null)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), ALL_PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ALL_PERMISSIONS_REQUEST_CODE) return

        val micIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val micGranted = micIndex != -1 && grantResults.getOrNull(micIndex) == PackageManager.PERMISSION_GRANTED

        if (micGranted) {
            // Previously stuck silently if the user denied then re-granted from
            // Settings — nothing ever re-checked. Kick the session off now.
            if (voiceService?.isSessionRunning() != true) {
                startVoiceSession()
            }
        } else {
            Toast.makeText(this, "Mic permission chahiye JARVIS ke liye", Toast.LENGTH_SHORT).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifIndex = permissions.indexOf(Manifest.permission.POST_NOTIFICATIONS)
            if (notifIndex != -1 && grantResults.getOrNull(notifIndex) == PackageManager.PERMISSION_GRANTED) {
                voiceService?.updateNotification()
            }
        }
    }

    private fun startStatusUpdates() {
        statusRunnable = object : Runnable {
            override fun run() {
                updateStatusBar()
                statusHandler.postDelayed(this, 30_000)
            }
        }
        statusRunnable?.run()
    }

    private fun updateStatusBar() {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        timeText.text = timeFmt.format(Date())

        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        batteryText.text = "$level%"
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val usedPercent = (100 - (memInfo.availMem * 100 / memInfo.totalMem)).toInt()
        ramText.text = "RAM $usedPercent%"

        if (cyberHudLoaded) {
            cyberHudWebView.evaluateJavascript("if (window.updateBattery) window.updateBattery($level, 'RAM $usedPercent%');", null)
        }
    }

    // ---------------------------------------------------------------
    // Voice session (delegates to JarvisVoiceService)
    // ---------------------------------------------------------------

    private fun prefs() = getSharedPreferences(JarvisApplication.PREFS_NAME, Context.MODE_PRIVATE)

    private fun startVoiceSession(forceRestart: Boolean = false) {
        if (!isApiKeyConfigured()) {
            setOrbState(OrbState.IDLE)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), ALL_PERMISSIONS_REQUEST_CODE)
            Toast.makeText(this, "Microphone permission is required to start JARVIS voice session", Toast.LENGTH_SHORT).show()
            return
        }

        var apiKey = EnvLoader.getApiKey(this)
        if (apiKey.isBlank()) {
            apiKey = prefs().getString("api_key", "") ?: ""
        }

        val userName = prefs().getString("user_name", "Sir") ?: "Sir"
        val modelString = prefs().getString("gemini_model", "models/gemini-3.1-flash-live-preview")
            ?: "models/gemini-3.1-flash-live-preview"
        val voiceName = prefs().getString("gemini_voice", "Puck") ?: "Puck"
        val personality = prefs().getString("personality_mode", "jarvis") ?: "jarvis"

        activePersonality = personality
        activeVoice = voiceName
        activeUserName = userName
        activeApiKey = apiKey
        activeModelString = modelString

        val maleVoiceNames = setOf("puck", "charon", "fenrir", "orus", "arvind", "amartya", "dev")
        val isFemale = !maleVoiceNames.contains(voiceName.lowercase().trim())

        val systemPrompt = buildSystemPrompt(userName, personality, isFemale, voiceName)
        android.util.Log.d("MainActivity", "startVoiceSession: apiKey len=${apiKey.length}, model=$modelString, forceRestart=$forceRestart, voiceService=$voiceService")
        if (forceRestart) {
            voiceService?.restartSession(apiKey, modelString, systemPrompt, voiceName)
        } else {
            voiceService?.startSession(apiKey, modelString, systemPrompt, voiceName)
        }
    }

    private fun buildSystemPrompt(userName: String, personality: String, isFemale: Boolean, voiceName: String = "Puck"): String {
        return com.jarvis.assistant.util.PromptBuilder.buildSystemPrompt(userName, personality, isFemale, voiceName)
    }

    private fun isApiKeyConfigured(): Boolean {
        var apiKey = EnvLoader.getApiKey(this)
        if (apiKey.isBlank()) {
            apiKey = prefs().getString("api_key", "") ?: ""
        }
        return apiKey.isNotBlank()
    }

    private fun checkApiKeyAndExecute(action: () -> Unit) {
        if (!isApiKeyConfigured()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("API Key Required")
                .setMessage("Please configure your Gemini API Key in Settings to turn ON and use JARVIS.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            action()
        }
    }

    // ---------------------------------------------------------------
    // Orb + waveform color sync
    // ---------------------------------------------------------------

    inner class OrbBridge {
        @android.webkit.JavascriptInterface
        fun onOrbClicked() {
            runOnUiThread {
                checkApiKeyAndExecute {
                    if (isShutDown || voiceService?.isSessionRunning() != true) {
                        restartJarvis()
                    } else if (voiceService?.isStandby?.value == true) {
                        voiceService?.enterActiveState(fromWakeWord = false)
                        setOrbState(OrbState.LISTENING)
                    } else {
                        toggleMute()
                    }
                }
            }
        }
    }

    inner class StandbyBarBridge {
        @android.webkit.JavascriptInterface
        fun onVisionClicked() {
            runOnUiThread {
                checkApiKeyAndExecute { toggleScreenShare() }
            }
        }

        @android.webkit.JavascriptInterface
        fun onCameraClicked() {
            runOnUiThread {
                checkApiKeyAndExecute { toggleCameraVision(false) }
            }
        }

        @android.webkit.JavascriptInterface
        fun onMicClicked() {
            runOnUiThread {
                checkApiKeyAndExecute {
                    if (isShutDown || voiceService?.isSessionRunning() != true) {
                        restartJarvis()
                    } else if (voiceService?.isStandby?.value == true) {
                        voiceService?.enterActiveState(fromWakeWord = false)
                        setOrbState(OrbState.LISTENING)
                    } else {
                        toggleMute()
                    }
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onPowerClicked() {
            runOnUiThread {
                checkApiKeyAndExecute {
                    if (isShutDown || voiceService?.isSessionRunning() != true) {
                        restartJarvis()
                    } else {
                        shutdownJarvis()
                    }
                }
            }
        }
    }

    inner class CyberHudBridge {
        @android.webkit.JavascriptInterface
        fun onMicClicked() {
            runOnUiThread {
                checkApiKeyAndExecute {
                    if (isShutDown || voiceService?.isSessionRunning() != true) {
                        restartJarvis()
                    } else if (voiceService?.isStandby?.value == true) {
                        voiceService?.enterActiveState(fromWakeWord = false)
                        setOrbState(OrbState.LISTENING)
                    } else {
                        toggleMute()
                    }
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onMicTouch(isDown: Boolean) {
            runOnUiThread {
                if (isDown) {
                    if (isShutDown || voiceService?.isSessionRunning() != true) {
                        restartJarvis()
                    } else if (voiceService?.isStandby?.value == true) {
                        voiceService?.enterActiveState(fromWakeWord = false)
                        setOrbState(OrbState.LISTENING)
                    } else {
                        if (isMuted) toggleMute()
                    }
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onModeClicked(modeIndex: Int) {
            runOnUiThread {
                when (modeIndex) {
                    0 -> { // Idle / Standby
                        if (!isMuted) toggleMute()
                        setOrbState(OrbState.IDLE)
                    }
                    1 -> { // Listen
                        if (isShutDown || voiceService?.isSessionRunning() != true) {
                            restartJarvis()
                        } else {
                            if (isMuted) toggleMute()
                            setOrbState(OrbState.LISTENING)
                        }
                    }
                    2 -> { // Think
                        setOrbState(OrbState.THINKING)
                    }
                    3 -> { // Speak
                        setOrbState(OrbState.SPEAKING)
                    }
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onSettingsClicked() {
            runOnUiThread {
                openSettingsSafely()
            }
        }

        @android.webkit.JavascriptInterface
        fun onOpenApiKeySettings() {
            runOnUiThread {
                openSettingsSafely()
            }
        }

        @android.webkit.JavascriptInterface
        fun onSendMessage(text: String) {
            runOnUiThread {
                val clean = com.jarvis.assistant.util.ChatHistoryManager.cleanToHinglish(text)
                if (clean.isNotEmpty()) {
                    val userMsg = ChatMessage(clean, isUser = true)
                    chatAdapter.addMessage(userMsg)
                    com.jarvis.assistant.util.ChatHistoryManager.saveMessage(this@MainActivity, userMsg)
                    chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
                    voiceService?.sendTextToGemini(clean)
                }
            }
        }
    }

    private fun setOrbState(state: OrbState) {
        val themeColorHex = ThemeManager.getPrimaryColorHex(this)
        val (stateText, _) = when (state) {
            OrbState.IDLE -> "Standby" to themeColorHex
            OrbState.LISTENING, OrbState.ACTIVE -> "Speak" to themeColorHex
            OrbState.SPEAKING -> "Speaking" to themeColorHex
            OrbState.THINKING -> "Thinking" to themeColorHex
        }
        val isMutedState = isMuted
        val hasApiKey = isApiKeyConfigured()
        val isRunning = voiceService?.isSessionRunning() == true
        val isPoweredOn = !isShutDown && hasApiKey && isRunning
        val labelToDisplay = if (!hasApiKey || isShutDown || !isRunning) "OFF" else if (isMutedState) "Muted" else stateText

        val jsBar = "if (window.setBarState) window.setBarState('$labelToDisplay', '$themeColorHex', $isMutedState, $isPoweredOn);"
        standbyBarWebView.evaluateJavascript(jsBar, null)

        val jsOrb = "if (window.setOrbState) window.setOrbState('${state.name}', '$themeColorHex', $isMutedState, $isPoweredOn);"
        orbWebView.evaluateJavascript(jsOrb, null)

        if (cyberHudLoaded) {
            val cyberHudState = if (!hasApiKey || isShutDown || isMutedState) 0 else when (state) {
                OrbState.IDLE -> 0
                OrbState.LISTENING, OrbState.ACTIVE -> 1
                OrbState.THINKING -> 2
                OrbState.SPEAKING -> 3
            }
            cyberHudWebView.evaluateJavascript("if (window.setOrbState) window.setOrbState($cyberHudState);", null)
            cyberHudWebView.evaluateJavascript("if (window.setApiKeyConfigured) window.setApiKeyConfigured($hasApiKey);", null)
        }
    }

    private fun updateOrbAudioLevel(level: Float) {
        orbWebView.evaluateJavascript("if (window.setAudioLevel) window.setAudioLevel($level);", null)
    }

    private fun updateBarAudioLevel(level: Float) {
        standbyBarWebView.evaluateJavascript("if (window.setAudioAmplitude) window.setAudioAmplitude($level);", null)
        if (cyberHudLoaded) {
            cyberHudWebView.evaluateJavascript("if (window.setAudioAmplitude) window.setAudioAmplitude($level);", null)
        }
    }

    // ---------------------------------------------------------------
    // Chat / transcript flow
    // ---------------------------------------------------------------

    private fun openChatDrawer() {
        drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
    }

    private fun closeChatDrawer() {
        drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
    }

    private fun sendTextMessage() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return

        if (isGroupChatMode) {
            // Group chat mode: send to Firestore
            chatInput.setText("")
            GroupChatManager.sendMessage(this, text) { success ->
                if (!success) {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            // Jarvis bot chat mode: send to Gemini
            val hinglishText = com.jarvis.assistant.util.ChatHistoryManager.cleanToHinglish(text)
            if (hinglishText.isEmpty()) return

            chatInput.setText("")
            val userMsg = ChatMessage(hinglishText, isUser = true)
            chatAdapter.addMessage(userMsg)
            com.jarvis.assistant.util.ChatHistoryManager.saveMessage(this, userMsg)
            chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)

            voiceService?.sendTextToGemini(hinglishText)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev != null) {
            gestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun flushTranscriptBuffers() {
        val rawUser = inputBuffer.toString().trim()
        val rawJarvis = outputBuffer.toString().trim()

        val userText = com.jarvis.assistant.util.ChatHistoryManager.cleanToHinglish(rawUser)
        val jarvisText = com.jarvis.assistant.util.ChatHistoryManager.cleanToHinglish(rawJarvis)

        if (userText.isNotEmpty()) {
            val userMsg = ChatMessage(userText, isUser = true)
            chatAdapter.addMessage(userMsg)
            com.jarvis.assistant.util.ChatHistoryManager.saveMessage(this, userMsg)
            chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
            if (cyberHudLoaded) {
                val quoted = JSONObject.quote(userText)
                cyberHudWebView.evaluateJavascript("if (window.addChatMessage) window.addChatMessage(true, $quoted);", null)
            }
        }

        if (jarvisText.isNotEmpty()) {
            val jarvisMsg = ChatMessage(jarvisText, isUser = false)
            chatAdapter.addMessage(jarvisMsg)
            com.jarvis.assistant.util.ChatHistoryManager.saveMessage(this, jarvisMsg)
            chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
            if (cyberHudLoaded) {
                val quotedJarvis = JSONObject.quote(jarvisText)
                cyberHudWebView.evaluateJavascript("if (window.addChatMessage) window.addChatMessage(false, $quotedJarvis);", null)
            }
        }

        inputBuffer.clear()
        outputBuffer.clear()
    }

    // ---------------------------------------------------------------
    // Mic controls
    // ---------------------------------------------------------------

    private fun toggleMute() {
        isMuted = !isMuted
        voiceService?.setMicMuted(isMuted)
        updateMicVisuals()
        setOrbState(if (isMuted) OrbState.IDLE else OrbState.LISTENING)
    }

    private fun updateMicVisuals() {
        setOrbState(if (isMuted) OrbState.IDLE else OrbState.LISTENING)
    }

    private fun interruptJarvis() {
        voiceService?.interrupt()
        setOrbState(OrbState.LISTENING)
    }

    /**
     * Long-press on the mic: fully shuts JARVIS down — disconnects the Gemini
     * WebSocket, releases the mic (AudioEngine), and stops the foreground
     * service entirely (JarvisVoiceService.stopSession() calls stopSelf()).
     * Distinct from a plain tap (toggleMute), which just mutes the mic while
     * keeping the session/service alive in the background.
     */
    private fun shutdownJarvis() {
        if (isShutDown) return
        isShutDown = true
        isMuted = true

        voiceService?.stopSession()
        if (isBound) {
            voiceService?.removeListener(voiceListener)
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                // service may already be gone
            }
            isBound = false
        }
        voiceService = null

        setOrbState(OrbState.IDLE)
        updateMicVisuals()
    }

    /** Brings JARVIS back after a full shutdown: restarts the foreground service, rebinds, and starts a fresh session. */
    private fun restartJarvis() {
        isShutDown = false
        isMuted = false
        setOrbState(OrbState.LISTENING)
        startAndBindVoiceService()
    }

    private fun setActiveMode(active: Boolean) {
        isActiveMode = active
        redOverlay.alpha = 0f
    }

    private fun containsForbiddenScript(text: String): Boolean {
        for (c in text) {
            val code = c.code
            if (Character.isLetter(c) &&
                c !in 'A'..'Z' &&
                c !in 'a'..'z' &&
                code !in 0x0900..0x097F
            ) {
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------
    // Error Overlay
    // ---------------------------------------------------------------

    private fun showErrorOverlay(errorMessage: String) {
        errorDetailText.text = errorMessage
        errorTimestamp.text = "Occurred at: ${SimpleDateFormat("HH:mm:ss dd-MMM-yyyy", Locale.getDefault()).format(Date())}"
        errorOverlay.visibility = View.VISIBLE
        errorOverlay.alpha = 0f
        errorOverlay.animate().alpha(1f).setDuration(200).start()
    }

    private fun dismissErrorOverlay() {
        errorOverlay.animate().alpha(0f).setDuration(150).withEndAction {
            errorOverlay.visibility = View.GONE
        }.start()
    }

    private fun checkAndDisplayCrashLog() {
        val prefs = getSharedPreferences("jarvis_crash_log", Context.MODE_PRIVATE)
        val crashLog = prefs.getString("last_crash", null)
        if (!crashLog.isNullOrBlank()) {
            prefs.edit().remove("last_crash").apply()
            Handler(Looper.getMainLooper()).postDelayed({
                showErrorOverlay("⚡ JARVIS crashed in previous session:\n\n$crashLog")
            }, 1500)
        }
    }

    // ---------------------------------------------------------------
    // Skeleton Loaders
    // ---------------------------------------------------------------

    private fun startSkeletonPulse(view: View): ObjectAnimator {
        return ObjectAnimator.ofFloat(view, "alpha", 0.3f, 0.8f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun hideSkeletonView(skeletonView: View, animator: ObjectAnimator?) {
        animator?.cancel()
        skeletonView.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                skeletonView.visibility = View.GONE
            }
            .start()
    }

    // ---------------------------------------------------------------
    // Group Chat
    // ---------------------------------------------------------------

    private fun toggleGroupChat() {
        if (isGroupChatMode) {
            // Switch back to Jarvis bot chat
            switchToJarvisBotChat()
        } else {
            // Check if this is admin — auto-join without prompt
            if (GroupChatManager.isCurrentUserAdmin(this)) {
                switchToGroupChat()
            } else if (!GroupChatManager.hasDisplayName(this)) {
                // First-time non-admin user — ask for name once
                showGroupChatNamePrompt()
            } else {
                // Name already saved — go straight to group chat
                switchToGroupChat()
            }
        }
    }

    private fun showGroupChatNamePrompt() {
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val titleText = android.widget.TextView(this).apply {
            text = "Enter your name for Group Chat"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        dialogView.addView(titleText)

        val subtitleText = android.widget.TextView(this).apply {
            text = "This name will be visible to all users"
            setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
            textSize = 12f
            setPadding(0, 8, 0, 24)
        }
        dialogView.addView(subtitleText)

        val nameInput = android.widget.EditText(this).apply {
            hint = "Your display name"
            setHintTextColor(android.graphics.Color.parseColor("#55FFFFFF"))
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(32, 24, 32, 24)
            inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
            textSize = 14f
            // Pre-fill with saved user name if available
            val savedName = prefs().getString("user_name", "") ?: ""
            if (savedName.isNotBlank() && savedName != "Jarvis User") {
                setText(savedName)
                setSelection(savedName.length)
            }
        }
        dialogView.addView(nameInput)

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(dialogView)
            .setPositiveButton("JOIN") { d, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotBlank()) {
                    GroupChatManager.saveDisplayName(this, name)
                    switchToGroupChat()
                } else {
                    Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
            }
            .setNegativeButton("CANCEL") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        dialog.show()

        // Style the buttons
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            android.graphics.Color.parseColor("#00E5FF")
        )
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
            android.graphics.Color.parseColor("#99FFFFFF")
        )
    }

    private fun switchToGroupChat() {
        isGroupChatMode = true
        chatDrawerTitleText.text = "JARVIS GROUP CHAT"
        chatDrawerSubtitleText.text = "ALL USERS • LIVE"
        chatRecycler.visibility = View.GONE
        groupChatRecycler.visibility = View.VISIBLE
        clearHistoryBtn.visibility = View.GONE
        chatInput.hint = "Message the group..."

        // Tint the group icon to indicate active state
        groupChatBtn.setColorFilter(android.graphics.Color.parseColor("#00E5FF"))

        // Start Firestore real-time listener
        GroupChatManager.startListening { messages ->
            runOnUiThread {
                groupChatAdapter.setMessages(messages)
                if (messages.isNotEmpty()) {
                    groupChatRecycler.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private fun switchToJarvisBotChat() {
        isGroupChatMode = false
        chatDrawerTitleText.text = "JARVIS CHAT BOT"
        chatDrawerSubtitleText.text = "HINGLISH HISTORY"
        groupChatRecycler.visibility = View.GONE
        chatRecycler.visibility = View.VISIBLE
        clearHistoryBtn.visibility = View.VISIBLE
        chatInput.hint = "Type in Hinglish..."

        // Remove tint from group icon
        groupChatBtn.clearColorFilter()

        // Stop Firestore listener to save bandwidth
        GroupChatManager.stopListening()
    }
}
