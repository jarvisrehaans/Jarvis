package com.jarvis.assistant.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.R
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages JARVIS dynamic theme styling.
 *
 * Supported themes:
 * - [THEME_BLUE] ("blue"): Classic Electric Arc Blue (Default)
 * - [THEME_GOLD] ("gold"): Amber Gold
 *
 * Semantic Tokens:
 *  Token            Arc Blue    Amber Gold
 *  Primary Accent   #3B82F6     #F59E0B
 *  Glow             #60A5FA     #FBBF24
 *  Border           #2563EB     #D97706
 *  Surface Glow     #2E3B82F6   rgba(245,158,11,0.18) (#2EF59E0B)
 */
object ThemeManager {
    const val PREF_KEY_THEME = "app_theme"
    const val THEME_BLUE = "blue"
    const val THEME_GOLD = "gold"

    // Arc Blue Semantic Tokens
    const val COLOR_BLUE_PRIMARY = "#3B82F6"
    const val COLOR_BLUE_GLOW = "#60A5FA"
    const val COLOR_BLUE_BORDER = "#2563EB"
    const val COLOR_BLUE_SURFACE_GLOW = "#2E3B82F6"
    const val COLOR_BLUE_DIM = "#1E3A8A"

    // Amber Gold Semantic Tokens
    const val COLOR_GOLD_PRIMARY = "#F59E0B"
    const val COLOR_GOLD_GLOW = "#FBBF24"
    const val COLOR_GOLD_BORDER = "#D97706"
    const val COLOR_GOLD_SURFACE_GLOW = "#2EF59E0B"
    const val COLOR_GOLD_DIM = "#78350F"

    private val themeListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: (String) -> Unit) {
        if (!themeListeners.contains(listener)) {
            themeListeners.add(listener)
        }
    }

    fun removeListener(listener: (String) -> Unit) {
        themeListeners.remove(listener)
    }

    private fun notifyThemeChanged(theme: String) {
        mainHandler.post {
            for (listener in themeListeners) {
                try {
                    listener(theme)
                } catch (_: Exception) {}
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(JarvisApplication.PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Returns current theme code ("blue" or "gold"). Default is "blue". */
    fun getTheme(context: Context): String {
        return prefs(context).getString(PREF_KEY_THEME, THEME_BLUE) ?: THEME_BLUE
    }

    /** Saves new theme code and notifies all active listeners immediately without restart. */
    fun setTheme(context: Context, theme: String) {
        val target = if (theme == THEME_GOLD) THEME_GOLD else THEME_BLUE
        val current = getTheme(context)
        prefs(context).edit().putString(PREF_KEY_THEME, target).apply()
        if (current != target) {
            notifyThemeChanged(target)
        }
    }

    /** Returns true if active theme is Gold. */
    fun isGoldTheme(context: Context): Boolean {
        return getTheme(context) == THEME_GOLD
    }

    /** Applies the appropriate theme resource to an Activity before setContentView. */
    fun applyTheme(activity: Activity) {
        if (isGoldTheme(activity)) {
            activity.setTheme(R.style.Theme_Jarvis_Gold)
        } else {
            activity.setTheme(R.style.Theme_Jarvis_Blue)
        }
    }

    /** Returns the primary color hex string (#3B82F6 or #F59E0B). */
    fun getPrimaryColorHex(context: Context): String {
        return if (isGoldTheme(context)) COLOR_GOLD_PRIMARY else COLOR_BLUE_PRIMARY
    }

    /** Returns the glow / secondary color hex string (#60A5FA or #FBBF24). */
    fun getGlowColorHex(context: Context): String {
        return if (isGoldTheme(context)) COLOR_GOLD_GLOW else COLOR_BLUE_GLOW
    }

    fun getSecondaryColorHex(context: Context): String = getGlowColorHex(context)

    /** Returns the border color hex string (#2563EB or #D97706). */
    fun getBorderColorHex(context: Context): String {
        return if (isGoldTheme(context)) COLOR_GOLD_BORDER else COLOR_BLUE_BORDER
    }

    /** Returns the surface glow color hex string (#2E3B82F6 or #2EF59E0B). */
    fun getSurfaceGlowColorHex(context: Context): String {
        return if (isGoldTheme(context)) COLOR_GOLD_SURFACE_GLOW else COLOR_BLUE_SURFACE_GLOW
    }

    /** Returns the dim color hex string (#1E3A8A or #78350F). */
    fun getDimColorHex(context: Context): String {
        return if (isGoldTheme(context)) COLOR_GOLD_DIM else COLOR_BLUE_DIM
    }

    /** Returns the primary color as an Int. */
    fun getPrimaryColorInt(context: Context): Int {
        return Color.parseColor(getPrimaryColorHex(context))
    }

    /** Returns the glow / secondary color as an Int. */
    fun getGlowColorInt(context: Context): Int {
        return Color.parseColor(getGlowColorHex(context))
    }

    fun getSecondaryColorInt(context: Context): Int = getGlowColorInt(context)

    /** Returns the border color as an Int. */
    fun getBorderColorInt(context: Context): Int {
        return Color.parseColor(getBorderColorHex(context))
    }

    /** Returns the surface glow color as an Int. */
    fun getSurfaceGlowColorInt(context: Context): Int {
        return Color.parseColor(getSurfaceGlowColorHex(context))
    }

    /** Returns the dim color as an Int. */
    fun getDimColorInt(context: Context): Int {
        return Color.parseColor(getDimColorHex(context))
    }

    /** Returns uniform float for Orb shaders (0.0f for Blue, 1.0f for Gold). */
    fun getOrbThemeFloat(context: Context): Float {
        return if (isGoldTheme(context)) 1.0f else 0.0f
    }

    /** Returns the top header panel background drawable resource. */
    fun getTopHeaderDrawable(context: Context): Int {
        return if (isGoldTheme(context)) R.drawable.bg_top_header_panel_gold else R.drawable.bg_top_header_panel_blue
    }

    /** Returns the save button / pill button background drawable resource. */
    fun getSaveButtonDrawable(context: Context): Int {
        return if (isGoldTheme(context)) R.drawable.bg_save_button_gold else R.drawable.bg_save_button_blue
    }

    /** Returns the user chat bubble background drawable resource. */
    fun getUserChatDrawable(context: Context): Int {
        return if (isGoldTheme(context)) R.drawable.bg_chat_user_gold else R.drawable.bg_chat_user_blue
    }

    /** Dynamically creates a themed chip background drawable. */
    fun createChipDrawable(context: Context, isSelected: Boolean): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 999f * density
            if (isSelected) {
                setColor(getSurfaceGlowColorInt(context))
                setStroke((1.5f * density).toInt(), getBorderColorInt(context))
            } else {
                setColor(Color.parseColor("#33000000"))
                setStroke((1f * density).toInt(), Color.parseColor("#18FFFFFF"))
            }
        }
    }

    /** Dynamically creates a themed active badge drawable. */
    fun createBadgeDrawable(context: Context): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 4f * density
            setColor(getPrimaryColorInt(context))
        }
    }

    /** Dynamically creates a themed card background drawable with border and surface glow. */
    fun createCardDrawable(context: Context, isSelected: Boolean = false): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 16f * density
            if (isSelected) {
                setColor(getSurfaceGlowColorInt(context))
                setStroke((1.5f * density).toInt(), getPrimaryColorInt(context))
            } else {
                setColor(Color.parseColor("#E60D0D14"))
                setStroke((1f * density).toInt(), Color.parseColor("#1AFFFFFF"))
            }
        }
    }
}

