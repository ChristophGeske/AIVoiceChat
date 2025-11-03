package com.example.advancedvoice.domain.strategy

import android.util.Log
import com.example.advancedvoice.core.text.SentenceSplitter
import com.example.advancedvoice.data.common.ChatMessage
import com.example.advancedvoice.data.gemini.GeminiService
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.domain.util.GroundingUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class FastFirstSentenceStrategy(
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
            var turnFinished = false // Local flag to control finally block
            try {
                val gem = GeminiService(geminiKeyProvider, http)
                val mappedHistory = mapHistory(history)

                // --- PHASE 1 ---
                Log.i("FastFirst", "--- PHASE 1: GENERATING FIRST SENTENCE ---")
                val phase1SystemPrompt = "$systemPrompt\n\nIMPORTANT: Your entire response must be only a single, complete sentence."
                val (firstSentence, sources) = gem.generateTextWithSources(
                    systemPrompt = phase1SystemPrompt,
                    history = mappedHistory,
                    modelName = modelName,
                    temperature = 0.7,
                    enableGoogleSearch = true
                )

                if (!active || firstSentence.isBlank()) {
                    if (firstSentence.isBlank()) Log.w("FastFirst", "Phase 1 returned empty sentence.")
                    // If Phase 1 fails, we must end the turn.
                    callbacks.onTurnFinish()
                    turnFinished = true
                    return@launch
                }

                GroundingUtils.processAndDisplaySources(sources, callbacks.onSystem)
                callbacks.onFirstSentence(firstSentence)

                if (maxSentences <= 1) {
                    Log.i("FastFirst", "Max sentences is 1, skipping Phase 2.")
                    // If we are done after Phase 1, end the turn.
                    callbacks.onTurnFinish()
                    turnFinished = true
                    return@launch
                }

                // --- PHASE 2 ---
                Log.i("FastFirst", "--- PHASE 2: GENERATING REMAINDER ---")
                val phase2History = mappedHistory + ChatMessage("assistant", firstSentence)
                val remainingCount = maxSentences - 1
                val plural = if (remainingCount > 1) "sentences" else "sentence"
                val phase2SystemPrompt = "$systemPrompt\n\nContinue your previous thought. You have already said: \"$firstSentence\". Now, provide the rest of your response in AT MOST $remainingCount more $plural."

                val (remainderText, remainderSources) = gem.generateTextWithSources(
                    systemPrompt = phase2SystemPrompt,
                    history = emptyList(),
                    modelName = modelName,
                    temperature = 0.7,
                    enableGoogleSearch = true
                )

                if (!active) return@launch

                GroundingUtils.processAndDisplaySources(remainderSources, callbacks.onSystem)

                if (remainderText.isNotBlank()) {
                    val remainingSentences = SentenceSplitter.splitIntoSentences(remainderText)
                    callbacks.onRemainingSentences(remainingSentences)
                } else {
                    Log.w("FastFirst", "Phase 2 returned an empty remainder.")
                }

                // ✅ FIX: The turn is only finished AFTER all callbacks have been sent.
                callbacks.onTurnFinish()
                turnFinished = true

            } catch (t: Throwable) {
                Log.e("FastFirstSentenceStrategy", "Error: ${t.message}", t)
                if (active) callbacks.onError(t.message ?: "Generation failed")
            } finally {
                // ✅ FIX: Use a local flag to prevent the finally block from calling onTurnFinish again.
                if (active && !turnFinished) {
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