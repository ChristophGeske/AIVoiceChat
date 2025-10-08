// ConversationModels.kt
package com.example.advancedvoice

data class ConversationEntry(
    val speaker: String,
    val sentences: List<String>,
    val isAssistant: Boolean,
    val streamingText: String? = null
)

data class SpokenSentence(
    val text: String,
    val entryIndex: Int,
    val sentenceIndex: Int
)