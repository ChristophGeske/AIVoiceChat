package com.example.advancedvoice.domain.strategy

import android.util.Log
import com.example.advancedvoice.core.text.SentenceSplitter
import com.example.advancedvoice.data.common.ChatMessage
import com.example.advancedvoice.data.gemini.GeminiService
import com.example.advancedvoice.data.openai.OpenAiService
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.domain.util.GroundingUtils // ✅ IMPORT the new utility.
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class FastFirstSentenceStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String // Keep for consistency, even if unused
) : IGenerationStrategy {

    @Volatile private var active = false

    override fun execute(
        scope: CoroutineScope,
        history: List<SentenceTurnEngine.Msg>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        active = true
        scope.launch(Dispatchers.IO) {
            try {
                // Phase 1: Get the first sentence and sources quickly.
                val gem = GeminiService(geminiKeyProvider, http)
                val (fullText, sources) = gem.generateTextWithSources(
                    systemPrompt = systemPrompt,
                    history = mapHistory(history),
                    modelName = modelName,
                    temperature = 0.7,
                    enableGoogleSearch = true
                )

                if (!active) return@launch

                // ✅ Use the new utility to handle sources.
                GroundingUtils.processAndDisplaySources(sources, callbacks.onSystem)

                // The rest of the logic for splitting sentences remains the same.
                val (firstSentence, restOfText) = SentenceSplitter.extractFirstSentence(fullText)

                if (firstSentence.isNotBlank()) {
                    callbacks.onFirstSentence(firstSentence)
                }

                // If there's more text, pass it to onRemainingSentences.
                if (restOfText.isNotBlank()) {
                    val remainingSentences = SentenceSplitter.splitIntoSentences(restOfText)
                    if (remainingSentences.isNotEmpty()) {
                        callbacks.onRemainingSentences(remainingSentences)
                    }
                } else if (firstSentence.isBlank()) {
                    // If both are blank, we still need to signal the turn is over.
                    callbacks.onFinalResponse("") // Send empty to trigger finish logic
                }

            } catch (t: Throwable) {
                Log.e("FastFirstSentenceStrategy", "Error: ${t.message}", t)
                if (active) callbacks.onError(t.message ?: "Generation failed")
            } finally {
                if (active) {
                    active = false
                    callbacks.onTurnFinish()
                }
            }
        }
    }

    override fun abort() {
        active = false
    }

    private fun mapHistory(history: List<SentenceTurnEngine.Msg>): List<ChatMessage> =
        history.map { ChatMessage(it.role, it.text) }
}