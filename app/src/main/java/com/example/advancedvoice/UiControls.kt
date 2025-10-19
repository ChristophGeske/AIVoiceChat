package com.example.advancedvoice

data class ControlsState(
    val speakEnabled: Boolean,
    val stopEnabled: Boolean,
    val newRecordingEnabled: Boolean,
    val clearEnabled: Boolean
)

object ControlsLogic {
    // Centralized control logic for buttons
    fun derive(
        isSpeaking: Boolean, // This is true when TTS is active
        isListening: Boolean,
        engineActive: Boolean,
        settingsVisible: Boolean
    ): ControlsState {
        val blocked = isListening || engineActive
        val overlay = settingsVisible

        val speakEnabled = !blocked && !isSpeaking && !overlay
        val stopEnabled = isSpeaking || isListening || engineActive

        // KORREKTUR: "New Recording" is enabled while the assistant is speaking
        val newRecordingEnabled = isSpeaking && !overlay

        val clearEnabled = !blocked && !isSpeaking && !overlay

        return ControlsState(
            speakEnabled = speakEnabled,
            stopEnabled = stopEnabled,
            newRecordingEnabled = newRecordingEnabled,
            clearEnabled = clearEnabled
        )
    }
}