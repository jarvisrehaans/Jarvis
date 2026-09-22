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
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * System Overlay Service that renders the classic 3D draggable liquid fluid orb.
 */
class FloatingOrbService : Service() {

    companion object {
        private const val TAG = "FloatingOrbService"
        private const val NOTIFICATION_ID = 202
        private const val CHANNEL_ID = "jarvis_floating_orb_channel"

        const val ACTION_START = "com.jarvis.assistant.action.START_FLOATING_ORB"
        const val ACTION_STOP = "com.jarvis.assistant.action.STOP_FLOATING_ORB"
        const val ACTION_TRIGGER_VOICE = "com.jarvis.assistant.action.TRIGGER_VOICE"

        const val STYLE_ORB = "orb"

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

        fun reloadStyle(context: Context) {
            // Kept for backward compatibility
        }
    }

    private var windowManager: WindowManager? = null

    // Orb Overlay
    private var orbContainer: FrameLayout? = null
    private var orbView: OrbAnimationView? = null
    private var orbLayoutParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())

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
            mainHandler.post {
                updateOrbState(OrbAnimationView.OrbState.THINKING)
            }
        }

        override fun onSetupComplete() {
            mainHandler.post {
                updateOrbState(OrbAnimationView.OrbState.LISTENING)
            }
        }

        override fun onSpeakingStarted() {
            mainHandler.post {
                updateOrbState(OrbAnimationView.OrbState.SPEAKING)
            }
        }

        override fun onSpeakingStopped() {
            mainHandler.post {
                updateOrbState(OrbAnimationView.OrbState.IDLE)
            }
        }

        override fun onAmplitudeChanged(rms: Float) {
            mainHandler.post {
                orbView?.setAmplitude(rms * 1.5f)
            }
        }

        override fun onInputTranscript(text: String) {
            mainHandler.post {
                updateOrbState(OrbAnimationView.OrbState.LISTENING)
            }
        }

        override fun onOutputTranscript(text: String) {
            mainHandler.post {
                updateOrbState(OrbAnimationView.OrbState.SPEAKING)
            }
        }

        override fun onTurnComplete() {
            mainHandler.post {
                updateOrbState(OrbAnimationView.OrbState.IDLE)
            }
        }

        override fun onStandbyStateChanged(isStandby: Boolean) {
            mainHandler.post {
                if (isStandby) {
                    updateOrbState(OrbAnimationView.OrbState.IDLE)
                } else {
                    updateOrbState(OrbAnimationView.OrbState.LISTENING)
                }
            }
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
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        ThemeManager.addListener(themeListener)
        setupOrbWindow()
        bindVoiceService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_VOICE -> {
                triggerVoiceAction()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Floating Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "JARVIS 3D Floating Orb Overlay"
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
            .setContentTitle("JARVIS 3D Floating Orb Active")
            .setContentText("Tap orb to speak with JARVIS")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ==========================================
    // 3D FLOATING ORB OVERLAY
    // ==========================================

    private fun setupOrbWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Cannot setup Orb overlay: SYSTEM_ALERT_WINDOW permission missing.")
            return
        }

        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(metrics)

        val density = resources.displayMetrics.density
        val orbSizePx = (72 * density).toInt()

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        orbLayoutParams = WindowManager.LayoutParams(
            orbSizePx,
            orbSizePx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - orbSizePx - 24
            y = metrics.heightPixels / 3
        }

        orbContainer = FrameLayout(this).apply {
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

        orbContainer?.addView(orbView)
        setupOrbTouchListener(metrics.widthPixels, orbSizePx)

        try {
            windowManager?.addView(orbContainer, orbLayoutParams)
            Log.d(TAG, "Floating Orb overlay successfully added to WindowManager.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Floating Orb view: ${e.message}", e)
        }
    }

    private fun removeOrbWindow() {
        if (orbContainer != null && windowManager != null) {
            try {
                windowManager?.removeView(orbContainer)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing Orb overlay: ${e.message}")
            }
            orbContainer = null
            orbView = null
        }
    }

    private fun setupOrbTouchListener(screenWidth: Int, orbSizePx: Int) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastClickTime = 0L

        orbContainer?.setOnTouchListener { _, event ->
            val params = orbLayoutParams ?: return@setOnTouchListener false

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
                    windowManager?.updateViewLayout(orbContainer, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)

                    if (deltaX < 12 && deltaY < 12) {
                        val now = System.currentTimeMillis()
                        if (now - lastClickTime < 300) {
                            openMainActivity()
                        } else {
                            triggerVoiceAction()
                        }
                        lastClickTime = now
                    } else {
                        val targetX = if (params.x + orbSizePx / 2 < screenWidth / 2) {
                            16
                        } else {
                            screenWidth - orbSizePx - 16
                        }
                        animateOrbSnapToEdge(params.x, targetX)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun animateOrbSnapToEdge(startX: Int, endX: Int) {
        val animator = ValueAnimator.ofInt(startX, endX).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val params = orbLayoutParams ?: return@addUpdateListener
                params.x = anim.animatedValue as Int
                windowManager?.updateViewLayout(orbContainer, params)
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
        orbView?.setState(state)
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
        removeOrbWindow()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
