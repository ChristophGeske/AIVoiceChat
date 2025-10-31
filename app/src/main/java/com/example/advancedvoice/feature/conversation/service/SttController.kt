package com.example.advancedvoice.feature.conversation.service

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over speech-to-text sources.
 */
interface SttController {
    val isListening: StateFlow<Boolean>
    val isHearingSpeech: StateFlow<Boolean>
    // NEW: State to indicate the STT engine is processing the final transcript after speech has ended.
    val isTranscribing: StateFlow<Boolean>
    val transcripts: SharedFlow<String> // Final transcripts only
    val errors: SharedFlow<String>

    suspend fun start()
    suspend fun stop(transcribe: Boolean = true)
    fun release()
}