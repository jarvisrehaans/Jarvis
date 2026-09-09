package com.jarvis.assistant.model

/**
 * A single chat bubble entry shown in the conversation list.
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
