package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Procedural, ultra-low latency sound effect generator for JARVIS.
 * Synthesizes high-tech sci-fi earcons (wake & standby) directly into 16-bit PCM,
 * completely eliminating external audio file dependencies and load latency.
 */
object JarvisSoundEffects {
    private const val TAG = "JarvisSoundEffects"
    private const val SAMPLE_RATE = 44100

    private val wakeSoundPcm: ByteArray by lazy { generateWakeSound() }
    private val standbySoundPcm: ByteArray by lazy { generateStandbySound() }

    /**
     * Futuristic ascending dual-tone chime when JARVIS wakes up or comes out of background.
     */
    fun playWakeSound() {
        playPcm(wakeSoundPcm)
    }

    /**
     * Sleek descending harmonic earcon when JARVIS enters standby / goes to background.
     */
    fun playStandbySound() {
        playPcm(standbySoundPcm)
    }

    private fun playPcm(pcmData: ByteArray) {
        Thread {
            try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val format = AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()

                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(pcmData.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcmData, 0, pcmData.size)
                track.play()

                // Calculate duration to allow AudioTrack to complete playback before cleanup
                val durationMs = (pcmData.size / 2 * 1000L) / SAMPLE_RATE
                Thread.sleep(durationMs + 60)
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Error playing sound effect: ${e.message}")
            }
        }.start()
    }

    /**
     * Synthesizes a crisp, two-tone ascending futuristic chime:
     * - Note 1: 587.33 Hz (D5) with 1174.66 Hz overtone for ~90ms
     * - Silence: 20ms
     * - Note 2: 880.0 Hz (A5) with 1760.0 Hz & 2640.0 Hz overtones for ~160ms with smooth decay
     */
    private fun generateWakeSound(): ByteArray {
        val totalDurationMs = 270
        val totalSamples = (SAMPLE_RATE * totalDurationMs) / 1000
        val buffer = ByteArray(totalSamples * 2)

        val note1Samples = (SAMPLE_RATE * 90) / 1000
        val silenceSamples = (SAMPLE_RATE * 20) / 1000
        val note2Start = note1Samples + silenceSamples

        var idx = 0
        for (i in 0 until totalSamples) {
            val sampleVal: Short = when {
                i < note1Samples -> {
                    // Note 1: 587.33 Hz (D5)
                    val t = i.toDouble() / SAMPLE_RATE
                    val attack = (i.toDouble() / ((SAMPLE_RATE * 8) / 1000)).coerceAtMost(1.0)
                    val decay = ((note1Samples - i).toDouble() / ((SAMPLE_RATE * 30) / 1000)).coerceIn(0.0, 1.0)
                    val env = attack * decay
                    val wave = sin(2 * PI * 587.33 * t) + 0.3 * sin(2 * PI * 1174.66 * t)
                    (wave * env * 22000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
                i < note2Start -> 0.toShort()
                else -> {
                    // Note 2: 880.0 Hz (A5)
                    val sampleInNote = i - note2Start
                    val note2Total = totalSamples - note2Start
                    val t = sampleInNote.toDouble() / SAMPLE_RATE
                    val attack = (sampleInNote.toDouble() / ((SAMPLE_RATE * 6) / 1000)).coerceAtMost(1.0)
                    val decay = ((note2Total - sampleInNote).toDouble() / (note2Total * 0.75)).coerceIn(0.0, 1.0)
                    val env = attack * decay * decay // parabolic decay for pleasant ring-out
                    val wave = sin(2 * PI * 880.0 * t) + 0.35 * sin(2 * PI * 1760.0 * t) + 0.15 * sin(2 * PI * 2640.0 * t)
                    (wave * env * 25000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
            }

            buffer[idx++] = (sampleVal.toInt() and 0xFF).toByte()
            buffer[idx++] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }

        return buffer
    }

    /**
     * Synthesizes a smooth, two-tone descending futuristic standby earcon:
     * - Note 1: 880.0 Hz (A5) with 1760.0 Hz overtone for ~80ms
     * - Silence: 20ms
     * - Note 2: 587.33 Hz (D5) with gentle fading decay for ~150ms
     */
    private fun generateStandbySound(): ByteArray {
        val totalDurationMs = 250
        val totalSamples = (SAMPLE_RATE * totalDurationMs) / 1000
        val buffer = ByteArray(totalSamples * 2)

        val note1Samples = (SAMPLE_RATE * 80) / 1000
        val silenceSamples = (SAMPLE_RATE * 20) / 1000
        val note2Start = note1Samples + silenceSamples

        var idx = 0
        for (i in 0 until totalSamples) {
            val sampleVal: Short = when {
                i < note1Samples -> {
                    // Note 1: 880.0 Hz (A5)
                    val t = i.toDouble() / SAMPLE_RATE
                    val attack = (i.toDouble() / ((SAMPLE_RATE * 6) / 1000)).coerceAtMost(1.0)
                    val decay = ((note1Samples - i).toDouble() / ((SAMPLE_RATE * 35) / 1000)).coerceIn(0.0, 1.0)
                    val env = attack * decay
                    val wave = sin(2 * PI * 880.0 * t) + 0.25 * sin(2 * PI * 1760.0 * t)
                    (wave * env * 20000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
                i < note2Start -> 0.toShort()
                else -> {
                    // Note 2: 587.33 Hz (D5)
                    val sampleInNote = i - note2Start
                    val note2Total = totalSamples - note2Start
                    val t = sampleInNote.toDouble() / SAMPLE_RATE
                    val attack = (sampleInNote.toDouble() / ((SAMPLE_RATE * 6) / 1000)).coerceAtMost(1.0)
                    val decay = ((note2Total - sampleInNote).toDouble() / (note2Total * 0.7)).coerceIn(0.0, 1.0)
                    val env = attack * decay * decay
                    val wave = sin(2 * PI * 587.33 * t) + 0.2 * sin(2 * PI * 1174.66 * t)
                    (wave * env * 21000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
            }

            buffer[idx++] = (sampleVal.toInt() and 0xFF).toByte()
            buffer[idx++] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }

        return buffer
    }
}
