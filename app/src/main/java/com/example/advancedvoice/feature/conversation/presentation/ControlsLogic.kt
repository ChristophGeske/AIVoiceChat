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

        // Voice interruption is only allowed BEFORE the first sentence is delivered.
        val voiceInterruptAllowed = phase == GenerationPhase.GENERATING_FIRST ||
                phase == GenerationPhase.SINGLE_SHOT_GENERATING

        // Determine the primary button text
        val buttonText = when {
            isTranscribing -> "Transcribing..."
            isHearingSpeech -> "Listening..."
            isListening -> "Listening..."
            // After TTS starts, the only way to interrupt is by tapping.
            isSpeaking -> "Speaking (Tap to Interrupt)"
            // Show "Speak to Interrupt" only when it's actually possible.
            isGenerating && voiceInterruptAllowed -> "Generating (Speak to Interrupt)"
            // For phase 2 generation, no voice interrupt is allowed.
            isGenerating && !voiceInterruptAllowed -> "Generating..."
            else -> "Tap to Speak"
        }

        // The "Stop" button is for any background activity.
        val anythingInProgress = isSpeaking || isListening || isHearingSpeech || isGenerating || isTranscribing

        // The main "Speak" button should always be enabled unless actively listening/transcribing.
        // Tapping it while busy will act as an interruption.
        val speakEnabled = !isListening && !isHearingSpeech && !isTranscribing

        return ControlsState(
            speakEnabled = speakEnabled,
            stopEnabled = anythingInProgress,
            clearEnabled = !anythingInProgress,
            speakButtonText = buttonText
        )
    }
}