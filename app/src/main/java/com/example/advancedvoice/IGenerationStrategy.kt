package com.example.advancedvoice

import kotlinx.coroutines.CoroutineScope

/**
 * Defines a common interface for different strategies of generating a response from an LLM.
 * This allows the main engine to switch between modes like "Regular" or "Fast First Sentence"
 * without changing its core logic.
 */
interface IGenerationStrategy {

    /**
     * Executes the generation strategy.
     *
     * @param scope The CoroutineScope to launch async operations in.
     * @param history The current conversation history.
     * @param modelName The user-selected model identifier (e.g., "gemini-2.5-pro").
     * @param systemPrompt The system instructions for the model.
     * @param maxSentences The maximum number of sentences to generate for TTS.
     * @param callbacks A set of functions to call for reporting progress back to the UI.
     */
    fun execute(
        scope: CoroutineScope,
        history: List<SentenceTurnEngine.Msg>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    )

    /**
     * Aborts any ongoing generation process for this strategy.
     */
    fun abort()
}