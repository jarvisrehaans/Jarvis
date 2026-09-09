package com.jarvis.assistant.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.jarvis.assistant.vision.CameraVisionEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Handles immediate direct photo capture (front selfie camera vs back camera)
 * and saves captured images directly to the user's Gallery.
 */
object CameraManagerHelper {
    private const val TAG = "CameraManagerHelper"

    /**
     * Instantly captures a photo from back camera or front selfie camera without countdown
     * and saves it to device Gallery storage.
     */
    suspend fun captureDirectPhoto(context: Context, mode: String): Pair<Boolean, String> {
        val targetMode = mode.lowercase().trim()
        val isSelfie = targetMode.contains("selfie") || targetMode.contains("front")

        val captureDeferred = CompletableDeferred<ByteArray?>()
        var visionEngine: CameraVisionEngine? = null

        var frameCount = 0
        val warmupFrames = 5 // Allow 5 warmup frames (~500ms) for lens AF to lock and AE to settle

        try {
            visionEngine = CameraVisionEngine(context, isPhotoCaptureMode = true) { jpegBytes ->
                frameCount++
                if (frameCount >= warmupFrames && !captureDeferred.isCompleted && jpegBytes.isNotEmpty()) {
                    captureDeferred.complete(jpegBytes)
                }
            }

            visionEngine.startCamera(useFront = isSelfie)

            val capturedBytes = withTimeoutOrNull(5000L) {
                captureDeferred.await()
            }

            visionEngine.stopCamera()

            if (capturedBytes == null || capturedBytes.isEmpty()) {
                return Pair(false, "Could not capture frame from ${if (isSelfie) "front selfie" else "back"} camera.")
            }

            // Save to Pictures/Jarvis_Photos/
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val jarvisPhotosDir = File(picturesDir, "Jarvis_Photos")
            if (!jarvisPhotosDir.exists()) {
                jarvisPhotosDir.mkdirs()
            }

            val lensTag = if (isSelfie) "Selfie" else "Back"
            val timestamp = System.currentTimeMillis()
            val photoFile = File(jarvisPhotosDir, "JARVIS_${lensTag}_$timestamp.jpg")

            photoFile.writeBytes(capturedBytes)

            // Register with system MediaStore so photo instantly appears in Gallery app
            MediaScannerConnection.scanFile(
                context,
                arrayOf(photoFile.absolutePath),
                arrayOf("image/jpeg"),
                null
            )

            val cameraLabel = if (isSelfie) "front selfie" else "back"
            Log.d(TAG, "Photo saved successfully to ${photoFile.absolutePath}")
            return Pair(true, "Captured picture using $cameraLabel camera and saved to Gallery!")

        } catch (e: Exception) {
            Log.e(TAG, "captureDirectPhoto error", e)
            visionEngine?.stopCamera()
            return Pair(false, "Failed to capture photo: ${e.localizedMessage}")
        }
    }
}
