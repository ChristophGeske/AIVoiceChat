package com.example.advancedvoice.domain.strategy

import android.util.Log
import com.example.advancedvoice.data.common.ChatMessage
import com.example.advancedvoice.data.gemini.GeminiService
import com.example.advancedvoice.data.openai.OpenAiService
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * One-pass strategy: call the selected model once and return the full text.
 */
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
                val text = if (isGemini) {
                    val gem = GeminiService(geminiKeyProvider, http)
                    gem.generateText(
                        systemPrompt = systemPrompt,
                        history = mapHistory(history),
                        modelName = modelName,
                        temperature = 0.7,
                        enableGoogleSearch = true
                    )
                } else {
                    val openai = OpenAiService(openAiKeyProvider, http)
                    openai.generateResponses(
                        systemPrompt = systemPrompt,
                        history = mapHistory(history),
                        model = modelName,
                        effort = "low",
                        verbosity = "low",
                        enableWebSearch = true
                    ).text
                }

                if (!active) return@launch
                callbacks.onFinalResponse(text.trim())
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
