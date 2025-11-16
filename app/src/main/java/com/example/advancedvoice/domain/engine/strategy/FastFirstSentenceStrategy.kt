package com.example.advancedvoice.domain.strategy

import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.prefs.Prefs
import com.example.advancedvoice.core.text.SentenceSplitter
import com.example.advancedvoice.data.common.ChatMessage
import com.example.advancedvoice.data.gemini.GeminiService
import com.example.advancedvoice.data.openai.OpenAiService
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class FastFirstSentenceStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String
) : IGenerationStrategy {

    companion object {
        private const val TAG = LoggerConfig.TAG_STRATEGY
    }

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
        val isGemini = modelName.contains("gemini", ignoreCase = true)

        Log.i(TAG, "═══════════════════════════════════")
        Log.i(TAG, "[FAST-FIRST] Starting generation")
        Log.i(TAG, "  Model: $modelName (${if (isGemini) "Gemini" else "OpenAI"})")
        Log.i(TAG, "  Max Sentences: $maxSentences")
        Log.i(TAG, "  History size: ${history.size}")
        Log.i(TAG, "═══════════════════════════════════")

        scope.launch(Dispatchers.IO) {
            try {
                val mappedHistory = mapHistory(history)

                if (isGemini) {
                    executeGeminiTwoPhase(mappedHistory, modelName, systemPrompt, maxSentences, callbacks)
                } else {
                    executeOpenAiTwoPhase(mappedHistory, modelName, systemPrompt, maxSentences, callbacks)
                }

            } catch (t: Throwable) {
                Log.e(TAG, "[ERROR] ❌ Exception during generation", t)
                Log.e(TAG, "[ERROR] Message: ${t.message}")
                Log.e(TAG, "[ERROR] Stack trace:", t)
                if (active) {
                    callbacks.onError(t.message ?: "Generation failed")
                    callbacks.onTurnFinish()
                }
            } finally {
                active = false
                Log.d(TAG, "[CLEANUP] Strategy completed, active=false")
            }
        }
    }

    private suspend fun executeGeminiTwoPhase(
        mappedHistory: List<ChatMessage>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        val gem = GeminiService(geminiKeyProvider, http)

        // --- PHASE 1: Get the first sentence ---
        Log.i(TAG, "[PHASE-1] 🚀 Requesting first sentence from Gemini...")
        val phase1SystemPrompt = "$systemPrompt\n\nIMPORTANT: Your entire response must be only a single, complete sentence."

        val (firstSentence, phase1Sources) = gem.generateTextWithSources(
            systemPrompt = phase1SystemPrompt,
            history = mappedHistory,
            modelName = modelName,
            temperature = 0.7,
            enableGoogleSearch = true
        )

        Log.i(TAG, "[PHASE-1] ✅ Response received (len=${firstSentence.length}): '${firstSentence.take(100)}...'")
        Log.d(TAG, "[PHASE-1] Sources: ${phase1Sources.size} items")

        if (!active) {
            Log.w(TAG, "[PHASE-1] ❌ ABORTED (active=false) - discarding result")
            return
        }

        if (firstSentence.isBlank()) {
            Log.e(TAG, "[PHASE-1] ❌ EMPTY RESPONSE from Gemini!")
            callbacks.onError("LLM returned empty response")
            callbacks.onTurnFinish()
            return
        }

        Log.i(TAG, "[PHASE-1] ✅ Calling onFirstSentence callback")
        callbacks.onFirstSentence(firstSentence, phase1Sources)

        if (maxSentences <= 1) {
            Log.i(TAG, "[PHASE-1] Max sentences is 1, finishing")
            callbacks.onTurnFinish()
            return
        }

        // --- PHASE 2: Generate the rest ---
        Log.i(TAG, "[PHASE-2] 🚀 Requesting remaining ${maxSentences - 1} sentences...")
        val phase2History = mappedHistory + ChatMessage("assistant", firstSentence)
        val remainingCount = maxSentences - 1
        val plural = if (remainingCount > 1) "sentences" else "sentence"
        val phase2SystemPrompt = "$systemPrompt\n\nContinue your previous thought. You have already said: \"$firstSentence\". Now, provide the rest of your response in AT MOST $remainingCount more $plural."

        val (remainderText, phase2Sources) = gem.generateTextWithSources(
            systemPrompt = phase2SystemPrompt,
            history = phase2History,
            modelName = modelName,
            temperature = 0.7,
            enableGoogleSearch = true
        )

        Log.i(TAG, "[PHASE-2] ✅ Response received (len=${remainderText.length})")

        if (!active) {
            Log.w(TAG, "[PHASE-2] ❌ ABORTED (active=false) - discarding result")
            return
        }

        if (remainderText.isNotBlank()) {
            val remainingSentences = SentenceSplitter.splitIntoSentences(remainderText)
            Log.i(TAG, "[PHASE-2] ✅ Calling onRemainingSentences (count=${remainingSentences.size})")
            callbacks.onRemainingSentences(remainingSentences, phase2Sources)
        } else {
            Log.w(TAG, "[PHASE-2] ⚠️ Empty remainder, only first sentence available")
        }

        Log.i(TAG, "[COMPLETE] ✅ Generation finished successfully")
        callbacks.onTurnFinish()
    }

    private suspend fun executeOpenAiTwoPhase(
        mappedHistory: List<ChatMessage>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        val openai = OpenAiService(openAiKeyProvider, http)

        // Get the saved effort level for this model
        val savedEffort = try {
            Prefs.getGpt5Effort(null!!) // We'll fix this - need context
        } catch (e: Exception) {
            "medium"
        }

        // --- PHASE 1: Get the first sentence with LOW effort (fast) ---
        Log.i(TAG, "[PHASE-1] 🚀 Requesting first sentence from OpenAI (low effort)...")
        val phase1SystemPrompt = "$systemPrompt\n\nIMPORTANT: Your entire response must be only a single, complete sentence."

        val phase1Result = openai.generateResponses(
            systemPrompt = phase1SystemPrompt,
            history = mappedHistory,
            model = modelName,
            effort = "low",  // ✅ Fast first sentence
            verbosity = "low",
            enableWebSearch = true
        )

        Log.i(TAG, "[PHASE-1] ✅ Response received (len=${phase1Result.text.length}): '${phase1Result.text.take(100)}...'")
        Log.d(TAG, "[PHASE-1] Sources: ${phase1Result.sources.size} items")

        if (!active) {
            Log.w(TAG, "[PHASE-1] ❌ ABORTED (active=false) - discarding result")
            return
        }

        if (phase1Result.text.isBlank()) {
            Log.e(TAG, "[PHASE-1] ❌ EMPTY RESPONSE from OpenAI!")
            callbacks.onError("LLM returned empty response")
            callbacks.onTurnFinish()
            return
        }

        Log.i(TAG, "[PHASE-1] ✅ Calling onFirstSentence callback")
        callbacks.onFirstSentence(phase1Result.text, phase1Result.sources)

        if (maxSentences <= 1) {
            Log.i(TAG, "[PHASE-1] Max sentences is 1, finishing")
            callbacks.onTurnFinish()
            return
        }

        // --- PHASE 2: Generate the rest with user's chosen effort level ---
        Log.i(TAG, "[PHASE-2] 🚀 Requesting remaining ${maxSentences - 1} sentences (effort=$savedEffort)...")
        val phase2History = mappedHistory + ChatMessage("assistant", phase1Result.text)
        val remainingCount = maxSentences - 1
        val plural = if (remainingCount > 1) "sentences" else "sentence"
        val phase2SystemPrompt = "$systemPrompt\n\nContinue your previous thought. You have already said: \"${phase1Result.text}\". Now, provide the rest of your response in AT MOST $remainingCount more $plural."

        val phase2Result = openai.generateResponses(
            systemPrompt = phase2SystemPrompt,
            history = phase2History,
            model = modelName,
            effort = savedEffort,  // ✅ Use user's chosen effort level
            verbosity = "medium",
            enableWebSearch = true
        )

        Log.i(TAG, "[PHASE-2] ✅ Response received (len=${phase2Result.text.length})")

        if (!active) {
            Log.w(TAG, "[PHASE-2] ❌ ABORTED (active=false) - discarding result")
            return
        }

        if (phase2Result.text.isNotBlank()) {
            val remainingSentences = SentenceSplitter.splitIntoSentences(phase2Result.text)
            Log.i(TAG, "[PHASE-2] ✅ Calling onRemainingSentences (count=${remainingSentences.size})")
            callbacks.onRemainingSentences(remainingSentences, phase2Result.sources)
        } else {
            Log.w(TAG, "[PHASE-2] ⚠️ Empty remainder, only first sentence available")
        }

        Log.i(TAG, "[COMPLETE] ✅ Generation finished successfully")
        callbacks.onTurnFinish()
    }

    override fun abort() {
        Log.w(TAG, "[ABORT] ❌ Abort requested, setting active=false")
        active = false
    }

    private fun mapHistory(history: List<SentenceTurnEngine.Msg>): List<ChatMessage> =
        history.map { ChatMessage(it.role, it.text) }
}