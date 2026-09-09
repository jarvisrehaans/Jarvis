package com.jarvis.assistant.ui.main

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.AttributeSet
import android.view.TextureView
import com.jarvis.assistant.util.ThemeManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The JARVIS orb, ported 1:1 onto native OpenGL ES 2.0.
 * Supports dynamic real-time color theming (Classic Electric Arc Blue [Default] and Amber Gold).
 *
 * Uses [TextureView] (backed by a self-managed EGL render thread) rather than
 * [android.opengl.GLSurfaceView] / [android.view.SurfaceView], because those
 * composite via a "hole punch" that can misbehave behind non-rectangular
 * backgrounds (e.g. the floating overlay's circular frame) or overlapping
 * sibling views (e.g. the overlay's close button). TextureView is a regular
 * View for compositing purposes, so it just works everywhere this orb is used.
 *
 * State → color mapping:
 *  - Arc Blue Theme (u_theme = 0.0):
 *      IDLE                → Deep Shadow Blue & Electric Arc Cyan
 *      LISTENING / ACTIVE  → High-voltage Arc Cyan Waves
 *      THINKING            → Pulsing Blue Core & Luminous Cyan Highlight
 *      SPEAKING            → Radiant Fluid Arc Reactor Cyan Waves
 *      LOGIN               → Full Arc Cyan Palette Waves
 *  - Amber Gold Theme (u_theme = 1.0):
 *      5-color Amber Gold family (#553300, #884400, #DD7700, #FFAA30, #FFCC66)
 *
 * Transitions between states ease in over ~10 frames and briefly brighten
 * ("light up") right at the moment of change.
 */
class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    enum class OrbState { IDLE, LISTENING, SPEAKING, THINKING, ACTIVE, LOGIN }

    private var renderThread: OrbRenderThread? = null
    private var pendingState = 0f
    private var pendingAmplitude = 0f
    private var pendingTheme = ThemeManager.getOrbThemeFloat(context)

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setState(newState: OrbState) {
        val newUniform = stateToUniform(newState)
        if (newUniform != pendingState) {
            pendingState = newUniform
            renderThread?.setTargetState(newUniform)
        }
    }

    fun setAmplitude(value: Float) {
        pendingAmplitude = value.coerceIn(0f, 1f)
        renderThread?.amplitude = pendingAmplitude
    }

    /** Set theme float: 0.0f = Electric Arc Blue (Default), 1.0f = Amber Gold. */
    fun setOrbTheme(themeFloat: Float) {
        pendingTheme = themeFloat
        renderThread?.theme = pendingTheme
    }

    /** Refresh theme from ThemeManager. */
    fun refreshTheme() {
        setOrbTheme(ThemeManager.getOrbThemeFloat(context))
    }

    /** No-op if the surface is already live; kept for API parity with the Activity/Service lifecycle wiring. */
    fun onResume() {
        refreshTheme()
        renderThread?.paused = false
    }

    /** Pauses the draw loop without tearing down the EGL context, so resuming is instant. */
    fun onPause() {
        renderThread?.paused = true
    }

    private fun stateToUniform(state: OrbState): Float = when (state) {
        OrbState.IDLE -> 0f
        OrbState.LISTENING, OrbState.ACTIVE -> 1f
        OrbState.THINKING -> 2f
        OrbState.SPEAKING -> 3f
        OrbState.LOGIN -> 4f
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        refreshTheme()
        renderThread = OrbRenderThread(surface, width, height).apply {
            primeState(pendingState)
            amplitude = pendingAmplitude
            theme = pendingTheme
            start()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderThread?.shutdown()
        renderThread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    /**
     * Owns its own EGL context/surface and a tight draw loop targeting ~60fps.
     * All GL calls happen on this thread only.
     */
    private class OrbRenderThread(
        private val surfaceTexture: SurfaceTexture,
        initialWidth: Int,
        initialHeight: Int
    ) : Thread("JarvisOrbGL") {

        @Volatile private var targetState: Float = 0f
        @Volatile var amplitude: Float = 0f
        @Volatile var theme: Float = 0f
        @Volatile var paused: Boolean = false
        @Volatile private var running = true
        @Volatile private var width = initialWidth
        @Volatile private var height = initialHeight

        private var currentState: Float = 0f
        private var flashStartNs: Long = 0L
        private val FLASH_DURATION_NS = 450_000_000L

        fun primeState(initial: Float) {
            targetState = initial
            currentState = initial
        }

        fun setTargetState(newState: Float) {
            targetState = newState
            flashStartNs = System.nanoTime()
        }

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var program = 0
        private var positionHandle = 0
        private var timeHandle = 0
        private var stateHandle = 0
        private var themeHandle = 0
        private var flashHandle = 0
        private var amplitudeHandle = 0
        private var resolutionHandle = 0
        private var vertexBuffer: FloatBuffer? = null
        private var startTimeNs = 0L

        private val VERTEX_SHADER = """
            attribute vec2 position;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            precision highp float;
            uniform float u_time;
            uniform float u_state;
            uniform float u_theme; // 0.0 = Arc Blue, 1.0 = Amber Gold
            uniform float u_amplitude;
            uniform float u_flash;
            uniform vec2 u_resolution;

            void main() {
                vec2 uv = (gl_FragCoord.xy * 2.0 - u_resolution.xy) / min(u_resolution.y, u_resolution.x);
                float dist = length(uv);

                vec3 color1, color2, color3;
                vec3 c_midShadow;
                vec3 highlightColor;
                float speed = 0.7;

                if (u_theme < 0.5) {
                    // ==========================================
                    // ARC BLUE PALETTE
                    // ==========================================
                    c_midShadow = vec3(0.118, 0.227, 0.541);     // #1E3A8A (Deep Shadow Blue)
                    highlightColor = vec3(0.376, 0.647, 0.980);  // #60A5FA (Arc Blue Center Glow)

                    if (u_state < 0.5) { // Idle: Deep Shadow Blue & Electric Blue
                        color1 = vec3(0.059, 0.133, 0.353); // #0F225A
                        color2 = vec3(0.145, 0.388, 0.922); // #2563EB (Border Blue)
                        color3 = vec3(0.231, 0.510, 0.965); // #3B82F6 (Primary Accent)
                        speed = 0.5;
                    } else if (u_state < 1.5) { // Listening: Vibrant Arc Blue Waves
                        color1 = vec3(0.118, 0.227, 0.541); // #1E3A8A
                        color2 = vec3(0.231, 0.510, 0.965); // #3B82F6
                        color3 = vec3(0.376, 0.647, 0.980); // #60A5FA
                        speed = 1.2;
                    } else if (u_state < 2.5) { // Thinking: High-voltage Core & Cyan Blue Aura
                        color1 = vec3(0.059, 0.133, 0.353); // #0F225A
                        color2 = vec3(0.231, 0.510, 0.965); // #3B82F6
                        color3 = vec3(0.133, 0.827, 0.933); // #22D3EE (Cyan Aura)
                        speed = 1.6;
                    } else if (u_state < 3.5) { // Speaking: Radiant Fluid Arc Reactor Waves
                        color1 = vec3(0.145, 0.388, 0.922); // #2563EB
                        color2 = vec3(0.231, 0.510, 0.965); // #3B82F6
                        color3 = vec3(0.376, 0.647, 0.980); // #60A5FA
                        speed = 1.0;
                    } else { // Full Blue Waveform
                        color1 = vec3(0.059, 0.133, 0.353);
                        color2 = vec3(0.145, 0.388, 0.922);
                        color3 = vec3(0.231, 0.510, 0.965);
                        speed = 0.85;
                    }
                } else {
                    // ==========================================
                    // AMBER GOLD PALETTE
                    // ==========================================
                    c_midShadow = vec3(0.471, 0.208, 0.059);     // #78350F (Mid Shadow Bronze)
                    highlightColor = vec3(0.984, 0.749, 0.141);  // #FBBF24 (Center Gold Highlight)

                    if (u_state < 0.5) { // Idle: Warm Amber & Gold Core
                        color1 = vec3(0.271, 0.118, 0.024); // #451E06
                        color2 = vec3(0.851, 0.467, 0.024); // #D97706 (Border Amber)
                        color3 = vec3(0.961, 0.620, 0.043); // #F59E0B (Primary Gold)
                        speed = 0.5;
                    } else if (u_state < 1.5) { // Listening: Vibrant Amber & Gold Waves
                        color1 = vec3(0.471, 0.208, 0.059); // #78350F
                        color2 = vec3(0.851, 0.467, 0.024); // #D97706
                        color3 = vec3(0.961, 0.620, 0.043); // #F59E0B
                        speed = 1.2;
                    } else if (u_state < 2.5) { // Thinking: Electric Amber & Gold Aura
                        color1 = vec3(0.271, 0.118, 0.024); // #451E06
                        color2 = vec3(0.961, 0.620, 0.043); // #F59E0B
                        color3 = vec3(0.984, 0.749, 0.141); // #FBBF24
                        speed = 1.6;
                    } else if (u_state < 3.5) { // Speaking: Radiant Fluid Amber Gold Waves
                        color1 = vec3(0.851, 0.467, 0.024); // #D97706
                        color2 = vec3(0.961, 0.620, 0.043); // #F59E0B
                        color3 = vec3(0.984, 0.749, 0.141); // #FBBF24
                        speed = 1.0;
                    } else { // Full Gold Waveform
                        color1 = vec3(0.271, 0.118, 0.024);
                        color2 = vec3(0.851, 0.467, 0.024);
                        color3 = vec3(0.961, 0.620, 0.043);
                        speed = 0.85;
                    }
                }

                speed *= (1.0 + u_amplitude * 0.4);

                // Silky 3D Liquid Fluid Wave Mathematics
                vec2 q = vec2(0.0);
                q.x = sin(u_time * speed * 0.8 + uv.x * 2.5 + uv.y * 1.2);
                q.y = cos(u_time * speed * 0.8 + uv.y * 2.5 + uv.x * 1.2);

                float fluid = sin(dist * 7.0 - u_time * speed * 2.2 + length(q) * 2.0);
                float wave2 = cos(uv.x * 4.0 - uv.y * 3.0 + u_time * speed);

                vec3 fluidColor = mix(color1, c_midShadow, fluid * 0.25 + 0.25);
                fluidColor = mix(fluidColor, color2, fluid * 0.5 + 0.5);
                fluidColor = mix(fluidColor, color3, wave2 * 0.35 + 0.35);
                fluidColor = mix(fluidColor, highlightColor, wave2 * 0.25 + 0.25);

                // Flash cue on state change
                fluidColor *= (1.0 + u_flash * 0.6);

                // 3D Sphere Edge Glow & Soft Volumetric Alpha Mask
                float sphereEdge = smoothstep(0.92, 0.2, dist);
                float rimGlow = pow(dist, 3.0) * 0.45;
                vec3 finalColor = fluidColor + highlightColor * rimGlow;

                float alpha = sphereEdge;
                alpha = min(1.0, alpha + u_flash * 0.15);

                gl_FragColor = vec4(finalColor * alpha, alpha * 0.95);
            }
        """.trimIndent()

        fun resize(w: Int, h: Int) {
            width = w
            height = h
        }

        fun shutdown() {
            running = false
            try {
                join(200)
            } catch (e: InterruptedException) {
                // best effort
            }
        }

        override fun run() {
            if (!initEgl()) return
            initGl()
            startTimeNs = System.nanoTime()

            while (running) {
                if (paused) {
                    safeSleep(50)
                    continue
                }
                drawFrame()
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                safeSleep(16) // ~60fps
            }
            releaseEgl()
        }

        private fun safeSleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                // ignore, loop condition re-checked next iteration
            }
        }

        private fun initEgl(): Boolean {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) return false
            val eglConfig = configs[0] ?: return false

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        private fun initGl() {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
            }

            positionHandle = GLES20.glGetAttribLocation(program, "position")
            timeHandle = GLES20.glGetUniformLocation(program, "u_time")
            stateHandle = GLES20.glGetUniformLocation(program, "u_state")
            themeHandle = GLES20.glGetUniformLocation(program, "u_theme")
            flashHandle = GLES20.glGetUniformLocation(program, "u_flash")
            amplitudeHandle = GLES20.glGetUniformLocation(program, "u_amplitude")
            resolutionHandle = GLES20.glGetUniformLocation(program, "u_resolution")

            val quad = floatArrayOf(
                -1f, -1f, 1f, -1f, -1f, 1f,
                -1f, 1f, 1f, -1f, 1f, 1f
            )
            vertexBuffer = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(quad); position(0) }
        }

        private fun drawFrame() {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            currentState += (targetState - currentState) * 0.12f

            val nowNs = System.nanoTime()
            val sinceFlashNs = nowNs - flashStartNs
            val flash = if (sinceFlashNs in 0 until FLASH_DURATION_NS) {
                val t = 1f - (sinceFlashNs.toFloat() / FLASH_DURATION_NS)
                t * t
            } else 0f

            val elapsedSeconds = (nowNs - startTimeNs) / 1_000_000_000f
            GLES20.glUniform1f(timeHandle, elapsedSeconds)
            GLES20.glUniform1f(stateHandle, currentState)
            GLES20.glUniform1f(themeHandle, theme)
            GLES20.glUniform1f(flashHandle, flash)
            GLES20.glUniform1f(amplitudeHandle, amplitude)
            GLES20.glUniform2f(resolutionHandle, width.toFloat(), height.toFloat())

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            GLES20.glDisableVertexAttribArray(positionHandle)
        }

        private fun compileShader(type: Int, source: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
            }
        }

        private fun releaseEgl() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }
}
