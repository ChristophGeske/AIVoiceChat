package com.example.advancedvoice.feature.conversation.presentation

object ControlsLogic {
    fun derive(
        isSpeaking: Boolean,
        isListening: Boolean,
        isHearingSpeech: Boolean,
        phase: GenerationPhase
    ): ControlsState {
        val isProcessing = phase != GenerationPhase.IDLE
        val activityInProgress = isListening || isProcessing || isSpeaking

        val text = when {
            // FIX: Combine "Listening" and "Hearing" for better feedback
            isHearingSpeech -> "Listening + Hearing..."
            isListening -> "Listening..."
            isSpeaking -> "Assistant Speaking..."
            isProcessing -> "Generating..."
            else -> "Tap to Speak"
        }

        return ControlsState(
            speakEnabled = !activityInProgress,
            stopEnabled = activityInProgress,
            clearEnabled = !activityInProgress,
            speakButtonText = text
        )
    }
}