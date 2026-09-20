package com.jarvis.assistant.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.util.WhatsAppNotificationManager

/**
 * Listens for incoming WhatsApp notifications to enable hands-free voice interaction:
 * - Reads out incoming messages
 * - Captures RemoteInput PendingIntent for background direct replies
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotifListener"
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private const val DEDUP_WINDOW_MS = 45_000L // 45 seconds suppression for identical messages

        // LRU Cache for recently announced message signatures: "sender:text" -> timestamp
        private val announcedMessages = object : java.util.LinkedHashMap<String, Long>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 50
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: return
        if (packageName !in WHATSAPP_PACKAGES) return

        val notification = sbn.notification ?: return

        // Check if user disabled Notification Reader in Settings
        val prefs = getSharedPreferences(com.jarvis.assistant.JarvisApplication.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notification_reader_enabled", true)) {
            Log.d(TAG, "Notification Reader is disabled in Settings, skipping.")
            return
        }

        // Skip group summaries and ongoing background service notifications
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        // Ignore stale notifications older than 2 minutes (e.g. initial dump on reconnect)
        val now = System.currentTimeMillis()
        if (sbn.postTime > 0 && now - sbn.postTime > 120_000L) return

        val extras = notification.extras ?: return
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""

        if (text.isBlank() && bigText.isNotBlank()) {
            text = bigText
        }

        // Try extracting from MessagingStyle (WhatsApp standard on modern Android)
        var isOutgoingMessage = false
        try {
            val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            if (messagingStyle != null && messagingStyle.messages.isNotEmpty()) {
                val user = messagingStyle.user
                val userName = user?.name?.toString()?.trim()?.lowercase()

                // Check the very last message in the conversation thread
                val lastMsg = messagingStyle.messages.last()
                val lastPersonName = lastMsg.person?.name?.toString()?.trim()
                val lastPersonLower = lastPersonName?.lowercase() ?: ""
                val senderName = lastMsg.sender?.toString()?.trim()
                val senderLower = senderName?.lowercase() ?: ""

                // Detect if the latest message was sent by US (outgoing / self-reply)
                val isLastMsgFromSelf = (lastMsg.person == null && senderName.isNullOrBlank()) ||
                    (lastMsg.person != null && user != null && lastMsg.person == user) ||
                    lastPersonLower in setOf("you", "me", "aap", "आप") ||
                    senderLower in setOf("you", "me", "aap", "आप") ||
                    (!userName.isNullOrBlank() && (lastPersonLower == userName || senderLower == userName))

                if (isLastMsgFromSelf) {
                    // This notification update was caused by our own reply! Do NOT announce it.
                    Log.d(TAG, "Ignoring self-sent outgoing message: '${lastMsg.text}'")
                    isOutgoingMessage = true
                } else {
                    // The latest message is indeed from an external sender
                    val validName = when {
                        !lastPersonName.isNullOrBlank() -> lastPersonName
                        !senderName.isNullOrBlank() -> senderName
                        else -> null
                    }
                    if (!validName.isNullOrBlank()) {
                        title = validName
                    }
                    val msgContent = lastMsg.text?.toString()?.trim()
                    if (!msgContent.isNullOrBlank()) {
                        text = msgContent
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MessagingStyle extraction exception: ${e.message}")
        }

        if (isOutgoingMessage) return

        // Check if this text was recently sent as a direct reply by JARVIS
        if (WhatsAppNotificationManager.isRecentlySentReply(text)) {
            Log.d(TAG, "Ignoring recently sent WhatsApp direct reply: '$text'")
            return
        }

        // Fallbacks for title and text
        if (title.isBlank()) {
            title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim() ?: ""
        }
        if (text.isBlank()) {
            text = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        }

        // Ignore empty messages, self titles, WhatsApp status, or backup notices
        if (title.isBlank() || text.isBlank()) return
        val lowerTitle = title.lowercase()
        val lowerText = text.lowercase()
        if (lowerTitle == "whatsapp" ||
            lowerTitle in setOf("you", "me", "aap", "आप") ||
            lowerText.startsWith("you:") ||
            lowerText.startsWith("you :") ||
            lowerText.startsWith("aap:") ||
            lowerText.startsWith("aap :") ||
            lowerText.startsWith("me:") ||
            lowerText.startsWith("आप:") ||
            lowerText.startsWith("आप :") ||
            lowerTitle.contains("backup") ||
            lowerTitle.contains("checking for new messages") ||
            lowerTitle.contains("whatsapp web") ||
            (lowerText.contains("messages") && lowerText.contains("chats")) ||
            lowerText.contains("backup in progress") ||
            lowerText.contains("finished backup")
        ) {
            return
        }

        if (WhatsAppNotificationManager.isRecentlySentReply(text)) {
            return
        }

        // DEDUPLICATION: Prevent announcing the exact same message repeatedly
        val dedupKey = "$lowerTitle:$lowerText".trim()
        synchronized(announcedMessages) {
            val lastTime = announcedMessages[dedupKey]
            if (lastTime != null && (now - lastTime) < DEDUP_WINDOW_MS) {
                Log.d(TAG, "Suppressed duplicate WhatsApp notification for: '$dedupKey' (${now - lastTime}ms ago)")
                return
            }
            announcedMessages[dedupKey] = now
        }

        // Look for direct reply action with RemoteInput (both compat and framework)
        var replyAction: NotificationCompat.Action? = null
        val actions = NotificationCompat.getActionCount(notification)
        for (i in 0 until actions) {
            val act = NotificationCompat.getAction(notification, i) ?: continue
            val remoteInputs = act.remoteInputs
            if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                replyAction = act
                break
            }
        }

        var frameworkAction: Notification.Action? = null
        if (replyAction == null && notification.actions != null) {
            for (act in notification.actions) {
                if (act.remoteInputs != null && act.remoteInputs.isNotEmpty()) {
                    frameworkAction = act
                    break
                }
            }
        }

        Log.i(TAG, "Incoming WhatsApp from: '$title' text: '$text' (canReply=${replyAction != null || frameworkAction != null})")

        // Store message state for voice direct replies
        WhatsAppNotificationManager.onNewMessage(
            sender = title,
            text = text,
            packageName = packageName,
            replyAction = replyAction,
            pendingIntent = replyAction?.actionIntent ?: frameworkAction?.actionIntent,
            remoteInputs = replyAction?.remoteInputs,
            frameworkRemoteInputs = frameworkAction?.remoteInputs
        )

        // Notify active Jarvis voice session
        JarvisVoiceService.instance?.handleIncomingWhatsAppNotification(title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
