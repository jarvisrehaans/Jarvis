package com.jarvis.assistant.update

import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.Keep
import androidx.core.content.FileProvider
import com.jarvis.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * In-App Auto-Updater Manager for Jarvis AI.
 * Handles remote version checks, cyberpunk glassmorphic update pop-up dialog,
 * background APK streaming, fallback direct downloader, and system package installer launch.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    // Default remote version metadata URL
    private const val DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/jarvisrehaans/Jarvis/main/version.json"

    @Keep
    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String,
        val forceUpdate: Boolean = false
    )

    @Volatile
    private var isDialogOpen = false

    @Volatile
    private var isUpdateInProgress = false

    @Volatile
    var cachedUpdateInfo: UpdateInfo? = null
        private set

    @Volatile
    var hasUpdateAvailable: Boolean = false
        private set

    private var activeDialog: Dialog? = null

    /**
     * Checks update status in background and notifies listeners (used by Settings and MainActivity).
     */
    fun checkUpdateStatus(context: Context, onResult: (Boolean, UpdateInfo?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val updateInfo = fetchRemoteVersionInfo(DEFAULT_VERSION_URL)
                val currentVersionCode = getLocalVersionCode(context)
                val isAvailable = updateInfo != null && updateInfo.versionCode > currentVersionCode

                cachedUpdateInfo = updateInfo
                hasUpdateAvailable = isAvailable

                withContext(Dispatchers.Main) {
                    onResult(isAvailable, updateInfo)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking update status: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResult(false, null)
                }
            }
        }
    }

    /**
     * Checks for updates asynchronously.
     * Shows custom update pop-up dialog if a newer version is available.
     */
    fun checkForUpdates(activity: Activity, versionUrl: String = DEFAULT_VERSION_URL, manualCheck: Boolean = false) {
        if (isDialogOpen && !manualCheck) {
            Log.d(TAG, "Update check skipped: dialog already open")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val updateInfo = fetchRemoteVersionInfo(versionUrl)
                if (updateInfo == null) {
                    if (manualCheck) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Unable to check updates. Please check network.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }

                val currentVersionCode = getLocalVersionCode(activity)
                val hasNewerVersion = updateInfo.versionCode > currentVersionCode

                cachedUpdateInfo = updateInfo
                hasUpdateAvailable = hasNewerVersion

                Log.d(TAG, "Update check: Remote Code = ${updateInfo.versionCode}, Local Code = $currentVersionCode, HasUpdate = $hasNewerVersion")

                if (hasNewerVersion) {
                    withContext(Dispatchers.Main) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            showUpdateDialog(activity, updateInfo)
                        }
                    }
                } else {
                    Log.d(TAG, "Jarvis AI is up to date (Local Code: $currentVersionCode, Remote Code: ${updateInfo.versionCode})")
                    if (manualCheck) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                activity,
                                "✨ Jarvis AI is up to date (v${com.jarvis.assistant.BuildConfig.VERSION_NAME})",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                if (manualCheck) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Update check error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun fetchRemoteVersionInfo(url: String): UpdateInfo? {
        return try {
            val cacheBusterUrl = if (url.contains("?")) "$url&cb=${System.currentTimeMillis()}" else "$url?cb=${System.currentTimeMillis()}"
            val client = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(cacheBusterUrl)
                .header("User-Agent", "Mozilla/5.0 JarvisAI-Android")
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null

            val json = JSONObject(body)
            val urlFromKey = json.optString("downloadUrl", "").ifBlank { json.optString("apkUrl", "") }
            val baseApkUrl = if (urlFromKey.isNotBlank()) urlFromKey else "https://raw.githubusercontent.com/rehaanoffical77-gif/Jarvis-Ai/main/Jarvis-AI-Release.apk"
            val finalApkUrl = if (baseApkUrl.contains("?")) "$baseApkUrl&cb=${System.currentTimeMillis()}" else "$baseApkUrl?cb=${System.currentTimeMillis()}"

            UpdateInfo(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.optString("versionName", "1.0.0"),
                apkUrl = finalApkUrl,
                changelog = json.optString("changelog", "Bug fixes and performance improvements."),
                forceUpdate = json.optBoolean("forceUpdate", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching version info", e)
            null
        }
    }

    fun getLocalVersionCode(context: Context): Int {
        return try {
            val pmCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            }
            if (pmCode > 0) pmCode else com.jarvis.assistant.BuildConfig.VERSION_CODE
        } catch (e: Exception) {
            com.jarvis.assistant.BuildConfig.VERSION_CODE
        }
    }

    /**
     * Displays custom glassmorphic cyberpunk update dialog.
     */
    fun showUpdateDialog(activity: Activity, updateInfo: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        // Close any existing dialog safely
        try {
            activeDialog?.dismiss()
        } catch (e: Exception) {
            // Ignored
        }

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_app, null)
        dialog.setContentView(view)

        val displayMetrics = activity.resources.displayMetrics
        val dialogWidth = (displayMetrics.widthPixels * 0.92).toInt().coerceAtMost((420 * displayMetrics.density).toInt())

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                dialogWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = view.findViewById<TextView>(R.id.dialogUpdateTitle)
        val versionTransitionView = view.findViewById<TextView>(R.id.dialogVersionTransition)
        val changelogView = view.findViewById<TextView>(R.id.dialogChangelogText)
        val progressSection = view.findViewById<View>(R.id.dialogDownloadProgressSection)
        val progressBar = view.findViewById<ProgressBar>(R.id.dialogProgressBar)
        val progressStatusText = view.findViewById<TextView>(R.id.dialogProgressStatusText)
        val actionsContainer = view.findViewById<View>(R.id.dialogActionsContainer)
        val updateNowBtn = view.findViewById<FrameLayout>(R.id.dialogUpdateNowBtn)
        val updateBtnText = view.findViewById<TextView?>(R.id.dialogUpdateNowBtnText)
        val browserDirectBtn = view.findViewById<FrameLayout>(R.id.dialogBrowserDirectBtn)
        val laterBtn = view.findViewById<TextView>(R.id.dialogLaterBtn)
        val changelogScrollView = view.findViewById<View?>(R.id.dialogChangelogScrollView)

        // Safety safeguard: strictly enforce changelog height so action buttons are never pushed off screen
        changelogScrollView?.let {
            val maxChangelogHeight = (130 * displayMetrics.density).toInt()
            val lp = it.layoutParams
            if (lp != null && (lp.height > maxChangelogHeight || lp.height <= 0)) {
                lp.height = maxChangelogHeight
                it.layoutParams = lp
            }
        }

        titleView.text = "JARVIS AI v${updateInfo.versionName}"
        val localVersionName = com.jarvis.assistant.BuildConfig.VERSION_NAME
        versionTransitionView.text = "Installed: v$localVersionName (Build ${getLocalVersionCode(activity)})  ➔  Available: v${updateInfo.versionName}"
        changelogView.text = updateInfo.changelog
        updateBtnText?.text = "UPDATE NOW • v${updateInfo.versionName}"

        if (updateInfo.forceUpdate) {
            laterBtn.visibility = View.GONE
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        } else {
            laterBtn.visibility = View.VISIBLE
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)
        }

        updateNowBtn.setOnClickListener {
            progressSection.visibility = View.VISIBLE
            actionsContainer.visibility = View.GONE
            progressBar.isIndeterminate = true
            progressStatusText.text = "Initiating update download..."

            startApkDownload(activity, updateInfo) { status, isDone ->
                progressStatusText.text = status
                if (isDone) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            dialog.dismiss()
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }, 1200)
                }
            }
        }

        browserDirectBtn.setOnClickListener {
            dialog.dismiss()
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.apkUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(activity, "Error opening browser link", Toast.LENGTH_SHORT).show()
            }
        }

        laterBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            isDialogOpen = false
            activeDialog = null
        }

        isDialogOpen = true
        activeDialog = dialog
        dialog.show()
    }

    /**
     * Downloads the APK file using DownloadManager with fallback to direct HTTP downloader.
     */
    fun startApkDownload(
        context: Context,
        updateInfo: UpdateInfo,
        onProgressUpdate: ((status: String, isDone: Boolean) -> Unit)? = null
    ) {
        if (updateInfo.apkUrl.isBlank()) {
            Toast.makeText(context, "Invalid update package URL", Toast.LENGTH_SHORT).show()
            isUpdateInProgress = false
            onProgressUpdate?.invoke("Invalid package URL", true)
            return
        }

        // Check install package permission for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "Please grant permission to install updates", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                isUpdateInProgress = false
                onProgressUpdate?.invoke("Permission required", true)
                return
            }
        }

        isUpdateInProgress = true
        Toast.makeText(context, "Downloading update in background...", Toast.LENGTH_SHORT).show()
        onProgressUpdate?.invoke("Downloading update package...", false)

        val downloadUri = Uri.parse(updateInfo.apkUrl)
        val fileName = "Jarvis-AI-v${updateInfo.versionName}.apk"
        val destinationFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        try {
            val request = DownloadManager.Request(downloadUri).apply {
                setTitle("Downloading Jarvis AI v${updateInfo.versionName}")
                setDescription("Fetching official update package...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(destinationFile))
                setMimeType("application/vnd.android.package-archive")
                addRequestHeader("User-Agent", "Mozilla/5.0 JarvisAI-Android")
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id == downloadId) {
                        try {
                            context.unregisterReceiver(this)
                        } catch (e: Exception) {
                            // Already unregistered
                        }

                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        var success = false

                        if (cursor != null && cursor.moveToFirst()) {
                            val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = if (statusCol >= 0) cursor.getInt(statusCol) else -1
                            if (status == DownloadManager.STATUS_SUCCESSFUL && destinationFile.exists() && destinationFile.length() > 0) {
                                success = true
                                onProgressUpdate?.invoke("Download complete! Launching installer...", true)
                                installApk(context, destinationFile)
                            }
                            cursor.close()
                        }

                        if (!success) {
                            Log.w(TAG, "DownloadManager failed, trying fallback direct download...")
                            downloadDirectFallback(context, updateInfo.apkUrl, destinationFile, onProgressUpdate)
                        } else {
                            isUpdateInProgress = false
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    onComplete,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    onComplete,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "DownloadManager failed to enqueue, attempting direct download", e)
            downloadDirectFallback(context, updateInfo.apkUrl, destinationFile, onProgressUpdate)
        }
    }

    /**
     * Fallback direct OkHttp downloader in case DownloadManager fails or is blocked.
     */
    private fun downloadDirectFallback(
        context: Context,
        apkUrl: String,
        destinationFile: File,
        onProgressUpdate: ((status: String, isDone: Boolean) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    onProgressUpdate?.invoke("Streaming update directly...", false)
                }

                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(apkUrl)
                    .header("User-Agent", "Mozilla/5.0 JarvisAI-Android")
                    .build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful && response.body != null) {
                    val inputStream = response.body!!.byteStream()
                    val outputStream = FileOutputStream(destinationFile)
                    inputStream.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (destinationFile.exists() && destinationFile.length() > 0) {
                        withContext(Dispatchers.Main) {
                            isUpdateInProgress = false
                            onProgressUpdate?.invoke("Download complete! Launching installer...", true)
                            installApk(context, destinationFile)
                        }
                        return@launch
                    }
                }

                withContext(Dispatchers.Main) {
                    isUpdateInProgress = false
                    onProgressUpdate?.invoke("Download failed. Please check network.", true)
                    Toast.makeText(context, "Failed to download update APK. Please check connection.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct download fallback failed", e)
                withContext(Dispatchers.Main) {
                    isUpdateInProgress = false
                    onProgressUpdate?.invoke("Download failed: ${e.message}", true)
                    Toast.makeText(context, "Update error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Triggers Android system package installer using FileProvider.
     */
    fun installApk(context: Context, apkFile: File) {
        isUpdateInProgress = false
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "APK file is missing or empty: ${apkFile.absolutePath}")
            Toast.makeText(context, "Downloaded APK is invalid or corrupt", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching package installer", e)
            Toast.makeText(context, "Failed to launch package installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
