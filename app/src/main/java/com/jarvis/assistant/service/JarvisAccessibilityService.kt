package com.jarvis.assistant.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import kotlinx.coroutines.*

/**
 * Handles the YouTube actions and accessibility interactions.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val TAG = "JarvisAccessibilityService"

        /** Non-null while the service is enabled and running. */
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "JarvisAccessibilityService connected successfully.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handled on-demand via tools; safely ignore unneeded events without throwing
    }

    override fun onInterrupt() {
        Log.w(TAG, "JarvisAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (instance == this) instance = null
        Log.i(TAG, "JarvisAccessibilityService destroyed.")
    }

    // ---------------------------------------------------------------
    // Button-tap actions
    // ---------------------------------------------------------------

    /** Taps the "Skip Ad" / "Skip Ads" button if currently visible. Returns true if found and tapped. */
    fun skipAd(): Boolean = clickNodeMatching("skip ad")

    /** Taps the Like button on the currently open video. */
    fun likeVideo(): Boolean = clickNodeMatching("like", exclude = "dislike")

    /** Taps the Subscribe button on the current channel/video. */
    fun subscribeChannel(): Boolean = clickNodeMatching("subscribe")

    /**
     * Opens the channel page by tapping the channel avatar/name row under the
     * video (NOT the "Subscribe" button — that row is usually a separate
     * clickable node right next to it, commonly labelled with just the
     * channel's name, or exposed via a resource id containing "channel").
     */
    fun openChannel(): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectByResourceId(root, "channel", candidates)
        val target = candidates
            .filter { it.isClickable }
            .minByOrNull { nodeLabel(it).length }
        if (target != null) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Fallback: a node labelled just "channel" or "go to channel", excluding
        // the subscribe button itself so we don't just re-trigger subscribe.
        return clickNodeMatching("channel", exclude = "subscribe")
    }

    /**
     * Taps the fullscreen toggle button (works for both entering and exiting
     * fullscreen). YouTube's own accessibility label is usually the single
     * word "fullscreen", so that variant has to be checked too — matching
     * only "full screen" (with a space) meant this never found the button.
     * Some YouTube versions leave the button's text/contentDescription empty
     * (icon-only) and only expose it via its resource id, so that's checked
     * as a fallback too.
     */
    fun toggleFullscreen(): Boolean {
        if (clickNodeMatching("full screen", "fullscreen")) return true

        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectByResourceId(root, "fullscreen", candidates)
        val target = candidates.firstOrNull { it.isClickable } ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Continuously scans Google Play Store screen over several seconds to find and tap
     * the "Install", "Update", or "Get" button. Handles search lists, app detail pages,
     * resource IDs, and parent node hierarchy walking.
     */
    fun startAutoInstallScanner(appName: String = "") {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var attempts = 0
        val maxAttempts = 16 // 16 attempts * 500ms = 8 seconds total scan window

        val scanRunnable = object : Runnable {
            override fun run() {
                attempts++
                val clicked = performInstallTap(appName)
                if (clicked) {
                    android.util.Log.d(TAG, "Successfully tapped Install button for '$appName' on attempt $attempts")
                    return
                }
                if (attempts < maxAttempts) {
                    handler.postDelayed(this, 500L)
                } else {
                    android.util.Log.w(TAG, "Finished scanning Play Store for '$appName' after $maxAttempts attempts.")
                }
            }
        }
        handler.post(scanRunnable)
    }

    private fun performInstallTap(appName: String = ""): Boolean {
        val root = rootInActiveWindow ?: return false

        fun isInputFieldOrSearchBar(node: AccessibilityNodeInfo): Boolean {
            val cls = node.className?.toString() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            if (cls.contains("EditText") || cls.contains("AutoCompleteTextView") || cls.contains("SearchView")) return true
            if (viewId.contains("url_bar") || viewId.contains("search_box") || viewId.contains("search_src_text") ||
                viewId.contains("search_plate") || viewId.contains("location_bar") || viewId.contains("toolbar") || viewId.contains("input")) return true
            return false
        }

        // Checks if a node or its parent container is marked as Sponsored / Ad
        fun isSponsoredContainer(node: AccessibilityNodeInfo): Boolean {
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth < 6) {
                val label = nodeLabel(current).lowercase()
                if (label.contains("sponsored") || label.contains(" ad ") || label.startsWith("ad ") || label.contains("promoted")) {
                    return true
                }
                current = current.parent
                depth++
            }
            return false
        }

        // 1. Try finding Install / Update / Get buttons on active screen that are NOT inside sponsored ads or search inputs
        val keywords = listOf("install", "update", "get", "download", "इन्स्टॉल", "instalar", "installer")
        val exclude = listOf("installed", "installing", "uninstall", "cancel", "search", "query")
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun scanTextNodes(node: AccessibilityNodeInfo) {
            if (isInputFieldOrSearchBar(node)) return

            val label = nodeLabel(node).lowercase()
            if (label.isNotBlank()) {
                val matchesKeyword = keywords.any { label.contains(it) }
                val matchesExclude = exclude.any { label.contains(it) }
                if (matchesKeyword && !matchesExclude && !isSponsoredContainer(node)) {
                    candidates.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scanTextNodes(child)
            }
        }

        scanTextNodes(root)

        for (candidate in candidates.sortedBy { nodeLabel(it).length }) {
            if (!isInputFieldOrSearchBar(candidate) && tapNodeOrParent(candidate)) return true
        }

        // 2. Try finding nodes by Play Store resource ID ("right_button", "install_button", "buy_button", "action_button")
        val idNeedles = listOf("install_button", "right_button", "buy_button", "action_button")
        val idCandidates = mutableListOf<AccessibilityNodeInfo>()
        for (needle in idNeedles) {
            collectByResourceId(root, needle, idCandidates)
        }

        for (candidate in idCandidates) {
            if (!isSponsoredContainer(candidate) && tapNodeOrParent(candidate)) return true
        }

        // 3. If appName is specified and we are on Play Store search list, find non-sponsored card matching appName and tap it
        if (appName.isNotBlank()) {
            val appTitleCandidates = mutableListOf<AccessibilityNodeInfo>()
            fun scanAppTitles(node: AccessibilityNodeInfo) {
                if (isInputFieldOrSearchBar(node)) return
                val label = nodeLabel(node).lowercase()
                if (label.contains(appName.lowercase()) && !isSponsoredContainer(node)) {
                    if (node.isClickable || node.parent?.isClickable == true) {
                        appTitleCandidates.add(node)
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    scanAppTitles(child)
                }
            }
            scanAppTitles(root)
            for (candidate in appTitleCandidates) {
                if (!isInputFieldOrSearchBar(candidate) && tapNodeOrParent(candidate)) return true
            }
        }

        return false
    }

    private fun tapNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return true
            }
            current = current.parent
        }
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            return tapAtAbsoluteCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        return false
    }

    /** Finds clickable nodes whose Android view-id (not label) contains [needle]. */
    private fun collectByResourceId(
        node: AccessibilityNodeInfo,
        needle: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        try {
            val id = node.viewIdResourceName?.lowercase() ?: ""
            if (id.contains(needle)) out.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectByResourceId(child, needle, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "collectByResourceId exception", e)
        }
    }

    /**
     * Finds every currently visible clickable/checkable node whose label
     * contains one of [keywords], then taps the best candidate.
     *
     * A plain "first match wins" search is unreliable on YouTube: a whole
     * channel row (avatar + name + "Subscribe" button) is often exposed as
     * one big accessibility node whose combined label also contains the
     * keyword, e.g. "Example Channel, 1.2M subscribers, Subscribe". If that
     * container is reached before the actual button, tapping it opens the
     * channel page instead of subscribing — which is exactly the bug where
     * "subscribe" opened the channel and reported success anyway.
     *
     * To avoid that, every match is collected first, and the one with the
     * *shortest* label wins — the real button's label ("Subscribe") is
     * always much shorter than a container's combined description.
     */
    private fun clickNodeMatching(vararg keywords: String, exclude: String? = null): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val lowerKeywords = keywords.map { it.lowercase() }
            val lowerExclude = exclude?.lowercase()

            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectMatches(root, lowerKeywords, lowerExclude, candidates)
            if (candidates.isEmpty()) return false

            val target = candidates.minByOrNull { nodeLabel(it).length } ?: return false
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Log.w(TAG, "clickNodeMatching exception", e)
            false
        }
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString() ?: ""

    private fun collectMatches(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        exclude: String?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        try {
            val label = nodeLabel(node).lowercase()
            if (label.isNotBlank()) {
                val matches = keywords.any { label.contains(it) } && (exclude == null || !label.contains(exclude))
                if (matches && (node.isClickable || node.isCheckable)) {
                    out.add(node)
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectMatches(child, keywords, exclude, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "collectMatches exception", e)
        }
    }

    // ---------------------------------------------------------------
    // Generic Mobile Touch, Typing & Gesture Actions
    // ---------------------------------------------------------------

    /** Click any visible node matching [text]. */
    fun clickNodeWithText(text: String): Boolean {
        if (text.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val lowerText = text.lowercase()
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectMatches(root, listOf(lowerText), null, candidates)
        if (candidates.isEmpty()) {
            // Try partial match without clickability restriction, then walk up parents
            val anyNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodesWithText(root, lowerText, anyNodes)
            for (node in anyNodes) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) {
                        return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    current = current.parent
                }
            }
            return false
        }
        val target = candidates.minByOrNull { nodeLabel(it).length } ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Tap at normalized screen percentages (x: 0..100, y: 0..100). */
    fun tapAtPercentage(xPercent: Float, yPercent: Float): Boolean {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return false
        @Suppress("DEPRECATION")
        wm.defaultDisplay?.getRealMetrics(metrics) ?: return false

        val x = (metrics.widthPixels * (xPercent.coerceIn(0f, 100f) / 100f))
        val y = (metrics.heightPixels * (yPercent.coerceIn(0f, 100f) / 100f))

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** Tap at absolute screen pixel coordinates (x, y). */
    fun tapAtAbsoluteCoordinates(x: Float, y: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    /** Type text into the currently focused text field on screen. */
    fun typeText(textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root) ?: return false

        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** Execute system navigation gestures: home, back, recents, scroll_down, scroll_up. */
    fun performSystemGesture(action: String): Boolean {
        return when (action.lowercase()) {
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "recents", "recent_apps" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "scroll_down", "swipe_down" -> scrollScreen(isDown = true)
            "scroll_up", "swipe_up" -> scrollScreen(isDown = false)
            else -> false
        }
    }

    /**
     * Smart screen scrolling & Reels/Shorts auto-change:
     * "scroll_up", "scroll_down", "scroll_to_top", "scroll_to_bottom", "next_reel", "prev_reel"
     */
    fun smartScroll(action: String): Boolean {
        val act = action.lowercase().trim()
        return when {
            act.contains("to_top") || act.contains("boundary_top") -> {
                repeat(5) { scrollScreen(isDown = false); try { Thread.sleep(120) } catch (_: Exception) {} }
                true
            }
            act.contains("to_bottom") || act.contains("boundary_bottom") -> {
                repeat(5) { scrollScreen(isDown = true); try { Thread.sleep(120) } catch (_: Exception) {} }
                true
            }
            act.contains("next_reel") || act.contains("next_short") || act.contains("next") -> {
                swipeVertical(swipeUp = true)
            }
            act.contains("prev_reel") || act.contains("prev_short") || act.contains("previous") -> {
                swipeVertical(swipeUp = false)
            }
            act.contains("up") -> scrollScreen(isDown = false)
            act.contains("down") -> scrollScreen(isDown = true)
            else -> scrollScreen(isDown = true)
        }
    }

    private fun swipeVertical(swipeUp: Boolean): Boolean {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return false
        @Suppress("DEPRECATION")
        wm.defaultDisplay?.getRealMetrics(metrics) ?: return false

        val startY = if (swipeUp) metrics.heightPixels * 0.8f else metrics.heightPixels * 0.2f
        val endY = if (swipeUp) metrics.heightPixels * 0.2f else metrics.heightPixels * 0.8f
        val path = Path().apply {
            moveTo(metrics.widthPixels * 0.5f, startY)
            lineTo(metrics.widthPixels * 0.5f, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Deletes WhatsApp message in active chat screen.
     * [deleteTarget] is "everyone" (Delete for everyone) or "me" (Delete for me).
     */
    fun deleteWhatsAppMessage(deleteTarget: String = "everyone"): Boolean {
        val root = rootInActiveWindow ?: return false

        val messageNodes = mutableListOf<AccessibilityNodeInfo>()
        fun scanChatMessages(node: AccessibilityNodeInfo) {
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val cls = node.className?.toString() ?: ""
            if (viewId.contains("message_text") || viewId.contains("msg_layout") || viewId.contains("conversation_row") ||
                cls.contains("ViewGroup") || cls.contains("RelativeLayout") || cls.contains("LinearLayout")) {
                if (nodeLabel(node).isNotBlank() && (node.isClickable || node.isLongClickable)) {
                    messageNodes.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scanChatMessages(child)
            }
        }

        scanChatMessages(root)

        val targetMsgNode = messageNodes.lastOrNull()
        if (targetMsgNode != null) {
            targetMsgNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } else {
            val metrics = DisplayMetrics()
            val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return false
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(metrics) ?: return false
            val path = Path().apply { moveTo(metrics.widthPixels * 0.7f, metrics.heightPixels * 0.75f) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 800)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }

        try { Thread.sleep(450) } catch (_: Exception) {}

        val deletedIconTapped = clickNodeMatching("delete", "trash") || tapTrashIcon()
        if (!deletedIconTapped) return false

        try { Thread.sleep(350) } catch (_: Exception) {}

        val isEveryone = deleteTarget.lowercase().contains("everyone") || deleteTarget.lowercase().contains("all")
        val optionTapped = if (isEveryone) {
            clickNodeMatching("delete for everyone", "delete for all")
        } else {
            clickNodeMatching("delete for me")
        }

        if (!optionTapped) {
            clickNodeMatching("delete for me", "delete for everyone", "ok", "delete")
        }

        return true
    }

    private fun tapTrashIcon(): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectByResourceId(root, "delete", candidates)
        collectByResourceId(root, "trash", candidates)
        for (c in candidates) {
            if (tapNodeOrParent(c)) return true
        }
        return false
    }

    /** Auto-clicks 'Install' / 'Get' button when Play Store page is active. */
    fun autoInstallPlayStoreApp(): Boolean {
        if (rootInActiveWindow == null) return false
        val keywords = listOf("install", "get", "download", "update")
        for (kw in keywords) {
            if (clickNodeMatching(kw)) return true
        }
        return false
    }

    /** Auto-clicks first video result when YouTube search opens. */
    fun clickFirstVideoResult(): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickableVideoNodes(root, candidates)
        if (candidates.isNotEmpty()) {
            return candidates.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return tapAtPercentage(50f, 30f)
    }

    private fun scrollScreen(isDown: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (isDown) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val scrollable = findScrollableNode(root)
        if (scrollable != null) {
            return scrollable.performAction(action)
        }
        // Fallback swipe gesture
        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return false
        @Suppress("DEPRECATION")
        wm.defaultDisplay?.getRealMetrics(metrics) ?: return false
        val startY = if (isDown) metrics.heightPixels * 0.7f else metrics.heightPixels * 0.3f
        val endY = if (isDown) metrics.heightPixels * 0.3f else metrics.heightPixels * 0.7f
        val path = Path().apply {
            moveTo(metrics.widthPixels * 0.5f, startY)
            lineTo(metrics.widthPixels * 0.5f, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun collectAllNodesWithText(
        node: AccessibilityNodeInfo,
        text: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val label = nodeLabel(node).lowercase()
        if (label.contains(text)) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodesWithText(child, text, out)
        }
    }

    private fun collectClickableVideoNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isClickable && (node.className?.contains("ViewGroup") == true || node.className?.contains("RelativeLayout") == true || node.className?.contains("FrameLayout") == true)) {
            val label = nodeLabel(node)
            if (label.length > 20) out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableVideoNodes(child, out)
        }
    }

    // ---------------------------------------------------------------
    // App Lock Unlocking Action
    // ---------------------------------------------------------------

    /**
     * Unlocks an App Lock screen by entering the PIN/passcode/password into visible input fields
     * or tapping numeric keypad buttons (0-9) sequentially.
     */
    fun unlockAppLock(passcode: String): Boolean {
        if (passcode.isBlank()) return false
        val root = rootInActiveWindow ?: return false

        var unlocked = false

        // 1. Try entering full string into editable text fields (password / PIN field)
        val editableNode = findFirstEditableNode(root)
        if (editableNode != null) {
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, passcode)
            }
            val setOk = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (setOk) {
                unlocked = true
                clickNodeMatching("ok", "enter", "done", "submit", "unlock")
            }
        }

        // 2. If keypad digits exist, tap digit buttons 0-9 sequentially
        if (!unlocked || passcode.all { it.isDigit() }) {
            var digitTapCount = 0
            for (ch in passcode) {
                if (ch.isDigit()) {
                    val digitStr = ch.toString()
                    val tapped = clickDigitKeypadButton(root, digitStr)
                    if (tapped) {
                        digitTapCount++
                        try { Thread.sleep(80L) } catch (_: Exception) {}
                    }
                }
            }
            if (digitTapCount > 0) {
                unlocked = true
                clickNodeMatching("ok", "enter", "done", "submit", "unlock")
            }
        }

        return unlocked
    }

    private fun clickDigitKeypadButton(root: AccessibilityNodeInfo, digit: String): Boolean {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        fun scanDigitNodes(node: AccessibilityNodeInfo) {
            val label = nodeLabel(node).trim()
            if (label == digit && (node.isClickable || node.parent?.isClickable == true)) {
                candidates.add(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scanDigitNodes(child)
            }
        }
        scanDigitNodes(root)

        for (candidate in candidates) {
            if (tapNodeOrParent(candidate)) return true
        }

        return clickNodeWithText(digit)
    }

    // ---------------------------------------------------------------
    // Seek gestures (double-tap left/right, mirroring YouTube's own UX)
    // ---------------------------------------------------------------

    fun seekForward(): Boolean = doubleTapAt(xFraction = 0.8f)

    fun seekBackward(): Boolean = doubleTapAt(xFraction = 0.2f)

    private fun doubleTapAt(xFraction: Float): Boolean {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return false
        @Suppress("DEPRECATION")
        wm.defaultDisplay?.getRealMetrics(metrics) ?: return false

        val x = metrics.widthPixels * xFraction
        val y = metrics.heightPixels * 0.5f

        val path = Path().apply { moveTo(x, y) }
        val firstTap = GestureDescription.StrokeDescription(path, 0, 50)
        val secondTap = GestureDescription.StrokeDescription(path, 150, 50)

        val gesture1 = GestureDescription.Builder().addStroke(firstTap).build()
        val gesture2 = GestureDescription.Builder().addStroke(secondTap).build()

        val dispatched1 = dispatchGesture(gesture1, null, null)
        return if (dispatched1) {
            dispatchGesture(gesture2, null, null)
        } else {
            false
        }
    }

    // ---------------------------------------------------------------
    // Auto-Tap Chooser Dialog for Dual Apps (Vivo, Samsung, Xiaomi, etc.)
    // ---------------------------------------------------------------

    /**
     * Called when opening a dual/cloned app instance. If an OEM system chooser popup
     * (e.g. Vivo/Samsung/Xiaomi dual app dialog) appears on screen, automatically taps
     * choice 1 or 2 based on [appNumber].
     */
    fun handleDualAppSelection(appName: String, appNumber: Int) {
        val targetIndex = (appNumber - 1).coerceAtLeast(0)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        mainHandler.postDelayed(object : Runnable {
            var attempts = 0
            override fun run() {
                val root = rootInActiveWindow
                if (root != null) {
                    val appChoices = mutableListOf<AccessibilityNodeInfo>()
                    collectAppChooserNodes(root, appName, appChoices)

                    if (appChoices.size >= 2 && targetIndex < appChoices.size) {
                        val targetNode = appChoices[targetIndex]
                        var clickable: AccessibilityNodeInfo? = targetNode
                        while (clickable != null && !clickable.isClickable) {
                            clickable = clickable.parent
                        }
                        val finalTarget = clickable ?: targetNode
                        finalTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return
                    }
                }

                attempts++
                if (attempts < 8) {
                    mainHandler.postDelayed(this, 250)
                }
            }
        }, 200)
    }

    private fun collectAppChooserNodes(
        node: AccessibilityNodeInfo,
        appName: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val label = nodeLabel(node).lowercase()
        val lowerAppName = appName.lowercase()

        if (label.isNotBlank() && (label.contains(lowerAppName) || label.contains("clone") || label.contains("dual") || label.contains("whatsapp"))) {
            if (node.isClickable || (node.parent != null && node.parent.isClickable)) {
                if (!out.contains(node)) {
                    out.add(node)
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAppChooserNodes(child, appName, out)
        }
    }

    /**
     * Monitors the screen after a WhatsApp deep-link is launched and automatically taps the Send button
     * (content-desc="Send" or id="send" or send icon) for hands-free message delivery.
     */
    fun scheduleWhatsAppAutoSend() {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.postDelayed(object : Runnable {
            var attempts = 0
            override fun run() {
                val root = rootInActiveWindow
                if (root != null) {
                    val sendNode = findWhatsAppSendButton(root)
                    if (sendNode != null) {
                        tapNodeOrParent(sendNode)
                        return
                    }
                }

                attempts++
                if (attempts < 12) {
                    mainHandler.postDelayed(this, 300)
                }
            }
        }, 500)
    }

    private fun findWhatsAppSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (desc == "send" || desc == "send message" || text == "send" || viewId.endsWith(":id/send")) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findWhatsAppSendButton(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * Monitors the active WhatsApp chat screen after launching a chat and auto-taps the Voice Call or Video Call icon.
     * Also detects and auto-confirms the WhatsApp modal "Start voice call?" / "Call" confirmation dialog if present.
     */
    fun scheduleWhatsAppCallAutoTap(callType: String) {
        val isVideo = callType.lowercase().contains("video")
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.postDelayed(object : Runnable {
            var attempts = 0
            var callButtonTapped = false

            override fun run() {
                val root = rootInActiveWindow
                if (root != null) {
                    if (!callButtonTapped) {
                        val callNode = findWhatsAppCallButton(root, isVideo)
                        if (callNode != null) {
                            val success = tapDirectOrImmediateClickable(callNode)
                            if (success) {
                                callButtonTapped = true
                                Log.d(TAG, "Tapped WhatsApp call button, watching for confirmation dialog...")
                            }
                        }
                    } else {
                        // After tapping call icon, check if WhatsApp shows confirmation dialog ("Call" / "Start call" / android:id/button1)
                        val confirmNode = findWhatsAppCallConfirmButton(root)
                        if (confirmNode != null) {
                            tapNodeOrParent(confirmNode)
                            Log.d(TAG, "Tapped WhatsApp Call confirmation dialog button!")
                            return
                        }
                    }
                }

                attempts++
                if (attempts < 16) {
                    mainHandler.postDelayed(this, 350)
                }
            }
        }, 500)
    }

    private fun tapDirectOrImmediateClickable(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) return true
        }
        val parent = node.parent
        if (parent != null && parent.isClickable) {
            val parentId = parent.viewIdResourceName?.lowercase() ?: ""
            // Ensure parent is not the whole conversation header or toolbar to avoid profile photo clicks
            if (!parentId.contains("header") && !parentId.contains("action_bar") && !parentId.contains("toolbar")) {
                val ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return true
            }
        }
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            return tapAtAbsoluteCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        return false
    }

    private fun findWhatsAppCallConfirmButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (viewId.endsWith(":id/button1") ||
            text == "call" || text == "video call" || text == "start call" || text == "कॉल करें" || text == "कॉल" ||
            desc == "call" || desc == "video call" || desc == "start call") {
            if (node.isClickable || node.parent?.isClickable == true) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findWhatsAppCallConfirmButton(child)
            if (res != null) return res
        }
        return null
    }

    private fun findWhatsAppCallButton(node: AccessibilityNodeInfo, isVideo: Boolean): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // Strictly avoid profile photo, avatar, conversation title, or back buttons
        if (desc.contains("profile") || desc.contains("photo") || desc.contains("picture") ||
            desc.contains("avatar") || desc.contains("header") || desc.contains("navigate up") ||
            desc.contains("back") || viewId.contains("picture") || viewId.contains("avatar")) {
            return null
        }

        val isTarget = if (isVideo) {
            (desc.contains("video call") || viewId.endsWith(":id/video_call") || viewId.endsWith(":id/menuitem_video_call")) &&
                    !desc.contains("voice")
        } else {
            (desc == "voice call" || desc == "call" || desc.startsWith("voice call") ||
                    viewId.endsWith(":id/voice_call") || viewId.endsWith(":id/menuitem_call")) &&
                    !desc.contains("video")
        }

        if (isTarget && (node.isClickable || node.parent?.isClickable == true)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findWhatsAppCallButton(child, isVideo)
            if (result != null) return result
        }
        return null
    }

    /**
     * Continuously scans Chrome web page over several seconds to find and tap download links/buttons.
     * Excludes Chrome's URL bar, search boxes, and text input fields.
     */
    fun startSongDownloadScanner(songName: String = "") {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var attempts = 0
        val maxAttempts = 20 // 20 attempts * 500ms = 10 seconds total scan window

        val scanRunnable = object : Runnable {
            override fun run() {
                attempts++
                val clicked = performSongDownloadTap(songName)
                if (clicked) {
                    android.util.Log.d(TAG, "Successfully tapped Download link for '$songName' on attempt $attempts")
                    return
                }
                if (attempts < maxAttempts) {
                    handler.postDelayed(this, 500L)
                } else {
                    android.util.Log.w(TAG, "Finished scanning Chrome page for '$songName' download links after $maxAttempts attempts.")
                }
            }
        }
        // Delay 2 seconds initially so Chrome opens and page loads before scanning
        handler.postDelayed(scanRunnable, 2000L)
    }

    private fun performSongDownloadTap(songName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val keywords = listOf("download mp3", "download song", "320kbps", "128kbps", "download audio", "download file", "direct download")
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun isInputFieldOrSearchBar(node: AccessibilityNodeInfo): Boolean {
            val cls = node.className?.toString() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            if (cls.contains("EditText") || cls.contains("AutoCompleteTextView")) return true
            if (viewId.contains("url_bar") || viewId.contains("search_box") || viewId.contains("search_src_text") ||
                viewId.contains("search_plate") || viewId.contains("location_bar") || viewId.contains("toolbar")) return true
            return false
        }

        fun scanDownloadNodes(node: AccessibilityNodeInfo) {
            if (isInputFieldOrSearchBar(node)) {
                return // Do not process search bar or input field
            }

            val label = nodeLabel(node).lowercase()
            if (label.isNotBlank()) {
                // Ignore search query text echoed in search result header or search bar
                val isSearchQueryEcho = songName.isNotBlank() && label == songName.lowercase()
                if (!isSearchQueryEcho && keywords.any { label.contains(it) }) {
                    candidates.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scanDownloadNodes(child)
            }
        }

        scanDownloadNodes(root)

        val target = candidates.firstOrNull { node ->
            !isInputFieldOrSearchBar(node) && (node.isClickable || node.parent?.isClickable == true)
        }

        if (target != null) {
            return tapNodeOrParent(target)
        }

        // Phase 2: If no direct download button found on active screen, tap top search result link
        val searchResultCandidates = mutableListOf<AccessibilityNodeInfo>()
        fun scanSearchResultNodes(node: AccessibilityNodeInfo) {
            if (isInputFieldOrSearchBar(node)) return
            val label = nodeLabel(node).lowercase()
            if (label.isNotBlank() && label.length > 8) {
                val isSearchQueryEcho = songName.isNotBlank() && label == songName.lowercase()
                val isResultTitle = label.contains("mp3") || label.contains("song") || label.contains("download") ||
                        label.contains("pagalworld") || label.contains("songspk") || label.contains("jiosaavn") ||
                        (songName.isNotBlank() && label.contains(songName.lowercase()))
                if (!isSearchQueryEcho && isResultTitle) {
                    searchResultCandidates.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scanSearchResultNodes(child)
            }
        }

        scanSearchResultNodes(root)

        val resultTarget = searchResultCandidates.firstOrNull { node ->
            !isInputFieldOrSearchBar(node) && (node.isClickable || node.parent?.isClickable == true)
        }

        if (resultTarget != null) {
            android.util.Log.d(TAG, "Tapping search result link: ${nodeLabel(resultTarget)}")
            return tapNodeOrParent(resultTarget)
        }

        return false
    }

    /**
     * Dedicated scanner for pagalnew.com song downloading:
     * Step 1: Tap first search result link on Google for pagalnew.com
     * Step 2: Once pagalnew page opens, scroll down if needed and tap 320 Kbps or 128 Kbps download button!
     */
    fun startPagalNewSongScanner(songName: String = "") {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        // Step 1: Tap top pagalnew.com search result link on Google
        handler.postDelayed({
            val tappedLink = performPagalNewResultTap(songName)
            android.util.Log.d(TAG, "PagalNew Step 1 link tap result: $tappedLink")

            // Step 2: On pagalnew song page, scan for 320kbps / 128kbps download buttons
            var downloadAttempts = 0
            val maxDownloadAttempts = 16

            val downloadScanRunnable = object : Runnable {
                override fun run() {
                    downloadAttempts++
                    var clicked = performPagalNewDownloadTap()
                    
                    // If download button not found on screen yet, scroll down slightly to reveal download options
                    if (!clicked && (downloadAttempts == 3 || downloadAttempts == 6)) {
                        android.util.Log.d(TAG, "Scrolling down pagalnew page to reveal download options...")
                        scrollScreen(isDown = true)
                    }

                    if (clicked) {
                        android.util.Log.d(TAG, "Successfully tapped pagalnew download button on attempt $downloadAttempts")
                        return
                    }

                    if (downloadAttempts < maxDownloadAttempts) {
                        handler.postDelayed(this, 600L)
                    }
                }
            }
            // Delay 2.5 seconds after tapping link so song page opens
            handler.postDelayed(downloadScanRunnable, 2500L)
        }, 1800L)
    }

    private fun performPagalNewResultTap(songName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun scanResult(node: AccessibilityNodeInfo) {
            val cls = node.className?.toString() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            if (cls.contains("EditText") || viewId.contains("url_bar") || viewId.contains("search_box")) return

            val label = nodeLabel(node).lowercase()
            if (label.isNotBlank() && label.length > 6) {
                if (label.contains("pagalnew") || (songName.isNotBlank() && label.contains(songName.lowercase()))) {
                    candidates.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scanResult(child)
            }
        }

        scanResult(root)

        val target = candidates.firstOrNull { node -> node.isClickable || node.parent?.isClickable == true }
        if (target != null) {
            return tapNodeOrParent(target)
        }
        return false
    }

    private fun performPagalNewDownloadTap(): Boolean {
        val root = rootInActiveWindow ?: return false
        val keywords = listOf("320 kbps", "128 kbps", "320kbps", "128kbps", "download 320", "download 128", "download mp3", "download song", "download")
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun scanDownload(node: AccessibilityNodeInfo) {
            val cls = node.className?.toString() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            if (cls.contains("EditText") || viewId.contains("url_bar") || viewId.contains("search_box")) return

            val label = nodeLabel(node).lowercase()
            if (label.isNotBlank()) {
                if (keywords.any { label.contains(it) }) {
                    candidates.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scanDownload(child)
            }
        }

        scanDownload(root)

        // Prioritize 320kbps or 128kbps buttons
        val bestTarget = candidates.firstOrNull { node ->
            val label = nodeLabel(node).lowercase()
            (label.contains("320") || label.contains("128")) && (node.isClickable || node.parent?.isClickable == true)
        } ?: candidates.firstOrNull { node -> node.isClickable || node.parent?.isClickable == true }

        if (bestTarget != null) {
            return tapNodeOrParent(bestTarget)
        }
        return false
    }

    // ---------------------------------------------------------------
    // Universal Settings & Developer Options Automation Hub
    // ---------------------------------------------------------------

    /**
     * Toggles a setting switch (Wi-Fi, Bluetooth, Airplane mode, etc.) to target state asynchronously.
     */
    fun automateUniversalSettingToggle(settingName: String, targetState: Boolean, onResult: ((Boolean, String) -> Unit)? = null) {
        val cleanName = settingName.lowercase().trim()
        serviceScope.launch(Dispatchers.Default) {
            var targetSwitch: AccessibilityNodeInfo? = null

            for (attempt in 1..10) {
                delay(300)
                val root = rootInActiveWindow ?: continue

                val switchNodes = mutableListOf<AccessibilityNodeInfo>()
                fun collectSwitches(node: AccessibilityNodeInfo) {
                    val cls = node.className?.toString() ?: ""
                    if (cls.contains("Switch") || cls.contains("CheckBox") || cls.contains("ToggleButton") || cls.contains("CompoundButton")) {
                        switchNodes.add(node)
                    }
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        collectSwitches(child)
                    }
                }
                collectSwitches(root)

                if (switchNodes.size == 1) {
                    targetSwitch = switchNodes.first()
                    break
                }

                for (sw in switchNodes) {
                    var parent: AccessibilityNodeInfo? = sw.parent
                    var depth = 0
                    while (parent != null && depth < 4) {
                        val label = nodeLabel(parent).lowercase()
                        if (label.contains(cleanName) || (cleanName == "wifi" && label.contains("wi-fi")) || (cleanName == "bluetooth" && label.contains("bluetooth"))) {
                            targetSwitch = sw
                            break
                        }
                        parent = parent.parent
                        depth++
                    }
                    if (targetSwitch != null) break
                }
                if (targetSwitch != null) break
            }

            if (targetSwitch == null) {
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, "I couldn't find the $settingName toggle on screen, Sir.")
                }
                return@launch
            }

            // Only tap if the current state differs from what was asked for.
            if (targetSwitch.isChecked != targetState) {
                val bounds = android.graphics.Rect()
                targetSwitch.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    tapAtAbsoluteCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                } else {
                    tapNodeOrParent(targetSwitch)
                }
                delay(400)
            }

            // Verify.
            var verified: Boolean? = null
            for (i in 0 until 4) {
                verified = targetSwitch.isChecked
                if (verified == targetState) break
                delay(250)
                targetSwitch.refresh()
            }

            withContext(Dispatchers.Main) {
                if (verified == targetState) {
                    onResult?.invoke(true, "$settingName turned ${if (targetState) "ON" else "OFF"}, Sir.")
                    delay(400)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                } else {
                    onResult?.invoke(false, "I tapped the $settingName toggle but it's still ${if (verified == true) "ON" else "OFF"}, Sir.")
                }
            }
        }
    }

    /**
     * Automates connecting to a specific Wi-Fi SSID, entering password, and clicking Connect.
     */
    fun automateWifiConnect(ssid: String, password: String) {
        serviceScope.launch(Dispatchers.Default) {
            for (attempt in 1..14) {
                delay(500)
                val root = rootInActiveWindow ?: continue

                // Check if password dialog is already open
                val editable = findFirstEditableNode(root)
                if (editable != null && password.isNotBlank()) {
                    val args = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password)
                    }
                    editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    delay(250)
                    clickNodeMatching("connect", "join", "save", "done", "ok")
                    Log.d(TAG, "Wi-Fi password entered and Connect tapped for $ssid")
                    break
                }

                // Otherwise, find and tap the SSID row in the network list
                val candidates = mutableListOf<AccessibilityNodeInfo>()
                collectAllNodesWithText(root, ssid.lowercase(), candidates)
                val ssidNode = candidates.firstOrNull { it.isClickable || it.parent?.isClickable == true }
                if (ssidNode != null) {
                    tapNodeOrParent(ssidNode)
                    Log.d(TAG, "Tapped Wi-Fi SSID row: $ssid")
                    if (password.isBlank()) break
                }
            }
        }
    }

    /**
     * Automates clicking a Bluetooth device in Bluetooth settings and confirming pairing.
     */
    fun automateBluetoothConnect(deviceName: String) {
        serviceScope.launch(Dispatchers.Default) {
            for (attempt in 1..12) {
                delay(600)
                val root = rootInActiveWindow ?: continue

                // Confirm pair dialog if visible
                if (clickNodeMatching("pair", "allow access", "connect", "ok", "confirm")) {
                    Log.d(TAG, "Confirmed Bluetooth pairing dialog.")
                    break
                }

                val candidates = mutableListOf<AccessibilityNodeInfo>()
                collectAllNodesWithText(root, deviceName.lowercase(), candidates)
                val devNode = candidates.firstOrNull { it.isClickable || it.parent?.isClickable == true }
                if (devNode != null) {
                    tapNodeOrParent(devNode)
                    Log.d(TAG, "Tapped Bluetooth device: $deviceName")
                }
            }
        }
    }

    /**
     * Automates toggling Personal Hotspot switch.
     */
    fun automateHotspotControl(enable: Boolean) {
        serviceScope.launch(Dispatchers.Default) {
            for (attempt in 1..8) {
                delay(400)
                val root = rootInActiveWindow ?: continue
                val keywords = listOf("personal hotspot", "wi-fi hotspot", "portable hotspot", "hotspot", "tethering")
                for (kw in keywords) {
                    automateUniversalSettingToggle(kw, enable)
                }
            }
        }
    }

    /**
     * Reads Hotspot password from the settings screen.
     */
    fun automateReadHotspotPassword(): String? {
        val root = rootInActiveWindow ?: return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun scanNodes(node: AccessibilityNodeInfo) {
            val label = nodeLabel(node)
            if (label.isNotBlank()) {
                candidates.add(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scanNodes(child)
            }
        }
        scanNodes(root)

        for (i in candidates.indices) {
            val node = candidates[i]
            val label = nodeLabel(node).lowercase()
            if (label.contains("password") || label.contains("security key") || label.contains("passphrase") || label.contains("hotspot password")) {
                if (label.length > 15 && !label.startsWith("set") && !label.startsWith("change")) {
                    val parts = label.split(":", "-", "\n")
                    if (parts.size > 1 && parts[1].trim().length >= 8) {
                        return parts[1].trim()
                    }
                }
                val nextNode = candidates.getOrNull(i + 1)
                if (nextNode != null) {
                    val nextLabel = nodeLabel(nextNode).trim()
                    if (nextLabel.length >= 8 && !nextLabel.contains("hotspot", ignoreCase = true) && !nextLabel.contains("configure", ignoreCase = true)) {
                        return nextLabel
                    }
                }
            }
        }
        return null
    }

    /**
     * Switches mobile data to SIM 1 or SIM 2 automatically (pulls Control Center or opens Network Settings).
     */
    fun automateSimDataSwitch(targetSimSlot: Int) {
        val targetLabel = "sim $targetSimSlot"
        val altLabel = "sim$targetSimSlot"
        val slotLabel = "slot $targetSimSlot"
        val cardLabel = "card $targetSimSlot"

        serviceScope.launch(Dispatchers.Default) {
            var switched = false

            // Step 1: Automatically pull down Quick Settings / Control Center
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            delay(700)

            for (attempt in 1..4) {
                val root = rootInActiveWindow
                if (root != null) {
                    val candidates = mutableListOf<AccessibilityNodeInfo>()
                    fun searchSim(node: AccessibilityNodeInfo) {
                        val label = nodeLabel(node).lowercase()
                        if (label.contains(targetLabel) || label.contains(altLabel) || label.contains(slotLabel) || label.contains(cardLabel)) {
                            candidates.add(node)
                        }
                        for (i in 0 until node.childCount) {
                            val child = node.getChild(i) ?: continue
                            searchSim(child)
                        }
                    }
                    searchSim(root)

                    for (cand in candidates) {
                        if (tapNodeOrParent(cand)) {
                            Log.d(TAG, "Tapped SIM candidate in Quick Settings: ${nodeLabel(cand)}")
                            delay(400)
                            clickNodeMatching("switch", "change", "confirm", "ok", "use", "yes")
                            switched = true
                            break
                        }
                    }
                    if (switched) break

                    // Tap mobile data tile in quick settings to open SIM chooser
                    clickNodeMatching("mobile data", "data", "internet", "default data")
                }
                delay(400)
            }

            // Step 2: If not in Quick Settings, open Network Settings directly
            if (!switched) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(300)
                val intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try { startActivity(intent) } catch (_: Exception) {}
                delay(800)

                for (attempt in 1..8) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        val candidates = mutableListOf<AccessibilityNodeInfo>()
                        fun searchSim(node: AccessibilityNodeInfo) {
                            val label = nodeLabel(node).lowercase()
                            if (label.contains(targetLabel) || label.contains(altLabel) || label.contains(slotLabel) || label.contains(cardLabel)) {
                                candidates.add(node)
                            }
                            for (i in 0 until node.childCount) {
                                val child = node.getChild(i) ?: continue
                                searchSim(child)
                            }
                        }
                        searchSim(root)

                        for (cand in candidates) {
                            if (tapNodeOrParent(cand)) {
                                Log.d(TAG, "Tapped SIM candidate in Settings: ${nodeLabel(cand)}")
                                delay(400)
                                clickNodeMatching("switch", "change", "confirm", "ok", "use", "yes")
                                switched = true
                                break
                            }
                        }
                        if (switched) break

                        val keywords = listOf(
                            "default mobile data", "data sim", "default sim", "mobile data sim",
                            "internet", "mobile data", "sim card", "cellular data"
                        )
                        for (kw in keywords) {
                            if (clickNodeMatching(kw)) {
                                delay(400)
                                break
                            }
                        }
                        clickNodeMatching("switch", "change", "confirm", "ok", "yes", "allow")
                    }
                    delay(400)
                }
            }

            // Step 3: Automatically return to previous screen
            delay(500)
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /**
     * Navigates Developer Options, scrolls to find [optionName] ("usb debugging" or "wireless debugging"),
     * toggles the switch to [enable], confirms any system prompt, and VERIFIES the final on-screen state
     * before reporting success. [onResult] always fires exactly once with the true outcome — this function
     * never claims success it didn't confirm.
     */
    fun automateDeveloperOptionToggle(optionName: String, enable: Boolean, onResult: ((Boolean, String) -> Unit)? = null) {
        val cleanName = optionName.lowercase().trim()
        serviceScope.launch(Dispatchers.Default) {
            var scrollAttempts = 0
            val maxScrolls = 8
            var switchNode: AccessibilityNodeInfo? = null

            // 1. Locate the row + its switch.
            findLoop@ while (scrollAttempts < maxScrolls) {
                val root = rootInActiveWindow
                if (root != null) {
                    val candidates = mutableListOf<AccessibilityNodeInfo>()
                    collectAllNodesWithText(root, cleanName, candidates)

                    for (target in candidates) {
                        var parent: AccessibilityNodeInfo? = target
                        var depth = 0
                        var found: AccessibilityNodeInfo? = null
                        while (parent != null && depth < 4) {
                            for (i in 0 until parent.childCount) {
                                val child = parent.getChild(i) ?: continue
                                val cls = child.className?.toString() ?: ""
                                if (cls.contains("Switch") || cls.contains("CheckBox") || cls.contains("ToggleButton")) {
                                    found = child
                                    break
                                }
                            }
                            if (found != null) break
                            parent = parent.parent
                            depth++
                        }
                        if (found != null) {
                            switchNode = found
                            break@findLoop
                        }
                    }
                }
                scrollScreen(isDown = true)
                scrollAttempts++
                delay(450)
            }

            if (switchNode == null) {
                Log.w(TAG, "automateDeveloperOptionToggle: could not locate '$cleanName' row on screen")
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, "I couldn't find \"$optionName\" on screen, Sir. It may be on a different Developer Options layout for this device.")
                }
                return@launch
            }

            // 2. Check current state — only tap if it's not already what was asked for.
            val alreadyCorrect = switchNode.isChecked == enable
            if (!alreadyCorrect) {
                val bounds = android.graphics.Rect()
                switchNode.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    tapAtAbsoluteCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                } else {
                    tapNodeOrParent(switchNode)
                }
                delay(400)
                // Confirm any resulting system dialog (e.g. "Allow USB debugging?")
                clickNodeMatching("ok", "allow", "always allow from this computer", "turn on", "enable")
                delay(400)
            }

            // 3. Verify the final state by re-reading the row from a fresh node tree.
            var verifiedOn: Boolean? = null
            for (i in 0 until 5) {
                val freshRoot = rootInActiveWindow
                if (freshRoot != null) {
                    val fresh = mutableListOf<AccessibilityNodeInfo>()
                    collectAllNodesWithText(freshRoot, cleanName, fresh)
                    for (target in fresh) {
                        var parent: AccessibilityNodeInfo? = target
                        var depth = 0
                        while (parent != null && depth < 4) {
                            for (j in 0 until parent.childCount) {
                                val child = parent.getChild(j) ?: continue
                                val cls = child.className?.toString() ?: ""
                                if (cls.contains("Switch") || cls.contains("CheckBox") || cls.contains("ToggleButton")) {
                                    verifiedOn = child.isChecked
                                    break
                                }
                            }
                            if (verifiedOn != null) break
                            parent = parent.parent
                            depth++
                        }
                        if (verifiedOn != null) break
                    }
                }
                if (verifiedOn != null) break
                delay(300)
            }

            val success = verifiedOn == enable
            withContext(Dispatchers.Main) {
                if (success) {
                    onResult?.invoke(true, "$optionName has been ${if (enable) "turned on" else "turned off"}, Sir.")
                } else if (verifiedOn == null) {
                    onResult?.invoke(false, "I tapped $optionName but couldn't confirm the new state, Sir. Please check the screen.")
                } else {
                    onResult?.invoke(false, "I tried, but $optionName is still ${if (verifiedOn) "on" else "off"}, Sir. There may be a system prompt waiting for you.")
                }
            }
        }
    }

    /**
     * Taps "Build number" 7 times in About Phone to unlock Developer Options if locked.
     */
    fun unlockDeveloperOptions() {
        serviceScope.launch(Dispatchers.Default) {
            for (scroll in 1..5) {
                val root = rootInActiveWindow
                if (root != null) {
                    val candidates = mutableListOf<AccessibilityNodeInfo>()
                    collectAllNodesWithText(root, "build number", candidates)
                    collectAllNodesWithText(root, "software version", candidates)
                    collectAllNodesWithText(root, "version", candidates)

                    val buildNode = candidates.firstOrNull { it.isClickable || it.parent?.isClickable == true }
                    if (buildNode != null) {
                        for (i in 1..7) {
                            tapNodeOrParent(buildNode)
                            delay(120)
                        }
                        Log.d(TAG, "Tapped Build Number 7 times.")
                        break
                    }
                }
                scrollScreen(isDown = true)
                delay(500)
            }
        }
    }
}