package com.example.advancedvoice.feature.conversation.presentation

object ControlsLogic {
    fun derive(
        isSpeaking: Boolean,
        isListening: Boolean,
        isHearingSpeech: Boolean,
        isTranscribing: Boolean,
        phase: GenerationPhase
    ): ControlsState {
        val isGenerating = phase != GenerationPhase.IDLE

        // Activity in progress that blocks the speak button
        val activityInProgress = isListening || isHearingSpeech || isTranscribing

        val text = when {
            isHearingSpeech -> "Listening..."
            isListening -> "Listening..."
            isSpeaking -> "Assistant Speaking (tap to interrupt)"
            isGenerating -> "Generating (speak to interrupt)"  // FIX: Indicate speak-to-interrupt
            else -> "Tap to Speak"
        }

        return ControlsState(
            speakEnabled = !activityInProgress,  // Can press during generation or TTS
            stopEnabled = activityInProgress || isGenerating || isSpeaking,  // Stop always available
            clearEnabled = !(activityInProgress || isGenerating || isSpeaking),
            speakButtonText = text
        )
    }
}