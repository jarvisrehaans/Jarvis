package com.jarvis.assistant.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Live Camera Vision & Direct Photo engine using Android Camera2 API + ImageReader.
 * Supports both continuous live vision streaming (to Gemini Live) and sharp, high-definition
 * direct photo capture (saved to Gallery).
 * Automatically calculates physical and display rotation compensation so that both front
 * and back camera photos are always upright and accurately aligned with the user's phone position.
 */
class CameraVisionEngine(
    private val context: Context,
    private val isPhotoCaptureMode: Boolean = false,
    private val onFrameCaptured: (ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "CameraVisionEngine"
        private const val LIVE_CAPTURE_INTERVAL_MS = 600L
        private const val PHOTO_CAPTURE_INTERVAL_MS = 100L
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isFrontCamera = false
    private var isStreaming = false
    private var lastFrameTimeMs = 0L

    private var selectedWidth = 1280
    private var selectedHeight = 720

    private var previewTextureView: TextureView? = null
    private var orientationListener: OrientationEventListener? = null
    @Volatile private var physicalDeviceOrientation = 0

    fun setPreviewTextureView(textureView: TextureView?) {
        this.previewTextureView = textureView
        if (isStreaming && cameraDevice != null) {
            startCaptureSession()
        }
    }

    fun isFrontLens(): Boolean = isFrontCamera
    fun isCameraStreaming(): Boolean = isStreaming

    private fun initOrientationListener() {
        try {
            orientationListener = object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    physicalDeviceOrientation = when (orientation) {
                        in 45..134 -> 270
                        in 135..224 -> 180
                        in 225..314 -> 90
                        else -> 0
                    }
                }
            }
            if (orientationListener?.canDetectOrientation() == true) {
                orientationListener?.enable()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable OrientationEventListener: ${e.message}")
        }
    }

    private fun getDeviceRotationDegrees(): Int {
        if (physicalDeviceOrientation != 0) {
            return physicalDeviceOrientation
        }
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display?.rotation ?: Surface.ROTATION_0
            } catch (_: Exception) {
                Surface.ROTATION_0
            }
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
        return when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    @SuppressLint("MissingPermission")
    fun startCamera(useFront: Boolean = false) {
        if (isStreaming) {
            if (isFrontCamera == useFront) return
            stopCamera()
        }

        isFrontCamera = useFront
        isStreaming = true

        initOrientationListener()
        startBackgroundThread()

        val cameraId = getCameraId(useFront)
        if (cameraId == null) {
            Log.e(TAG, "No suitable camera found for useFront=$useFront")
            isStreaming = false
            return
        }

        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val optimalSize = selectOptimalSize(characteristics)
            selectedWidth = optimalSize.width
            selectedHeight = optimalSize.height
            Log.d(TAG, "Using camera resolution: ${selectedWidth}x${selectedHeight}, isPhotoCaptureMode=$isPhotoCaptureMode")

            imageReader = ImageReader.newInstance(
                selectedWidth, selectedHeight,
                ImageFormat.YUV_420_888, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    processImage(reader)
                }, backgroundHandler)
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    isStreaming = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    isStreaming = false
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            isStreaming = false
        }
    }

    private fun selectOptimalSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        if (supportedSizes.isEmpty()) {
            return Size(1280, 720)
        }

        return if (isPhotoCaptureMode) {
            // Target Full HD (1920x1080 / 1080x1920) or best available HD size for razor-sharp photos
            supportedSizes.firstOrNull { (it.width == 1920 && it.height == 1080) || (it.width == 1080 && it.height == 1920) }
                ?: supportedSizes.filter { it.width >= 1280 && it.height >= 720 }
                    .maxByOrNull { it.width * it.height }
                ?: supportedSizes.maxByOrNull { it.width * it.height }
                ?: Size(1280, 720)
        } else {
            // Target 720p (1280x720 / 720x1280) for high-clarity, low-latency live AI vision
            supportedSizes.firstOrNull { (it.width == 1280 && it.height == 720) || (it.width == 720 && it.height == 1280) }
                ?: supportedSizes.filter { it.width in 640..1280 && it.height in 480..960 }
                    .maxByOrNull { it.width * it.height }
                ?: supportedSizes.firstOrNull()
                ?: Size(1280, 720)
        }
    }

    fun switchCamera() {
        startCamera(!isFrontCamera)
    }

    fun stopCamera() {
        if (!isStreaming) return
        isStreaming = false

        try {
            orientationListener?.disable()
            orientationListener = null
        } catch (_: Exception) {}

        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }

        stopBackgroundThread()
    }

    private fun getCameraId(useFront: Boolean): String? {
        val targetFacing = if (useFront) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == targetFacing) return id
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun startCaptureSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return

        val surfaces = mutableListOf<Surface>(reader.surface)

        val previewSurface = if (previewTextureView?.isAvailable == true) {
            val texture = previewTextureView!!.surfaceTexture
            texture?.setDefaultBufferSize(selectedWidth, selectedHeight)
            if (texture != null) Surface(texture) else null
        } else null

        if (previewSurface != null) {
            surfaces.add(previewSurface)
        }

        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        if (cameraDevice == null) return
                        captureSession = session
                        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(reader.surface)
                            if (previewSurface != null) addTarget(previewSurface)
                            // Enable continuous autofocus for sharp clarity
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            // Enable auto exposure and auto white balance
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            // High quality noise reduction & edge enhancement to eliminate blur
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                        }
                        session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "onConfigured setRepeatingRequest safely caught: ${e.message}", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure camera session")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture session", e)
        }
    }

    private fun processImage(reader: ImageReader) {
        val image: Image? = try {
            reader.acquireLatestImage() ?: reader.acquireNextImage()
        } catch (e: Exception) {
            null
        }

        if (image == null) return

        val now = System.currentTimeMillis()
        val interval = if (isPhotoCaptureMode) PHOTO_CAPTURE_INTERVAL_MS else LIVE_CAPTURE_INTERVAL_MS
        if (now - lastFrameTimeMs < interval) {
            image.close()
            return
        }
        lastFrameTimeMs = now

        try {
            val jpegBytes = yuv420ToJpeg(image)
            if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                onFrameCaptured(jpegBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image conversion error", e)
        } finally {
            image.close()
        }
    }

    private fun yuv420ToJpeg(image: Image): ByteArray? {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val numPixels = width * height
        val nv21 = ByteArray(numPixels + (numPixels / 2))

        var id = 0

        // Y plane
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, numPixels)
            id = numPixels
        } else {
            var yOffset = 0
            for (i in 0 until height) {
                yBuffer.position(yOffset)
                yBuffer.get(nv21, id, width)
                id += width
                yOffset += yRowStride
            }
        }

        // UV planes
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val uvWidth = width / 2
        val uvHeight = height / 2

        val uRaw = ByteArray(uBuffer.remaining())
        val vRaw = ByteArray(vBuffer.remaining())
        uBuffer.get(uRaw)
        vBuffer.get(vRaw)

        var uvPos = numPixels
        for (row in 0 until uvHeight) {
            val rowStart = row * uvRowStride
            for (col in 0 until uvWidth) {
                val index = rowStart + col * uvPixelStride
                nv21[uvPos++] = vRaw.getOrNull(index) ?: 0
                nv21[uvPos++] = uRaw.getOrNull(index) ?: 0
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        val quality = if (isPhotoCaptureMode) 95 else 85
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)

        val rawJpeg = out.toByteArray()

        // Calculate rotation compensation for exact device physical orientation
        val cameraId = getCameraId(isFrontCamera) ?: return rawJpeg
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val deviceDegrees = getDeviceRotationDegrees()
        val rotationCompensation = if (isFrontCamera) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }

        return try {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return rawJpeg
            val matrix = Matrix().apply {
                if (rotationCompensation != 0) {
                    postRotate(rotationCompensation.toFloat())
                }
                if (isFrontCamera) {
                    postScale(-1f, 1f) // Mirror selfie for natural orientation
                }
            }
            val orientedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val orientedOut = ByteArrayOutputStream()
            orientedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, orientedOut)
            orientedOut.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap orientation rotation error: ${e.message}", e)
            rawJpeg
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraVisionThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }
}
