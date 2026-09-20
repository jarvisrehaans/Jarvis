package com.jarvis.assistant.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.main.MainActivity
import com.jarvis.assistant.ui.main.OrbAnimationView
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.abs

/**
 * System Overlay Service that renders a draggable, glowing 3D liquid fluid orb
 * widget floating over the Android Home Screen and all other applications.
 */
class FloatingOrbService : Service() {

    companion object {
        private const val TAG = "FloatingOrbService"
        private const val NOTIFICATION_ID = 202
        private const val CHANNEL_ID = "jarvis_floating_orb_channel"

        const val ACTION_START = "com.jarvis.assistant.action.START_FLOATING_ORB"
        const val ACTION_STOP = "com.jarvis.assistant.action.STOP_FLOATING_ORB"

        fun startService(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot start FloatingOrbService: SYSTEM_ALERT_WINDOW permission missing.")
                return
            }
            val intent = Intent(context, FloatingOrbService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FloatingOrbService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayContainer: FrameLayout? = null
    private var orbView: OrbAnimationView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var voiceService: JarvisVoiceService? = null
    private var isBoundToVoiceService = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? JarvisVoiceService.LocalBinder
            voiceService = localBinder?.getService()
            isBoundToVoiceService = true
            voiceService?.addListener(voiceListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            isBoundToVoiceService = false
        }
    }

    private val voiceListener = object : JarvisVoiceService.JarvisVoiceListener {
        override fun onConnected() {
            updateOrbState(OrbAnimationView.OrbState.THINKING)
        }

        override fun onSetupComplete() {
            updateOrbState(OrbAnimationView.OrbState.LISTENING)
        }

        override fun onSpeakingStarted() {
            updateOrbState(OrbAnimationView.OrbState.SPEAKING)
        }

        override fun onSpeakingStopped() {
            updateOrbState(OrbAnimationView.OrbState.IDLE)
        }

        override fun onAmplitudeChanged(rms: Float) {
            orbView?.post {
                orbView?.setAmplitude(rms * 1.5f)
            }
        }

        override fun onInputTranscript(text: String) {
            updateOrbState(OrbAnimationView.OrbState.LISTENING)
        }

        override fun onOutputTranscript(text: String) {
            updateOrbState(OrbAnimationView.OrbState.SPEAKING)
        }

        override fun onTurnComplete() {
            updateOrbState(OrbAnimationView.OrbState.IDLE)
        }

        override fun onShutdownRequested() {
            stopSelf()
        }
    }

    private val themeListener: (String) -> Unit = {
        orbView?.post {
            orbView?.refreshTheme()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ThemeManager.addListener(themeListener)
        setupOverlayWindow()
        bindVoiceService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Floating Orb Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating 3D Orb Home Screen HUD"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Floating Orb Active")
            .setContentText("Tap orb to speak or drag to move")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupOverlayWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Cannot setup overlay: SYSTEM_ALERT_WINDOW permission missing.")
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(metrics)

        val density = resources.displayMetrics.density
        val orbSizePx = (96 * density).toInt()

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            orbSizePx,
            orbSizePx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - orbSizePx - (16 * density).toInt()
            y = metrics.heightPixels / 3
        }

        overlayContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        orbView = OrbAnimationView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setState(OrbAnimationView.OrbState.IDLE)
        }

        overlayContainer?.addView(orbView)
        setupTouchListener(metrics.widthPixels, orbSizePx)

        try {
            windowManager?.addView(overlayContainer, layoutParams)
            Log.d(TAG, "Floating Orb overlay successfully added to WindowManager.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Floating Orb view: ${e.message}", e)
        }
    }

    private fun setupTouchListener(screenWidth: Int, orbSizePx: Int) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastClickTime = 0L

        overlayContainer?.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayContainer, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)

                    if (deltaX < 12 && deltaY < 12) {
                        // Single tap vs double tap detection
                        val now = System.currentTimeMillis()
                        if (now - lastClickTime < 300) {
                            // Double tap: Launch main app
                            openMainActivity()
                        } else {
                            // Single tap: Trigger voice interaction
                            triggerVoiceAction()
                        }
                        lastClickTime = now
                    } else {
                        // Drag end: Magnetically snap to nearest screen edge (left or right)
                        val targetX = if (params.x + orbSizePx / 2 < screenWidth / 2) {
                            16 // Snap to left edge
                        } else {
                            screenWidth - orbSizePx - 16 // Snap to right edge
                        }
                        animateSnapToEdge(params.x, targetX)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun animateSnapToEdge(startX: Int, endX: Int) {
        val animator = ValueAnimator.ofInt(startX, endX).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val params = layoutParams ?: return@addUpdateListener
                params.x = anim.animatedValue as Int
                windowManager?.updateViewLayout(overlayContainer, params)
            }
        }
        animator.start()
    }

    private fun triggerVoiceAction() {
        if (voiceService?.isSessionRunning() == true) {
            voiceService?.enterActiveState(fromWakeWord = false)
            updateOrbState(OrbAnimationView.OrbState.LISTENING)
        } else {
            openMainActivity()
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun updateOrbState(state: OrbAnimationView.OrbState) {
        orbView?.post {
            orbView?.setState(state)
        }
    }

    private var isVoiceServiceBound = false

    private fun bindVoiceService() {
        if (!isVoiceServiceBound) {
            val intent = Intent(this, JarvisVoiceService::class.java)
            try {
                isVoiceServiceBound = bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind VoiceService: ${e.message}")
            }
        }
    }

    private fun unbindVoiceService() {
        if (isVoiceServiceBound) {
            try {
                voiceService?.removeListener(voiceListener)
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding voice service: ${e.message}")
            }
            isVoiceServiceBound = false
            isBoundToVoiceService = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ThemeManager.removeListener(themeListener)
        unbindVoiceService()
        if (overlayContainer != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayContainer)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
