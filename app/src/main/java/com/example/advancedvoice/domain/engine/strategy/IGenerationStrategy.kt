package com.example.advancedvoice.domain.engine.strategy

import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import kotlinx.coroutines.CoroutineScope

interface IGenerationStrategy {
    fun execute(
        scope: CoroutineScope,
        history: List<SentenceTurnEngine.Msg>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    )

    // NEW: Execute with audio input
    fun executeWithAudio(
        scope: CoroutineScope,
        history: List<SentenceTurnEngine.Msg>,
        audioInput: SentenceTurnEngine.AudioInput,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        // Default implementation falls back to text-based execution
        // using the streaming transcript
        execute(scope, history + SentenceTurnEngine.Msg("user", audioInput.streamingTranscript),
            modelName, systemPrompt, maxSentences, callbacks)
    }

    fun abort()
}
