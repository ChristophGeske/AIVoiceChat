package com.example.advancedvoice.domain.strategy

import android.util.Log
import com.example.advancedvoice.core.text.SentenceSplitter
import com.example.advancedvoice.data.common.ChatMessage
import com.example.advancedvoice.data.gemini.GeminiService
import com.example.advancedvoice.data.openai.OpenAiService
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Two-phase strategy:
 *  - Phase 1: fast first sentence
 *  - Phase 2: remaining sentences
 */
class FastFirstSentenceStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String
) : IGenerationStrategy {

    companion object { private const val TAG = "FastFirst" }

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
        Log.i(TAG, "[FastFirst] Starting two-phase generation: model=$modelName, maxSentences=$maxSentences")

        scope.launch(Dispatchers.IO) {
            try {
                val isGemini = modelName.contains("gemini", ignoreCase = true)

                // PHASE 1: Generate first sentence quickly
                Log.i(TAG, "[FastFirst] PHASE 1: Generating first sentence with low temperature...")
                val (firstSentence, remaining) = if (isGemini) {
                    val gem = GeminiService(geminiKeyProvider, http)
                    val phase1Prompt = "$systemPrompt\n\nRespond with ONE concise complete sentence that directly answers the user's request."
                    val phase1Text = gem.generateText(
                        systemPrompt = phase1Prompt,
                        history = mapHistory(history),
                        modelName = modelName,
                        temperature = 0.2,
                        enableGoogleSearch = true
                    )
                    val first = SentenceSplitter.extractFirstSentence(phase1Text).first.trim()
                    Log.i(TAG, "[FastFirst] PHASE 1 complete: '${first.take(80)}...'")
                    callbacks.onFirstSentence(first)

                    if (!active) {
                        Log.w(TAG, "[FastFirst] Aborted after phase 1")
                        return@launch
                    }

                    // PHASE 2: Generate remaining sentences
                    val remainingCount = (maxSentences - 1).coerceAtLeast(1)
                    Log.i(TAG, "[FastFirst] PHASE 2: Generating $remainingCount additional sentence(s)...")
                    val phase2Prompt = """
                        $systemPrompt

                        The user already received: "$first".
                        Continue with $remainingCount more sentence${if (remainingCount != 1) "s" else ""}, do NOT repeat or rephrase the opening.
                        Start immediately with new information.
                    """.trimIndent()
                    val phase2Text = gem.generateText(
                        systemPrompt = phase2Prompt,
                        history = mapHistory(history) + ChatMessage("assistant", first),
                        modelName = modelName,
                        temperature = 0.7,
                        enableGoogleSearch = true
                    )
                    val remainingSentences = SentenceSplitter.splitIntoSentences(phase2Text)
                    Log.i(TAG, "[FastFirst] PHASE 2 complete: ${remainingSentences.size} sentences")
                    first to remainingSentences
                } else {
                    val openai = OpenAiService(openAiKeyProvider, http)
                    val phase1Prompt = "$systemPrompt\n\nRespond with ONE concise complete sentence that directly answers the user's request."
                    val phase1 = openai.generateResponses(
                        systemPrompt = phase1Prompt,
                        history = mapHistory(history),
                        model = modelName,
                        effort = "low",
                        verbosity = "low",
                        enableWebSearch = true
                    ).text
                    val first = SentenceSplitter.extractFirstSentence(phase1).first.trim()
                    Log.i(TAG, "[FastFirst] PHASE 1 complete: '${first.take(80)}...'")
                    callbacks.onFirstSentence(first)

                    if (!active) {
                        Log.w(TAG, "[FastFirst] Aborted after phase 1")
                        return@launch
                    }

                    val remainingCount = (maxSentences - 1).coerceAtLeast(1)
                    Log.i(TAG, "[FastFirst] PHASE 2: Generating $remainingCount additional sentence(s)...")
                    val phase2Prompt = """
                        $systemPrompt

                        The user already received: "$first".
                        Continue with $remainingCount more sentence${if (remainingCount != 1) "s" else ""}, do NOT repeat or rephrase the opening.
                        Start immediately with new information.
                    """.trimIndent()
                    val phase2 = openai.generateResponses(
                        systemPrompt = phase2Prompt,
                        history = mapHistory(history) + ChatMessage("assistant", first),
                        model = modelName,
                        effort = "low",
                        verbosity = "low",
                        enableWebSearch = true
                    ).text
                    val remainingSentences = SentenceSplitter.splitIntoSentences(phase2)
                    Log.i(TAG, "[FastFirst] PHASE 2 complete: ${remainingSentences.size} sentences")
                    first to remainingSentences
                }

                if (!active) {
                    Log.w(TAG, "[FastFirst] Aborted before emitting remaining sentences")
                    return@launch
                }

                if (remaining.isNotEmpty()) {
                    callbacks.onRemainingSentences(remaining)
                }

                val final = buildString {
                    append(firstSentence)
                    if (remaining.isNotEmpty()) {
                        append(' ')
                        append(remaining.joinToString(" "))
                    }
                }.trim()

                Log.i(TAG, "[FastFirst] Complete response (len=${final.length})")
                callbacks.onFinalResponse(final)
            } catch (t: Throwable) {
                Log.e(TAG, "[FastFirst] Error: ${t.message}", t)
                if (active) callbacks.onError(t.message ?: "Generation failed")
            } finally {
                if (active) {
                    active = false
                    Log.i(TAG, "[FastFirst] Turn finished")
                    callbacks.onTurnFinish()
                }
            }
        }
    }

    override fun abort() {
        Log.w(TAG, "[FastFirst] abort() called")
        active = false
    }

    private fun mapHistory(history: List<SentenceTurnEngine.Msg>): List<ChatMessage> =
        history.map { ChatMessage(it.role, it.text) }
}