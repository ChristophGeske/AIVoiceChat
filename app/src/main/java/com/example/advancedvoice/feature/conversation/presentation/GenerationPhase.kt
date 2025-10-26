package com.example.advancedvoice.feature.conversation.presentation

enum class GenerationPhase {
    IDLE,
    GENERATING_FIRST,
    GENERATING_REMAINDER,
    SINGLE_SHOT_GENERATING
}
