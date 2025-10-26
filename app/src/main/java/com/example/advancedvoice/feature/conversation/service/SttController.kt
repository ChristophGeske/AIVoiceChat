package com.example.advancedvoice.feature.conversation.service

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over speech-to-text sources.
 * Implementations:
 * - StandardSttController (Android SpeechRecognizer)
 * - GeminiLiveSttController (VadRecorder + GeminiLiveTranscriber)
 */
interface SttController {
    val isListening: StateFlow<Boolean>
    // NEW: State to indicate when the VAD or recognizer is actively detecting speech.
    val isHearingSpeech: StateFlow<Boolean>
    val transcripts: SharedFlow<String>
    val errors: SharedFlow<String>

    suspend fun start()
    suspend fun stop(transcribe: Boolean = true)
    fun release()
}