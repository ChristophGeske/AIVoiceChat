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
            var turnFinished = false
            try {
                // ✅ FIX: Create a Set to collect and automatically de-duplicate sources from both phases.
                val combinedSources = mutableSetOf<Pair<String, String?>>()

                val gem = GeminiService(geminiKeyProvider, http)
                val mappedHistory = mapHistory(history)

                // --- PHASE 1: Get the first sentence and sources ---
                Log.i("FastFirst", "--- PHASE 1: GENERATING FIRST SENTENCE ---")
                val phase1SystemPrompt = "$systemPrompt\n\nIMPORTANT: Your entire response must be only a single, complete sentence."
                val (firstSentence, phase1Sources) = gem.generateTextWithSources(
                    systemPrompt = phase1SystemPrompt,
                    history = mappedHistory,
                    modelName = modelName,
                    temperature = 0.7,
                    enableGoogleSearch = true
                )

                if (!active || firstSentence.isBlank()) {
                    if (firstSentence.isBlank()) Log.w("FastFirst", "Phase 1 returned an empty sentence.")
                    callbacks.onTurnFinish()
                    turnFinished = true
                    return@launch
                }

                // ✅ FIX: Do NOT display sources yet. Just add them to our collection.
                combinedSources.addAll(phase1Sources)
                callbacks.onFirstSentence(firstSentence)

                if (maxSentences <= 1) {
                    Log.i("FastFirst", "Max sentences is 1, skipping Phase 2.")
                    // ✅ FIX: Display the collected sources now since the turn is over.
                    GroundingUtils.processAndDisplaySources(combinedSources.toList(), callbacks.onSystem)
                    callbacks.onTurnFinish()
                    turnFinished = true
                    return@launch
                }

                // --- PHASE 2: Generate the rest of the response ---
                Log.i("FastFirst", "--- PHASE 2: GENERATING REMAINDER ---")
                val phase2History = mappedHistory + ChatMessage("assistant", firstSentence)
                val remainingCount = maxSentences - 1
                val plural = if (remainingCount > 1) "sentences" else "sentence"
                val phase2SystemPrompt = "$systemPrompt\n\nContinue your previous thought. You have already said: \"$firstSentence\". Now, provide the rest of your response in AT MOST $remainingCount more $plural."

                val (remainderText, phase2Sources) = gem.generateTextWithSources(
                    systemPrompt = phase2SystemPrompt,
                    history = emptyList(),
                    modelName = modelName,
                    temperature = 0.7,
                    enableGoogleSearch = true
                )

                if (!active) return@launch

                // ✅ FIX: Add Phase 2 sources to our collection. Do not display yet.
                combinedSources.addAll(phase2Sources)

                if (remainderText.isNotBlank()) {
                    val remainingSentences = SentenceSplitter.splitIntoSentences(remainderText)
                    callbacks.onRemainingSentences(remainingSentences)
                } else {
                    Log.w("FastFirst", "Phase 2 returned an empty remainder.")
                }

                // ✅ FIX: Now that all sentences are delivered, display the final, combined list of sources.
                GroundingUtils.processAndDisplaySources(combinedSources.toList(), callbacks.onSystem)

                callbacks.onTurnFinish()
                turnFinished = true

            } catch (t: Throwable) {
                Log.e("FastFirstSentenceStrategy", "Error: ${t.message}", t)
                if (active) callbacks.onError(t.message ?: "Generation failed")
            } finally {
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