package com.jarvis.assistant.model

/**
 * A single message entry in the Jarvis Group Chat.
 * Stored in Firestore 'group_chat' collection and displayed in the group chat RecyclerView.
 */
data class GroupChatMessage(
    val id: String = "",
    val senderName: String = "",
    val senderEmail: String = "",
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isAdmin: Boolean = false
)
