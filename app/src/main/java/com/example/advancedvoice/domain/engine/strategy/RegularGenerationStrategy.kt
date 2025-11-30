package com.example.advancedvoice.domain.engine.strategy

import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.text.SentenceSplitter
import com.example.advancedvoice.data.common.ChatMessage
import com.example.advancedvoice.data.gemini.GeminiAudioService
import com.example.advancedvoice.data.gemini.GeminiService
import com.example.advancedvoice.data.openai.OpenAiService
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Single-shot generation strategy:
 * Gets all sentences in one request, then sends the complete response.
 *
 * Supports both text and audio input.
 */
class RegularGenerationStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String
) : IGenerationStrategy {

    companion object {
        private const val TAG = LoggerConfig.TAG_STRATEGY
    }

    @Volatile private var active = false

    // Lazy-initialized services
    private val geminiService by lazy { GeminiService(geminiKeyProvider, http) }
    private val geminiAudioService by lazy { GeminiAudioService(geminiKeyProvider, http) }
    private val openAiService by lazy { OpenAiService(openAiKeyProvider, http) }

    // ================================================================================
    // PUBLIC INTERFACE
    // ================================================================================

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
        Log.i(TAG, "[REGULAR] Starting text generation")
        Log.i(TAG, "  Model: $modelName (${if (isGemini) "Gemini" else "OpenAI"})")
        Log.i(TAG, "  Max Sentences: $maxSentences")
        Log.i(TAG, "  History size: ${history.size}")
        Log.i(TAG, "═══════════════════════════════════")

        scope.launch(Dispatchers.IO) {
            try {
                val mappedHistory = mapHistory(history)

                if (isGemini) {
                    executeGemini(mappedHistory, modelName, systemPrompt, maxSentences, callbacks)
                } else {
                    executeOpenAi(mappedHistory, modelName, systemPrompt, maxSentences, callbacks)
                }
            } catch (t: Throwable) {
                handleError(t, callbacks)
            } finally {
                active = false
                Log.d(TAG, "[CLEANUP] Strategy completed")
            }
        }
    }

    override fun executeWithAudio(
        scope: CoroutineScope,
        history: List<SentenceTurnEngine.Msg>,
        audioInput: SentenceTurnEngine.AudioInput,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        active = true
        val isGemini = modelName.contains("gemini", ignoreCase = true)

        if (!isGemini) {
            Log.w(TAG, "Audio input only supported for Gemini models, falling back to text")
            execute(
                scope,
                history + SentenceTurnEngine.Msg("user", audioInput.streamingTranscript),
                modelName, systemPrompt, maxSentences, callbacks
            )
            return
        }

        Log.i(TAG, "═══════════════════════════════════")
        Log.i(TAG, "[REGULAR-AUDIO] Starting audio generation")
        Log.i(TAG, "  Model: $modelName")
        Log.i(TAG, "  Audio segments: ${audioInput.segments.size}")
        Log.i(TAG, "  Streaming transcript: '${audioInput.streamingTranscript.take(50)}...'")
        Log.i(TAG, "  Max Sentences: $maxSentences")
        Log.i(TAG, "═══════════════════════════════════")

        scope.launch(Dispatchers.IO) {
            try {
                val mappedHistory = mapHistory(history)
                executeGeminiWithAudio(
                    mappedHistory, audioInput.segments, modelName,
                    systemPrompt, maxSentences, callbacks
                )
            } catch (t: Throwable) {
                handleError(t, callbacks)
            } finally {
                active = false
                Log.d(TAG, "[CLEANUP] Audio strategy completed")
            }
        }
    }

    override fun abort() {
        Log.w(TAG, "[ABORT] Abort requested")
        active = false
    }

    // ================================================================================
    // GEMINI AUDIO IMPLEMENTATION
    // ================================================================================

    private suspend fun executeGeminiWithAudio(
        textHistory: List<ChatMessage>,
        audioSegments: List<ByteArray>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        Log.i(TAG, "[AUDIO] 🚀 Generating response from audio...")

        val fullPrompt = buildAudioPrompt(systemPrompt, maxSentences)

        val (fullResponse, sources) = geminiAudioService.generateFromAudio(
            systemPrompt = fullPrompt,
            textHistory = textHistory,
            audioSegments = audioSegments,
            modelName = modelName,
            temperature = 0.7,
            enableGoogleSearch = true
        )

        Log.i(TAG, "[AUDIO] ✅ Response: ${fullResponse.length} chars")

        if (!active) {
            Log.w(TAG, "[AUDIO] ❌ ABORTED")
            return
        }

        if (fullResponse.isBlank()) {
            callbacks.onError("LLM returned empty response from audio")
            callbacks.onTurnFinish()
            return
        }

        val sentences = SentenceSplitter.splitIntoSentences(fullResponse)
        Log.i(TAG, "[AUDIO] ✅ Split into ${sentences.size} sentences")

        callbacks.onFinalResponse(fullResponse, sources)
        callbacks.onTurnFinish()
    }

    // ================================================================================
    // GEMINI TEXT IMPLEMENTATION
    // ================================================================================

    private suspend fun executeGemini(
        history: List<ChatMessage>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        Log.i(TAG, "[GEMINI] 🚀 Generating response...")

        val fullPrompt = buildTextPrompt(systemPrompt, maxSentences)

        val (fullResponse, sources) = geminiService.generateTextWithSources(
            systemPrompt = fullPrompt,
            history = history,
            modelName = modelName,
            temperature = 0.7,
            enableGoogleSearch = true
        )

        Log.i(TAG, "[GEMINI] ✅ Response: ${fullResponse.length} chars")

        if (!active) {
            Log.w(TAG, "[GEMINI] ❌ ABORTED")
            return
        }

        if (fullResponse.isBlank()) {
            callbacks.onError("LLM returned empty response")
            callbacks.onTurnFinish()
            return
        }

        callbacks.onFinalResponse(fullResponse, sources)
        callbacks.onTurnFinish()
    }

    // ================================================================================
    // OPENAI TEXT IMPLEMENTATION
    // ================================================================================

    private suspend fun executeOpenAi(
        history: List<ChatMessage>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        Log.i(TAG, "[OPENAI] 🚀 Generating response...")

        val fullPrompt = buildTextPrompt(systemPrompt, maxSentences)

        val result = openAiService.generateResponses(
            systemPrompt = fullPrompt,
            history = history,
            model = modelName,
            effort = "medium",
            verbosity = "medium",
            enableWebSearch = true
        )

        Log.i(TAG, "[OPENAI] ✅ Response: ${result.text.length} chars")

        if (!active) {
            Log.w(TAG, "[OPENAI] ❌ ABORTED")
            return
        }

        if (result.text.isBlank()) {
            callbacks.onError("LLM returned empty response")
            callbacks.onTurnFinish()
            return
        }

        callbacks.onFinalResponse(result.text, result.sources)
        callbacks.onTurnFinish()
    }

    // ================================================================================
    // HELPER METHODS
    // ================================================================================

    private fun buildAudioPrompt(basePrompt: String, maxSentences: Int): String = """
        $basePrompt
        
        AUDIO INPUT INSTRUCTIONS:
        - The user's message is provided as audio. Listen carefully and understand what they are saying.
        - Respond naturally to what the user said in the audio.
        - If the audio is unclear, do your best to understand the intent.
        
        RESPONSE FORMAT:
        - Your response must be AT MOST $maxSentences sentences long.
    """.trimIndent()

    private fun buildTextPrompt(basePrompt: String, maxSentences: Int): String =
        "$basePrompt\n\nIMPORTANT: Your response must be AT MOST $maxSentences sentences long."

    private fun mapHistory(history: List<SentenceTurnEngine.Msg>): List<ChatMessage> =
        history.map { ChatMessage(it.role, it.text) }

    private fun handleError(t: Throwable, callbacks: SentenceTurnEngine.Callbacks) {
        Log.e(TAG, "[ERROR] ❌ Exception during generation", t)
        if (active) {
            callbacks.onError(t.message ?: "Generation failed")
            callbacks.onTurnFinish()
        }
    }
}