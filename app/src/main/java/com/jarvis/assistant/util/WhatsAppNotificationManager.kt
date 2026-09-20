package com.jarvis.assistant.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput as CompatRemoteInput
import android.app.RemoteInput as FrameworkRemoteInput

/**
 * Manages incoming WhatsApp notifications, keeps track of the latest
 * message state, and dispatches background direct replies via Android RemoteInput.
 */
object WhatsAppNotificationManager {

    private const val TAG = "WhatsAppNotifManager"
    private const val EXPIRY_MILLIS = 2 * 60 * 1000L // 2 minutes auto-timeout

    data class IncomingWhatsAppMessage(
        val sender: String,
        val text: String,
        val timestamp: Long,
        val packageName: String,
        val replyAction: NotificationCompat.Action?,
        val pendingIntent: PendingIntent?,
        val remoteInputs: Array<CompatRemoteInput>?,
        val frameworkRemoteInputs: Array<FrameworkRemoteInput>?
    )

    @Volatile
    private var latestMessage: IncomingWhatsAppMessage? = null

    // Cache of recently sent direct replies to prevent re-announcing our own messages
    private val recentlySentReplies = object : java.util.LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 20
    }

    /**
     * Records a text reply sent by JARVIS so subsequent notification updates for this text are ignored.
     */
    fun recordSentReply(text: String) {
        val clean = text.trim().lowercase()
        if (clean.isNotBlank()) {
            synchronized(recentlySentReplies) {
                recentlySentReplies[clean] = System.currentTimeMillis()
            }
        }
    }

    /**
     * Checks whether the given text matches a direct reply sent recently (within 60s).
     */
    fun isRecentlySentReply(text: String): Boolean {
        val clean = text.trim().lowercase()
        if (clean.isBlank()) return false
        val now = System.currentTimeMillis()
        synchronized(recentlySentReplies) {
            for ((sent, time) in recentlySentReplies) {
                if (now - time < 60_000L) {
                    if (clean == sent || clean.contains(sent) || sent.contains(clean)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Stores a new incoming WhatsApp message.
     */
    fun onNewMessage(
        sender: String,
        text: String,
        packageName: String,
        replyAction: NotificationCompat.Action?,
        pendingIntent: PendingIntent?,
        remoteInputs: Array<CompatRemoteInput>?,
        frameworkRemoteInputs: Array<FrameworkRemoteInput>? = null
    ) {
        val msg = IncomingWhatsAppMessage(
            sender = sender,
            text = text,
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            replyAction = replyAction,
            pendingIntent = pendingIntent,
            remoteInputs = remoteInputs,
            frameworkRemoteInputs = frameworkRemoteInputs
        )
        latestMessage = msg
        Log.i(TAG, "Stored incoming message from: $sender (hasReplyAction=${replyAction != null || frameworkRemoteInputs != null})")
    }

    /**
     * Returns the latest incoming message if received within the timeout window.
     */
    fun getRecentMessage(): IncomingWhatsAppMessage? {
        val msg = latestMessage ?: return null
        if (System.currentTimeMillis() - msg.timestamp > EXPIRY_MILLIS) {
            latestMessage = null
            return null
        }
        return msg
    }

    /**
     * Sends a direct background reply to the latest WhatsApp message using RemoteInput.
     * This avoids opening WhatsApp or turning on the screen.
     */
    fun sendDirectReply(context: Context, replyText: String): Boolean {
        val msg = getRecentMessage()
        if (msg == null) {
            Log.w(TAG, "sendDirectReply failed: No active recent message to reply to")
            return false
        }

        val pIntent = msg.pendingIntent ?: msg.replyAction?.actionIntent
        if (pIntent == null) {
            Log.w(TAG, "sendDirectReply failed: Missing PendingIntent")
            return false
        }

        // Try AndroidX RemoteInput
        val rInputs = msg.remoteInputs ?: msg.replyAction?.remoteInputs
        if (rInputs != null && rInputs.isNotEmpty()) {
            try {
                val intent = Intent()
                val bundle = Bundle()
                for (remoteInput in rInputs) {
                    bundle.putCharSequence(remoteInput.resultKey, replyText)
                }
                CompatRemoteInput.addResultsToIntent(rInputs, intent, bundle)
                pIntent.send(context, 0, intent)
                recordSentReply(replyText)
                Log.i(TAG, "Direct reply sent successfully via CompatRemoteInput to ${msg.sender}: $replyText")
                latestMessage = null
                return true
            } catch (e: Exception) {
                Log.w(TAG, "CompatRemoteInput direct reply failed, trying framework", e)
            }
        }

        // Try Framework RemoteInput
        val fInputs = msg.frameworkRemoteInputs
        if (fInputs != null && fInputs.isNotEmpty()) {
            try {
                val intent = Intent()
                val bundle = Bundle()
                for (remoteInput in fInputs) {
                    bundle.putCharSequence(remoteInput.resultKey, replyText)
                }
                FrameworkRemoteInput.addResultsToIntent(fInputs, intent, bundle)
                pIntent.send(context, 0, intent)
                recordSentReply(replyText)
                Log.i(TAG, "Direct reply sent successfully via FrameworkRemoteInput to ${msg.sender}: $replyText")
                latestMessage = null
                return true
            } catch (e: Exception) {
                Log.e(TAG, "FrameworkRemoteInput direct reply failed", e)
            }
        }

        Log.w(TAG, "sendDirectReply failed: No remote inputs available for reply")
        return false
    }

    fun clear() {
        latestMessage = null
    }
}
