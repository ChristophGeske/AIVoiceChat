package com.example.advancedvoice.feature.conversation.presentation

data class ControlsState(
    val speakEnabled: Boolean,
    val stopEnabled: Boolean,
    val clearEnabled: Boolean,
    val speakButtonText: String
)
