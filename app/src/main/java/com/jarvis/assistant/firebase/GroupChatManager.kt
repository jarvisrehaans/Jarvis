package com.jarvis.assistant.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.jarvis.assistant.model.GroupChatMessage

/**
 * Manages the Jarvis Group Chat via Firebase Firestore.
 *
 * - Collection: 'group_chat'
 * - Documents: auto-generated IDs
 * - Real-time listener via addSnapshotListener
 * - Admin detection for rehaanoffical77@gmail.com
 */
object GroupChatManager {

    private const val TAG = "GroupChatManager"
    private const val COLLECTION = "group_chat"
    private const val PREFS_NAME = "jarvis_prefs"
    private const val KEY_GROUP_CHAT_NAME = "group_chat_display_name"
    const val ADMIN_EMAIL = "rehaanoffical77@gmail.com"

    // Limit to last 200 messages to keep things performant
    private const val MESSAGE_LIMIT = 200L

    private val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    private var snapshotListener: ListenerRegistration? = null

    /**
     * Gets the current user's email from FirebaseAuth or SharedPreferences.
     */
    fun getUserEmail(context: Context): String {
        val authEmail = try {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.trim() ?: ""
        } catch (_: Exception) { "" }
        if (authEmail.isNotBlank()) return authEmail

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("user_email", "")?.trim() ?: ""
    }

    /**
     * Checks if the current user is the admin.
     */
    fun isCurrentUserAdmin(context: Context): Boolean {
        return isAdmin(getUserEmail(context))
    }

    /**
     * Checks if the user has set their group chat display name.
     * Admin always returns true (never asked for a name).
     */
    fun hasDisplayName(context: Context): Boolean {
        if (isCurrentUserAdmin(context)) return true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GROUP_CHAT_NAME, "")?.isNotBlank() == true
    }

    /**
     * Gets the saved group chat display name.
     * For admin, always returns "Admin".
     */
    fun getDisplayName(context: Context): String {
        if (isCurrentUserAdmin(context)) return "Admin"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GROUP_CHAT_NAME, "") ?: ""
    }

    /**
     * Saves the user's group chat display name.
     */
    fun saveDisplayName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GROUP_CHAT_NAME, name.trim()).apply()
    }

    /**
     * Checks if the given email is the admin email.
     */
    fun isAdmin(email: String): Boolean {
        return email.trim().lowercase() == ADMIN_EMAIL.lowercase()
    }

    /**
     * Sends a message to the group chat.
     */
    fun sendMessage(context: Context, text: String, onComplete: ((Boolean) -> Unit)? = null) {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) {
            onComplete?.invoke(false)
            return
        }

        val senderEmail = getUserEmail(context)
        val userIsAdmin = isAdmin(senderEmail)
        val senderName = if (userIsAdmin) "Admin" else getDisplayName(context)

        if (senderName.isBlank()) {
            Log.e(TAG, "Cannot send message: display name is not set")
            onComplete?.invoke(false)
            return
        }

        val messageData = hashMapOf<String, Any>(
            "senderName" to senderName,
            "senderEmail" to senderEmail.trim().lowercase(),
            "text" to trimmedText,
            "timestamp" to System.currentTimeMillis(),
            "isAdmin" to userIsAdmin
        )

        firestore.collection(COLLECTION)
            .add(messageData)
            .addOnSuccessListener {
                Log.d(TAG, "Group chat message sent successfully")
                onComplete?.invoke(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send group chat message: ${e.message}", e)
                onComplete?.invoke(false)
            }
    }

    /**
     * Starts listening for real-time group chat messages.
     * Calls onMessagesChanged whenever new messages arrive.
     */
    fun startListening(onMessagesChanged: (List<GroupChatMessage>) -> Unit) {
        // Remove any existing listener first
        stopListening()

        snapshotListener = firestore.collection(COLLECTION)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .limitToLast(MESSAGE_LIMIT)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening for group chat: ${error.message}", error)
                    return@addSnapshotListener
                }

                if (snapshots == null) return@addSnapshotListener

                val messages = snapshots.documents.mapNotNull { doc ->
                    try {
                        GroupChatMessage(
                            id = doc.id,
                            senderName = doc.getString("senderName") ?: "Unknown",
                            senderEmail = doc.getString("senderEmail") ?: "",
                            text = doc.getString("text") ?: "",
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            isAdmin = doc.getBoolean("isAdmin") ?: false
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse group chat message: ${e.message}")
                        null
                    }
                }

                onMessagesChanged(messages)
            }

        Log.d(TAG, "Started listening for group chat messages")
    }

    /**
     * Stops listening for group chat messages.
     */
    fun stopListening() {
        snapshotListener?.remove()
        snapshotListener = null
    }
}
