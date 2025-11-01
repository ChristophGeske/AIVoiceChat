package com.example.advancedvoice.feature.conversation.service

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface SttController {
    val isListening: StateFlow<Boolean>
    val isHearingSpeech: StateFlow<Boolean>
    val isTranscribing: StateFlow<Boolean>
    val transcripts: SharedFlow<String>
    val errors: SharedFlow<String>

    suspend fun start(isAutoListen: Boolean = false)  // FIX: Add parameter
    suspend fun stop(transcribe: Boolean)
    fun release()
}