package com.example.advancedvoice.domain.strategy

import android.util.Log
import com.example.advancedvoice.data.common.ChatMessage
import com.example.advancedvoice.data.gemini.GeminiService
import com.example.advancedvoice.data.openai.OpenAiService
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.domain.util.GroundingUtils // ✅ 1. IMPORT the new utility.
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class RegularGenerationStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String
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
                val isGemini = modelName.contains("gemini", ignoreCase = true)

                // ✅ 2. MODIFY this block to capture both text and sources.
                if (isGemini) {
                    val gem = GeminiService(geminiKeyProvider, http)
                    // Call the method that returns both text and sources.
                    val (text, sources) = gem.generateTextWithSources(
                        systemPrompt = systemPrompt,
                        history = mapHistory(history),
                        modelName = modelName,
                        temperature = 0.7,
                        enableGoogleSearch = true
                    )

                    if (!active) return@launch
                    callbacks.onFinalResponse(text.trim())
                    // Use the new utility to process and display the sources.
                    GroundingUtils.processAndDisplaySources(sources, callbacks.onSystem)

                } else {
                    val openai = OpenAiService(openAiKeyProvider, http)
                    val result = openai.generateResponses(
                        systemPrompt = systemPrompt,
                        history = mapHistory(history),
                        model = modelName,
                        effort = "low",
                        verbosity = "low",
                        enableWebSearch = true
                    )

                    if (!active) return@launch
                    callbacks.onFinalResponse(result.text.trim())
                    // Although OpenAI doesn't use the same grounding, this is good future-proofing.
                    // For now, it will likely be an empty list and do nothing.
                    GroundingUtils.processAndDisplaySources(result.sources, callbacks.onSystem)
                }

            } catch (t: Throwable) {
                Log.e("RegularGenerationStrategy", "Error: ${t.message}", t)
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