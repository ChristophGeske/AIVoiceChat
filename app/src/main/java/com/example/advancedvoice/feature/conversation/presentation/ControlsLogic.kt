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

        // FIX: Determine if interruption is allowed based on specific phase
        val interruptionAllowed = when (phase) {
            GenerationPhase.GENERATING_FIRST,
            GenerationPhase.SINGLE_SHOT_GENERATING -> true
            GenerationPhase.GENERATING_REMAINDER -> false  // Block interruption during Phase 2
            GenerationPhase.IDLE -> false
        }

        // Determine the primary button text
        val buttonText = when {
            isTranscribing -> "Transcribing..."
            isHearingSpeech -> "Listening..."
            isListening -> "Listening..."
            isSpeaking -> "Speaking (Tap to Interrupt)"
            // FIX: Only show interrupt hint if interruption is allowed
            isGenerating && interruptionAllowed -> "Generating (Speak to Interrupt)"
            isGenerating -> "Generating..."  // Phase 2 - no interrupt hint
            else -> "Tap to Speak"
        }

        // The "Stop" button should be enabled if ANY background activity is happening
        val anythingInProgress = isSpeaking || isListening || isHearingSpeech || isGenerating || isTranscribing

        // FIX: The "Speak" button logic
        val speakEnabled = when {
            isListening || isHearingSpeech || isTranscribing -> false // Never while actively listening/transcribing
            isGenerating && !interruptionAllowed -> false // Block during Phase 2
            else -> true // Allow tap to interrupt speaking or Phase 1 generation
        }

        return ControlsState(
            speakEnabled = speakEnabled,
            stopEnabled = anythingInProgress,
            clearEnabled = !anythingInProgress,
            speakButtonText = buttonText
        )
    }
}