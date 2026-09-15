package com.jarvis.assistant.audio

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream

object VoskModelManager {
    private const val TAG = "VoskModelManager"

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    @Volatile
    var model: Model? = null
        private set

    @Volatile
    private var isInitializing = false

    fun init(context: Context, scope: CoroutineScope) {
        if (model != null || isInitializing) return
        isInitializing = true

        scope.launch(Dispatchers.IO) {
            try {
                val targetDir = File(context.filesDir, "vosk-model")
                val markerFile = File(targetDir, "am/final.mdl")

                if (!markerFile.exists() || markerFile.length() == 0L) {
                    Log.i(TAG, "Unpacking Vosk model to internal storage: ${targetDir.absolutePath}")
                    targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    copyAssetFolder(context.assets, "model-en-us", targetDir)
                    Log.i(TAG, "Vosk model unpacked successfully.")
                } else {
                    Log.i(TAG, "Vosk model already unpacked at: ${targetDir.absolutePath}")
                }

                val loadedModel = Model(targetDir.absolutePath)
                model = loadedModel
                _isModelReady.value = true
                isInitializing = false
                Log.i(TAG, "Vosk Model ready in memory!")
            } catch (e: Throwable) {
                isInitializing = false
                Log.e(TAG, "FATAL: Failed to load Vosk model: ${e.message}", e)
            }
        }
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toDir: File) {
        val list = assetManager.list(fromAssetPath)
        if (list.isNullOrEmpty()) {
            try {
                assetManager.open(fromAssetPath).use { input ->
                    toDir.parentFile?.mkdirs()
                    FileOutputStream(toDir).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy asset $fromAssetPath: ${e.message}")
            }
        } else {
            toDir.mkdirs()
            for (item in list) {
                val childAsset = if (fromAssetPath.isEmpty()) item else "$fromAssetPath/$item"
                val childTarget = File(toDir, item)
                copyAssetFolder(assetManager, childAsset, childTarget)
            }
        }
    }
}
