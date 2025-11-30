package com.example.advancedvoice.domain.entities

data class ConversationEntry(
    val speaker: String,
    val sentences: List<String>,
    val isAssistant: Boolean,
    val streamingText: String? = null,
    val isRefinedTranscript: Boolean = false,  //  true after background refinement
    val audioSegments: List<ByteArray>? = null  //  stored audio for potential re-processing

)
