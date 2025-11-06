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

        // ✅ CRITICAL: TTS speaking ALWAYS takes priority over other states
        val buttonText = when {
            // ✅ Check TTS first - this prevents "Listening..." showing during echo
            isSpeaking -> "Speaking (Tap to Interrupt)"

            // Then check STT states
            isTranscribing -> "Transcribing..."
            isHearingSpeech -> "Listening..."
            isListening -> "Listening..."

            // Generation states
            isGenerating && voiceInterruptAllowed -> "Generating (Speak to Interrupt)"
            isGenerating && !voiceInterruptAllowed -> "Generating..."

            // Default idle
            else -> "Tap to Speak"
        }

        // The "Stop" button is for any background activity.
        val anythingInProgress = isSpeaking || isListening || isHearingSpeech || isGenerating || isTranscribing

        // The main "Speak" button should always be enabled unless actively listening/transcribing.
        // ✅ Also disable during TTS to prevent accidental double-taps
        val speakEnabled = !isListening && !isHearingSpeech && !isTranscribing && !isSpeaking

        return ControlsState(
            speakEnabled = speakEnabled,
            stopEnabled = anythingInProgress,
            clearEnabled = !anythingInProgress,
            speakButtonText = buttonText
        )
    }
}