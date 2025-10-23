package com.example.advancedvoice

data class ControlsState(
    val speakEnabled: Boolean,
    val stopEnabled: Boolean,
    val newRecordingEnabled: Boolean,
    val clearEnabled: Boolean,
    val speakButtonText: String
)

object ControlsLogic {
    fun derive(
        isSpeaking: Boolean,
        isListening: Boolean,
        isTranscribing: Boolean,
        generationPhase: GenerationPhase,
        settingsVisible: Boolean
    ): ControlsState {
        val isProcessing = isTranscribing || generationPhase != GenerationPhase.IDLE
        val activityInProgress = isListening || isProcessing || isSpeaking
        val settingsAreOpen = settingsVisible

        val buttonText = when {
            isListening && isProcessing -> "Processing & Listening..."
            isListening -> "Listening..."
            isTranscribing -> "Processing..."
            generationPhase != GenerationPhase.IDLE -> "Generating..."
            isSpeaking -> "Assistant Speaking..."
            else -> "Tap to Speak"
        }

        val speakEnabled = !activityInProgress && !settingsAreOpen
        val stopEnabled = activityInProgress && !settingsAreOpen
        val newRecordingEnabled = isSpeaking && !settingsAreOpen
        val clearEnabled = !activityInProgress && !settingsAreOpen

        return ControlsState(
            speakEnabled = speakEnabled,
            stopEnabled = stopEnabled,
            newRecordingEnabled = newRecordingEnabled,
            clearEnabled = clearEnabled,
            speakButtonText = buttonText
        )
    }
}