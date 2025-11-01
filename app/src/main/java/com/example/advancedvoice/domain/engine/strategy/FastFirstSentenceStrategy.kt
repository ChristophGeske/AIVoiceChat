package com.example.advancedvoice.domain.strategy

import android.util.Log
import com.example.advancedvoice.core.text.SentenceSplitter
import com.example.advancedvoice.data.common.ChatMessage
import com.example.advancedvoice.data.gemini.GeminiService
import com.example.advancedvoice.data.openai.OpenAiService
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.domain.util.CombinedSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class FastFirstSentenceStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String
) : IGenerationStrategy {

    companion object { private const val TAG = "FastFirst" }

    private var executionJob: Job? = null

    override fun execute(
        scope: CoroutineScope,
        history: List<SentenceTurnEngine.Msg>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        executionJob?.cancel()

        Log.i(TAG, "[FastFirst] Starting two-phase generation: model=$modelName, maxSentences=$maxSentences")

        val combinedSources = CombinedSources()

        executionJob = scope.launch(Dispatchers.IO) {
            try {
                val isGemini = modelName.contains("gemini", ignoreCase = true)

                Log.i(TAG, "[FastFirst] PHASE 1: Generating first sentence with low temperature...")
                val (firstSentence, remaining) = if (isGemini) {
                    val gem = GeminiService(geminiKeyProvider, http)
                    val phase1Prompt = "$systemPrompt\n\nRespond with ONE concise complete sentence that directly answers the user's request."

                    val (phase1Text, sources1) = gem.generateTextWithSources(
                        systemPrompt = phase1Prompt,
                        history = mapHistory(history),
                        modelName = modelName,
                        temperature = 0.2,
                        enableGoogleSearch = true
                    )
                    combinedSources.addAll(sources1)

                    ensureActive()

                    val first = SentenceSplitter.extractFirstSentence(phase1Text).first.trim()
                    Log.i(TAG, "[FastFirst] PHASE 1 complete: '${first.take(80)}...'")

                    ensureActive()
                    callbacks.onFirstSentence(first)

                    ensureActive()

                    val remainingCount = (maxSentences - 1).coerceAtLeast(1)
                    Log.i(TAG, "[FastFirst] PHASE 2: Generating $remainingCount additional sentence(s)...")
                    val phase2Prompt = """
                        $systemPrompt

                        The user already received: "$first".
                        Continue with $remainingCount more sentence${if (remainingCount != 1) "s" else ""}, do NOT repeat or rephrase the opening.
                        Start immediately with new information.
                    """.trimIndent()

                    val (phase2Text, sources2) = gem.generateTextWithSources(
                        systemPrompt = phase2Prompt,
                        history = mapHistory(history) + ChatMessage("assistant", first),
                        modelName = modelName,
                        temperature = 0.7,
                        enableGoogleSearch = true
                    )
                    combinedSources.addAll(sources2)

                    ensureActive()

                    val remainingSentences = SentenceSplitter.splitIntoSentences(phase2Text)
                    Log.i(TAG, "[FastFirst] PHASE 2 complete: ${remainingSentences.size} sentences")
                    first to remainingSentences
                } else {
                    val openai = OpenAiService(openAiKeyProvider, http)
                    val phase1Prompt = "$systemPrompt\n\nRespond with ONE concise complete sentence that directly answers the user's request."

                    val result1 = openai.generateResponses(
                        systemPrompt = phase1Prompt,
                        history = mapHistory(history),
                        model = modelName,
                        effort = "low",
                        verbosity = "low",
                        enableWebSearch = true
                    )
                    combinedSources.addAll(result1.sources)

                    ensureActive()

                    val first = SentenceSplitter.extractFirstSentence(result1.text).first.trim()
                    Log.i(TAG, "[FastFirst] PHASE 1 complete: '${first.take(80)}...'")

                    ensureActive()
                    callbacks.onFirstSentence(first)

                    ensureActive()

                    val remainingCount = (maxSentences - 1).coerceAtLeast(1)
                    Log.i(TAG, "[FastFirst] PHASE 2: Generating $remainingCount additional sentence(s)...")
                    val phase2Prompt = """
                        $systemPrompt

                        The user already received: "$first".
                        Continue with $remainingCount more sentence${if (remainingCount != 1) "s" else ""}, do NOT repeat or rephrase the opening.
                        Start immediately with new information.
                    """.trimIndent()

                    val result2 = openai.generateResponses(
                        systemPrompt = phase2Prompt,
                        history = mapHistory(history) + ChatMessage("assistant", first),
                        model = modelName,
                        effort = "low",
                        verbosity = "low",
                        enableWebSearch = true
                    )
                    combinedSources.addAll(result2.sources)

                    ensureActive()

                    val remainingSentences = SentenceSplitter.splitIntoSentences(result2.text)
                    Log.i(TAG, "[FastFirst] PHASE 2 complete: ${remainingSentences.size} sentences")
                    first to remainingSentences
                }

                ensureActive()

                // Always call this callback, even if remaining is empty
                callbacks.onRemainingSentences(remaining)

                Log.i(TAG, "[FastFirst] Strategy complete (first + remaining delivered)")

                // Post sources as system message
                combinedSources.toHtmlCompact()?.let { sourcesHtml ->
                    callbacks.onSystem(sourcesHtml)
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "[FastFirst] Job was cancelled")
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "[FastFirst] Error: ${t.message}", t)
                callbacks.onError(t.message ?: "Generation failed")
            } finally {
                if (isActive) {
                    Log.i(TAG, "[FastFirst] Turn finished normally")
                    callbacks.onTurnFinish()
                } else {
                    Log.i(TAG, "[FastFirst] Turn cancelled, skipping onTurnFinish")
                }
            }
        }
    }

    override fun abort() {
        Log.w(TAG, "[FastFirst] abort() called - CANCELLING JOB")
        executionJob?.cancel()
    }

    private fun mapHistory(history: List<SentenceTurnEngine.Msg>): List<ChatMessage> =
        history.map { ChatMessage(it.role, it.text) }
}