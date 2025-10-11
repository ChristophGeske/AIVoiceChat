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
        isSpeaking: Boolean,
        isListening: Boolean,
        engineActive: Boolean,
        settingsVisible: Boolean
    ): ControlsState {
        val blocked = isSpeaking || isListening || engineActive
        val overlay = settingsVisible

        val speakEnabled = !blocked && !overlay
        val stopEnabled = blocked
        val newRecordingEnabled = isSpeaking && !overlay
        val clearEnabled = !blocked && !overlay

        return ControlsState(
            speakEnabled = speakEnabled,
            stopEnabled = stopEnabled,
            newRecordingEnabled = newRecordingEnabled,
            clearEnabled = clearEnabled
        )
    }
}