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

        val voiceInterruptAllowed = phase == GenerationPhase.GENERATING_FIRST ||
                phase == GenerationPhase.SINGLE_SHOT_GENERATING

        // ✅ FIXED: Strict priority order - generating/speaking ALWAYS override listening states
        val buttonText = when {
            // Priority 1: TTS is speaking
            isSpeaking -> "Speaking (Tap to Interrupt)"

            // Priority 2: Transcribing STT result
            isTranscribing -> "Transcribing..."

            // Priority 3: Generating response (MUST be before listening checks)
            isGenerating && voiceInterruptAllowed -> "Generating (Speak to Interrupt)"
            isGenerating -> "Generating..."

            // Priority 4: Actively listening AND hearing speech
            isListening && isHearingSpeech -> "Listening..."

            // Priority 5: Listening for speech
            isListening -> "Listening..."

            // Priority 6: Idle
            else -> "Tap to Speak"
        }

        val anythingInProgress = isSpeaking || isListening || isHearingSpeech || isGenerating || isTranscribing

        val speakEnabled = !isListening && !isTranscribing

        return ControlsState(
            speakEnabled = speakEnabled,
            stopEnabled = anythingInProgress,
            clearEnabled = !anythingInProgress,
            speakButtonText = buttonText
        )
    }
}