package com.jarvis.assistant.util

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * Manages device flashlight (torch) toggle and brightness control.
 */
object FlashlightController {
    private const val TAG = "FlashlightController"
    private var isTorchOn = false

    /**
     * Toggles flashlight state or sets exact brightness level (1..100%).
     * Action: "on", "off", "toggle", or "brightness".
     */
    fun controlFlashlight(context: Context, action: String, brightnessPercent: Int? = null): Pair<Boolean, String> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return Pair(false, "Camera service unavailable.")

        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }

            if (cameraId == null) {
                return Pair(false, "No flashlight (flash unit) found on this device.")
            }

            val targetAction = action.lowercase().trim()
            val shouldEnable = when (targetAction) {
                "on" -> true
                "off" -> false
                "toggle" -> !isTorchOn
                else -> brightnessPercent != null && brightnessPercent > 0
            }

            if (shouldEnable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && brightnessPercent != null) {
                    try {
                        val method = cameraManager.javaClass.getMethod("turnOnTorchWithStrengthLevel", String::class.java, Int::class.javaPrimitiveType)
                        val level = ((brightnessPercent.coerceIn(1, 100) / 100f) * 5).toInt().coerceIn(1, 5)
                        method.invoke(cameraManager, cameraId, level)
                        isTorchOn = true
                        return Pair(true, "Flashlight turned on at $brightnessPercent% brightness.")
                    } catch (e: Exception) {
                        Log.w(TAG, "Reflection for turnOnTorchWithStrengthLevel failed: ${e.message}")
                    }
                }
                cameraManager.setTorchMode(cameraId, true)
                isTorchOn = true
                Pair(true, "Flashlight turned on.")
            } else {
                cameraManager.setTorchMode(cameraId, false)
                isTorchOn = false
                Pair(true, "Flashlight turned off.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "controlFlashlight failed: ${e.message}", e)
            Pair(false, "Flashlight control failed: ${e.localizedMessage}")
        }
    }
}
