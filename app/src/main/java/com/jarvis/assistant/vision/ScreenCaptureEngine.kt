package com.jarvis.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Captures live screen frames using Android MediaProjection + VirtualDisplay + ImageReader
 * and compresses them to JPEG byte arrays at ~1 FPS for real-time Gemini Live vision input.
 */
class ScreenCaptureEngine(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onFrameCaptured: (ByteArray) -> Unit
) {

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var isCapturing = false
    private var lastCapturedTimeMs = 0L
    @Volatile private var isProcessingFrame = false

    companion object {
        private const val TAG = "ScreenCaptureEngine"
        private const val CAPTURE_INTERVAL_MS = 1000L // 1 FPS optimal rate for Google Gemini Multimodal Live API
        private const val TARGET_CAPTURE_WIDTH = 440
    }

    fun start() {
        if (isCapturing) return
        isCapturing = true

        handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
        handler = Handler(handlerThread!!.looper)

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val density = metrics.densityDpi
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // Proportionally scale preserving exact native device aspect ratio (no distorted/squashed UI)
        val scale = minOf(1.0f, TARGET_CAPTURE_WIDTH.toFloat() / screenWidth.coerceAtLeast(1))
        var width = (screenWidth * scale).toInt()
        var height = (screenHeight * scale).toInt()

        // Enforce even dimensions for hardware display buffers
        if (width % 2 != 0) width--
        if (height % 2 != 0) height--
        if (width <= 0) width = 440
        if (height <= 0) height = 960

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastCapturedTimeMs >= CAPTURE_INTERVAL_MS && !isProcessingFrame) {
                lastCapturedTimeMs = now
                processNextFrame(reader)
            } else {
                try {
                    val img = reader.acquireLatestImage() ?: reader.acquireNextImage()
                    img?.close()
                } catch (_: Exception) {}
            }
        }, handler)

        // Initial 800ms quiet window so system speech/handshake finishes before first video frame transmission
        lastCapturedTimeMs = System.currentTimeMillis() - (CAPTURE_INTERVAL_MS - 800L)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "JarvisScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        Log.d(TAG, "ScreenCaptureEngine live vision stream started at ${width}x${height} (~1 FPS)")
    }

    private fun processNextFrame(reader: ImageReader) {
        var image: Image? = null
        isProcessingFrame = true
        try {
            image = reader.acquireLatestImage() ?: reader.acquireNextImage() ?: return
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else {
                bitmap
            }

            val baos = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 35, baos)
            val jpegBytes = baos.toByteArray()

            if (croppedBitmap != bitmap) croppedBitmap.recycle()
            bitmap.recycle()

            onFrameCaptured(jpegBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing screen frame", e)
        } finally {
            image?.close()
            isProcessingFrame = false
        }
    }

    fun stop() {
        if (!isCapturing) return
        isCapturing = false

        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            handlerThread?.quitSafely()
            handlerThread = null
            handler = null
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ScreenCaptureEngine", e)
        }
        Log.d(TAG, "ScreenCaptureEngine stopped")
    }
}
