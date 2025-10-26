package com.example.advancedvoice.data.common

/**
 * Minimal chat message model for data-layer services.
 * role: "user" | "assistant"
 */
data class ChatMessage(
    val role: String,
    val text: String
)
