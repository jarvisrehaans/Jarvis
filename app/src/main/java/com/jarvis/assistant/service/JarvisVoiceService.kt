package com.jarvis.assistant.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.util.Log
import com.jarvis.assistant.R
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.ai.AudioEngine
import com.jarvis.assistant.ai.GeminiLiveClient
import com.jarvis.assistant.ui.main.MainActivity
import com.jarvis.assistant.util.AppLauncher
import com.jarvis.assistant.util.ContactCaller
import android.content.Context
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.jarvis.assistant.youtube.YouTubeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import com.jarvis.assistant.audio.JarvisSoundEffects
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.wake.WakeWordDetector
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.RecognitionListener as VoskRecognitionListener

/**
 * Owns the mic capture + Gemini Live WebSocket for the whole app lifetime,
 * independent of whatever Activity is currently on screen.
 *
 * Why this exists: Android blocks microphone access for apps that are not
 * in the foreground and have no active foreground service. Previously
 * AudioEngine/GeminiLiveClient lived inside MainActivity, so the moment
 * another app (e.g. YouTube, opened via open_app) came to the front, the
 * OS cut mic access and JARVIS effectively went silent/crashed. Running this
 * as a foreground service with type "microphone" keeps the conversation
 * alive in the background.
 *
 * MainActivity binds to this service for UI updates (transcripts,
 * amplitude, speaking state) via [JarvisVoiceListener], but the service
 * itself does not depend on the Activity being bound or visible.
 *
 * --- Wake word ("Jarvis") gating ---
 * The mic is ALWAYS on and ALWAYS streaming to Gemini (no separate local
 * speech recognizer, so no extra mic-open/close cycles and no earcon
 * beeping). Gemini transcribes speech in real time via input transcription;
 * this class buffers each spoken turn's transcript and only lets JARVIS
 * actually speak a reply or run a tool (open an app, call a contact, etc.)
 * if that turn's transcript contains "Jarvis". If it doesn't, the reply is
 * silently dropped and no tool is executed — so "open YouTube" alone does
 * nothing, but "Jarvis, open YouTube" works.
 */
class JarvisVoiceService : Service() {

    companion object {
        private const val CHANNEL_ID = "jarvis_voice_channel"
        private const val NOTIFICATION_ID = 101

        // Common correct + likely-misheard spellings of the wake word, including
        // Hindi Devanagari script and ASR transliterations so background detection is reliable.
        private val WAKE_PHRASES = listOf(
            "jarvis", "hi jarvis", "hello jarvis", "hey jarvis", "ok jarvis", "okay jarvis", "listen jarvis",
            "jarviss", "jaarvis", "jarvish", "javis", "hey javis", "hi javis", "hello javis",
            "hey jarwis", "jarwis", "charvis", "chavis", "jharvis", "jervis", "zarvis", "dharvis", "garvis",
            "arvis", "jarvez", "hai jarvis", "he jarvis", "hay jarvis", "sun jarvis", "suno jarvis",
            "जरविस", "जरवीस", "जार्विश", "जार्विस सुनो", "जागो जार्विस"
        )

        private const val IDLE_TO_SLEEP_MS = 120_000L  // 2 minutes of silence -> auto-sleep
        private const val FOLLOW_UP_WINDOW_MS = 45_000L // 45s follow-up window after speech/turn
        private const val BACKGROUND_AUTO_STANDBY_MS = 120_000L // 2 minutes auto-standby window in background
        @Volatile var instance: JarvisVoiceService? = null
    }

    interface JarvisVoiceListener {
        fun onConnected() {}
        fun onSetupComplete() {}
        fun onDisconnected() {}
        fun onError(msg: String) {}
        fun onInputTranscript(text: String) {}
        fun onOutputTranscript(text: String) {}
        fun onTurnComplete() {}
        fun onAmplitudeChanged(rms: Float) {}
        fun onSpeakingStarted() {}
        fun onSpeakingStopped() {}
        fun onToolCall(name: String, args: JSONObject, callId: String) {}
        /** A turn was heard but ignored because it didn't start with "Jarvis". */
        fun onCommandIgnored() {}
        fun onScreenShareStateChanged(isSharing: Boolean) {}
        fun onCameraVisionStateChanged(isActive: Boolean, isFront: Boolean) {}
        fun onResearchStateChanged(isSearching: Boolean, query: String) {}
        fun onStandbyStateChanged(isStandby: Boolean) {}
        fun onShutdownRequested() {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): JarvisVoiceService = this@JarvisVoiceService
    }

    private val binder = LocalBinder()
    private val toolScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** UI listeners (MainActivity, FloatingOrbService) receive callbacks simultaneously. */
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<JarvisVoiceListener>()

    fun addListener(listener: JarvisVoiceListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: JarvisVoiceListener) {
        listeners.remove(listener)
    }

    var uiListener: JarvisVoiceListener?
        get() = listeners.firstOrNull()
        set(value) {
            if (value != null) addListener(value)
        }

    private inline fun dispatchToListeners(crossinline action: (JarvisVoiceListener) -> Unit) {
        for (l in listeners) {
            try {
                action(l)
            } catch (e: Exception) {
                Log.e("JarvisVoiceService", "Error notifying listener", e)
            }
        }
    }

    private var currentVoiceName: String = "Aoede"
    private var isVoiceFemale: Boolean = true

    fun setVoiceConfig(voiceName: String) {
        currentVoiceName = voiceName
        val maleVoiceNames = setOf("puck", "charon", "fenrir", "orus", "arvind", "amartya", "dev")
        isVoiceFemale = !maleVoiceNames.contains(voiceName.lowercase().trim())
    }

    fun getCurrentVoice(): String = currentVoiceName

    fun updateVoice(newVoice: String) {
        if (newVoice.isBlank()) return
        val changed = currentVoiceName.lowercase().trim() != newVoice.lowercase().trim()
        setVoiceConfig(newVoice)
        cachedVoiceName = newVoice
        try {
            getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE).edit()
                .putString("gemini_voice", newVoice)
                .putString("cached_voice", newVoice)
                .apply()
        } catch (_: Exception) {}
        if (changed) {
            Log.i("JarvisVoiceService", "Voice dynamically updated to '$newVoice' — clearing session handle and rebuilding prompt")
            geminiLive?.clearSessionHandle()
            val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            val userName = prefs.getString("user_name", "Boss") ?: "Boss"
            val personality = prefs.getString("personality_mode", "best_friend") ?: "best_friend"
            val maleVoices = setOf("puck", "charon", "fenrir", "orus", "arvind", "amartya", "dev")
            val isFemale = !maleVoices.contains(newVoice.lowercase().trim())
            val newPrompt = com.jarvis.assistant.util.PromptBuilder.buildSystemPrompt(userName, personality, isFemale, newVoice)
            cachedSystemPrompt = newPrompt
            if (isSessionStarted && !isInBackgroundStandby()) {
                restartSession(cachedApiKey, cachedModelString, newPrompt, newVoice)
            }
        }
    }

    private var geminiLive: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null
    private var screenCaptureEngine: com.jarvis.assistant.vision.ScreenCaptureEngine? = null
    private var cameraVisionEngine: com.jarvis.assistant.vision.CameraVisionEngine? = null
    private var isSessionStarted = false
    private var isUserMuted = false

    // ---------------------------------------------------------------
    // Conversation State Machine
    // ---------------------------------------------------------------
    enum class ConversationState {
        /** Full conversation mode. Mic streams to Gemini. All responses play aloud. */
        ACTIVE,
        /** User has gone silent. 2-minute countdown to SLEEPING. Any speech resets to ACTIVE. */
        IDLE_COUNTDOWN,
        /** Background silent mode. Mic does NOT stream to Gemini. Only WakeWordDetector listens. */
        SLEEPING
    }

    @Volatile private var conversationState = ConversationState.ACTIVE
    private var isAppInForeground = true
    @Volatile private var lastUserSpeechTimeMs = System.currentTimeMillis()
    private var idleCountdownJob: Job? = null
    private var autoSleepJob: Job? = null
    private var connectionHealthJob: Job? = null
    /** Active follow-up window timer (30s after launching an app, conversation can continue without wake word) */
    private var activeFollowUpJob: Job? = null
    @Volatile private var isInFollowUpWindow = false

    // --- Standby / Offline Wake Word components (Continuous in-memory Vosk Recognizer) ---
    @Volatile private var voskRecognizer: Recognizer? = null
    private var standbyActivatedTime = 0L
    // 1.5s cooldown prevents the standby sound tail or immediate echo from falsely re-triggering wake word.
    private val STANDBY_COOLDOWN_MS = 1500L
    private val _isStandby = MutableStateFlow(false)
    val isStandby: StateFlow<Boolean> = _isStandby.asStateFlow()

    // Rolling audio buffer to capture user speech during/immediately after wake word (~2.0 seconds)
    private val wakePreRollBuffer = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val WAKE_PRE_ROLL_MAX_CHUNKS = 50 // 50 * 40ms = 2.0s
    private var smartGreetingJob: Job? = null
    @Volatile private var userContinuedSpeakingAfterWake = false

    fun enterSleepingState(sayGoodbye: Boolean = false) {
        enterStandby(sayGoodbye = sayGoodbye, playSound = false)
    }

    /**
     * Transition to background standby mode.
     * JARVIS plays descending harmonic sci-fi chime, pauses Gemini Live audio transport (hot standby),
     * keeps hardware AudioRecord active to prevent Android microphone privacy indicator blinking,
     * and streams chunks to the local offline Vosk Recognizer in memory.
     * ZERO tokens and ZERO network consumed while in background.
     */
    fun enterStandby(sayGoodbye: Boolean = false, playSound: Boolean = true) {
        if (conversationState == ConversationState.SLEEPING && _isStandby.value) return
        if (screenCaptureEngine != null || cameraVisionEngine?.isCameraStreaming() == true) {
            Log.d("JarvisVoiceService", "enterStandby ignored — screen sharing or camera vision is active.")
            return
        }
        Log.i("JarvisVoiceService", "Entering SLEEPING / STANDBY state (playSound=$playSound).")

        // Cancel health check job and auto-standby job so they don't interfere
        connectionHealthJob?.cancel()
        cancelBackgroundAutoStandby()

        // Gate all Live callbacks first
        conversationState = ConversationState.SLEEPING
        _isStandby.value = true
        currentTurnHasWakeWord = false
        isInFollowUpWindow = false
        userContinuedSpeakingAfterWake = false
        smartGreetingJob?.cancel()

        // 1. Sleek descending futuristic standby sound effect if requested
        if (playSound) {
            JarvisSoundEffects.playStandbySound()
        }

        // 2. Stop playback only — KEEP RECORDING STEADY (ZERO MIC BLINKING)
        audioEngine?.stopPlayback()
        audioEngine?.clearPlaybackQueue()
        audioEngine?.startRecording() // Guarantees hardware mic remains continuously active without cycling
        standbyAudioBuffer.clear()
        preConnectionAudioBuffer.clear()
        synchronized(wakePreRollBuffer) { wakePreRollBuffer.clear() }
        resetTurnState()
        try {
            voskRecognizer?.reset()
        } catch (_: Exception) {}

        // 3. Hot standby: Suspend microphone traffic to Gemini Live without tearing down WebSocket session.
        // This ensures 0 tokens used in background while allowing instant 0ms resumption upon wake word!
        try {
            geminiLive?.setAudioTransportPaused(true)
        } catch (e: Exception) {
            Log.w("JarvisVoiceService", "Error pausing geminiLive audio transport: ${e.message}")
        }

        // 4. Finish standby bookkeeping
        standbyActivatedTime = System.currentTimeMillis()
        idleCountdownJob?.cancel()
        autoSleepJob?.cancel()
        activeFollowUpJob?.cancel()

        // 5. Update notification to show standby & notify UI
        updateNotificationState(ServiceNotificationState.STANDBY)
        dispatchToListeners { it.onStandbyStateChanged(true) }

        // 6. Pre-warm in-memory recognizer
        prepareVoskRecognizer()

        Log.d("JarvisVoiceService", "SLEEPING: Standby mode active. Continuous hardware mic active. Zero Gemini tokens used.")
    }

    private fun prepareVoskRecognizer(): Recognizer? {
        val existing = voskRecognizer
        if (existing != null) return existing
        val model = VoskModelManager.model ?: return null
        return synchronized(this) {
            if (voskRecognizer != null) return@synchronized voskRecognizer
            try {
                // Initialize full acoustic recognizer without artificial grammar restrictions.
                // This decodes natural conversational English accurately and prevents ambient noise
                // from being forced into a false positive wake word path.
                val rec = Recognizer(model, 16000.0f)
                voskRecognizer = rec
                Log.i("JarvisVoiceService", "Direct Vosk continuous Recognizer initialized with full acoustic vocabulary")
                rec
            } catch (e: Exception) {
                Log.e("JarvisVoiceService", "Failed to create Vosk Recognizer: ${e.message}", e)
                null
            }
        }
    }

    private fun feedStandbyAudioChunk(chunk: ByteArray) {
        if (!_isStandby.value) return
        if (System.currentTimeMillis() - standbyActivatedTime < STANDBY_COOLDOWN_MS) return

        // Maintain rolling pre-roll buffer so words spoken during or immediately after the wake word are never dropped
        while (wakePreRollBuffer.size >= WAKE_PRE_ROLL_MAX_CHUNKS) {
            wakePreRollBuffer.poll()
        }
        wakePreRollBuffer.offer(chunk)

        val rec = prepareVoskRecognizer() ?: return
        try {
            if (rec.acceptWaveForm(chunk, chunk.size)) {
                checkVoskHypothesis(rec.result)
            } else {
                // Check partial hypothesis so detection is instant even in normal ambient noise
                // where background audio (fan, TV, room noise) prevents silence endpointing.
                checkVoskPartialHypothesis(rec.partialResult)
            }
        } catch (e: Exception) {
            Log.e("JarvisVoiceService", "Error in feedStandbyAudioChunk: ${e.message}")
        }
    }

    private fun checkVoskHypothesis(hypothesisJson: String?) {
        if (hypothesisJson.isNullOrBlank() || !_isStandby.value) return
        if (System.currentTimeMillis() - standbyActivatedTime < STANDBY_COOLDOWN_MS) return

        try {
            val json = JSONObject(hypothesisJson)
            val text = json.optString("text", "")
            val clean = text.trim().lowercase(Locale.ROOT)
            if (clean.isEmpty() || clean == "[unk]") return

            Log.i("JarvisVoiceService", "Vosk standby final hypothesis: '$clean'")
            if (containsStandbyWakeWord(clean)) {
                Log.i("JarvisVoiceService", "Vosk confirmed wake word (final): '$clean'")
                try { voskRecognizer?.reset() } catch (_: Exception) {}
                onWakeWordDetected()
            }
        } catch (e: Exception) {
            Log.e("JarvisVoiceService", "Error in checkVoskHypothesis: ${e.message}")
        }
    }

    private fun checkVoskPartialHypothesis(partialJson: String?) {
        if (partialJson.isNullOrBlank() || !_isStandby.value) return
        if (System.currentTimeMillis() - standbyActivatedTime < STANDBY_COOLDOWN_MS) return

        try {
            val json = JSONObject(partialJson)
            val partial = json.optString("partial", "")
            val clean = partial.trim().lowercase(Locale.ROOT)
            if (clean.isEmpty() || clean == "[unk]") return

            if (containsStandbyWakeWord(clean)) {
                Log.i("JarvisVoiceService", "Vosk confirmed wake word (partial): '$clean'")
                try { voskRecognizer?.reset() } catch (_: Exception) {}
                onWakeWordDetected()
            }
        } catch (e: Exception) {
            Log.e("JarvisVoiceService", "Error in checkVoskPartialHypothesis: ${e.message}")
        }
    }

    private val STANDBY_WAKE_REGEX = Regex("""\b(hey|hi|hello|ok|okay|wake\s+up)?\s*(jarvis|javis|jervis|jarves|jarviz)\b""")

    private fun containsStandbyWakeWord(phrase: String): Boolean {
        val clean = phrase.lowercase(Locale.ROOT).trim()
        if (clean.isEmpty()) return false

        // Fast regex match for standard combinations and phonetics
        if (STANDBY_WAKE_REGEX.containsMatchIn(clean)) {
            return true
        }

        // Token match for standalone wake tokens
        val validWakeTokens = setOf(
            "jarvis", "javis", "jervis", "jarves", "jarviz", "zarvis", "charvis"
        )
        val tokens = clean.split("\\s+".toRegex())
        return tokens.any { validWakeTokens.contains(it) }
    }

    private fun exitStandbyListening() {
        _isStandby.value = false
        try {
            voskRecognizer?.reset()
        } catch (_: Exception) {}
    }

    private fun onWakeWordDetected() {
        if (!_isStandby.value && conversationState == ConversationState.ACTIVE) return
        Log.i("JarvisVoiceService", "WAKE WORD DETECTED! Playing wake sound and activating Live session...")

        // 1. Brief haptic feedback
        vibrateBriefly()

        // 2. FIRST: Play futuristic ascending wake sound
        JarvisSoundEffects.playWakeSound()

        // 3. Stop offline standby listener
        exitStandbyListening()

        // 4. Reset turn state
        resetTurnState()
        currentTurnHasWakeWord = true
        userContinuedSpeakingAfterWake = false

        // 5. Reconnect/Unpause Gemini Live & enter ACTIVE conversation
        enterActiveState(fromWakeWord = true)
        updateNotificationState(ServiceNotificationState.LISTENING)

        // 6. Immediately flush pre-roll audio so Gemini hears commands spoken right with the wake word
        val preRollChunks = mutableListOf<ByteArray>()
        while (!wakePreRollBuffer.isEmpty()) {
            val chunk = wakePreRollBuffer.poll() ?: break
            preRollChunks.add(chunk)
        }
        if (preRollChunks.isNotEmpty()) {
            toolScope.launch {
                for (chunk in preRollChunks) {
                    if (geminiLive?.isConnected() == true) {
                        geminiLive?.sendAudioChunk(chunk)
                    } else {
                        while (preConnectionAudioBuffer.size >= 60) {
                            preConnectionAudioBuffer.poll()
                        }
                        preConnectionAudioBuffer.offer(chunk)
                    }
                }
            }
        }

        // 7. If woke up in background, schedule auto-standby window to listen for user command
        if (!isAppInForeground) {
            scheduleBackgroundAutoStandby(BACKGROUND_AUTO_STANDBY_MS)
        }

        // 8. Smart wake greeting: checks if user is already speaking a command.
        // Suppresses canned greeting if the user spoke their command immediately (e.g. "Hey Jarvis open Chrome")
        scheduleSmartWakeGreeting()
    }

    private fun scheduleSmartWakeGreeting() {
        smartGreetingJob?.cancel()
        smartGreetingJob = toolScope.launch {
            // Wait 1200ms to see if user is speaking a command after the wake word
            delay(1200L)
            if (userContinuedSpeakingAfterWake || currentTurnInputText.isNotEmpty()) {
                Log.i("JarvisVoiceService", "User spoke a direct command with/after wake word — suppressing canned greeting.")
                return@launch
            }
            if (conversationState != ConversationState.ACTIVE || isInBackgroundStandby()) {
                return@launch
            }
            // User paused after wake word ("Hey Jarvis... [silence]"). Greet them!
            triggerWakeGreeting()
        }
    }

    private fun vibrateBriefly() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    /**
     * Transition to ACTIVE state.
     */
    fun enterActiveState(fromWakeWord: Boolean = false) {
        val wasState = conversationState
        val wasStandby = _isStandby.value
        conversationState = ConversationState.ACTIVE
        _isStandby.value = false
        lastUserSpeechTimeMs = System.currentTimeMillis()
        isInFollowUpWindow = false
        idleCountdownJob?.cancel()
        autoSleepJob?.cancel()

        exitStandbyListening()
        geminiLive?.setAudioTransportPaused(false)
        audioEngine?.setStreamingPaused(false)
        audioEngine?.setExternalSpeaking(false)

        // If waking from standby or after long background idle, refresh hardware AudioRecord
        // to purge any drift or routing glitches caused by background media (YouTube/Reels).
        if (wasStandby || wasState == ConversationState.SLEEPING) {
            audioEngine?.refreshAudioRecordOnWake()
        }

        // Reconnect Gemini if needed (e.g. waking from standby or idle period)
        if (geminiLive?.isConnected() != true && isSessionStarted) {
            Log.d("JarvisVoiceService", "ACTIVE: Reconnecting Gemini Live WebSocket...")
            preConnectionAudioBuffer.clear()
            audioEngine?.startRecording()
            audioEngine?.startPlayback()
            geminiLive?.connect()
        } else {
            audioEngine?.startRecording()
            audioEngine?.startPlayback()
        }

        // Start the idle timer & connection health check
        startIdleTimer()
        startConnectionHealthCheck()
        updateNotificationState(ServiceNotificationState.LISTENING)
        dispatchToListeners { it.onStandbyStateChanged(false) }

        Log.d("JarvisVoiceService", "ACTIVE: Full conversation mode enabled (was $wasState, standby=$wasStandby, wakeWord=$fromWakeWord)")
    }

    /**
     * Start the 2-minute idle timer. If no user speech, transitions to SLEEPING.
     */
    private fun startIdleTimer() {
        autoSleepJob?.cancel()
        autoSleepJob = toolScope.launch {
            while (true) {
                delay(15_000L) // Check every 15s
                if (isAppInForeground) continue // Do not auto-sleep while user is actively viewing the app!

                val silenceDuration = System.currentTimeMillis() - lastUserSpeechTimeMs
                if ((conversationState == ConversationState.ACTIVE || !_isStandby.value) && silenceDuration >= IDLE_TO_SLEEP_MS) {
                    if (screenCaptureEngine != null || cameraVisionEngine?.isCameraStreaming() == true) {
                        // Keep awake during active screen sharing or camera vision
                        continue
                    }
                    Log.d("JarvisVoiceService", "Idle timer: ${silenceDuration / 1000}s of silence in background → transitioning to SLEEPING")
                    Handler(Looper.getMainLooper()).post { enterSleepingState(sayGoodbye = false) }
                    return@launch
                }
            }
        }
    }

    /**
     * Touch: reset idle timer because the user interacted.
     */
    private fun touchUserActivity() {
        lastUserSpeechTimeMs = System.currentTimeMillis()
        if (conversationState != ConversationState.ACTIVE) {
            conversationState = ConversationState.ACTIVE
        }
        if (!isAppInForeground) {
            scheduleBackgroundAutoStandby(BACKGROUND_AUTO_STANDBY_MS)
        }
    }

    /**
     * Start a 45-second follow-up window (e.g. after Jarvis finishes speaking or executes a tool).
     * During this window, Jarvis continues listening and responds naturally without requiring wake word.
     */
    private fun startFollowUpWindow() {
        isInFollowUpWindow = true
        activeFollowUpJob?.cancel()
        activeFollowUpJob = toolScope.launch {
            delay(FOLLOW_UP_WINDOW_MS)
            isInFollowUpWindow = false
        }
        if (!isAppInForeground) {
            scheduleBackgroundAutoStandby(BACKGROUND_AUTO_STANDBY_MS)
        }
    }

    private var backgroundAutoStandbyJob: Job? = null

    private fun scheduleBackgroundAutoStandby(delayMs: Long = BACKGROUND_AUTO_STANDBY_MS) {
        if (isAppInForeground) return
        backgroundAutoStandbyJob?.cancel()
        backgroundAutoStandbyJob = toolScope.launch {
            delay(delayMs)
            if (!isAppInForeground && conversationState == ConversationState.ACTIVE && !_isStandby.value) {
                val now = System.currentTimeMillis()
                val isRecentUserSpeech = (now - lastUserSpeechTimeMs) < 15_000L
                val isRecentTurn = isInFollowUpWindow
                val isSpeaking = audioEngine?.isCurrentlySpeaking() == true
                if (isRecentUserSpeech || isRecentTurn || isSpeaking) {
                    scheduleBackgroundAutoStandby(30_000L)
                    return@launch
                }
                Log.d("JarvisVoiceService", "Background silence timeout (${delayMs}ms) elapsed -> returning to standby")
                enterStandby(sayGoodbye = false, playSound = false)
            }
        }
    }

    private fun cancelBackgroundAutoStandby() {
        backgroundAutoStandbyJob?.cancel()
        backgroundAutoStandbyJob = null
    }

    private var lastAppOpenGreetingTimeMs = 0L
    private val GREETING_COOLDOWN_MS = 45_000L
    @Volatile private var pendingAppOpenGreeting = false
    @Volatile private var pendingWakeGreeting = false

    fun triggerWakeGreeting() {
        pendingWakeGreeting = false
        toolScope.launch {
            // Wait 400ms so the futuristic ascending wake sound finishes playing
            delay(400L)
            val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            val userName = prefs.getString("user_name", "Sir")?.ifBlank { "Sir" } ?: "Sir"

            val greetingPrompt = "Please respond out loud right now. Say: 'Hi $userName, how can I help you?' Keep it to one short sentence. Do not call any tools."
            Log.i("JarvisVoiceService", "Triggering wake greeting for $userName: $greetingPrompt")

            if (geminiLive?.isConnected() == true) {
                geminiLive?.sendText(greetingPrompt)
            } else {
                pendingWakeGreeting = true
            }
        }
    }

    fun triggerAppOpenGreeting() {
        if (!isAppInForeground || isInBackgroundStandby()) return
        lastAppOpenGreetingTimeMs = System.currentTimeMillis()
        pendingAppOpenGreeting = false

        toolScope.launch {
            delay(400L) // Brief delay to let WebSocket session finish initialization
            if (!isAppInForeground || isInBackgroundStandby()) return@launch

            val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            val userName = prefs.getString("user_name", "Sir")?.ifBlank { "Sir" } ?: "Sir"

            val greetingPrompt = "Please greet the user out loud right now. Say: 'Hi $userName, how can I help you?' Keep it to one short sentence. Do not call any tools."
            Log.i("JarvisVoiceService", "Triggering app-open greeting for $userName: $greetingPrompt")
            geminiLive?.sendText(greetingPrompt)
        }
    }

    fun setAppForeground(isForeground: Boolean) {
        val wasForeground = isAppInForeground
        if (wasForeground == isForeground) return
        isAppInForeground = isForeground
        Log.d("JarvisVoiceService", "setAppForeground: $wasForeground -> $isForeground (isStandby=${_isStandby.value})")
        if (isForeground) {
            cancelBackgroundAutoStandby()
            touchUserActivity()
            if (_isStandby.value || conversationState != ConversationState.ACTIVE) {
                enterActiveState(fromWakeWord = false)
            }
            // Trigger spoken greeting if cooldown elapsed
            val now = System.currentTimeMillis()
            if (now - lastAppOpenGreetingTimeMs > GREETING_COOLDOWN_MS) {
                if (geminiLive?.isConnected() == true) {
                    triggerAppOpenGreeting()
                } else {
                    pendingAppOpenGreeting = true
                }
            }
        } else {
            updateNotificationState(ServiceNotificationState.STANDBY)
            if (screenCaptureEngine == null && cameraVisionEngine?.isCameraStreaming() != true) {
                scheduleBackgroundAutoStandby(BACKGROUND_AUTO_STANDBY_MS)
            }
        }
    }

    private fun isVoicePlaybackAllowed(): Boolean {
        if (isInFollowUpWindow) return true
        if (isInBackgroundStandby()) return false
        return true
    }

    /** True once background standby is active. */
    private fun isInBackgroundStandby(): Boolean =
        !isInFollowUpWindow && (_isStandby.value || conversationState == ConversationState.SLEEPING)

    private val currentTurnInputText = StringBuilder()
    private val currentTurnOutputText = StringBuilder()
    private var currentTurnHasWakeWord = false
    private var interruptSentThisTurn = false
    @Volatile private var wakeSoundPlayedThisTurn = false

    private var cachedApiKey = ""
    private var cachedModelString = "models/gemini-3.1-flash-live-preview"
    private var cachedSystemPrompt = ""
    private var cachedVoiceName = "Aoede"

    private val SHUTDOWN_PHRASES = listOf(
        "turn off yourself", "shut down", "shutdown", "power off",
        "close yourself", "exit jarvis", "stop jarvis", "turn off",
        "go offline", "go off", "offline ho jao", "offline jao", "offline ho ja",
        "jarvis shutdown", "jarvis shut down", "jarvis turn off", "jarvis power off",
        "jarvis bandh ho jao", "khud ko band karo", "band ho jao",
        "jarvis off ho jao", "jarvis band ho ja", "band hoja", "band ho ja",
        "khud ko band kar do", "band kar do", "band karo", "stop listening"
    )

    private val BACKGROUND_PHRASES = listOf(
        "jarvis go to the background", "go to the background", "go to background",
        "jarvis go to background", "go into the background", "jarvis go into the background",
        "background mode", "minimize yourself", "minimize jarvis", "run in background",
        "send to background", "background me jao", "peeche chala ja", "background mode me jao",
        "peeche jao", "background me chalay jao", "background jao", "go background",
        "background mein jao", "background me ja", "chup ho jao", "chup raho",
        "go home", "go to home", "go to home screen", "go to sleep", "sleep jarvis",
        "enter standby", "standby mode", "go to standby", "take a break", "so jao", "chale jao",
        "bye", "goodbye", "bye jarvis", "goodbye jarvis", "see you later", "see ya", "talk to you later",
        "बैकग्राउंड में जाओ", "बैकग्राउंड जाओ", "पीछे जाओ", "चुप हो जाओ", "चुप रहो"
    )

    private val SHOW_YOURSELF_PHRASES = listOf(
        "show yourself", "show jarvis", "open jarvis", "bring jarvis",
        "come to front", "come back", "jarvis saamne aao", "saamne aao",
        "show me yourself", "appear", "maximize yourself", "open app jarvis",
        "open the app", "open jarvis app", "bring jarvis to front"
    )

    private fun textHasWakeWord(text: String): Boolean {
        val cleanText = text.lowercase().trim()
        if (cleanText.isEmpty()) return false
        if (WAKE_PHRASES.any { cleanText.contains(it) }) return true
        val words = cleanText.split("\\s+".toRegex())
        val singleWordMatches = setOf(
            "jarvis", "jarviss", "jaarvis", "jarvish", "javis",
            "jarwis", "charvis", "chavis", "jervis", "zarvis", "dharvis", "arvis",
            "jarves", "jarviz", "garvis", "charves", "jarvice", "javiz",
            "जार्विस", "जारविस", "जरविस", "जरवीस", "जार्विश", "जार्विज़", "जारविश"
        )
        return words.any { w -> singleWordMatches.contains(w) }
    }

    private fun isShutdownCommand(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F\\s]"), " ").trim()
        if (normalized.isEmpty()) return false
        return SHUTDOWN_PHRASES.any { normalized.contains(it) }
    }

    private fun isBackgroundCommand(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F\\s]"), " ").trim()
        if (normalized.isEmpty()) return false
        if (normalized == "go" || normalized == "jarvis go" || normalized == "just go" || normalized == "go away" || normalized == "sleep" || normalized == "bye" || normalized == "goodbye") return true
        if (BACKGROUND_PHRASES.any { normalized.contains(it) }) return true
        val bgRegex = Regex("""\b(go|send|run|move)\s+(in|into|to)?\s*(the)?\s*background\b""")
        return bgRegex.containsMatchIn(normalized)
    }

    private fun isShowYourselfCommand(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), " ").trim()
        if (normalized.isEmpty()) return false
        return SHOW_YOURSELF_PHRASES.any { normalized.contains(it) }
    }

    private val standbyAudioBuffer = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val preConnectionAudioBuffer = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var isTurnInterrupted = false

    private fun flushStandbyAudio() {
        while (!standbyAudioBuffer.isEmpty()) {
            val b = standbyAudioBuffer.poll() ?: break
            audioEngine?.queueAudio(b)
        }
    }

    /** Resets per-turn bookkeeping, ready for the next utterance. */
    private fun resetTurnState() {
        currentTurnInputText.clear()
        currentTurnOutputText.clear()
        standbyAudioBuffer.clear()
        preConnectionAudioBuffer.clear()
        interruptSentThisTurn = false
        currentTurnHasWakeWord = false
        isTurnInterrupted = false
        wakeSoundPlayedThisTurn = false
        audioEngine?.setExternalSpeaking(false)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Extract/load the local wake model while the app is starting, not after the user has
        // already asked JARVIS to go to the background.
        VoskModelManager.init(applicationContext, toolScope)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i("JarvisVoiceService", "App removed from recents — preserving JarvisVoiceService in background!")
        acquireWakeLock()
        ensureMicrophoneForegroundService()
        ensureSessionActive()

        try {
            val restartIntent = Intent(applicationContext, JarvisVoiceService::class.java)
            val pendingIntent = PendingIntent.getService(
                applicationContext, 1001, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1500L,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.w("JarvisVoiceService", "AlarmManager restart schedule error: ${e.message}")
        }
    }

    fun ensureSessionActive() {
        if (isSessionStarted && geminiLive?.isConnected() == true) return
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val apiKey = cachedApiKey.ifBlank {
            prefs.getString("cached_api_key", "") ?: ""
        }.ifBlank {
            com.jarvis.assistant.util.EnvLoader.getApiKey(this)
        }
        val model = cachedModelString.ifBlank {
            prefs.getString("cached_model", "models/gemini-3.1-flash-live-preview") ?: "models/gemini-3.1-flash-live-preview"
        }
        var voice = prefs.getString("gemini_voice", "")?.ifBlank {
            prefs.getString("cached_voice", "")
        }?.ifBlank {
            cachedVoiceName
        }?.ifBlank { "Aoede" } ?: "Aoede"
        if (voice.equals("Puck", ignoreCase = true) || voice.isBlank()) {
            voice = "Aoede"
            try {
                prefs.edit()
                    .putString("gemini_voice", "Aoede")
                    .putString("cached_voice", "Aoede")
                    .putBoolean("is_female_voice", true)
                    .apply()
            } catch (_: Exception) {}
        }
        val prompt = cachedSystemPrompt.ifBlank {
            prefs.getString("cached_prompt", "") ?: ""
        }.ifBlank {
            val userName = prefs.getString("user_name", "Boss") ?: "Boss"
            val personality = prefs.getString("personality_mode", "best_friend") ?: "best_friend"
            val isFemale = prefs.getBoolean("is_female_voice", true)
            com.jarvis.assistant.util.PromptBuilder.buildSystemPrompt(userName, personality, isFemale, voice)
        }
        if (apiKey.isNotBlank()) {
            startSession(apiKey, model, prompt, voice)
        }
    }

    fun performShutdownIntent() {
        Log.d("JarvisVoiceService", "Executing multilingual ShutdownIntent...")
        toolScope.launch {
            try {
                // Give Gemini a moment to finish speaking its warm goodbye in natural voice
                delay(2200L)
            } catch (_: Exception) {}
            try {
                geminiLive?.disconnect(manual = true)
                audioEngine?.stopRecording()
                audioEngine?.stopPlayback()
                audioEngine?.release()
                audioEngine = null
                try { voskRecognizer?.close() } catch (_: Exception) {}
                voskRecognizer = null
                com.jarvis.assistant.service.FloatingOrbService.stopService(this@JarvisVoiceService)
                isSessionStarted = false
                conversationState = ConversationState.SLEEPING
                currentTurnHasWakeWord = false
                dispatchToListeners { it.onShutdownRequested() }
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.e("JarvisVoiceService", "Error during performShutdownIntent", e)
            }
        }
    }

    fun performBackgroundModeIntent() {
        Log.d("JarvisVoiceService", "Executing BackgroundModeIntent — transitioning JARVIS to background standby mode...")

        // Unblock any external speaking flag so mic streams freely
        audioEngine?.setExternalSpeaking(false)

        // Enter standby mode (plays sleek descending sci-fi chime & starts offline wake word listening)
        // Does NOT close any open apps or navigate away from the user's current screen.
        enterStandby(sayGoodbye = false, playSound = true)
    }

    fun performShowYourselfIntent() {
        Log.d("JarvisVoiceService", "Bringing JARVIS to front...")
        isAppInForeground = true
        enterActiveState(fromWakeWord = false)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(this, intent)
        val enableOverlay = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE).getBoolean("enable_floating_overlay", false)
        if (enableOverlay) {
            com.jarvis.assistant.service.FloatingOrbService.startService(this)
        }
    }

    /**
     * Sends message through Gemini's natural AI voice. Robotic Android TTS is completely removed.
     */
    fun speakAloud(text: String, onDone: (() -> Unit)? = null) {
        if (text.isNotBlank()) {
            geminiLive?.sendText(text)
        }
        onDone?.invoke()
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::VoiceServiceWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours max
            }
            Log.d("JarvisVoiceService", "Acquired partial WakeLock for background voice listening")
        } catch (e: Exception) {
            Log.e("JarvisVoiceService", "Failed to acquire wakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("JarvisVoiceService", "Released WakeLock")
            }
        } catch (e: Exception) {
            Log.e("JarvisVoiceService", "Failed to release wakeLock: ${e.message}")
        }
        wakeLock = null
    }

    fun ensureMicrophoneForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (screenCaptureEngine != null) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                if (cameraVisionEngine?.isCameraStreaming() == true) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
                Log.d("JarvisVoiceService", "startForeground elevated to MICROPHONE | SPECIAL_USE (types=$serviceType)")
            } catch (e: Exception) {
                Log.w("JarvisVoiceService", "Could not elevate to MICROPHONE FGS type (likely background): ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } catch (e2: Exception) {
                    Log.e("JarvisVoiceService", "startForeground SPECIAL_USE failed: ${e2.message}")
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (screenCaptureEngine != null) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                if (cameraVisionEngine?.isCameraStreaming() == true) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } catch (e: Exception) {
                Log.w("JarvisVoiceService", "startForeground MICROPHONE failed: ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e2: Exception) {
                    android.util.Log.e("JarvisVoiceService", "startForeground plain failed", e2)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()
        ensureMicrophoneForegroundService()
        ensureSessionActive()
        val action = intent?.action
        if (action == "com.jarvis.assistant.ACTION_TEST_CHROME_CLOSE") {
            kotlin.concurrent.thread {
                JarvisAccessibilityService.instance?.closeChromeAllTabs()
            }
        }
        return START_STICKY
    }

    /** Restarts the session with updated settings (personality, voice, API key, etc.). */
    fun restartSession(
        apiKey: String,
        modelString: String,
        systemPrompt: String,
        voiceName: String
    ) {
        if (isSessionStarted) {
            resetTurnState()
            geminiLive?.clearSessionHandle()
            geminiLive?.disconnect()
            audioEngine?.release()
            geminiLive = null
            audioEngine = null
            isSessionStarted = false
        }
        startSession(apiKey, modelString, systemPrompt, voiceName)
    }

    /** Starts the mic/WebSocket session. Safe to call repeatedly; a no-op once already running. */
    fun startSession(
        apiKey: String,
        modelString: String,
        systemPrompt: String,
        voiceName: String
    ) {
        val effectiveApiKey = apiKey.ifBlank { cachedApiKey }
        val effectiveModel = modelString.ifBlank { cachedModelString }
        val effectivePrompt = systemPrompt.ifBlank { cachedSystemPrompt }
        val effectiveVoice = voiceName.ifBlank { cachedVoiceName }

        if (effectiveApiKey.isNotBlank()) {
            cachedApiKey = effectiveApiKey
            try {
                getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE).edit()
                    .putString("cached_api_key", effectiveApiKey)
                    .putString("cached_model", effectiveModel)
                    .putString("cached_voice", effectiveVoice)
                    .putString("cached_prompt", effectivePrompt)
                    .apply()
            } catch (_: Exception) {}
        }
        if (effectiveModel.isNotBlank()) cachedModelString = effectiveModel
        if (effectivePrompt.isNotBlank()) cachedSystemPrompt = effectivePrompt
        if (effectiveVoice.isNotBlank()) cachedVoiceName = effectiveVoice

        Log.d("JarvisVoiceService", "startSession called: key len=${effectiveApiKey.length}, model=$effectiveModel, isSessionStarted=$isSessionStarted, hasGeminiLive=${geminiLive != null}")

        if (isSessionStarted && geminiLive != null) return
        isSessionStarted = true

        val now = System.currentTimeMillis()
        if (isAppInForeground && (now - lastAppOpenGreetingTimeMs > GREETING_COOLDOWN_MS)) {
            pendingAppOpenGreeting = true
            lastAppOpenGreetingTimeMs = now
        }

        acquireWakeLock()
        ensureMicrophoneForegroundService()

        setVoiceConfig(effectiveVoice)
        resetTurnState()

        if (audioEngine == null) {
            audioEngine = AudioEngine(this).apply {
                onAudioChunkCaptured = { chunk ->
                    if (!isUserMuted) {
                        if (!isInBackgroundStandby()) {
                            val rms = audioEngine?.calculateRms(chunk) ?: 0f
                            if (rms > 0.04f) {
                                touchUserActivity()
                                userContinuedSpeakingAfterWake = true
                            }
                            if (geminiLive?.isConnected() == true) {
                                geminiLive?.sendAudioChunk(chunk)
                            } else {
                                while (preConnectionAudioBuffer.size >= 60) {
                                    preConnectionAudioBuffer.poll()
                                }
                                preConnectionAudioBuffer.offer(chunk)
                            }
                        } else {
                            // Direct continuous standby wake listening — ZERO mic blinking!
                            feedStandbyAudioChunk(chunk)
                        }
                    }
                }
                onAmplitudeChanged = { rms -> dispatchToListeners { it.onAmplitudeChanged(rms) } }
                onSpeakingStarted = {
                    cancelBackgroundAutoStandby()
                    if (!_isStandby.value) {
                        updateNotificationState(ServiceNotificationState.SPEAKING)
                    }
                    dispatchToListeners { it.onSpeakingStarted() }
                }
                onSpeakingStopped = {
                    audioEngine?.setStreamingPaused(false)
                    geminiLive?.setAudioTransportPaused(false)
                    if (!_isStandby.value) {
                        updateNotificationState(ServiceNotificationState.IDLE)
                    }
                    startFollowUpWindow()
                    dispatchToListeners { it.onSpeakingStopped() }
                }
                onInterruptTriggered = { interrupt() }
                setMuted(isUserMuted)
            }
        }

        if (effectiveApiKey.isNotBlank()) {
            geminiLive?.disconnect()
            geminiLive = GeminiLiveClient(effectiveApiKey, effectiveModel, effectivePrompt, effectiveVoice).apply {
                onConnected = { dispatchToListeners { it.onConnected() } }
                onSetupComplete = {
                    if (!isInBackgroundStandby()) {
                        audioEngine?.startRecording()
                        audioEngine?.startPlayback()
                        while (!preConnectionAudioBuffer.isEmpty()) {
                            val chunk = preConnectionAudioBuffer.poll() ?: break
                            geminiLive?.sendAudioChunk(chunk)
                        }
                        updateNotificationState(ServiceNotificationState.IDLE)
                        if (pendingAppOpenGreeting && isAppInForeground) {
                            pendingAppOpenGreeting = false
                            triggerAppOpenGreeting()
                        }
                        if (pendingWakeGreeting) {
                            pendingWakeGreeting = false
                            triggerWakeGreeting()
                        }
                    } else {
                        audioEngine?.startRecording() // KEEP RECORDING ACTIVE FOR VOSK
                        audioEngine?.stopPlayback()
                        preConnectionAudioBuffer.clear()
                    }
                    dispatchToListeners { it.onSetupComplete() }
                }
                onInterrupted = {
                    if (audioEngine?.isCurrentlySpeaking() == true) {
                        isTurnInterrupted = true
                        standbyAudioBuffer.clear()
                        audioEngine?.clearPlaybackQueue()
                        if (!_isStandby.value) {
                            updateNotificationState(ServiceNotificationState.IDLE)
                        }
                        dispatchToListeners { it.onSpeakingStopped() }
                    }
                }
                onAudioReceived = { bytes ->
                    if (!isUserMuted && !isInBackgroundStandby()) {
                        isTurnInterrupted = false
                        if (isVoicePlaybackAllowed()) {
                            flushStandbyAudio()
                            audioEngine?.queueAudio(bytes)
                        } else if (isAppInForeground) {
                            while (standbyAudioBuffer.size >= 50) {
                                standbyAudioBuffer.poll()
                            }
                            standbyAudioBuffer.offer(bytes)
                        }
                    }
                }
                onInputTranscript = { text ->
                    if (!isUserMuted && !isInBackgroundStandby()) {
                        cancelBackgroundAutoStandby()
                        isTurnInterrupted = false
                        touchUserActivity()
                        if (!_isStandby.value) {
                            updateNotificationState(ServiceNotificationState.LISTENING)
                        }
                        currentTurnInputText.append(text)
                        val fullInput = currentTurnInputText.toString()

                        if (isShutdownCommand(fullInput)) {
                            performBackgroundModeIntent()
                        } else if (isShowYourselfCommand(fullInput)) {
                            performShowYourselfIntent()
                        } else if (isBackgroundCommand(fullInput)) {
                            performBackgroundModeIntent()
                        } else {
                            if (textHasWakeWord(fullInput)) {
                                currentTurnHasWakeWord = true
                                if (conversationState == ConversationState.SLEEPING || _isStandby.value) {
                                    enterActiveState(fromWakeWord = true)
                                } else {
                                    touchUserActivity()
                                }
                                flushStandbyAudio()
                            }
                            if (isVoicePlaybackAllowed()) {
                                touchUserActivity()
                                dispatchToListeners { it.onInputTranscript(text) }
                            }
                        }
                    }
                }
                onOutputTranscript = { text ->
                    if (!isUserMuted && !isInBackgroundStandby()) {
                        currentTurnOutputText.append(text)
                        if (!isVoicePlaybackAllowed()) {
                            val fullInput = currentTurnInputText.toString()
                            // STRICT: ONLY activate if wake word was actually spoken by the user.
                            // NEVER activate on ambient speech, singing, or lyrics.
                            if (textHasWakeWord(fullInput)) {
                                currentTurnHasWakeWord = true
                                if (conversationState == ConversationState.SLEEPING) {
                                    enterActiveState(fromWakeWord = true)
                                } else {
                                    touchUserActivity()
                                }
                                flushStandbyAudio()
                            }
                        }
                        if (isVoicePlaybackAllowed()) {
                            dispatchToListeners { it.onOutputTranscript(text) }
                        }
                    }
                }
                onTurnComplete = {
                    if (isUserMuted || isInBackgroundStandby()) {
                        resetTurnState()
                    } else {
                        val fullInput = currentTurnInputText.toString()
                        val hasWakeWord = isVoicePlaybackAllowed() || textHasWakeWord(fullInput)
                        if (!isVoicePlaybackAllowed() && textHasWakeWord(fullInput)) {
                            currentTurnHasWakeWord = true
                            if (conversationState == ConversationState.SLEEPING) {
                                enterActiveState(fromWakeWord = true)
                            } else {
                                touchUserActivity()
                            }
                            flushStandbyAudio()
                        }
                        val userMsg = fullInput.trim()
                        val jarvisMsg = currentTurnOutputText.toString().trim()

                        if (hasWakeWord && (userMsg.isNotEmpty() || jarvisMsg.isNotEmpty())) {
                            val toSave = mutableListOf<com.jarvis.assistant.model.ChatMessage>()
                            if (userMsg.isNotEmpty()) toSave.add(com.jarvis.assistant.model.ChatMessage(userMsg, isUser = true))
                            if (jarvisMsg.isNotEmpty()) toSave.add(com.jarvis.assistant.model.ChatMessage(jarvisMsg, isUser = false))
                            if (toSave.isNotEmpty()) {
                                com.jarvis.assistant.util.ChatHistoryManager.saveMessages(this@JarvisVoiceService, toSave)
                            }
                            dispatchToListeners { it.onTurnComplete() }
                            touchUserActivity()
                        } else if (isVoicePlaybackAllowed()) {
                            // Active conversation: never flush or drop playing speech
                            dispatchToListeners { it.onTurnComplete() }
                            touchUserActivity()
                        } else {
                            // Turn ignored ONLY in standby when wake word was absent
                            standbyAudioBuffer.clear()
                            audioEngine?.clearPlaybackQueue()
                            dispatchToListeners { it.onCommandIgnored() }
                        }
                        if (!_isStandby.value) {
                            updateNotificationState(ServiceNotificationState.IDLE)
                        }
                        startFollowUpWindow()
                        audioEngine?.setStreamingPaused(false)
                        resetTurnState()
                    }
                }
                onDisconnected = {
                    dispatchToListeners { it.onDisconnected() }
                    // Auto-recover: if session is supposed to be running and active, reconnect after a short delay
                    if (isSessionStarted && geminiLive != null && conversationState == ConversationState.ACTIVE && !_isStandby.value) {
                        toolScope.launch {
                            delay(2000L)
                            if (isSessionStarted && geminiLive?.isConnected() != true && conversationState == ConversationState.ACTIVE && !_isStandby.value) {
                                Log.w("JarvisVoiceService", "Auto-recovery: session dropped, reconnecting...")
                                geminiLive?.connect()
                            }
                        }
                    }
                }
                onError = { msg -> dispatchToListeners { it.onError(msg) } }
                onToolCall = { name, args, callId ->
                    if (isInBackgroundStandby()) {
                        Log.d("JarvisVoiceService", "Ignoring stale background tool call '$name'.")
                        geminiLive?.sendToolResponse(callId, name, JSONObject().put("status", "ignored_background_mode"))
                    } else if (!isUserMuted) {
                        val fullInput = currentTurnInputText.toString()
                        val isAllowed = isVoicePlaybackAllowed() || textHasWakeWord(fullInput)
                        if (isAllowed) {
                            currentTurnHasWakeWord = true
                            touchUserActivity()
                            if (conversationState == ConversationState.SLEEPING || _isStandby.value) {
                                enterActiveState(fromWakeWord = true)
                            }
                            if (!_isStandby.value) {
                                updateNotificationState(ServiceNotificationState.THINKING)
                            }
                            startFollowUpWindow()
                            dispatchToListeners { it.onToolCall(name, args, callId) }
                            toolScope.launch { handleToolCall(name, args, callId) }
                        } else {
                            Log.d("JarvisVoiceService", "Background tool call '$name' ignored without wake word.")
                            geminiLive?.sendToolResponse(callId, name, JSONObject().put("status", "ignored_background_mode"))
                        }
                    }
                }
            }
            geminiLive?.connect()
            startConnectionHealthCheck()
        } else {
            audioEngine?.startRecording()
            audioEngine?.startPlayback()
            dispatchToListeners { it.onConnected() }
            dispatchToListeners { it.onSetupComplete() }
        }
    }

    // ---------------------------------------------------------------
    // Tool execution — runs here (not in MainActivity) so voice commands
    // like "open YouTube" or "pause it" still work even when the Activity
    // is backgrounded, e.g. while the user is already inside another app.
    // ---------------------------------------------------------------

    private suspend fun handleToolCall(name: String, args: JSONObject, callId: String) {
        val result = JSONObject()
        try {
            when (name) {
                "open_app" -> {
                    val appName = args.optString("app_name", "")
                    val appNumber = if (args.has("app_number")) args.optInt("app_number", 0) else null
                    if (appName.isBlank()) {
                        result.put("success", false)
                        result.put("message", "App name cannot be empty.")
                    } else {
                        val res = AppLauncher.openAppResult(this, appName, if (appNumber != null && appNumber > 0) appNumber else null)
                        when (res) {
                            is AppLauncher.OpenAppResult.Success -> {
                                result.put("success", true)
                                result.put("opened_app", res.app.label)
                                appNumber?.let { num ->
                                    if (num > 0) {
                                        JarvisAccessibilityService.instance?.handleDualAppSelection(appName, num)
                                    }
                                }
                            }
                            is AppLauncher.OpenAppResult.MultipleFound -> {
                                result.put("success", false)
                                result.put("multiple_apps", true)
                                result.put("count", res.matches.size)
                                result.put("app_name", appName)
                                result.put(
                                    "message",
                                    "In your mobile there are ${res.matches.size} $appName apps. Ask the user in their language: \"In your mobile there are ${res.matches.size} $appName apps. Which one should I open, 1 or 2?\""
                                )
                            }
                            is AppLauncher.OpenAppResult.NotFound -> {
                                result.put("success", false)
                                result.put("message", "No app matching \"$appName\" was found.")
                            }
                            AppLauncher.OpenAppResult.Failure -> {
                                result.put("success", false)
                                result.put("message", "Failed to launch \"$appName\".")
                            }
                        }
                    }
                }
                "search_and_play_youtube" -> {
                    val query = args.optString("query", "")
                    val play = YouTubeController.searchAndPlay(this, query)
                    result.put("success", play.success)
                    play.title?.let { result.put("playing", it) }
                    play.message?.let { result.put("message", it) }
                }
                "search_youtube" -> {
                    val query = args.optString("query", "")
                    val searchRes = YouTubeController.searchYouTube(this, query)
                    result.put("success", searchRes.success)
                    searchRes.message?.let { result.put("message", it) }
                }
                "media_playback_control" -> {
                    val action = args.optString("action", "")
                    val ok = YouTubeController.sendMediaKey(this, action)
                    result.put("success", ok)
                    if (!ok) result.put("message", "Couldn't send the $action command — nothing seems to be playing.")
                }
                "youtube_accessibility_action" -> {
                    val action = args.optString("action", "")
                    val svc = JarvisAccessibilityService.instance
                    if (svc == null) {
                        result.put("success", false)
                        result.put("message", "Accessibility isn't enabled for JARVIS yet — ask the user to turn it on in Settings > Accessibility.")
                    } else {
                        val ok = when (action) {
                            "skip_ad" -> svc.skipAd()
                            "like" -> svc.likeVideo()
                            "subscribe" -> svc.subscribeChannel()
                            "open_channel" -> svc.openChannel()
                            "seek_forward" -> svc.seekForward()
                            "seek_backward" -> svc.seekBackward()
                            "fullscreen" -> svc.toggleFullscreen()
                            else -> false
                        }
                        result.put("success", ok)
                        if (!ok) result.put("message", "Couldn't find that control on screen right now.")
                    }
                }
                "call_contact" -> {
                    val contactName = args.optString("contact_name", "")
                        .ifBlank { args.optString("name", "") }
                        .ifBlank { args.optString("query", "") }
                        .ifBlank { args.optString("contact", "") }
                        .ifBlank { args.optString("number", "") }
                    when (val callResult = ContactCaller.callContact(this, contactName)) {
                        is ContactCaller.CallResult.Success -> {
                            speakAloud("Okay sir, calling ${callResult.contact.name}.")
                            monitorPhoneCallAndKeepQuiet()
                            result.put("success", true)
                            result.put("calling", callResult.contact.name)
                        }
                        is ContactCaller.CallResult.NoMatch -> {
                            result.put("success", false)
                            result.put("message", "No contact matching \"${callResult.query}\" was found.")
                        }
                        is ContactCaller.CallResult.MultipleMatches -> {
                            val names = callResult.matches.map { it.name }.distinct().joinToString(", ")
                            result.put("success", false)
                            result.put("message", "Found more than one match for \"${callResult.query}\": $names. Ask the user which one they meant.")
                        }
                        ContactCaller.CallResult.MissingPermission -> {
                            result.put("success", false)
                            result.put("message", "JARVIS doesn't have Contacts/Phone permission yet — ask the user to grant it in Settings.")
                        }
                        ContactCaller.CallResult.CallFailed -> {
                            result.put("success", false)
                            result.put("message", "Found the contact but couldn't place the call.")
                        }
                    }
                }
                "send_whatsapp_message" -> {
                    val recipientName = args.optString("recipient_name", "")
                        .ifBlank { args.optString("contact_name", "") }
                        .ifBlank { args.optString("name", "") }
                    val message = args.optString("message", "")
                        .ifBlank { args.optString("text", "") }
                    val appNumber = if (args.has("app_number")) args.optInt("app_number", 0).takeIf { it in 1..2 } else null
                    val confirmed = args.optBoolean("confirmed", false)

                    when (val res = com.jarvis.assistant.util.WhatsAppMessenger.sendMessage(this, recipientName, message, appNumber, confirmed)) {
                        is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Success -> {
                            result.put("success", true)
                            result.put("message", "Sending message to ${res.contactName} via ${res.appName}.")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.RequiresConfirmation -> {
                            result.put("success", false)
                            result.put("requires_confirmation", true)
                            result.put("contact_name", res.contactName)
                            result.put("message", "Found contact \"${res.contactName}\". Ask user: \"Is this ${res.contactName} contact to send a message?\" (or in Hindi: \"Kya main ${res.contactName} ko ye message bhej doon?\"). When user confirms yes, call send_whatsapp_message(recipient_name=\"${res.contactName}\", message=\"${res.message}\", confirmed=true).")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MultipleAppsFound -> {
                            result.put("success", false)
                            result.put("multiple_apps", true)
                            result.put("message", "In your mobile there are 2 WhatsApp apps. Ask user: \"In your mobile there are 2 WhatsApp apps. Which one should I use, 1 or 2?\"")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.ContactNotFound -> {
                            result.put("success", false)
                            result.put("message", "Could not find any contact named \"${res.name}\" in phone contacts.")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MultipleContactsFound -> {
                            val matchesList = res.matches.joinToString(", ")
                            result.put("success", false)
                            result.put("message", "Multiple contacts found for \"${res.name}\": $matchesList. Ask user which person to message.")
                        }
                        com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MissingPermission -> {
                            result.put("success", false)
                            result.put("message", "Contacts permission is required to read contact phone numbers.")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Error -> {
                            result.put("success", false)
                            result.put("message", res.reason)
                        }
                    }
                }
                "whatsapp_call" -> {
                    val recipientName = args.optString("recipient_name", "")
                        .ifBlank { args.optString("contact_name", "") }
                        .ifBlank { args.optString("name", "") }
                    val callType = args.optString("call_type", "voice")
                    val appNumber = if (args.has("app_number")) args.optInt("app_number", 0).takeIf { it in 1..2 } else null
                    val confirmed = args.optBoolean("confirmed", false)

                    when (val res = com.jarvis.assistant.util.WhatsAppMessenger.placeCall(this, recipientName, callType, appNumber, confirmed)) {
                        is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.Success -> {
                            result.put("success", true)
                            result.put("message", "Connecting WhatsApp ${res.callType} call to ${res.contactName} via ${res.appName}.")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.RequiresConfirmation -> {
                            result.put("success", false)
                            result.put("requires_confirmation", true)
                            result.put("contact_name", res.contactName)
                            result.put("call_type", res.callType)
                            val callPrompt = if (res.callType == "video") "Should I start a WhatsApp video call to ${res.contactName}?" else "Should I call ${res.contactName} on WhatsApp?"
                            result.put("message", "Found contact \"${res.contactName}\". Ask user: \"$callPrompt\" (or in Hindi: \"Kya main ${res.contactName} ko WhatsApp call karoon?\"). When user confirms yes, call whatsapp_call(recipient_name=\"${res.contactName}\", call_type=\"${res.callType}\", confirmed=true).")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MultipleAppsFound -> {
                            result.put("success", false)
                            result.put("multiple_apps", true)
                            result.put("message", "In your mobile there are 2 WhatsApp apps. Ask user: \"In your mobile there are 2 WhatsApp apps. Which one should I use, 1 or 2?\"")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.ContactNotFound -> {
                            result.put("success", false)
                            result.put("message", "Could not find any contact named \"${res.name}\" in phone contacts.")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MultipleContactsFound -> {
                            val matchesList = res.matches.joinToString(", ")
                            result.put("success", false)
                            result.put("message", "Multiple contacts found for \"${res.name}\": $matchesList. Ask user which person to call.")
                        }
                        com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MissingPermission -> {
                            result.put("success", false)
                            result.put("message", "Contacts permission is required to read contact phone numbers.")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.Error -> {
                            result.put("success", false)
                            result.put("message", res.reason)
                        }
                    }
                }
                "set_volume" -> {
                    val ok = adjustVolume(args)
                    result.put("success", ok)
                }
                "set_brightness" -> {
                    val ok = adjustBrightness(args)
                    result.put("success", ok)
                }
                "search_playstore_and_install" -> {
                    val appName = args.optString("app_name", "").ifBlank { args.optString("query", "") }
                    val cleanAppQuery = appName.replace("download", "", ignoreCase = true).replace("install", "", ignoreCase = true).trim()

                    toolScope.launch {
                        val knownPkg = resolvePlayStorePackageName(appName)
                        val playStoreUri = if (knownPkg != null) {
                            Uri.parse("market://details?id=$knownPkg")
                        } else {
                            val encoded = Uri.encode(cleanAppQuery.ifBlank { appName })
                            Uri.parse("market://search?q=$encoded&c=apps")
                        }

                        val playStoreIntent = Intent(Intent.ACTION_VIEW, playStoreUri).apply {
                            setPackage("com.android.vending")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val launched = try {
                            startActivity(playStoreIntent)
                            true
                        } catch (e: Exception) {
                            val marketFallbackIntent = Intent(Intent.ACTION_VIEW, playStoreUri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try { startActivity(marketFallbackIntent); true } catch (e3: Exception) { false }
                        }
                        if (launched) {
                            if (JarvisAccessibilityService.isEnabled()) {
                                JarvisAccessibilityService.instance?.startAutoInstallScanner(cleanAppQuery.ifBlank { appName })
                            }
                        }
                    }

                    result.put("success", true)
                    result.put("message", "Opening Google Play Store for $appName and starting auto-installer.")
                }
                "create_website" -> {
                    val websiteName = args.optString("website_name", "").ifBlank { args.optString("name", "JarvisWebsite") }
                    val businessDesc = args.optString("business_description", "").ifBlank { websiteName }
                    val openRouterKey = com.jarvis.assistant.util.OpenRouterWebsiteGenerator.getApiKey(this)

                    if (openRouterKey.isBlank()) {
                        val noKeyMsg = "Sir, please add your OpenRouter API key in Settings under Website Builder to create websites."
                        speakAloud(noKeyMsg)
                        result.put("success", false)
                        result.put("message", noKeyMsg)
                    } else {
                        val startMsg = "Oh yeah Sir, I have started coding your $websiteName website!"
                        speakAloud(startMsg)
                        result.put("success", true)
                        result.put("message", startMsg)

                        toolScope.launch {
                            delay(2000L)

                            val overlayIntent = Intent(this@JarvisVoiceService, com.jarvis.assistant.service.WebsiteOverlayService::class.java).apply {
                                action = com.jarvis.assistant.service.WebsiteOverlayService.ACTION_SHOW
                                putExtra(com.jarvis.assistant.service.WebsiteOverlayService.EXTRA_WEBSITE_NAME, websiteName)
                            }
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(overlayIntent)
                                } else {
                                    startService(overlayIntent)
                                }
                            } catch (e: Exception) {
                                Log.e("JarvisVoiceService", "startForegroundService overlay failed", e)
                            }

                            val genRes = com.jarvis.assistant.util.OpenRouterWebsiteGenerator.generateWebsite(
                                context = this@JarvisVoiceService,
                                websiteName = websiteName,
                                businessDescription = businessDesc
                            )

                            delay(500L)
                            if (genRes.success) {
                                speakAloud("Sir, I have finished coding your website! Please check your mobile screen.")
                            } else {
                                speakAloud("Sir, website creation encountered an issue: ${genRes.message}")
                            }
                        }
                    }
                }
                "open_website" -> {
                    val rawUrlsArray = args.optJSONArray("urls")
                    val query = args.optString("query", "")
                    val targetUrls = mutableListOf<String>()

                    if (rawUrlsArray != null && rawUrlsArray.length() > 0) {
                        for (i in 0 until rawUrlsArray.length()) {
                            val u = rawUrlsArray.optString(i, "").trim()
                            if (u.isNotEmpty()) {
                                val extracted = extractUrlsFromText(u)
                                if (extracted.isNotEmpty()) targetUrls.addAll(extracted)
                                else targetUrls.add(u)
                            }
                        }
                    }

                    if (targetUrls.isEmpty() && query.isNotBlank()) {
                        targetUrls.addAll(extractUrlsFromText(query))
                    }

                    if (targetUrls.isEmpty() && query.isNotBlank()) {
                        val single = parseTargetUrl(query)
                        if (single != null) targetUrls.add(single)
                    }

                    if (targetUrls.isEmpty() && query.isNotBlank()) {
                        targetUrls.add("https://www.google.com/search?q=${Uri.encode(query)}")
                    }

                    val ok = openUrlsInChromeTabs(targetUrls)
                    result.put("success", ok)
                    if (ok) {
                        if (targetUrls.size > 1) {
                            result.put("message", "Opened ${targetUrls.size} websites in separate Chrome tabs: ${targetUrls.joinToString(", ")}.")
                        } else {
                            result.put("message", "Opened ${targetUrls.firstOrNull() ?: "website"} in Chrome.")
                        }
                    } else {
                        result.put("message", "I couldn't open the websites, Sir.")
                    }
                }
                "search_in_chrome" -> {
                    val query = args.optString("query", "")
                    val extracted = extractUrlsFromText(query)

                    if (extracted.isNotEmpty()) {
                        val ok = openUrlsInChromeTabs(extracted)
                        result.put("success", ok)
                        if (ok) {
                            if (extracted.size > 1) {
                                result.put("message", "Opened ${extracted.size} websites in separate Chrome tabs: ${extracted.joinToString(", ")}.")
                            } else {
                                result.put("message", "Opened ${extracted.first()} in Chrome.")
                            }
                        } else {
                            result.put("message", "I couldn't open the websites, Sir.")
                        }
                    } else {
                        val targetUrl = parseTargetUrl(query)
                        val encoded = Uri.encode(query)
                        val searchUrl = targetUrl ?: "https://www.google.com/search?q=$encoded"
                        val ok = openUrlsInChromeTabs(listOf(searchUrl))
                        result.put("success", ok)
                        if (ok) {
                            if (targetUrl != null) {
                                result.put("message", "Opened $targetUrl in Chrome.")
                            } else {
                                result.put("message", "Searched \"$query\" in Chrome.")
                            }
                        } else {
                            result.put("message", "I couldn't open that, Sir.")
                        }
                    }
                }
                "download_song" -> {
                    val songName = args.optString("song_name", "").ifBlank { args.optString("query", "") }
                    val artist = args.optString("artist", "")
                    val fullQuery = if (artist.isNotBlank()) "$songName $artist" else songName
                    val searchQuery = "pagalnew.com $fullQuery"
                    val encoded = Uri.encode(searchQuery)
                    val chromeSearchUrl = "https://www.google.com/search?q=$encoded"

                    val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(chromeSearchUrl)).apply {
                        setPackage("com.android.chrome")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val launched = try {
                        startActivity(chromeIntent)
                        true
                    } catch (e: Exception) {
                        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(chromeSearchUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try { startActivity(fallbackIntent); true } catch (e2: Exception) { false }
                    }

                    // Perform background research & download via DownloadManager targeting pagalnew.com
                    toolScope.launch {
                        val songRes = com.jarvis.assistant.util.SongDownloader.checkAndDownloadPagalNew(this@JarvisVoiceService, fullQuery)
                        if (songRes.isAvailable) {
                            if (JarvisAccessibilityService.isEnabled()) {
                                JarvisAccessibilityService.instance?.startPagalNewSongScanner(songName)
                            }
                        } else {
                            // Song is not available on pagalnew.com: SPEAK ALOUD exact apology and redirect to home screen
                            val apologyMsg = "Sorry sir, you asked me to download $fullQuery. It is not available so please I am sorry."
                            speakAloud(apologyMsg) {
                                val accSvc = JarvisAccessibilityService.instance
                                if (accSvc != null) {
                                    accSvc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                                } else {
                                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_HOME)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    try { startActivity(homeIntent) } catch (e: Exception) {}
                                }
                            }
                        }
                    }

                    result.put("success", launched)
                    result.put("message", "Opened Chrome for pagalnew.com $fullQuery research.")
                }
                "play_music" -> {
                    val songName = args.optString("song_name", "").ifBlank { args.optString("query", "") }.ifBlank { args.optString("title", "") }
                    if (songName.isNotBlank()) {
                        val intent = Intent(Intent.ACTION_SEARCH).apply {
                            setPackage("com.google.android.youtube")
                            putExtra("query", songName)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            startActivity(intent)
                            result.put("success", true)
                            result.put("message", "Opening YouTube for $songName.")
                        } catch (e: Exception) {
                            val webIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(songName, "UTF-8"))).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(webIntent)
                            result.put("success", true)
                            result.put("message", "Opening YouTube web for $songName.")
                        }
                    } else {
                        result.put("success", false)
                        result.put("message", "Song name was empty.")
                    }
                }
                "tap_screen_by_text" -> {
                    val text = args.optString("text", "").trim()
                    val svc = JarvisAccessibilityService.instance
                    if (svc == null) {
                        result.put("success", false)
                        result.put("message", "Accessibility Service is not enabled yet — ask user to enable it in Settings > Accessibility.")
                    } else {
                        val ok = svc.clickNodeWithText(text) || svc.findAndClickMediaTarget(text)
                        result.put("success", ok)
                        if (!ok) result.put("message", "Could not find or tap \"$text\" on screen.")
                    }
                }
                "tap_screen_coordinates" -> {
                    val x = args.optInt("x_percent", 50)
                    val y = args.optInt("y_percent", 50)
                    val svc = JarvisAccessibilityService.instance
                    if (svc == null) {
                        result.put("success", false)
                        result.put("message", "Accessibility Service is not enabled.")
                    } else {
                        val ok = svc.tapAtPercentage(x.toFloat(), y.toFloat())
                        result.put("success", ok)
                    }
                }
                "type_text" -> {
                    val textToType = args.optString("text", "")
                    val svc = JarvisAccessibilityService.instance
                    if (svc == null) {
                        result.put("success", false)
                        result.put("message", "Accessibility Service is not enabled.")
                    } else {
                        val ok = svc.typeText(textToType)
                        result.put("success", ok)
                        if (!ok) result.put("message", "No input text field currently focused.")
                    }
                }
                "perform_device_gesture" -> {
                    val gesture = args.optString("gesture", "home")
                    val svc = JarvisAccessibilityService.instance
                    if (svc == null) {
                        result.put("success", false)
                        result.put("message", "Accessibility Service is not enabled.")
                    } else {
                        val ok = svc.performSystemGesture(gesture)
                        result.put("success", ok)
                    }
                }
                "builtin_chrome_search" -> {
                    val query = args.optString("query", "")
                    withContext(Dispatchers.Main) {
                        dispatchToListeners { it.onResearchStateChanged(true, query) }
                    }
                    val searchResult = com.jarvis.assistant.util.BuiltInChromeEngine.searchAndExtract(query)
                    withContext(Dispatchers.Main) {
                        dispatchToListeners { it.onResearchStateChanged(false, "") }
                    }
                    result.put("success", true)
                    result.put("web_research_result", searchResult)
                }
                "unlock_app_lock" -> {
                    val passcode = args.optString("passcode", "").ifBlank { args.optString("pin", "") }
                    val svc = JarvisAccessibilityService.instance
                    if (svc == null) {
                        result.put("success", false)
                        result.put("message", "Accessibility Service is not enabled yet — ask user to enable it in Settings > Accessibility.")
                    } else {
                        val ok = svc.unlockAppLock(passcode)
                        result.put("success", ok)
                        if (ok) {
                            result.put("message", "Entered passcode '$passcode' to unlock the app lock screen.")
                        } else {
                            result.put("message", "Tried entering passcode '$passcode' on screen, but could not locate password field or keypad buttons.")
                        }
                    }
                }
                "delete_whatsapp_message" -> {
                    val target = args.optString("delete_target", "everyone")
                    val svc = JarvisAccessibilityService.instance
                    if (svc == null) {
                        result.put("success", false)
                        result.put("message", "Accessibility Service is not enabled yet — ask user to enable it in Settings > Accessibility.")
                    } else {
                        val ok = svc.deleteWhatsAppMessage(target)
                        result.put("success", ok)
                        result.put("message", if (ok) "Deleted WhatsApp message for $target." else "Could not delete WhatsApp message.")
                    }
                }
                "smart_screen_scroll" -> {
                    val action = args.optString("action", "scroll_down")
                    val svc = JarvisAccessibilityService.instance
                    if (svc == null) {
                        result.put("success", false)
                        result.put("message", "Accessibility Service is not enabled.")
                    } else {
                        val ok = svc.smartScroll(action)
                        result.put("success", ok)
                        result.put("message", if (ok) "Executed scroll action: $action." else "Scroll action failed.")
                    }
                }
                "set_alarm" -> {
                    val hour = args.optInt("hour", 0)
                    val minute = args.optInt("minute", 0)
                    val label = args.optString("label", "JARVIS Alarm")
                    val (ok, msg) = com.jarvis.assistant.util.AlarmTimerManager.setAlarm(this, hour, minute, label)
                    result.put("success", ok)
                    result.put("message", msg)
                }
                "set_timer" -> {
                    val seconds = args.optInt("seconds", 60)
                    val label = args.optString("label", "JARVIS Timer")
                    val (ok, msg) = com.jarvis.assistant.util.AlarmTimerManager.setTimer(this, seconds, label)
                    result.put("success", ok)
                    result.put("message", msg)
                }
                "set_reminder" -> {
                    val title = args.optString("title", "Reminder")
                    val delayMins = args.optInt("delay_minutes", 10)
                    val (ok, msg) = com.jarvis.assistant.util.AlarmTimerManager.setReminder(this, title, delayMins)
                    result.put("success", ok)
                    result.put("message", msg)
                }
                "get_system_info" -> {
                    val queryType = args.optString("query_type", "all")
                    val city = args.optString("city", "")
                    val info = com.jarvis.assistant.util.SystemInfoManager.getSystemSummary(this, queryType, city)
                    result.put("success", true)
                    result.put("system_info", info)
                }
                "control_camera" -> {
                    val action = args.optString("action", "take_photo")
                    val (ok, msg) = com.jarvis.assistant.util.CameraManagerHelper.captureDirectPhoto(this, action)
                    result.put("success", ok)
                    result.put("message", msg)
                }
                "analyze_scene" -> {
                    val mode = args.optString("mode", "full_analysis")
                    if (!isCameraVisionActive() && !isScreenSharing()) {
                        startCameraVision(useFront = false)
                    }
                    val promptText = when (mode) {
                        "read_text", "ocr" -> "[SYSTEM COMMAND] Perform OCR: read all text visible in the current camera/screen view out loud to the user."
                        "object_recognition", "objects" -> "[SYSTEM COMMAND] Identify and describe the main objects currently visible in the camera/screen view."
                        "describe_scene", "surroundings" -> "[SYSTEM COMMAND] Describe the current surroundings and scene in full detail to the user."
                        "solve_math", "math" -> "[SYSTEM COMMAND] Identify any mathematical equations or calculations visible in the camera/screen view, solve them step-by-step, and explain the answer clearly."
                        "qr_scanner", "qr_code", "barcode" -> "[SYSTEM COMMAND] Scan and read any QR code or barcode visible in the camera/screen view, and read out the decoded URL or data."
                        else -> "[SYSTEM COMMAND] Provide a full visual analysis: read any text, identify objects, and describe the scene context."
                    }
                    geminiLive?.sendText(promptText)
                    result.put("success", true)
                    result.put("message", "Analyzing scene: $mode.")
                }
                "control_flashlight" -> {
                    val action = args.optString("action", "toggle")
                    val level = if (args.has("brightness_level")) args.optInt("brightness_level", -1).takeIf { it > 0 } else null
                    val (ok, msg) = com.jarvis.assistant.util.FlashlightController.controlFlashlight(this, action, level)
                    result.put("success", ok)
                    result.put("message", msg)
                }
                "control_wifi" -> {
                    val action = args.optString("action", "on").lowercase().trim()
                    val ssid = args.optString("ssid", "").trim()
                    val password = args.optString("password", "").trim()

                    when (action) {
                        "connect" -> {
                            val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.connectToWifi(this, ssid, password)
                            result.put("success", ok)
                            result.put("message", msg)
                        }
                        "status" -> {
                            val status = com.jarvis.assistant.util.DeviceSettingsController.getWifiStatus(this)
                            result.put("success", true)
                            result.put("message", status)
                            result.put("wifi_status", status)
                        }
                        else -> {
                            val enable = (action == "on")
                            val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.setWifiEnabled(this, enable) { verifiedOk, verifiedMsg ->
                                speakAloud(verifiedMsg)
                            }
                            result.put("success", ok)
                            result.put("message", msg)
                        }
                    }
                }
                "control_bluetooth" -> {
                    val action = args.optString("action", "on").lowercase().trim()
                    val deviceName = args.optString("device_name", "").trim()

                    when (action) {
                        "connect" -> {
                            val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.connectToBluetoothDevice(this, deviceName)
                            result.put("success", ok)
                            result.put("message", msg)
                        }
                        "status" -> {
                            val status = com.jarvis.assistant.util.DeviceSettingsController.getBluetoothStatus(this)
                            result.put("success", true)
                            result.put("message", status)
                            result.put("bluetooth_status", status)
                        }
                        else -> {
                            val enable = (action == "on")
                            val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.setBluetoothEnabled(this, enable) { verifiedOk, verifiedMsg ->
                                speakAloud(verifiedMsg)
                            }
                            result.put("success", ok)
                            result.put("message", msg)
                        }
                    }
                }
                "control_hotspot" -> {
                    val action = args.optString("action", "on").lowercase().trim()
                    if (action == "get_password") {
                        val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.getHotspotPassword(this)
                        result.put("success", ok)
                        result.put("message", msg)
                    } else {
                        val enable = (action == "on")
                        val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.toggleHotspot(this, enable)
                        result.put("success", ok)
                        result.put("message", msg)
                    }
                }
                "control_battery_optimization" -> {
                    val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.requestIgnoreBatteryOptimizations(this)
                    result.put("success", ok)
                    result.put("message", msg)
                }
                "switch_sim_network" -> {
                    val slot = args.optInt("sim_slot", 1)
                    val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.switchMobileDataSim(this, slot)
                    result.put("success", ok)
                    result.put("message", msg)
                }
                "control_developer_options" -> {
                    val action = args.optString("action", "open").lowercase().trim()
                    when {
                        action.contains("wireless") -> {
                            val enable = !action.contains("disable")
                            val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.controlDeveloperOption(this, "wireless debugging", enable) { verifiedOk, verifiedMsg ->
                                speakAloud(verifiedMsg)
                            }
                            result.put("success", ok)
                            result.put("message", msg)
                        }
                        action.contains("usb") -> {
                            val enable = !action.contains("disable")
                            val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.controlDeveloperOption(this, "usb debugging", enable) { verifiedOk, verifiedMsg ->
                                speakAloud(verifiedMsg)
                            }
                            result.put("success", ok)
                            result.put("message", msg)
                        }
                        else -> {
                            val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.openDeveloperOptions(this)
                            result.put("success", ok)
                            result.put("message", msg)
                        }
                    }
                }
                "control_system_settings" -> {
                    val setting = args.optString("setting", "settings")
                    val (ok, msg) = com.jarvis.assistant.util.DeviceSettingsController.openSystemSetting(this, setting)
                    result.put("success", ok)
                    result.put("message", msg)
                }
                "show_code_preview_bar", "open_code_review_bar" -> {
                    val overlayIntent = Intent(this@JarvisVoiceService, com.jarvis.assistant.service.WebsiteOverlayService::class.java).apply {
                        action = com.jarvis.assistant.service.WebsiteOverlayService.ACTION_SHOW
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(overlayIntent)
                        } else {
                            startService(overlayIntent)
                        }
                    } catch (e: Exception) {
                        Log.e("JarvisVoiceService", "startForegroundService overlay failed", e)
                    }
                    result.put("success", true)
                    result.put("message", "Re-opened website code preview bar overlay on screen.")
                }
                "control_floating_orb" -> {
                    val action = args.optString("action", "off").lowercase()
                    if (action == "off" || action == "hide") {
                        com.jarvis.assistant.service.FloatingOrbService.stopService(this@JarvisVoiceService)
                        val msg = "Floating Voice Orb turned off, sir. I am running and talking in the background."
                        result.put("success", true)
                        result.put("message", msg)
                    } else {
                        com.jarvis.assistant.service.FloatingOrbService.startService(this@JarvisVoiceService)
                        val msg = "Floating Voice Orb turned back on, sir."
                        result.put("success", true)
                        result.put("message", msg)
                    }
                }
                "send_to_background", "go_to_background" -> {
                    result.put("success", true)
                    result.put("message", "Going to background standby mode.")
                    Handler(Looper.getMainLooper()).post { performBackgroundModeIntent() }
                }
                "show_yourself" -> {
                    val msg = "I have brought the JARVIS interface to the front screen, Sir."
                    result.put("success", true)
                    result.put("message", msg)
                    Handler(Looper.getMainLooper()).post { performShowYourselfIntent() }
                }
                "chrome_action" -> {
                    val action = args.optString("action", "").lowercase().trim()
                    var svc = JarvisAccessibilityService.instance
                    if (svc == null) {
                        Thread.sleep(400)
                        svc = JarvisAccessibilityService.instance
                    }
                    when (action) {
                        "incognito" -> {
                            var ok = false
                            try {
                                val directIntent = Intent("org.chromium.chrome.browser.incognito.OPEN_PRIVATE_TAB").apply {
                                    component = android.content.ComponentName("com.android.chrome", "org.chromium.chrome.browser.incognito.IncognitoTabLauncher")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(directIntent)
                                ok = true
                            } catch (e: Exception) {
                                Log.w("JarvisVoiceService", "Direct incognito launch failed, trying accessibility", e)
                            }
                            if (!ok) {
                                ok = svc?.openChromeIncognito() ?: false
                            }
                            result.put("success", ok)
                            result.put("message", if (ok) "Opened Chrome in Incognito mode." else "Could not open Incognito tab.")
                        }
                        "close_all_tabs" -> {
                            val ok = svc?.closeChromeAllTabs() ?: false
                            result.put("success", ok)
                            result.put("message", if (ok) "Closed all Chrome tabs." else "Could not close Chrome tabs.")
                        }
                        "read_page" -> {
                            val pageContent = svc?.readChromeWebpageContent() ?: ""
                            if (pageContent.isNotBlank()) {
                                result.put("success", true)
                                result.put("page_content", pageContent)
                                result.put("message", "Webpage content: $pageContent")
                            } else {
                                result.put("success", false)
                                result.put("message", "Could not read webpage content. Ensure Chrome is active and open.")
                            }
                        }
                        "summarize_page" -> {
                            val pageContent = svc?.readChromeWebpageContent() ?: ""
                            if (pageContent.isNotBlank()) {
                                result.put("success", true)
                                result.put("page_content", pageContent)
                                result.put("message", "Please summarize this webpage content in 3-4 concise points for the user: $pageContent")
                            } else {
                                result.put("success", false)
                                result.put("message", "Could not read webpage content to summarize.")
                            }
                        }
                        else -> {
                            result.put("success", false)
                            result.put("message", "Unknown Chrome action: $action")
                        }
                    }
                }
                "youtube_auto_ad_skip" -> {
                    val action = args.optString("action", "start").lowercase()
                    val svc = JarvisAccessibilityService.instance
                    if (svc == null) {
                        result.put("success", false)
                        result.put("message", "Accessibility Service is not active.")
                    } else {
                        if (action == "start" || action == "on" || action == "enable") {
                            svc.startYouTubeAdSkipper()
                            result.put("success", true)
                            result.put("message", "YouTube Auto Ad-Skipper activated. Ads will be skipped automatically.")
                        } else {
                            svc.stopYouTubeAdSkipper()
                            result.put("success", true)
                            result.put("message", "YouTube Auto Ad-Skipper stopped.")
                        }
                    }
                }
                "open_whatsapp_chat" -> {
                    val contactName = args.optString("contact_name", "")
                    val appNumber = if (args.has("app_number")) args.optInt("app_number") else null
                    val confirmed = args.optBoolean("confirmed", false)
                    when (val sendResult = com.jarvis.assistant.util.WhatsAppMessenger.openChat(this, contactName, appNumber, confirmed)) {
                        is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Success -> {
                            result.put("success", true)
                            result.put("message", "Opened WhatsApp chat for ${sendResult.contactName}.")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.RequiresConfirmation -> {
                            result.put("success", true)
                            result.put("requires_confirmation", true)
                            result.put("contact_name", sendResult.contactName)
                            result.put("message", "Ask user: 'Kya main ${sendResult.contactName} ka WhatsApp chat open kar doon?'")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MultipleAppsFound -> {
                            result.put("success", true)
                            result.put("multiple_apps", true)
                            result.put("message", "In your mobile there are 2 WhatsApp apps. Which one should I use, 1 or 2?")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.ContactNotFound -> {
                            result.put("success", false)
                            result.put("message", "Could not find contact '${sendResult.name}'.")
                        }
                        is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Error -> {
                            result.put("success", false)
                            result.put("message", sendResult.reason)
                        }
                        else -> {
                            result.put("success", false)
                            result.put("message", "Failed to open WhatsApp chat.")
                        }
                    }
                }
                "reply_whatsapp_notification" -> {
                    val message = args.optString("message", "")
                    val ok = com.jarvis.assistant.util.WhatsAppNotificationManager.sendDirectReply(this, message)
                    result.put("success", ok)
                    result.put("message", if (ok) "Sent direct reply: \"$message\"" else "No active incoming WhatsApp message to reply to.")
                }
                "save_note" -> {
                    val prod = com.jarvis.assistant.productivity.ProductivityManager(this)
                    val action = args.optString("action", "").lowercase().trim()
                    val content = args.optString("content", "").trim()
                    val noteNum = args.optInt("note_number", -1)
                    val res = when {
                        action in setOf("list", "read", "view", "show", "get", "all") || (action.isBlank() && content.isBlank()) -> prod.listNotes()
                        action in setOf("delete", "remove") -> prod.deleteNote(noteNum)
                        else -> {
                            if (content.isNotBlank()) {
                                prod.saveNote(content)
                            } else {
                                prod.listNotes()
                            }
                        }
                    }
                    result.put("success", res.optBoolean("success", true))
                    result.put("message", res.optString("message", ""))
                    if (res.has("notes")) result.put("notes", res.get("notes"))
                    if (res.has("count")) result.put("count", res.optInt("count", 0))
                }
                "manage_todo" -> {
                    val prod = com.jarvis.assistant.productivity.ProductivityManager(this)
                    val action = args.optString("action", "list").lowercase().trim()
                    val task = args.optString("task", "").trim()
                    val itemNum = args.optInt("item_number", -1)
                    val res = when (action) {
                        "add" -> prod.addTodo(task)
                        "complete" -> prod.completeTodo(itemNum)
                        "remove", "delete" -> prod.removeTodo(itemNum)
                        else -> prod.listTodos()
                    }
                    result.put("success", res.optBoolean("success", true))
                    result.put("message", res.optString("message", ""))
                    if (res.has("todos")) result.put("todos", res.get("todos"))
                    if (res.has("count")) result.put("count", res.optInt("count", 0))
                }
                "manage_clipboard" -> {
                    val prod = com.jarvis.assistant.productivity.ProductivityManager(this)
                    val action = args.optString("action", "read").lowercase().trim()
                    val text = args.optString("text", "").trim()
                    val res = when (action) {
                        "write", "copy", "set" -> {
                            if (text.isNotBlank()) prod.writeClipboard(text) else prod.readClipboard()
                        }
                        else -> prod.readClipboard()
                    }
                    result.put("success", res.optBoolean("success", true))
                    result.put("message", res.optString("message", ""))
                    if (res.has("content")) result.put("content", res.get("content"))
                    if (res.has("clipboard_text")) result.put("clipboard_text", res.get("clipboard_text"))
                }
                "daily_briefing" -> {
                    val prod = com.jarvis.assistant.productivity.ProductivityManager(this)
                    val briefing = prod.getDailyBriefing()
                    result.put("success", briefing.optBoolean("success", true))
                    result.put("briefing", briefing.optString("briefing", ""))
                    result.put("message", "Daily briefing data: ${briefing.optString("briefing")}. Speak this smoothly in your voice!")
                }
                "create_calendar_event" -> {
                    val prod = com.jarvis.assistant.productivity.ProductivityManager(this)
                    val title = args.optString("title", "")
                    val desc = args.optString("description", "")
                    val duration = args.optInt("duration_minutes", 60)
                    val res = prod.createCalendarEvent(title = title, description = desc, durationMinutes = duration)
                    result.put("success", res.optBoolean("success", true))
                    result.put("message", res.optString("message", ""))
                }
                "shutdown_jarvis" -> {
                    result.put("success", true)
                    result.put("message", "Entering standby. Goodbye!")
                    Handler(Looper.getMainLooper()).post { performBackgroundModeIntent() }
                }
                else -> {
                    result.put("success", false)
                    result.put("message", "Unknown tool: $name")
                }
            }
        } catch (e: Exception) {
            val errorDetail = "Tool '$name' failed: ${e.javaClass.simpleName}: ${e.message}"
            android.util.Log.e("JarvisVoiceService", errorDetail, e)
            result.put("success", false)
            result.put("message", errorDetail)
            dispatchToListeners { it.onError(errorDetail) }
        }
        sendToolResponse(callId, name, result)
    }

    private fun parseIntegerFromAny(obj: Any?): Int {
        if (obj == null) return -1
        if (obj is Int) return obj
        if (obj is Double) return obj.toInt()
        if (obj is Long) return obj.toInt()
        if (obj is Float) return obj.toInt()
        if (obj is String) {
            val digits = obj.replace(Regex("[^0-9]"), "")
            if (digits.isNotEmpty()) {
                return digits.toIntOrNull() ?: -1
            }
        }
        return -1
    }

    private fun adjustVolume(args: JSONObject): Boolean {
        return try {
            val rawAction = args.optString("action", "").lowercase()
            val rawDir = args.optString("direction", "").lowercase()
            val rawMode = args.optString("mode", "").lowercase()
            val combinedStr = "$rawAction $rawDir $rawMode".lowercase()

            Log.d("JarvisVoiceService", "adjustVolume raw args: $args")

            var percent = -1
            // Inspect all fields in JSON object for a percentage / level number
            val keys = args.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val parsed = parseIntegerFromAny(args.get(key))
                if (parsed in 0..100) {
                    percent = parsed
                    break
                }
            }

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val isDecrease = combinedStr.contains("decrease") || combinedStr.contains("down") || combinedStr.contains("lower") || combinedStr.contains("kam") || combinedStr.contains("reduce") || combinedStr.contains("less") || combinedStr.contains("minus")
            val isIncrease = combinedStr.contains("increase") || combinedStr.contains("up") || combinedStr.contains("raise") || combinedStr.contains("badhao") || combinedStr.contains("more") || combinedStr.contains("high") || combinedStr.contains("plus")

            var finalDisplayPercent = percent
            var hardFailure: String? = null
            val maxVolBefore = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVolBefore = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            if (percent in 0..100) {
                val streams = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL)
                var anyStreamSucceeded = false
                for (stream in streams) {
                    try {
                        val maxVol = audioManager.getStreamMaxVolume(stream)
                        val targetVol = kotlin.math.round((percent / 100f) * maxVol).toInt().coerceIn(0, maxVol)
                        audioManager.setStreamVolume(stream, targetVol, AudioManager.FLAG_SHOW_UI)
                        anyStreamSucceeded = true
                    } catch (e: SecurityException) {
                        // Typically thrown when Do Not Disturb / notification policy access blocks the change.
                        Log.e("JarvisVoiceService", "Blocked setting stream $stream (likely DND policy): ${e.message}")
                    } catch (e: Exception) {
                        Log.e("JarvisVoiceService", "Error setting stream $stream: ${e.message}")
                    }
                }
                if (!anyStreamSucceeded) hardFailure = "Do Not Disturb or a system policy is blocking volume changes."
                finalDisplayPercent = kotlin.math.round((audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolBefore.toFloat()) * 100).toInt()
            } else if (isDecrease || isIncrease) {
                val dir = if (isDecrease) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                var succeeded = false
                try {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                    succeeded = true
                } catch (e: SecurityException) {
                    Log.e("JarvisVoiceService", "Blocked adjusting volume (likely DND policy): ${e.message}")
                }
                try { audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, dir, 0) } catch (_: Exception) {}
                val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                finalDisplayPercent = kotlin.math.round((curVol.toFloat() / maxVolBefore.toFloat()) * 100).toInt()
                if (!succeeded || curVol == curVolBefore) {
                    hardFailure = "Do Not Disturb or a system policy is blocking volume changes."
                }
            } else {
                Log.w("JarvisVoiceService", "adjustVolume: no direction or percent found in $args")
                finalDisplayPercent = kotlin.math.round((curVolBefore.toFloat() / maxVolBefore.toFloat()) * 100).toInt()
                hardFailure = "I couldn't tell whether you wanted volume up, down, or a specific level."
            }

            if (hardFailure != null) {
                speakAloud("I couldn't change the volume, Sir — $hardFailure")
                return false
            }

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "🔊 Volume: $finalDisplayPercent%", Toast.LENGTH_SHORT).show()
            }

            true
        } catch (e: Exception) {
            android.util.Log.e("JarvisVoiceService", "adjustVolume failed", e)
            speakAloud("Something went wrong changing the volume, Sir: ${e.message ?: "unknown error"}.")
            false
        }
    }

    private fun adjustBrightness(args: JSONObject): Boolean {
        return try {
            val actionStr = (args.optString("action", "") + " " + args.optString("direction", "") + " " + args.optString("mode", "")).lowercase()
            
            var percent = -1
            val keys = listOf("percentage", "percent", "level", "value", "brightness")
            for (k in keys) {
                if (args.has(k)) {
                    val valObj = args.get(k)
                    if (valObj is Int) percent = valObj
                    else if (valObj is Double) percent = valObj.toInt()
                    else if (valObj is String) {
                        val cleanDigits = valObj.replace(Regex("[^0-9]"), "")
                        if (cleanDigits.isNotEmpty()) percent = cleanDigits.toIntOrNull() ?: -1
                    }
                }
                if (percent in 0..100) break
            }

            val isDecrease = actionStr.contains("decrease") || actionStr.contains("down") || actionStr.contains("lower") || actionStr.contains("kam") || actionStr.contains("reduce") || actionStr.contains("less")
            val isIncrease = actionStr.contains("increase") || actionStr.contains("up") || actionStr.contains("raise") || actionStr.contains("badhao") || actionStr.contains("more") || actionStr.contains("high")

            val cr = contentResolver
            val currentBrightness = try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Exception) { 128 }

            val targetBrightnessInt = when {
                percent in 0..100 -> ((percent / 100f) * 255).toInt().coerceIn(15, 255)
                isDecrease -> (currentBrightness - 50).coerceAtLeast(15)
                isIncrease -> (currentBrightness + 50).coerceAtMost(255)
                else -> currentBrightness
            }

            val targetFloat = (targetBrightnessInt / 255f).coerceIn(0.05f, 1f)
            val targetPercentDisplay = (targetFloat * 100).toInt()

            // Without WRITE_SETTINGS granted, Android will silently no-op the write below — so
            // check this FIRST and be honest about it instead of reporting success anyway.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "I need \"Modify system settings\" permission to control brightness, Sir — please allow it on the screen I just opened.", Toast.LENGTH_LONG).show()
                }
                speakAloud("I need \"Modify system settings\" permission to control brightness, Sir. I've opened the screen to grant it — please turn it on there.")
                return false
            }

            var writeFailed = false
            try {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, targetBrightnessInt)
                try {
                    // Android 11+ (API 30+) float brightness setting — this is what the Quick Settings /
                    // Control Center slider actually reads, so it must be kept in sync with the int value above.
                    Settings.System.putFloat(cr, "screen_brightness_float", targetFloat)
                } catch (_: Exception) {}

                cr.notifyChange(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), null)
                try {
                    cr.notifyChange(Settings.System.getUriFor("screen_brightness_float"), null)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                android.util.Log.e("JarvisVoiceService", "Settings.System write failed", e)
                writeFailed = true
            }

            // Verify the write actually landed instead of assuming it did.
            val readBack = try { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) } catch (e: Exception) { -1 }
            if (writeFailed || readBack != targetBrightnessInt) {
                speakAloud("I tried to set brightness to $targetPercentDisplay% but it didn't stick, Sir. There may be a system restriction on this device.")
                return false
            }

            // Sync top window brightness in MainActivity
            com.jarvis.assistant.ui.main.MainActivity.instance?.setWindowBrightness(targetBrightnessInt)

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "☀️ JARVIS Brightness: $targetPercentDisplay%", Toast.LENGTH_SHORT).show()
            }

            true
        } catch (e: Exception) {
            android.util.Log.e("JarvisVoiceService", "adjustBrightness failed", e)
            speakAloud("Something went wrong changing brightness, Sir: ${e.message ?: "unknown error"}.")
            false
        }
    }

    /** Send a text message to Gemini Live (used for text chat input). */
    fun sendTextToGemini(text: String) {
        currentTurnHasWakeWord = true
        geminiLive?.sendText(text)
    }

    fun setMicMuted(muted: Boolean) {
        isUserMuted = muted
        audioEngine?.setMuted(muted)
    }

    fun isMicMuted(): Boolean = isUserMuted

    fun isCurrentlySpeaking(): Boolean = audioEngine?.isCurrentlySpeaking() ?: false

    fun interrupt() {
        isTurnInterrupted = true
        standbyAudioBuffer.clear()
        audioEngine?.clearPlaybackQueue()
        audioEngine?.setStreamingPaused(false)
        geminiLive?.setAudioTransportPaused(false)
        geminiLive?.sendInterrupt()
        dispatchToListeners { it.onSpeakingStopped() }
    }

    fun sendToolResponse(callId: String, name: String, result: JSONObject) {
        geminiLive?.sendToolResponse(callId, name, result)
    }

    fun isSessionRunning(): Boolean = isSessionStarted

    /** Periodic health check: if the WebSocket looks dead, force reconnect. */
    private fun startConnectionHealthCheck() {
        connectionHealthJob?.cancel()
        connectionHealthJob = toolScope.launch {
            while (true) {
                delay(30_000L)
                if (!isInBackgroundStandby() && isSessionStarted && geminiLive != null && geminiLive?.isConnected() != true) {
                    Log.w("JarvisVoiceService", "Health check: GeminiLive not connected but session should be running. Forcing reconnect...")
                    try {
                        geminiLive?.disconnect(manual = false)
                    } catch (_: Exception) {}
                    delay(1000L)
                    geminiLive?.connect()
                }
            }
        }
    }

    fun elevateToMediaProjectionForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (cameraVisionEngine?.isCameraStreaming() == true) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
                Log.d("JarvisVoiceService", "startForeground elevated to MEDIA_PROJECTION (types=$serviceType)")
            } catch (e: Exception) {
                Log.e("JarvisVoiceService", "elevateToMediaProjectionForegroundService failed: ${e.message}", e)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (cameraVisionEngine?.isCameraStreaming() == true) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } catch (e: Exception) {
                Log.e("JarvisVoiceService", "elevateToMediaProjectionForegroundService failed: ${e.message}", e)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun startScreenShare(resultCode: Int, data: Intent) {
        try {
            if (screenCaptureEngine != null) {
                screenCaptureEngine?.stop()
                screenCaptureEngine = null
            }

            // CRITICAL (Android 14+ / API 34+): Must elevate service to MEDIA_PROJECTION before calling getMediaProjection!
            elevateToMediaProjectionForegroundService()

            // TIMING SAFETY: On Android 14+, startForeground(MEDIA_PROJECTION) must fully commit
            // to the system before getMediaProjection() is called. Without this delay, the OS can
            // throw SecurityException("Media projections require a foreground service") because
            // the foreground service type hasn't been registered yet. 150ms is enough for the
            // system to process the startForeground IPC.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Thread.sleep(150)
            }

            enterActiveState(fromWakeWord = false)

            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("getMediaProjection returned null — user may have denied permission")

            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopScreenShare()
                }
            }, Handler(Looper.getMainLooper()))

            screenCaptureEngine = com.jarvis.assistant.vision.ScreenCaptureEngine(this, mediaProjection) { jpegBytes ->
                touchUserActivity()
                geminiLive?.sendVideoFrame(jpegBytes)
            }
            screenCaptureEngine?.start()
            dispatchToListeners { it.onScreenShareStateChanged(true) }

            // Gemini Live natural voice notification
            currentTurnHasWakeWord = true
            geminiLive?.sendText("[SYSTEM EVENT] Live screen sharing has started. You are now receiving continuous mobile screen frames in real time. Please briefly confirm to the user in your natural voice that you can see their screen.", turnComplete = true)
        } catch (e: Throwable) {
            // Catch ALL throwables including SecurityException, IllegalStateException, RuntimeException
            // from MediaProjection failures. Log and gracefully stop instead of crashing the entire app.
            Log.e("JarvisVoiceService", "startScreenShare failed: ${e.message}", e)
            stopScreenShare()
            // Notify user through Gemini voice that screen sharing couldn't start
            toolScope.launch {
                geminiLive?.sendText("[SYSTEM ERROR] Screen sharing could not start due to a system error. Please inform the user that screen sharing failed and ask them to try again.")
            }
        }
    }

    fun stopScreenShare() {
        val wasActive = screenCaptureEngine != null
        screenCaptureEngine?.stop()
        screenCaptureEngine = null
        ensureMicrophoneForegroundService()
        dispatchToListeners { it.onScreenShareStateChanged(false) }
        if (wasActive) {
            geminiLive?.sendText("[SYSTEM COMMAND] Screen vision has been closed by the user. You are no longer receiving screen frames.")
        }
    }

    @Volatile
    private var lastAnnouncedWhatsAppKey: String = ""
    @Volatile
    private var lastAnnouncedWhatsAppTime: Long = 0L

    /**
     * Handles incoming WhatsApp notifications dispatched from JarvisNotificationListenerService.
     * Informs Gemini Live so it can announce the message and prompt the user for a reply.
     */
    fun handleIncomingWhatsAppNotification(sender: String, text: String) {
        val prefs = getSharedPreferences(com.jarvis.assistant.JarvisApplication.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notification_reader_enabled", true)) {
            Log.d("JarvisVoiceService", "Notification Reader is disabled in Settings, skipping announcement.")
            return
        }

        val cleanKey = "${sender.trim().lowercase()}:${text.trim().lowercase()}"
        val now = System.currentTimeMillis()
        if (cleanKey == lastAnnouncedWhatsAppKey && (now - lastAnnouncedWhatsAppTime) < 45_000L) {
            Log.d("JarvisVoiceService", "Skipping duplicate WhatsApp announcement in JarvisVoiceService: $cleanKey (${now - lastAnnouncedWhatsAppTime}ms ago)")
            return
        }
        lastAnnouncedWhatsAppKey = cleanKey
        lastAnnouncedWhatsAppTime = now
        Log.i("JarvisVoiceService", "Incoming WhatsApp from $sender: $text")
        toolScope.launch {
            try {
                // 1. Acquire WakeLock so CPU does not sleep
                acquireWakeLock()

                // 2. Wake JARVIS up into ACTIVE state
                enterActiveState(fromWakeWord = true)

                // 3. Play wake sound to notify user
                try {
                    com.jarvis.assistant.audio.JarvisSoundEffects.playWakeSound()
                } catch (_: Exception) {}

                // 4. Activate follow-up window so user can speak reply directly without wake word
                startFollowUpWindow()
                currentTurnHasWakeWord = true

                // 5. If Gemini is not connected, reconnect and wait up to 3 seconds
                if (geminiLive?.isConnected() != true) {
                    ensureSessionActive()
                    var attempts = 0
                    while (geminiLive?.isConnected() != true && attempts < 15) {
                        delay(200L)
                        attempts++
                    }
                }

                // 6. Send announcement to Gemini Live
                val prompt = "[SYSTEM EVENT] Incoming WhatsApp message from $sender: \"$text\". " +
                    "Out loud in your natural charismatic voice, announce this message to the user right now: " +
                    "\"$sender ka message aaya hai: '$text'. Kya reply karna hai?\" " +
                    "(or in English if user prefers: \"$sender sent you a message: '$text'. Would you like me to reply?\"). Do not call any tools right now."
                Log.i("JarvisVoiceService", "Announcing incoming WhatsApp message via Gemini Live: $prompt")
                geminiLive?.sendText(prompt, turnComplete = true)
            } catch (e: Exception) {
                Log.e("JarvisVoiceService", "Failed to forward WhatsApp notification to Gemini", e)
            }
        }
    }


    private suspend fun resolvePlayStorePackageName(appName: String): String? = withContext(Dispatchers.IO) {
        val known = getKnownPackageName(appName)
        if (known != null) return@withContext known

        return@withContext try {
            val query = appName.replace("download", "", ignoreCase = true)
                .replace("install", "", ignoreCase = true).trim()
            val encoded = Uri.encode(query.ifBlank { appName })
            val url = URL("https://play.google.com/store/search?q=$encoded&c=apps")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile)")
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val html = conn.inputStream.bufferedReader().use { reader -> reader.readText() }
                val match = Regex("""/store/apps/details\?id=([a-zA-Z0-9_.]+)""").find(html)
                match?.groupValues?.get(1)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun getKnownPackageName(appName: String): String? {
        val clean = appName.lowercase().trim().replace(Regex("[^a-z0-9]"), "")
        return when {
            clean.contains("github") -> "com.github.android"
            clean.contains("blinkit") || clean.contains("grofers") -> "com.grofers.customerapp"
            clean.contains("zomato") -> "com.application.zomato"
            clean.contains("swiggy") -> "in.swiggy.android"
            clean.contains("zepto") -> "com.zepto.customer"
            clean.contains("whatsapp") -> "com.whatsapp"
            clean.contains("instagram") -> "com.instagram.android"
            clean.contains("telegram") -> "org.telegram.messenger"
            clean.contains("facebook") -> "com.facebook.katana"
            clean.contains("spotify") -> "com.spotify.music"
            clean.contains("snapchat") -> "com.snapchat.android"
            clean.contains("paytm") -> "net.one97.paytm"
            clean.contains("phonepe") -> "com.phonepe.app"
            clean.contains("gpay") || clean.contains("googlepay") -> "com.google.android.apps.nfc.payment"
            clean.contains("flipkart") -> "com.flipkart.android"
            clean.contains("amazon") -> "com.amazon.mShop.android.shopping"
            clean.contains("meesho") -> "com.meesho.supply"
            clean.contains("myntra") -> "com.myntra.android"
            clean.contains("uber") -> "com.ubercab"
            clean.contains("ola") -> "com.olacabs.customer"
            clean.contains("rapido") -> "com.rapido.passenger"
            clean.contains("linkedin") -> "com.linkedin.android"
            clean.contains("twitter") || clean == "x" -> "com.twitter.android"
            clean.contains("youtube") -> "com.google.android.youtube"
            clean.contains("netflix") -> "com.netflix.mediaclient"
            clean.contains("chrome") -> "com.android.chrome"
            clean.contains("discord") -> "com.discord"
            clean.contains("reddit") -> "com.reddit.frontpage"
            clean.contains("pinterest") -> "com.pinterest"
            clean.contains("duolingo") -> "com.duolingo"
            clean.contains("truecaller") -> "com.truecaller"
            else -> null
        }
    }

    fun isScreenSharing(): Boolean = screenCaptureEngine != null

    fun startCameraVision(useFront: Boolean = false, previewTextureView: android.view.TextureView? = null) {
        try {
            stopScreenShare() // Screen share and camera vision are mutually exclusive

            if (cameraVisionEngine == null) {
                cameraVisionEngine = com.jarvis.assistant.vision.CameraVisionEngine(this) { jpegBytes ->
                    geminiLive?.sendVideoFrame(jpegBytes)
                }
            }
            cameraVisionEngine?.setPreviewTextureView(previewTextureView)
            cameraVisionEngine?.startCamera(useFront)
            ensureMicrophoneForegroundService()
            dispatchToListeners { it.onCameraVisionStateChanged(true, useFront) }

            // Gemini Live natural voice notification
            geminiLive?.sendText("[SYSTEM EVENT] Live camera vision has been activated. You are now seeing through the user's camera in real time. Please briefly confirm to the user in your natural voice that you can see.", turnComplete = true)
        } catch (e: Exception) {
            android.util.Log.e("JarvisVoiceService", "startCameraVision failed", e)
            stopCameraVision()
        }
    }

    fun updateCameraPreviewTarget(previewTextureView: android.view.TextureView?) {
        cameraVisionEngine?.setPreviewTextureView(previewTextureView)
    }

    fun switchCameraLens() {
        cameraVisionEngine?.let { engine ->
            if (engine.isCameraStreaming()) {
                val newLensFront = !engine.isFrontLens()
                engine.switchCamera()
                dispatchToListeners { it.onCameraVisionStateChanged(true, newLensFront) }
            }
        }
    }

    fun stopCameraVision() {
        val wasActive = cameraVisionEngine?.isCameraStreaming() == true
        cameraVisionEngine?.stopCamera()
        cameraVisionEngine = null
        ensureMicrophoneForegroundService()
        dispatchToListeners { it.onCameraVisionStateChanged(false, false) }
        if (wasActive) {
            geminiLive?.sendText("[SYSTEM COMMAND] Camera vision has been closed by the user. You are no longer receiving camera frames. If asked, inform the user that camera vision is currently off.")
        }
    }

    fun isCameraVisionActive(): Boolean = cameraVisionEngine?.isCameraStreaming() == true
    fun isCameraFrontLens(): Boolean = cameraVisionEngine?.isFrontLens() == true

    fun extractUrlsFromText(input: String): List<String> {
        val urls = mutableListOf<String>()
        val explicitUrlRegex = Regex("""https?://[^\s,"'<>]+""", RegexOption.IGNORE_CASE)
        for (m in explicitUrlRegex.findAll(input)) {
            val u = m.value.trimEnd('.', ',', ';', '!', '?', ')')
            if (u.isNotEmpty() && !urls.contains(u)) urls.add(u)
        }

        val domainRegex = Regex("""\b(?:www\.)?([a-zA-Z0-9-]+\.)+(com|in|org|net|io|co|dev|app|ai|gov|edu|me|tech|info|online|xyz|site|store|cc|tv|uk|us|ca|de|fr|jp|cn|biz)(/[^\s,"'<>]*)?\b""", RegexOption.IGNORE_CASE)
        for (m in domainRegex.findAll(input)) {
            val clean = m.value.trimEnd('.', ',', ';', '!', '?', ')')
            val full = if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) clean else "https://$clean"
            if (!urls.any { it.equals(full, ignoreCase = true) || it.contains(clean, ignoreCase = true) }) {
                urls.add(full)
            }
        }
        return urls
    }

    suspend fun openUrlsInChromeTabs(urls: List<String>): Boolean {
        if (urls.isEmpty()) return false
        var anyLaunched = false
        val baseTime = System.currentTimeMillis()

        for ((index, rawUrl) in urls.withIndex()) {
            val urlString = if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
                rawUrl
            } else {
                "https://$rawUrl"
            }
            val uri = Uri.parse(urlString)
            val tabAppId = "${packageName}_tab_${baseTime}_$index"

            val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, tabAppId)
                putExtra("create_new_tab", true)
                putExtra("com.android.browser.application_id", tabAppId)
            }

            val launched = com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(this@JarvisVoiceService, chromeIntent)
            if (launched) {
                anyLaunched = true
            } else {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, tabAppId)
                    putExtra("create_new_tab", true)
                }
                if (com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(this@JarvisVoiceService, fallbackIntent)) {
                    anyLaunched = true
                }
            }

            // Delay 400ms between multiple tabs so Chrome creates separate tab tasks
            if (urls.size > 1 && index < urls.size - 1) {
                delay(400L)
            }
        }
        return anyLaunched
    }

    private fun parseTargetUrl(query: String): String? {
        var clean = query.trim()
        val lower = clean.lowercase()

        // Strip common voice command prefixes
        val prefixes = listOf("open website", "open site", "visit website", "visit site", "open url", "navigate to", "visit", "open")
        for (prefix in prefixes) {
            if (lower.startsWith(prefix)) {
                val candidate = clean.substring(prefix.length).trim()
                if (candidate.isNotEmpty()) {
                    clean = candidate
                    break
                }
            }
        }

        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            return clean
        }

        val cleanLower = clean.lowercase()
        // Domain pattern check with common TLDs
        val domainRegex = Regex("""^([a-zA-Z0-9-]+\.)+(com|in|org|net|io|co|dev|app|ai|gov|edu|me|tech|info|online|xyz|site|store|cc|tv|uk|us|ca|de|fr|jp|cn|biz)(/.*)?$""")
        if (domainRegex.matches(cleanLower)) {
            return "https://$clean"
        }

        // General URL host pattern (e.g. www.something or host.ext)
        if (cleanLower.startsWith("www.") || (cleanLower.contains(".") && !cleanLower.contains(" ") && cleanLower.indexOf(".") < cleanLower.length - 2)) {
            return "https://$clean"
        }

        return null
    }

    /** Fully tears down the voice session and stops the service (e.g. user quit JARVIS entirely). */
    fun stopSession() {
        resetTurnState()
        stopScreenShare()
        stopCameraVision()
        releaseWakeLock()
        autoSleepJob?.cancel()
        idleCountdownJob?.cancel()
        activeFollowUpJob?.cancel()
        connectionHealthJob?.cancel()
        geminiLive?.disconnect()
        audioEngine?.release()
        toolScope.coroutineContext.cancelChildren()
        geminiLive = null
        audioEngine = null
        isSessionStarted = false
        conversationState = ConversationState.SLEEPING
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        exitStandbyListening()
        try { voskRecognizer?.close() } catch (_: Exception) {}
        voskRecognizer = null
        releaseWakeLock()
        unregisterPhoneCallReceiver()
        stopCameraVision()
        stopSession()
    }

    private var phoneCallReceiverRegistered = false
    private val phoneCallReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val stateStr = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)
                Log.d("JarvisVoiceService", "Phone call state changed: $stateStr")
                if (stateStr == android.telephony.TelephonyManager.EXTRA_STATE_IDLE) {
                    Log.d("JarvisVoiceService", "Phone call finished — unmuting mic.")
                    setMicMuted(false)
                    unregisterPhoneCallReceiver()
                }
            }
        }
    }

    fun monitorPhoneCallAndKeepQuiet() {
        setMicMuted(true)
        audioEngine?.clearPlaybackQueue()
        if (!phoneCallReceiverRegistered) {
            val filter = android.content.IntentFilter(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            registerReceiver(phoneCallReceiver, filter)
            phoneCallReceiverRegistered = true
        }
    }

    private fun unregisterPhoneCallReceiver() {
        if (phoneCallReceiverRegistered) {
            try { unregisterReceiver(phoneCallReceiver) } catch (_: Exception) {}
            phoneCallReceiverRegistered = false
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "JARVIS Voice", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps JARVIS listening while other apps are open"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    enum class ServiceNotificationState {
        STANDBY, // background
        LISTENING,
        SPEAKING,
        IDLE,
        THINKING
    }

    @Volatile
    private var currentNotifState = ServiceNotificationState.IDLE

    fun updateNotificationState(state: ServiceNotificationState) {
        if (currentNotifState == state) return
        currentNotifState = state
        updateNotification()
    }

    fun updateNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w("JarvisVoiceService", "Failed to update notification: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val isSleepingOrStandby = _isStandby.value || conversationState == ConversationState.SLEEPING || currentNotifState == ServiceNotificationState.STANDBY

        val (title, text) = when {
            isSleepingOrStandby -> {
                "JARVIS is in background" to "If you want, call: \"Hey Jarvis\" or \"Jarvis wake up\""
            }
            currentNotifState == ServiceNotificationState.SPEAKING -> {
                "JARVIS is speaking" to "Playing voice response • Tap to open JARVIS"
            }
            currentNotifState == ServiceNotificationState.THINKING -> {
                "JARVIS is thinking" to "Processing request • Tap to open JARVIS"
            }
            currentNotifState == ServiceNotificationState.IDLE -> {
                "JARVIS is idle" to "Standing by in active session • Say \"Jarvis\" or tap to speak"
            }
            else -> {
                "JARVIS is listening" to "Listening for your voice • Tap to return to JARVIS"
            }
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_jarvis_notif)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
