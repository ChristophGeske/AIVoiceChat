package com.example.advancedvoice.feature.conversation.presentation

object ControlsLogic {
    fun derive(
        isSpeaking: Boolean,
        isListening: Boolean,
        isHearingSpeech: Boolean,
        isTranscribing: Boolean,
        phase: GenerationPhase
    ): ControlsState {
        val isProcessing = phase != GenerationPhase.IDLE

        // FIX: Allow "speak" button during generation for barge-in
        val activityInProgress = isListening || isHearingSpeech || isTranscribing || isSpeaking

        val text = when {
            isHearingSpeech -> "Listening..."
            isListening -> "Listening..."
            isSpeaking -> "Assistant Speaking..."
            isProcessing -> "Generating... (tap to interrupt)"  // FIX: Indicate interruption is possible
            else -> "Tap to Speak"
        }

        return ControlsState(
            speakEnabled = !activityInProgress,  // FIX: Can press during generation now
            stopEnabled = activityInProgress || isProcessing,
            clearEnabled = !(activityInProgress || isProcessing),
            speakButtonText = text
        )
    }
}