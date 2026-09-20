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
import java.nio.ByteBuffer

/**
 * Captures live screen frames using Android MediaProjection + VirtualDisplay + ImageReader
 * and compresses them to JPEG byte arrays at ~1 FPS for real-time Gemini Live vision input.
 *
 * CRASH FIX: On many Android GPU drivers (Adreno, Mali, Tensor), the ImageReader buffer's
 * last row omits the trailing row-padding bytes. The old code called copyPixelsFromBuffer()
 * which expects exactly (rowStride * height) bytes, throwing RuntimeException:
 * "Buffer not large enough for pixels". The fix safely pads the buffer before copying.
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
                } catch (_: Throwable) {}
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

    /**
     * Safely processes the next screen frame from ImageReader.
     *
     * GPU BUFFER SAFETY: Android GPU drivers write pixel data with a rowStride that may
     * exceed (pixelStride * width) due to hardware alignment. However, the ByteBuffer
     * returned by Image.Plane often contains FEWER bytes than (rowStride * height) because
     * the GPU omits trailing padding on the last row. Calling copyPixelsFromBuffer() on a
     * bitmap sized to rowStride width causes "Buffer not large enough for pixels" crash.
     *
     * FIX: We create the bitmap at exact image.width (NOT rowStride width), then manually
     * copy each row's valid pixel data (pixelStride * width bytes) into a clean buffer,
     * skipping the per-row padding. This guarantees zero buffer overflow on ALL GPU drivers.
     */
    private fun processNextFrame(reader: ImageReader) {
        var image: Image? = null
        isProcessingFrame = true
        try {
            image = reader.acquireLatestImage() ?: reader.acquireNextImage() ?: return
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val imgWidth = image.width
            val imgHeight = image.height

            // Valid pixel bytes per row (excluding GPU alignment padding)
            val validRowBytes = pixelStride * imgWidth
            val rowPadding = rowStride - validRowBytes

            val bitmap: Bitmap
            if (rowPadding == 0) {
                // No padding — buffer is tightly packed, safe to copy directly
                bitmap = Bitmap.createBitmap(imgWidth, imgHeight, Bitmap.Config.ARGB_8888)
                // Guard: ensure buffer has enough data before copying
                val requiredBytes = imgWidth * imgHeight * 4 // ARGB_8888 = 4 bytes/pixel
                if (buffer.remaining() >= requiredBytes) {
                    bitmap.copyPixelsFromBuffer(buffer)
                } else {
                    // Insufficient buffer data — skip this frame silently
                    bitmap.recycle()
                    return
                }
            } else {
                // Row padding present — copy row-by-row into a clean, tightly-packed buffer
                // to avoid "Buffer not large enough for pixels" crash
                val cleanBuffer = ByteBuffer.allocateDirect(validRowBytes * imgHeight)
                for (row in 0 until imgHeight) {
                    val srcOffset = row * rowStride
                    // Guard: don't read beyond the actual GPU buffer
                    if (srcOffset + validRowBytes > buffer.capacity()) break
                    buffer.position(srcOffset)
                    buffer.limit(srcOffset + validRowBytes)
                    cleanBuffer.put(buffer)
                }
                cleanBuffer.rewind()

                bitmap = Bitmap.createBitmap(imgWidth, imgHeight, Bitmap.Config.ARGB_8888)
                if (cleanBuffer.remaining() >= imgWidth * imgHeight * 4) {
                    bitmap.copyPixelsFromBuffer(cleanBuffer)
                } else {
                    bitmap.recycle()
                    return
                }
            }

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 35, baos)
            val jpegBytes = baos.toByteArray()
            bitmap.recycle()

            onFrameCaptured(jpegBytes)
        } catch (e: Throwable) {
            // Catch ALL throwables including OutOfMemoryError, RuntimeException from
            // graphics drivers, and IllegalStateException from buffer queue exhaustion.
            // A dropped frame is harmless — a crash is not.
            Log.e(TAG, "Error processing screen frame (safely dropped)", e)
        } finally {
            try { image?.close() } catch (_: Throwable) {}
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
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping ScreenCaptureEngine", e)
        }
        Log.d(TAG, "ScreenCaptureEngine stopped")
    }
}
