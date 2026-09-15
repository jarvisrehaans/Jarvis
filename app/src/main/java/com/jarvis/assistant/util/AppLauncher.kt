package com.jarvis.assistant.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/**
 * Lets JARVIS open any app installed on the device by spoken name,
 * e.g. "open YouTube", "open camera", "launch WhatsApp".
 *
 * Supports Dual Apps / Cloned Apps / Multi-user profiles (e.g. Samsung Dual Messenger,
 * Xiaomi Dual Apps, OnePlus Parallel Apps, Android Work Profile) by querying LauncherApps
 * across all active UserHandles.
 */
object AppLauncher {

    data class InstalledApp(
        val label: String,
        val packageName: String,
        val userHandle: UserHandle? = null,
        val componentName: ComponentName? = null,
        val isDualOrWorkProfile: Boolean = false
    )

    sealed class OpenAppResult {
        data class Success(val app: InstalledApp) : OpenAppResult()
        data class MultipleFound(val appName: String, val matches: List<InstalledApp>) : OpenAppResult()
        data class NotFound(val appName: String) : OpenAppResult()
        object Failure : OpenAppResult()
    }

    private var cachedApps: List<InstalledApp>? = null
    private var lastCacheTimeMs: Long = 0L
    private const val CACHE_TTL_MS: Long = 30_000L

    fun clearCache() {
        cachedApps = null
        lastCacheTimeMs = 0L
    }

    /**
     * Returns every user-launchable app across all user profiles (main user + dual app profiles).
     */
    fun getLaunchableApps(context: Context): List<InstalledApp> {
        val now = System.currentTimeMillis()
        val cached = cachedApps
        if (cached != null && (now - lastCacheTimeMs) < CACHE_TTL_MS) {
            return cached
        }

        val apps = mutableListOf<InstalledApp>()
        val pm = context.packageManager

        // 1. Query LauncherApps across all user profiles (handles Dual Apps / Cloned Apps / Work Profile)
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager

        if (launcherApps != null && userManager != null) {
            try {
                val profiles: List<UserHandle> = userManager.userProfiles ?: listOf(Process.myUserHandle())
                val myUser = Process.myUserHandle()
                for (profile in profiles) {
                    val isDualOrWork = profile != myUser
                    val activityList = launcherApps.getActivityList(null, profile)
                    for (item in activityList) {
                        val pkg = item.applicationInfo.packageName
                        val label = item.label.toString()
                        apps.add(
                            InstalledApp(
                                label = label,
                                packageName = pkg,
                                userHandle = profile,
                                componentName = item.componentName,
                                isDualOrWorkProfile = isDualOrWork
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AppLauncher", "LauncherApps query failed", e)
            }
        }

        // 2. Fallback / supplement with PackageManager for current user if LauncherApps returned nothing
        if (apps.isEmpty()) {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            } catch (e: Exception) {
                android.util.Log.e("AppLauncher", "queryIntentActivities failed", e)
                emptyList()
            }
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (!ri.activityInfo.enabled) continue
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                val label = ri.loadLabel(pm).toString()
                val component = ComponentName(pkg, ri.activityInfo.name)
                apps.add(
                    InstalledApp(
                        label = label,
                        packageName = pkg,
                        userHandle = null,
                        componentName = component,
                        isDualOrWorkProfile = false
                    )
                )
            }
        }

        // De-duplicate by combination of package name and user profile
        val result = apps
            .distinctBy { Pair(it.packageName, it.userHandle) }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.isDualOrWorkProfile }))
        cachedApps = result
        lastCacheTimeMs = now
        return result
    }

    /**
     * Common spoken names that don't literally match an app's installed label — either because
     * the everyday name differs from the on-device label (e.g. people say "Google Pay" for the
     * app labelled "GPay" on some builds, or vice versa), or because the app the user means has
     * been renamed/replaced on their device (e.g. PUBG Mobile is banned in India and BGMI is its
     * direct successor from the same publisher; "Free Fire" was superseded by "Free Fire Max").
     * Each spoken alias maps to a list of candidate labels/keywords to try, in priority order —
     * we still only ever launch something that is ACTUALLY INSTALLED; if none of the aliases
     * resolve to an installed app we fall through to normal fuzzy matching and then NotFound.
     */
    private val SPOKEN_ALIASES: Map<String, List<String>> = mapOf(
        "google pay" to listOf("gpay", "google pay"),
        "gpay" to listOf("gpay", "google pay"),
        "phone pay" to listOf("phonepe"),
        "phonepay" to listOf("phonepe"),
        "pubg" to listOf("bgmi", "pubg mobile", "pubg"),
        "pubg mobile" to listOf("bgmi", "pubg mobile", "pubg"),
        "free fire" to listOf("free fire max", "free fire"),
        "ff" to listOf("free fire max", "free fire"),
        "insta" to listOf("instagram"),
        "whats app" to listOf("whatsapp"),
        "snap chat" to listOf("snapchat"),
        "snap" to listOf("snapchat"),
        "fb" to listOf("facebook"),
        "yt" to listOf("youtube")
    )

    /**
     * Finds matching launchable apps for a spoken query.
     */
    fun findMatchingApps(context: Context, spokenName: String): List<InstalledApp> {
        val rawQuery = spokenName.trim().lowercase()
        if (rawQuery.isEmpty()) return emptyList()

        // Strip ordinal / index keywords like "1", "2", "first", "second", "1st", "2nd", "dual", "clone"
        val cleanQuery = rawQuery
            .replace(Regex("\\b(1|2|first|second|1st|2nd|dual|clone)\\b"), "")
            .trim()
            .ifEmpty { rawQuery }

        val apps = getLaunchableApps(context)

        // 0. Alias table first: try each candidate label, in priority order, against what's
        // actually installed. This is how "open PUBG" correctly opens BGMI when PUBG itself
        // isn't installed (it's banned in India), and "open Free Fire" opens Free Fire Max.
        SPOKEN_ALIASES[cleanQuery]?.let { candidates ->
            for (candidate in candidates) {
                val hit = apps.filter { it.label.lowercase() == candidate }
                if (hit.isNotEmpty()) return hit
            }
            for (candidate in candidates) {
                val hit = apps.filter { it.label.lowercase().contains(candidate) }
                if (hit.isNotEmpty()) return hit
            }
        }

        // Matching strategy
        val exactMatches = apps.filter { it.label.lowercase() == cleanQuery }
        if (exactMatches.isNotEmpty()) return exactMatches

        val startsWithMatches = apps.filter { it.label.lowercase().startsWith(cleanQuery) }
        if (startsWithMatches.isNotEmpty()) return startsWithMatches

        val containsMatches = apps.filter { it.label.lowercase().contains(cleanQuery) }
        if (containsMatches.isNotEmpty()) return containsMatches

        val pkgMatches = apps.filter { it.packageName.lowercase().contains(cleanQuery.replace(" ", "")) }
        if (pkgMatches.isNotEmpty()) return pkgMatches

        // Try rawQuery if cleanQuery didn't match
        if (cleanQuery != rawQuery) {
            val rawExact = apps.filter { it.label.lowercase() == rawQuery }
            if (rawExact.isNotEmpty()) return rawExact

            val rawContains = apps.filter { it.label.lowercase().contains(rawQuery) }
            if (rawContains.isNotEmpty()) return rawContains
        }

        return emptyList()
    }

    /**
     * Checks if dual app / app cloning is enabled specifically for [packageName] on this device.
     */
    fun isDualAppEnabled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val pkgLower = packageName.lowercase()

        // OEM System settings query for Vivo, Samsung, Xiaomi, Oppo Dual App lists
        try {
            val cr = context.contentResolver
            val settingKeys = listOf(
                "double_app_start_list",
                "clone_app_list",
                "app_clone_list",
                "multi_app_installed_list",
                "dual_app_official_list",
                "clone_apps"
            )
            for (key in settingKeys) {
                val v1 = android.provider.Settings.Secure.getString(cr, key)
                if (!v1.isNullOrEmpty() && v1.lowercase().contains(pkgLower)) return true

                val v2 = android.provider.Settings.System.getString(cr, key)
                if (!v2.isNullOrEmpty() && v2.lowercase().contains(pkgLower)) return true

                val v3 = android.provider.Settings.Global.getString(cr, key)
                if (!v3.isNullOrEmpty() && v3.lowercase().contains(pkgLower)) return true
            }
        } catch (e: Exception) {
            // ignore
        }

        return false
    }

    /**
     * Attempts to open an app based on [spokenName] and optional [appNumber] (1 or 2).
     * Single apps (like YouTube, Jio, Hotstar) open directly. Dual apps ask 1 or 2.
     */
    fun openAppResult(context: Context, spokenName: String, appNumber: Int? = null): OpenAppResult {
        val matches = findMatchingApps(context, spokenName)
        if (matches.isEmpty()) return OpenAppResult.NotFound(spokenName)

        // Check if an index was embedded in spokenName (e.g., "WhatsApp 2" or "2nd WhatsApp")
        var targetIndex: Int? = appNumber?.let { it - 1 }
        if (targetIndex == null) {
            val rawLower = spokenName.lowercase()
            if (rawLower.contains(Regex("\\b(2|2nd|second|dual)\\b"))) {
                targetIndex = 1
            } else if (rawLower.contains(Regex("\\b(1|1st|first)\\b"))) {
                targetIndex = 0
            }
        }

        // Dual App detection:
        // An app is considered dual ONLY if we found 2+ matching launchable activities
        // OR OEM system settings explicitly listed this package as cloned.
        val isDual = matches.size >= 2 || isDualAppEnabled(context, matches[0].packageName)

        if (!isDual) {
            // SINGLE APP: launch directly!
            val success = launchInstalledApp(context, matches[0], 1)
            return if (success) OpenAppResult.Success(matches[0]) else OpenAppResult.Failure
        }

        // DUAL APP:
        // If user already specified 1 or 2 (targetIndex != null), launch the requested instance!
        if (targetIndex != null && targetIndex in matches.indices) {
            val targetNum = targetIndex + 1
            val selectedApp = matches[targetIndex]
            val success = launchInstalledApp(context, selectedApp, targetNum)
            return if (success) OpenAppResult.Success(selectedApp) else OpenAppResult.Failure
        }

        if (targetIndex != null && matches.size == 1) {
            // Dual app managed by OS with single package entry
            val targetNum = targetIndex + 1
            val success = launchInstalledApp(context, matches[0], targetNum)
            return if (success) OpenAppResult.Success(matches[0]) else OpenAppResult.Failure
        }

        // Multiple/Dual found and no 1 or 2 specified yet -> return MultipleFound
        val displayMatches = if (matches.size >= 2) matches else listOf(
            matches[0],
            matches[0].copy(label = "${matches[0].label} 2", isDualOrWorkProfile = true)
        )
        return OpenAppResult.MultipleFound(spokenName, displayMatches)
    }

    /**
     * Legacy openApp signature for direct calls where result status isn't needed.
     */
    fun openApp(context: Context, spokenName: String): InstalledApp? {
        return when (val res = openAppResult(context, spokenName, null)) {
            is OpenAppResult.Success -> res.app
            is OpenAppResult.MultipleFound -> {
                if (launchInstalledApp(context, res.matches[0], 1)) res.matches[0] else null
            }
            else -> null
        }
    }

    /**
     * Launches a specific [InstalledApp] instance, injecting target [appNumber] (1 or 2) OEM extras.
     */
    fun launchInstalledApp(context: Context, app: InstalledApp, appNumber: Int = 1): Boolean {
        return try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            val profiles = try { userManager?.userProfiles ?: emptyList() } catch (e: Exception) { emptyList() }
            val secondaryProfile = profiles.firstOrNull { it != Process.myUserHandle() }

            // If appNumber == 2 and a secondary profile (User 95 / User 999) exists:
            if (appNumber == 2 && launcherApps != null && secondaryProfile != null && app.componentName != null) {
                launcherApps.startMainActivity(app.componentName, secondaryProfile, null, null)
                return true
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: return false

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

            // Inject OEM clone extras for Vivo, Samsung, Xiaomi, Oppo Dual Apps
            if (appNumber == 2) {
                launchIntent.putExtra("multi_user_id", 999)
                launchIntent.putExtra("userId", 999)
                launchIntent.putExtra("clone_id", 1)
                launchIntent.putExtra("is_clone_app", true)
                launchIntent.putExtra("is_clone", true)
                launchIntent.putExtra("clone_app", true)
                launchIntent.putExtra("vivo_clone_id", 1)
                launchIntent.putExtra("android.intent.extra.USER_HANDLE", 95)
            } else {
                launchIntent.putExtra("multi_user_id", 0)
                launchIntent.putExtra("userId", 0)
                launchIntent.putExtra("clone_id", 0)
                launchIntent.putExtra("is_clone_app", false)
                launchIntent.putExtra("is_clone", false)
                launchIntent.putExtra("clone_app", false)
                launchIntent.putExtra("vivo_clone_id", 0)
                launchIntent.putExtra("android.intent.extra.USER_HANDLE", 0)
            }

            if (launcherApps != null && app.userHandle != null && app.componentName != null) {
                val targetUser = if (appNumber == 2 && secondaryProfile != null) secondaryProfile else app.userHandle
                try {
                    launcherApps.startMainActivity(app.componentName, targetUser, null, null)
                    true
                } catch (e: Exception) {
                    ActivityLauncherHelper.startActivitySafely(context, launchIntent)
                }
            } else {
                ActivityLauncherHelper.startActivitySafely(context, launchIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("AppLauncher", "Failed to launch ${app.packageName} (${app.userHandle})", e)
            false
        }
    }

    /** True if at least one app label/package matches [spokenName] closely. */
    fun isAppInstalled(context: Context, spokenName: String): Boolean {
        return findMatchingApps(context, spokenName).isNotEmpty()
    }
}
