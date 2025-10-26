package com.example.advancedvoice.feature.conversation.presentation

data class ConversationState(
    val isSpeaking: Boolean = false,
    val isListening: Boolean = false,
    val isTranscribing: Boolean = false,
    val phase: GenerationPhase = GenerationPhase.IDLE
)
