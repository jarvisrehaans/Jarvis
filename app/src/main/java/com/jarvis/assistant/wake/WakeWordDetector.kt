package com.jarvis.assistant.wake

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Always-on, local "is someone talking to JARVIS?" gate.
 *
 * Runs Android's on-device SpeechRecognizer for fast (<500ms),
 * low-power wake word spotting. Supports English, Hindi, and Urdu wake variations.
 *
 * Reliability features:
 * - 30s silence auto-restart: if no results/errors for 30s, force-restart (Android silently kills recognizer)
 * - 60s full teardown watchdog: if no callbacks at all for 60s, destroy and recreate SpeechRecognizer
 * - Exponential backoff for error restarts: 250ms → 500ms → 1s → 2s max to prevent tight error loops
 * - Concurrent instance prevention: only one SpeechRecognizer at any time
 */
class WakeWordDetector(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val WAKE_LANGUAGE = "en-IN"
        private val WAKE_PHRASES = listOf(
            "hey jarvis", "hello jarvis", "hi jarvis", "wake jarvis", "wake up jarvis",
            "हे जार्विस", "हेलो जार्विस", "हाय जार्विस", "जागो जार्विस", "वेक अप जार्विस"
        )
        private const val RESTART_DELAY_BASE_MS = 250L
        private const val RESTART_DELAY_MAX_MS = 2000L
        private const val SILENCE_WATCHDOG_MS = 30_000L  // Force-restart if no results for 30s
        private const val FULL_TEARDOWN_WATCHDOG_MS = 60_000L  // Full teardown if no callbacks at all for 60s

        /** Unified text normalizer: lowercase, strip punctuation, trim, collapse extra whitespace. */
        fun normalizeText(input: String): String {
            return input.lowercase()
                .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    /** Fired on the main thread the moment a wake phrase is heard. */
    var onWakeWordDetected: (() -> Unit)? = null

    /** When true (e.g. JARVIS speaker or TTS is actively playing), detection is suppressed to prevent self-trigger. */
    @Volatile var isEchoSuppressed: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** True while we want to be listening. */
    private var wantsToListen = false

    /** Tracks consecutive error restarts for exponential backoff. */
    private var consecutiveErrors = 0

    /** Timestamp of last callback of any kind (results, partial, error). */
    @Volatile private var lastCallbackTimeMs = 0L

    /** Timestamp of last result/partial result callback. */
    @Volatile private var lastResultTimeMs = 0L

    /** Prevents concurrent SpeechRecognizer instances. */
    private var isCreatingRecognizer = false

    private var silenceWatchdogRunnable: Runnable? = null
    private var fullTeardownWatchdogRunnable: Runnable? = null

    fun start() {
        if (wantsToListen) return
        wantsToListen = true
        consecutiveErrors = 0
        mainHandler.post { beginListening() }
    }

    fun stop() {
        if (!wantsToListen && recognizer == null) return
        wantsToListen = false
        cancelWatchdogs()
        mainHandler.post {
            isCreatingRecognizer = false
            recognizer?.setRecognitionListener(null)
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    fun isListening(): Boolean = wantsToListen

    private fun beginListening() {
        if (!wantsToListen) return
        if (isCreatingRecognizer) return  // Guard: prevent concurrent instances

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition isn't available on this device — wake word disabled.")
            return
        }

        isCreatingRecognizer = true

        // Teardown any existing recognizer first
        try {
            recognizer?.setRecognitionListener(null)
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null

        val now = System.currentTimeMillis()
        lastCallbackTimeMs = now
        lastResultTimeMs = now

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    lastCallbackTimeMs = System.currentTimeMillis()
                    lastResultTimeMs = lastCallbackTimeMs
                    consecutiveErrors = 0  // Successful cycle resets backoff
                    if (!containsWakeWord(results)) restartIfNeeded()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    lastCallbackTimeMs = System.currentTimeMillis()
                    lastResultTimeMs = lastCallbackTimeMs
                    // Do NOT trigger on partial results in background standby.
                    // SpeechRecognizer partial results are interim guesses that cause false wakeups on ambient speech.
                }

                override fun onError(error: Int) {
                    lastCallbackTimeMs = System.currentTimeMillis()
                    Log.w(TAG, "SpeechRecognizer onError: code=$error")
                    // Errors 6 (SPEECH_TIMEOUT) and 7 (NO_MATCH) are normal silence/turn-completion events in ambient listening
                    if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                        consecutiveErrors = 0
                        restartIfNeeded()
                    } else {
                        consecutiveErrors++
                        try {
                            recognizer?.setRecognitionListener(null)
                            recognizer?.cancel()
                            recognizer?.destroy()
                        } catch (_: Exception) {}
                        recognizer = null
                        restartWithBackoff()
                    }
                }

                override fun onEndOfSpeech() {
                    lastCallbackTimeMs = System.currentTimeMillis()
                }
                override fun onReadyForSpeech(params: Bundle?) {
                    lastCallbackTimeMs = System.currentTimeMillis()
                    Log.d(TAG, "SpeechRecognizer ready for speech.")
                }
                override fun onBeginningOfSpeech() {
                    lastCallbackTimeMs = System.currentTimeMillis()
                }
                override fun onRmsChanged(rmsdB: Float) {
                    val now = System.currentTimeMillis()
                    if (now - lastCallbackTimeMs > 5000L) {
                        lastCallbackTimeMs = now
                    }
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        isCreatingRecognizer = false

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, WAKE_LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            recognizer?.startListening(intent)
            startWatchdogs()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake-word listening: ${e.message}")
            consecutiveErrors++
            restartWithBackoff()
        }
    }

    /** Returns true (and fires the callback, stopping further restarts) if a wake phrase is present. */
    private fun containsWakeWord(bundle: Bundle?): Boolean {
        if (isEchoSuppressed) {
            return false // Muted while JARVIS speaker is talking
        }

        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        for (candidate in matches) {
            val normalized = normalizeText(candidate)
            if (normalized.isEmpty() && candidate.isEmpty()) continue

            val hasWake = WAKE_PHRASES.any { normalized.contains(it) } ||
                    candidate.contains("जार्विस सुनो") || candidate.contains("जागो जार्विस")

            if (hasWake) {
                Log.d(TAG, "Wake word triggered from: \"$candidate\" (normalized: \"$normalized\")")
                wantsToListen = false
                cancelWatchdogs()
                try {
                    recognizer?.setRecognitionListener(null)
                    recognizer?.cancel()
                    recognizer?.destroy()
                } catch (_: Exception) {}
                recognizer = null
                onWakeWordDetected?.invoke()
                return true
            }
        }
        return false
    }

    /** Standard restart with reset backoff (used after successful result cycles). */
    private fun restartIfNeeded() {
        if (!wantsToListen) return
        mainHandler.postDelayed({ beginListening() }, RESTART_DELAY_BASE_MS)
    }

    /** Restart with exponential backoff (used after errors). */
    private fun restartWithBackoff() {
        if (!wantsToListen) return
        val delay = (RESTART_DELAY_BASE_MS * (1L shl consecutiveErrors.coerceAtMost(3)))
            .coerceAtMost(RESTART_DELAY_MAX_MS)
        Log.d(TAG, "Restarting after error (attempt $consecutiveErrors, delay ${delay}ms)")
        mainHandler.postDelayed({ beginListening() }, delay)
    }

    private fun startWatchdogs() {
        cancelWatchdogs()

        // Silence watchdog: if no results for 30s, force restart
        silenceWatchdogRunnable = Runnable {
            if (!wantsToListen) return@Runnable
            val silenceDuration = System.currentTimeMillis() - lastResultTimeMs
            if (silenceDuration >= SILENCE_WATCHDOG_MS) {
                Log.w(TAG, "Silence watchdog: no results for ${silenceDuration / 1000}s — force restarting recognizer")
                consecutiveErrors = 0
                beginListening()
            } else {
                // Reschedule
                silenceWatchdogRunnable?.let { mainHandler.postDelayed(it, SILENCE_WATCHDOG_MS) }
            }
        }
        mainHandler.postDelayed(silenceWatchdogRunnable!!, SILENCE_WATCHDOG_MS)

        // Full teardown watchdog: if no callbacks at all for 60s, full teardown
        fullTeardownWatchdogRunnable = Runnable {
            if (!wantsToListen) return@Runnable
            val callbackAge = System.currentTimeMillis() - lastCallbackTimeMs
            if (callbackAge >= FULL_TEARDOWN_WATCHDOG_MS) {
                Log.e(TAG, "Full teardown watchdog: no callbacks for ${callbackAge / 1000}s — destroying and recreating SpeechRecognizer")
                consecutiveErrors = 0
                mainHandler.post {
                    try {
                        recognizer?.setRecognitionListener(null)
                        recognizer?.cancel()
                        recognizer?.destroy()
                    } catch (_: Exception) {}
                    recognizer = null
                    isCreatingRecognizer = false
                    beginListening()
                }
            } else {
                // Reschedule
                fullTeardownWatchdogRunnable?.let { mainHandler.postDelayed(it, FULL_TEARDOWN_WATCHDOG_MS) }
            }
        }
        mainHandler.postDelayed(fullTeardownWatchdogRunnable!!, FULL_TEARDOWN_WATCHDOG_MS)
    }

    private fun cancelWatchdogs() {
        silenceWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        fullTeardownWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceWatchdogRunnable = null
        fullTeardownWatchdogRunnable = null
    }
}

