package com.example.advancedvoice.feature.conversation.presentation

object ControlsLogic {
    fun derive(
        isSpeaking: Boolean,
        isListening: Boolean,
        isHearingSpeech: Boolean,
        isTranscribing: Boolean, // This state is now managed and important
        phase: GenerationPhase
    ): ControlsState {
        val isGenerating = phase != GenerationPhase.IDLE

        // Determine the primary button text based on a priority of states
        val buttonText = when {
            isTranscribing -> "Transcribing..."
            isHearingSpeech -> "Listening..." // Show a consistent "Listening..." state
            isListening -> "Listening..."
            isSpeaking -> "Speaking (Tap to Interrupt)"
            isGenerating -> "Generating (Speak to Interrupt)"
            else -> "Tap to Speak"
        }

        // The "Stop" button should be enabled if ANY background activity is happening.
        val anythingInProgress = isSpeaking || isListening || isHearingSpeech || isGenerating || isTranscribing

        // The "Speak" button should only be enabled when the system is truly idle or can be interrupted.
        val speakEnabled = when {
            isListening || isHearingSpeech || isTranscribing -> false // Never while actively listening/transcribing
            else -> true // Allow tap to interrupt speaking or generating
        }

        return ControlsState(
            speakEnabled = speakEnabled,
            stopEnabled = anythingInProgress,
            clearEnabled = !anythingInProgress, // Only enable clear when idle
            speakButtonText = buttonText
        )
    }
}