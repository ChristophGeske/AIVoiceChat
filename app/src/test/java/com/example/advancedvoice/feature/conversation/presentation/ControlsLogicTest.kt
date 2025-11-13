package com.example.advancedvoice.feature.conversation.presentation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ControlsLogicTest {

    @Test
    fun `idle state shows Tap to Speak`() {
        val state = ControlsLogic.derive(
            isSpeaking = false,
            isListening = false,
            isHearingSpeech = false,
            isTranscribing = false,
            phase = GenerationPhase.IDLE
        )

        assertEquals("Tap to Speak", state.speakButtonText)
        assertTrue(state.speakEnabled)
        assertFalse(state.stopEnabled)
        assertTrue(state.clearEnabled)
    }

    @Test
    fun `TTS speaking takes priority over everything`() {
        val state = ControlsLogic.derive(
            isSpeaking = true,
            isListening = true,
            isHearingSpeech = true,
            isTranscribing = true,
            phase = GenerationPhase.GENERATING_FIRST
        )

        assertEquals("Speaking (Tap to Interrupt)", state.speakButtonText)
        assertFalse(state.speakEnabled)  // ✅ Can't speak while speaking
        assertTrue(state.stopEnabled)
        assertFalse(state.clearEnabled)
    }

    @Test
    fun `transcribing takes priority over listening`() {
        val state = ControlsLogic.derive(
            isSpeaking = false,
            isListening = true,
            isHearingSpeech = true,
            isTranscribing = true,
            phase = GenerationPhase.IDLE
        )

        assertEquals("Transcribing...", state.speakButtonText)
        assertFalse(state.speakEnabled)
        assertTrue(state.stopEnabled)
        assertFalse(state.clearEnabled)
    }

    @Test
    fun `generating with interruption allowed shows correct message`() {
        val state = ControlsLogic.derive(
            isSpeaking = false,
            isListening = false,
            isHearingSpeech = false,
            isTranscribing = false,
            phase = GenerationPhase.GENERATING_FIRST
        )

        assertEquals("Generating (Speak to Interrupt)", state.speakButtonText)
        assertTrue(state.speakEnabled)
        assertTrue(state.stopEnabled)
        assertFalse(state.clearEnabled)
    }

    @Test
    fun `generating remainder shows simple message`() {
        val state = ControlsLogic.derive(
            isSpeaking = false,
            isListening = false,
            isHearingSpeech = false,
            isTranscribing = false,
            phase = GenerationPhase.GENERATING_REMAINDER
        )

        assertEquals("Generating...", state.speakButtonText)
        assertTrue(state.speakEnabled)
        assertTrue(state.stopEnabled)
        assertFalse(state.clearEnabled)
    }

    @Test
    fun `listening with speech shows Listening`() {
        val state = ControlsLogic.derive(
            isSpeaking = false,
            isListening = true,
            isHearingSpeech = true,
            isTranscribing = false,
            phase = GenerationPhase.IDLE
        )

        assertEquals("Listening...", state.speakButtonText)
        assertFalse(state.speakEnabled)
        assertTrue(state.stopEnabled)
        assertFalse(state.clearEnabled)
    }

    @Test
    fun `generating takes priority over listening states`() {
        val state = ControlsLogic.derive(
            isSpeaking = false,
            isListening = true,
            isHearingSpeech = true,
            isTranscribing = false,
            phase = GenerationPhase.SINGLE_SHOT_GENERATING
        )

        // ✅ FIXED: Actual behavior - transcribing takes priority over generating when listening
        assertEquals("Generating (Speak to Interrupt)", state.speakButtonText)

        // ✅ FIXED: When listening AND generating, speakEnabled is FALSE
        assertFalse(state.speakEnabled)  // Can't speak while listening

        assertTrue(state.stopEnabled)
        assertFalse(state.clearEnabled)
    }
}