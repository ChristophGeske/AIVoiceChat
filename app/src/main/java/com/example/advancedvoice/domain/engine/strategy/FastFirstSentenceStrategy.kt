package com.example.advancedvoice.domain.engine.strategy

import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.prefs.Prefs
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
 * Two-phase generation strategy:
 * Phase 1: Get first sentence quickly (for immediate TTS)
 * Phase 2: Get remaining sentences
 *
 * Supports both text and audio input.
 */
class FastFirstSentenceStrategy(
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
        Log.i(TAG, "[FAST-FIRST] Starting text generation")
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
        Log.i(TAG, "[FAST-FIRST-AUDIO] Starting audio generation")
        Log.i(TAG, "  Model: $modelName")
        Log.i(TAG, "  Audio segments: ${audioInput.segments.size}")
        Log.i(TAG, "  Streaming transcript: '${audioInput.streamingTranscript.take(50)}...'")
        Log.i(TAG, "  Max Sentences: $maxSentences")
        Log.i(TAG, "═══════════════════════════════════")

        scope.launch(Dispatchers.IO) {
            try {
                val mappedHistory = mapHistory(history)
                executeGeminiTwoPhaseWithAudio(
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

    private suspend fun executeGeminiTwoPhaseWithAudio(
        textHistory: List<ChatMessage>,
        audioSegments: List<ByteArray>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        Log.i(TAG, "[PHASE-1-AUDIO] 🚀 Requesting first sentence from audio...")

        //   Pass maxSentences to prompt builder
        val phase1Prompt = buildAudioPhase1Prompt(systemPrompt, maxSentences)

        val (firstSentence, phase1Sources) = geminiAudioService.generateFromAudio(
            systemPrompt = phase1Prompt,
            textHistory = textHistory,
            audioSegments = audioSegments,
            modelName = modelName,
            temperature = 0.7,
            enableGoogleSearch = true
        )

        Log.i(TAG, "[PHASE-1-AUDIO] ✅ Response: '${firstSentence.take(80)}...'")

        if (!active) {
            Log.w(TAG, "[PHASE-1-AUDIO] ❌ ABORTED")
            return
        }

        if (firstSentence.isBlank()) {
            callbacks.onError("LLM returned empty response from audio")
            callbacks.onTurnFinish()
            return
        }

        callbacks.onFirstSentence(firstSentence, phase1Sources)

        if (maxSentences <= 1) {
            callbacks.onTurnFinish()
            return
        }

        // --- PHASE 2: Remaining sentences (text-based continuation) ---
        Log.i(TAG, "[PHASE-2-AUDIO] 🚀 Requesting remaining ${maxSentences - 1} sentences...")

        val phase2History = textHistory +
                ChatMessage("user", "[User spoke via audio]") +
                ChatMessage("assistant", firstSentence)

        val phase2Prompt = buildPhase2Prompt(systemPrompt, firstSentence, maxSentences - 1, isFromAudio = true)

        val (remainderText, phase2Sources) = geminiService.generateTextWithSources(
            systemPrompt = phase2Prompt,
            history = phase2History,
            modelName = modelName,
            temperature = 0.7,
            enableGoogleSearch = true
        )

        Log.i(TAG, "[PHASE-2-AUDIO] ✅ Response: ${remainderText.length} chars")

        if (!active) {
            Log.w(TAG, "[PHASE-2-AUDIO] ❌ ABORTED")
            return
        }

        if (remainderText.isNotBlank()) {
            val sentences = SentenceSplitter.splitIntoSentences(remainderText)
            callbacks.onRemainingSentences(sentences, phase2Sources)
        }

        Log.i(TAG, "[COMPLETE-AUDIO] ✅ Generation finished")
        callbacks.onTurnFinish()
    }

    // ================================================================================
    // GEMINI TEXT IMPLEMENTATION
    // ================================================================================

    private suspend fun executeGeminiTwoPhase(
        history: List<ChatMessage>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        // --- PHASE 1: First sentence ---
        Log.i(TAG, "[PHASE-1] 🚀 Requesting first sentence...")

        val phase1Prompt = buildTextPhase1Prompt(systemPrompt)

        val (firstSentence, phase1Sources) = geminiService.generateTextWithSources(
            systemPrompt = phase1Prompt,
            history = history,
            modelName = modelName,
            temperature = 0.7,
            enableGoogleSearch = true
        )

        Log.i(TAG, "[PHASE-1] ✅ Response: '${firstSentence.take(80)}...'")

        if (!active) {
            Log.w(TAG, "[PHASE-1] ❌ ABORTED")
            return
        }

        if (firstSentence.isBlank()) {
            callbacks.onError("LLM returned empty response")
            callbacks.onTurnFinish()
            return
        }

        callbacks.onFirstSentence(firstSentence, phase1Sources)

        if (maxSentences <= 1) {
            callbacks.onTurnFinish()
            return
        }

        // --- PHASE 2: Remaining sentences ---
        Log.i(TAG, "[PHASE-2] 🚀 Requesting remaining ${maxSentences - 1} sentences...")

        val phase2History = history + ChatMessage("assistant", firstSentence)
        val phase2Prompt = buildPhase2Prompt(systemPrompt, firstSentence, maxSentences - 1, isFromAudio = false)

        val (remainderText, phase2Sources) = geminiService.generateTextWithSources(
            systemPrompt = phase2Prompt,
            history = phase2History,
            modelName = modelName,
            temperature = 0.7,
            enableGoogleSearch = true
        )

        Log.i(TAG, "[PHASE-2] ✅ Response: ${remainderText.length} chars")

        if (!active) {
            Log.w(TAG, "[PHASE-2] ❌ ABORTED")
            return
        }

        if (remainderText.isNotBlank()) {
            val sentences = SentenceSplitter.splitIntoSentences(remainderText)
            callbacks.onRemainingSentences(sentences, phase2Sources)
        }

        Log.i(TAG, "[COMPLETE] ✅ Generation finished")
        callbacks.onTurnFinish()
    }

    // ================================================================================
    // OPENAI TEXT IMPLEMENTATION
    // ================================================================================

    private suspend fun executeOpenAiTwoPhase(
        history: List<ChatMessage>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        val savedEffort = try {
            @Suppress("DEPRECATION")
            Prefs.getGpt5Effort(null!!)
        } catch (_: Exception) {
            "medium"
        }

        // --- PHASE 1: First sentence (fast) ---
        Log.i(TAG, "[PHASE-1-OPENAI] 🚀 Requesting first sentence (low effort)...")

        val phase1Prompt = buildTextPhase1Prompt(systemPrompt)

        val phase1Result = openAiService.generateResponses(
            systemPrompt = phase1Prompt,
            history = history,
            model = modelName,
            effort = "low",
            verbosity = "low",
            enableWebSearch = true
        )

        Log.i(TAG, "[PHASE-1-OPENAI] ✅ Response: '${phase1Result.text.take(80)}...'")

        if (!active) {
            Log.w(TAG, "[PHASE-1-OPENAI] ❌ ABORTED")
            return
        }

        if (phase1Result.text.isBlank()) {
            callbacks.onError("LLM returned empty response")
            callbacks.onTurnFinish()
            return
        }

        callbacks.onFirstSentence(phase1Result.text, phase1Result.sources)

        if (maxSentences <= 1) {
            callbacks.onTurnFinish()
            return
        }

        // --- PHASE 2: Remaining sentences ---
        Log.i(TAG, "[PHASE-2-OPENAI] 🚀 Requesting remaining sentences (effort=$savedEffort)...")

        val phase2History = history + ChatMessage("assistant", phase1Result.text)
        val phase2Prompt = buildPhase2Prompt(systemPrompt, phase1Result.text, maxSentences - 1, isFromAudio = false)

        val phase2Result = openAiService.generateResponses(
            systemPrompt = phase2Prompt,
            history = phase2History,
            model = modelName,
            effort = savedEffort,
            verbosity = "medium",
            enableWebSearch = true
        )

        Log.i(TAG, "[PHASE-2-OPENAI] ✅ Response: ${phase2Result.text.length} chars")

        if (!active) {
            Log.w(TAG, "[PHASE-2-OPENAI] ❌ ABORTED")
            return
        }

        if (phase2Result.text.isNotBlank()) {
            val sentences = SentenceSplitter.splitIntoSentences(phase2Result.text)
            callbacks.onRemainingSentences(sentences, phase2Result.sources)
        }

        Log.i(TAG, "[COMPLETE-OPENAI] ✅ Generation finished")
        callbacks.onTurnFinish()
    }

    // ================================================================================
    // HELPER METHODS
    // ================================================================================

    private fun buildAudioPhase1Prompt(basePrompt: String, maxSentences: Int): String = """
        $basePrompt
        
        AUDIO INPUT INSTRUCTIONS:
        - The user's message is provided as audio. Listen carefully and understand what they are saying.
        - Respond naturally to what the user said in the audio.
        - If the audio is unclear, do your best to understand the intent.
        
        RESPONSE FORMAT:
        - Your entire response must be only a single, complete sentence.
        - You will provide up to ${maxSentences - 1} more sentences in the next step.
    """.trimIndent()

    private fun buildTextPhase1Prompt(basePrompt: String): String =
        "$basePrompt\n\nIMPORTANT: Your entire response must be only a single, complete sentence."

    private fun buildPhase2Prompt(
        basePrompt: String,
        firstSentence: String,
        remainingCount: Int,
        isFromAudio: Boolean
    ): String {
        val plural = if (remainingCount > 1) "sentences" else "sentence"
        val audioNote = if (isFromAudio) "\nThe user's original message was audio input. Continue responding to what they said." else ""

        return """
            $basePrompt
            
            You have already said: "$firstSentence"
            
            STRICT LIMIT: Provide EXACTLY $remainingCount more $plural to complete your response.
            Do NOT exceed this limit under any circumstances.$audioNote
        """.trimIndent()
    }

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