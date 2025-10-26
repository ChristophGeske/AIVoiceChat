package com.example.advancedvoice.domain.entities

data class ConversationEntry(
    val speaker: String,
    val sentences: List<String>,
    val isAssistant: Boolean,
    val streamingText: String? = null
)
