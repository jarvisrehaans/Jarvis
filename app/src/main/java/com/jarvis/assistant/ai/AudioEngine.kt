package com.jarvis.assistant.ai
import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Handles mic capture (AudioRecord, 16kHz mono PCM16) and
 * speaker playback (AudioTrack, 24kHz mono PCM16) — mirrors the
 * Python `sounddevice` reference pipeline.
 */
class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        const val MIC_SAMPLE_RATE = 16000
        const val SPEAKER_SAMPLE_RATE = 24000
        const val CHUNK_SIZE = 1280 // 1280 bytes = 640 samples = 40ms of 16kHz mono audio

        // Jitter cushion pre-buffering: 4800 bytes = ~100ms of 24kHz 16-bit mono audio.
        // Buffers an initial 100ms cushion so network bursts/jitter never cause AudioTrack underruns.
        private const val PREBUFFER_BYTES = 4800

        // Debounce before declaring speech finished: 400ms absorbs normal network jitter
        // gaps between Gemini WebSocket audio chunks without falsely chopping AudioTrack mid-sentence.
        private const val SPEAK_STOP_DEBOUNCE_MS = 400L

        // Post-speech echo guard: 120ms (3 chunks) cleanly absorbs the room reverberation
        // without eating the user's first words when they reply.
        private const val ECHO_COOLDOWN_MS = 120L

        // Grace period at the beginning of speech: 800ms protects turn onset from initial
        // speaker attack while allowing fast user interruption.
        private const val INITIAL_BARGE_IN_GRACE_MS = 800L

        // Minimum viable audio chunk size: skip padding/header-only chunks from Gemini.
        private const val MIN_PLAYABLE_CHUNK_BYTES = 100

        // Barge-In thresholds (40ms chunks):
        // On loudspeaker, speaker audio feedback can peak around 0.25-0.32. Setting threshold to 0.40f
        // and 5 chunks (200ms) prevents speaker self-interruption while allowing genuine intentional user barge-in.
        private const val BARGE_IN_RMS_HEADSET = 0.08f
        private const val BARGE_IN_CHUNKS_HEADSET = 2
        private const val BARGE_IN_RMS_SPEAKER = 0.40f
        private const val BARGE_IN_CHUNKS_SPEAKER = 5
        // Deep queue headroom so network bursts from Gemini are never dropped
        private const val MAX_QUEUE_CHUNKS = 200
    }

    var onAudioChunkCaptured: ((ByteArray) -> Unit)? = null
    var onAmplitudeChanged: ((Float) -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null
    var onInterruptTriggered: (() -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var isRecording = false
    private var isMuted = false
    @Volatile private var isSpeaking = false
    @Volatile private var isExternalSpeaking = false
    @Volatile private var isStreamingPaused = false
    @Volatile private var speakingStartTimeMs = 0L
    @Volatile private var lastSpeakingEndTimeMs = 0L
    @Volatile private var lastQueuedTimeMs = 0L
    @Volatile private var lastChunkPlayedTimeMs = 0L

    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null
    private var playbackJob: Job? = null

    private var externalSpeakingTimeoutJob: Job? = null

    /** True while JARVIS's audio is playing — used to suppress mic echo. */
    fun isCurrentlySpeaking() = isSpeaking || isExternalSpeaking

    fun isRecording(): Boolean = isRecording

    fun hasQueuedAudio(): Boolean = !playbackQueue.isEmpty()

    fun setExternalSpeaking(speaking: Boolean) {
        if (speaking) {
            speakingStartTimeMs = System.currentTimeMillis()
        } else if (isExternalSpeaking) {
            lastSpeakingEndTimeMs = System.currentTimeMillis()
        }
        isExternalSpeaking = speaking
        externalSpeakingTimeoutJob?.cancel()
        if (speaking) {
            externalSpeakingTimeoutJob = engineScope.launch {
                delay(4000L)
                if (isExternalSpeaking) {
                    Log.w(TAG, "Watchdog: auto-resetting isExternalSpeaking to false")
                    isExternalSpeaking = false
                    lastSpeakingEndTimeMs = System.currentTimeMillis()
                }
            }
        }
    }

    fun setStreamingPaused(paused: Boolean) {
        isStreamingPaused = paused
    }

    /**
     * Forces audio routing through the device's main loudspeaker (built-in speaker),
     * preventing voice from playing out of the telephony earpiece / calling speaker.
     */
    fun routeToSpeaker() {
        try {
            audioManager?.let { am ->
                am.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val speaker = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        am.setCommunicationDevice(speaker)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to route audio to loudspeaker: ${e.message}")
        }
    }

    private var audioFocusRequest: AudioFocusRequest? = null

    fun requestPlaybackAudioFocus() {
        try {
            val am = audioManager ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener { /* Focus changes handled gracefully */ }
                        .build()
                }
                audioFocusRequest?.let { am.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus: ${e.message}")
        }
    }

    fun abandonPlaybackAudioFocus() {
        try {
            val am = audioManager ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon audio focus: ${e.message}")
        }
    }

    private var lastHeadsetCheckTime = 0L
    private var cachedHeadsetConnected = false

    fun isHeadsetConnected(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHeadsetCheckTime > 2000L) {
            lastHeadsetCheckTime = now
            cachedHeadsetConnected = try {
                val am = audioManager ?: false
                if (am is AudioManager) {
                    val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    devices.any { device ->
                        device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                } else false
            } catch (_: Exception) {
                false
            }
        }
        return cachedHeadsetConnected
    }

    @SuppressLint("MissingPermission")
    private fun createAndConfigureAudioRecord(): AudioRecord? {
        val minBufSize = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Two 20ms packets keep capture latency low while leaving enough room for the
        // recorder's hardware callback. This matches the low-latency reference pipeline.
        val bufferSize = maxOf(minBufSize, CHUNK_SIZE * 2)

        // VOICE_COMMUNICATION opts into Android's real-time voice path (AEC/NS and lower
        // capture buffering). Fall back for devices whose vendor implementation is unreliable.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )
        var record: AudioRecord? = null
        for (src in sources) {
            try {
                val candidate = AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(MIC_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    record = candidate
                    Log.d(TAG, "AudioRecord initialized successfully with audio source $src")
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed for source $src: ${e.message}")
            }
        }

        if (record == null) {
            Log.e(TAG, "AudioRecord failed to initialize with all candidate sources")
            return null
        }

        val sessionId = record.audioSessionId
        if (sessionId != 0) {
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enable AcousticEchoCanceler: ${e.message}")
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enable NoiseSuppressor: ${e.message}")
                }
            }
        }

        return record
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun restartAudioRecordInternal() {
        if (!isRecording) return
        Log.d(TAG, "Watchdog auto-restarting AudioRecord to restore background mic...")
        try {
            aec?.release()
            ns?.release()
        } catch (_: Exception) {}
        aec = null
        ns = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        audioRecord = createAndConfigureAudioRecord()
        try {
            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord successfully restarted and resumed recording")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startRecording after watchdog restart: ${e.message}")
        }
    }

    /**
     * Re-initializes AudioRecord when waking up from long background standby.
     * Clears any hardware buffer drift or audio clock stalls caused by background media (YouTube/Reels).
     */
    fun refreshAudioRecordOnWake() {
        Log.i(TAG, "refreshAudioRecordOnWake: Re-initializing AudioRecord after background standby transition...")
        restartAudioRecordInternal()
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording && recordJob?.isActive == true && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) return

        routeToSpeaker()

        audioRecord = createAndConfigureAudioRecord()
        if (audioRecord == null) {
            Log.e(TAG, "AudioRecord could not be initialized — cannot start recording")
            return
        }

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startRecording: ${e.message}")
            return
        }
        isRecording = true

        recordJob?.cancel()
        recordJob = engineScope.launch {
            val buffer = ByteArray(CHUNK_SIZE)
            var consecutiveSpeechChunks = 0
            var consecutiveErrors = 0
            while (isActive && isRecording) {
                try {
                    val currentRecord = audioRecord
                    if (currentRecord == null || currentRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        consecutiveErrors++
                        if (consecutiveErrors % 20 == 0) {
                            Log.w(TAG, "AudioRecord not recording (state: ${currentRecord?.recordingState}), ensuring recording state...")
                        }
                        try {
                            currentRecord?.startRecording()
                        } catch (_: Exception) {}
                        if (consecutiveErrors >= 200) { // ~5 seconds of true persistent uninitialized state
                            consecutiveErrors = 0
                            restartAudioRecordInternal()
                        }
                        delay(25)
                        continue
                    }

                    val read = try {
                        currentRecord.read(buffer, 0, CHUNK_SIZE)
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception reading AudioRecord: ${e.message}")
                        -1
                    }

                    if (read > 0) {
                        consecutiveErrors = 0
                        val chunk = buffer.copyOf(read)
                        if (!isMuted) {
                            val rms = calculateRms(chunk)
                            val now = System.currentTimeMillis()

                            // Auto-heal stuck isSpeaking state only if completely silent and deadlocked for >2.5s
                            val sinceQueued = if (lastQueuedTimeMs > 0L) (now - lastQueuedTimeMs) else Long.MAX_VALUE
                            val sincePlayed = if (lastChunkPlayedTimeMs > 0L) (now - lastChunkPlayedTimeMs) else Long.MAX_VALUE
                            val sinceStarted = if (speakingStartTimeMs > 0L) (now - speakingStartTimeMs) else Long.MAX_VALUE
                            if (isSpeaking && sinceQueued > 2500L && sincePlayed > 2500L && sinceStarted > 2500L) {
                                Log.w(TAG, "Watchdog: clearing stuck isSpeaking flag (no activity for ${sincePlayed}ms, qSize=${playbackQueue.size}) to restore mic streaming")
                                playbackQueue.clear()
                                isSpeaking = false
                                isStreamingPaused = false
                                lastSpeakingEndTimeMs = now
                                abandonPlaybackAudioFocus()
                                engineScope.launch(Dispatchers.Main) {
                                    onSpeakingStopped?.invoke()
                                }
                            }

                            // Headset-aware Voice Interruption / Barge-In Detection
                            if (isSpeaking || isExternalSpeaking) {
                                val isHeadset = isHeadsetConnected()
                                val speechDuration = now - speakingStartTimeMs
                                // Enforce grace period at the beginning of speech to prevent turn-start cutoffs
                                val canBargeIn = isHeadset || (speechDuration >= INITIAL_BARGE_IN_GRACE_MS)

                                if (canBargeIn) {
                                    val threshold = if (isHeadset) BARGE_IN_RMS_HEADSET else BARGE_IN_RMS_SPEAKER
                                    val requiredChunks = if (isHeadset) BARGE_IN_CHUNKS_HEADSET else BARGE_IN_CHUNKS_SPEAKER

                                    if (rms >= threshold) {
                                        consecutiveSpeechChunks++
                                        if (consecutiveSpeechChunks >= requiredChunks) {
                                            consecutiveSpeechChunks = 0
                                            Log.d(TAG, "User voice interruption detected (RMS: $rms, headset=$isHeadset), stopping speech")
                                            clearPlaybackQueue()
                                            onInterruptTriggered?.invoke()
                                        }
                                    } else {
                                        consecutiveSpeechChunks = 0
                                    }
                                } else {
                                    consecutiveSpeechChunks = 0
                                }
                            } else {
                                consecutiveSpeechChunks = 0
                            }

                            // Orb visual feedback: Always dispatch user voice amplitude to orb when Jarvis is not speaking!
                            if (!isSpeaking && !isExternalSpeaking) {
                                onAmplitudeChanged?.invoke(rms)
                            }

                            // Strict speech state rule & Post-Speech Echo Guard for sending audio to Gemini:
                            val inEchoCooldown = (now - lastSpeakingEndTimeMs) < ECHO_COOLDOWN_MS
                            if (!isSpeaking && !isExternalSpeaking && !inEchoCooldown && !isStreamingPaused) {
                                onAudioChunkCaptured?.invoke(chunk)
                            }
                        }
                    } else {
                        // When read <= 0 (e.g. system ducking, brief background stall, or 0 bytes ready in buffer)
                        // Never tear down AudioRecord — tearing it down triggers the Android mic indicator to blink on and off.
                        // Simply wait and retry smoothly.
                        if (!isSpeaking && !isExternalSpeaking) {
                            consecutiveErrors++
                            if (consecutiveErrors % 150 == 0) {
                                Log.w(TAG, "AudioRecord read returned non-positive code: $read ($consecutiveErrors times)")
                            }
                        }
                        delay(20)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in recordJob loop: ${e.message}", e)
                    delay(20)
                }
            }
        }
    }

    fun stopRecording() {
        isRecording = false
        recordJob?.cancel()
        try {
            aec?.release()
            ns?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audiofx: ${e.message}")
        }
        aec = null
        ns = null

        audioRecord?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
            }
        }
        audioRecord = null
    }

    private val trackLock = Any()

    private fun createAudioTrack(): AudioTrack? {
        return try {
            val minBufSize = AudioTrack.getMinBufferSize(
                SPEAKER_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SPEAKER_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            // 48000 bytes = 1.0 second of buffer at 24kHz 16-bit mono.
            // Generous buffer headroom prevents hardware underruns, clicks, or voice stutter during network jitter
            val trackBufferBytes = maxOf(minBufSize * 6, 48000)

            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(trackBufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().apply { play() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack: ${e.message}", e)
            null
        }
    }

    private fun recreateAudioTrack() {
        synchronized(trackLock) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) {}
            audioTrack = createAudioTrack()
            Log.d(TAG, "AudioTrack recreated and ready for playback recovery.")
        }
    }

    fun startPlayback() {
        routeToSpeaker()
        synchronized(trackLock) {
            if (playbackJob?.isActive == true && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.play()
                    }
                } catch (_: Exception) {}
                return
            }
            playbackJob?.cancel()
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) {}
            audioTrack = createAudioTrack()
        }

        playbackJob = engineScope.launch(Dispatchers.IO) {
            var silenceSinceMs = 0L

            while (isActive) {
                try {
                    val chunk = playbackQueue.poll()
                    if (chunk != null) {
                        silenceSinceMs = 0L
                        if (!isSpeaking) {
                            isSpeaking = true
                            speakingStartTimeMs = System.currentTimeMillis()
                            requestPlaybackAudioFocus()
                            synchronized(trackLock) {
                                try {
                                    if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                                        recreateAudioTrack()
                                    } else {
                                        if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                            audioTrack?.play()
                                        }
                                    }
                                } catch (_: Exception) {
                                    recreateAudioTrack()
                                }
                            }
                            try { withContext(Dispatchers.Main) { onSpeakingStarted?.invoke() } } catch (_: Exception) {}
                        }

                        synchronized(trackLock) {
                            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                                recreateAudioTrack()
                            }
                            try {
                                if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    audioTrack?.play()
                                }
                                val written = audioTrack?.write(chunk, 0, chunk.size) ?: -1
                                if (written < 0) {
                                    Log.w(TAG, "AudioTrack write returned $written, recreating AudioTrack...")
                                    recreateAudioTrack()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "AudioTrack write exception: ${e.message}")
                                recreateAudioTrack()
                            }
                            lastChunkPlayedTimeMs = System.currentTimeMillis()
                        }

                        try { onAmplitudeChanged?.invoke(calculateRms(chunk)) } catch (_: Exception) {}
                    } else {
                        if (isSpeaking) {
                            // Start debounce timer instead of immediately stopping
                            if (silenceSinceMs == 0L) {
                                silenceSinceMs = System.currentTimeMillis()
                            }
                            val elapsed = System.currentTimeMillis() - silenceSinceMs
                            if (elapsed >= SPEAK_STOP_DEBOUNCE_MS && playbackQueue.isEmpty()) {
                                isSpeaking = false
                                lastSpeakingEndTimeMs = System.currentTimeMillis()
                                silenceSinceMs = 0L
                                abandonPlaybackAudioFocus()
                                Log.i(TAG, "Audio playback turn finished naturally, mic unblocked")
                                try { withContext(Dispatchers.Main) { onSpeakingStopped?.invoke() } } catch (_: Exception) {}
                            }
                        }
                        delay(12)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in playbackJob loop: ${e.message}", e)
                    delay(20)
                }
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        playbackQueue.clear()
        synchronized(trackLock) {
            audioTrack?.let {
                try {
                    it.stop()
                    it.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
                }
            }
            audioTrack = null
        }
        isSpeaking = false
        isStreamingPaused = false
        lastSpeakingEndTimeMs = System.currentTimeMillis()
        abandonPlaybackAudioFocus()
        engineScope.launch(Dispatchers.Main) {
            onSpeakingStopped?.invoke()
        }
    }

    /** Queue a chunk of 24kHz PCM16 audio received from Gemini for playback. */
    fun queueAudio(pcmBytes: ByteArray) {
        // Skip tiny header/padding chunks (e.g. 2 bytes) that Gemini sends before real audio.
        // These have zero audible content and would falsely trigger isSpeaking, causing the
        // debounce to fire on an empty queue and prematurely unmute the mic.
        if (pcmBytes.size < MIN_PLAYABLE_CHUNK_BYTES) {
            Log.d(TAG, "Skipping tiny audio chunk (${pcmBytes.size} bytes) — not playable")
            return
        }
        if (playbackJob?.isActive != true) {
            startPlayback()
        }
        // Immediately silence mic streaming before speaker output begins
        val now = System.currentTimeMillis()
        if (!isSpeaking) {
            speakingStartTimeMs = now
            lastChunkPlayedTimeMs = now
            Log.i(TAG, "Audio playback starting: received first real chunk (${pcmBytes.size} bytes)")
        }
        isSpeaking = true
        lastQueuedTimeMs = now
        while (playbackQueue.size >= MAX_QUEUE_CHUNKS) {
            playbackQueue.poll() // drop oldest chunk if buffer is bloated to prevent lag & voice freeze
        }
        playbackQueue.offer(pcmBytes)
    }

    fun clearPlaybackQueue() {
        playbackQueue.clear()
        synchronized(trackLock) {
            try {
                val track = audioTrack
                if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error flushing AudioTrack: ${e.message}")
            }
            Unit
        }
        isSpeaking = false
        isExternalSpeaking = false
        isStreamingPaused = false
        lastSpeakingEndTimeMs = System.currentTimeMillis()
        speakingStartTimeMs = 0L
        lastChunkPlayedTimeMs = 0L
        lastQueuedTimeMs = 0L
        externalSpeakingTimeoutJob?.cancel()
        abandonPlaybackAudioFocus()
        // Thread-safe: dispatch to main thread since callers may be on any thread
        engineScope.launch(Dispatchers.Main) {
            onSpeakingStopped?.invoke()
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun isMuted(): Boolean = isMuted

    /**
     * Pauses mic audio streaming flag if needed. AudioRecord stays smoothly active
     * under the Foreground Service so the Android mic indicator never flashes on and off.
     */
    fun pauseStreaming() {
        isStreamingPaused = false
        Log.d(TAG, "Mic streaming kept smoothly active (no mic on/off cycling)")
    }

    fun resumeStreaming() {
        isStreamingPaused = false
        Log.d(TAG, "Mic streaming active")
    }

    fun isStreamingPaused(): Boolean = isStreamingPaused

    fun release() {
        stopRecording()
        stopPlayback()
        engineScope.cancel()
    }

    fun calculateRms(chunk: ByteArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        var i = 0
        while (i < chunk.size - 1) {
            val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort()
            sum += sample * sample
            i += 2
        }
        val samples = chunk.size / 2
        if (samples == 0) return 0f
        val rms = sqrt(sum / samples)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}
