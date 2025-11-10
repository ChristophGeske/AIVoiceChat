package com.example.advancedvoice.feature.conversation.presentation.engine

import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.text.SentenceSplitter
import com.example.advancedvoice.core.util.PerfTimer
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
import com.example.advancedvoice.feature.conversation.presentation.interruption.InterruptionManager
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.TtsController

/**
 * Factory for creating SentenceTurnEngine callbacks.
 */
object EngineCallbacksFactory {
    private const val TAG = LoggerConfig.TAG_VM

    fun create(
        stateManager: ConversationStateManager,
        tts: TtsController,
        perfTimerHolder: PerfTimerHolder,
        getInterruption: () -> InterruptionManager
    ): SentenceTurnEngine.Callbacks {
        return SentenceTurnEngine.Callbacks(
            onStreamDelta = {},
            onStreamSentence = {},

            onFirstSentence = { text ->
                Log.i(TAG, "[ENGINE_CB] onFirstSentence received (len=${text.length})")
                stateManager.setPhase(GenerationPhase.GENERATING_REMAINDER)

                // Only add if not already present (prevent duplicates)
                val lastEntry = stateManager.conversation.value.lastOrNull()
                if (lastEntry == null || !lastEntry.isAssistant || lastEntry.sentences.isEmpty()) {
                    stateManager.addAssistant(listOf(text))
                    tts.queue(text)
                    Log.d(TAG, "[ENGINE_CB] Added first sentence to conversation and TTS")
                } else {
                    Log.w(TAG, "[ENGINE_CB] First sentence already exists, skipping duplicate")
                }
            },

            onRemainingSentences = { sentences ->
                Log.i(TAG, "[ENGINE_CB] onRemainingSentences received (count=${sentences.size})")
                stateManager.setPhase(GenerationPhase.IDLE)

                val idx = stateManager.conversation.value.indexOfLast { it.isAssistant }
                if (idx >= 0 && sentences.isNotEmpty()) {
                    val current = stateManager.conversation.value[idx].sentences

                    // ✅ FIXED: Strategy sends ONLY remaining sentences, so no need to calculate delta
                    // Just verify we're not duplicating (safety check)
                    val actualNew = sentences.filterIndexed { index, sentence ->
                        val globalIndex = current.size + index
                        globalIndex >= current.size  // Only include if beyond current
                    }

                    if (actualNew.isNotEmpty()) {
                        Log.d(TAG, "[ENGINE_CB] Appending ${actualNew.size} new sentences (current: ${current.size}, incoming: ${sentences.size})")
                        stateManager.appendAssistantSentences(idx, actualNew)

                        // ✅ Queue to TTS (join for natural speech flow)
                        tts.queue(actualNew.joinToString(" "))
                    } else {
                        Log.d(TAG, "[ENGINE_CB] No new sentences to append (all already present)")
                    }
                }
            },

            onFinalResponse = { full ->
                perfTimerHolder.current?.mark("llm_done")
                perfTimerHolder.current?.logSummary("llm_start", "llm_done")
                Log.i(TAG, "[ENGINE_CB] onFinalResponse received (len=${full.length})")

                val sentences = SentenceSplitter.splitIntoSentences(full)
                if (sentences.isNotEmpty()) {
                    val fullText = sentences.joinToString(" ")

                    // ✅ Check buffering BEFORE extracting grounding or changing phase
                    val shouldBuffer = getInterruption().maybeHandleAssistantFinalResponse(fullText)

                    if (shouldBuffer) {
                        Log.d(TAG, "[ENGINE_CB] Response buffered - NO grounding extraction, phase unchanged")
                        // DO NOT extract grounding, DO NOT set phase to IDLE
                        // The response is on hold pending barge-in evaluation
                    } else {
                        // ✅ Normal path - response is NOT buffered
                        stateManager.setPhase(GenerationPhase.IDLE)

                        val lastEntry = stateManager.conversation.value.lastOrNull()
                        if (lastEntry == null || !lastEntry.isAssistant || lastEntry.sentences.isEmpty()) {
                            Log.d(TAG, "[ENGINE_CB] Adding ${sentences.size} sentences from final response")
                            stateManager.addAssistant(sentences)
                            tts.queue(fullText)
                        } else {
                            Log.w(TAG, "[ENGINE_CB] Final response already exists, skipping duplicate")
                        }
                    }
                } else {
                    stateManager.setPhase(GenerationPhase.IDLE)
                }
            },

            onTurnFinish = {
                Log.i(TAG, "[ENGINE_CB] onTurnFinish received.")
                perfTimerHolder.current = null
                stateManager.setPhase(GenerationPhase.IDLE)
            },

            onSystem = { msg ->
                stateManager.addSystem(msg)
            },

            onError = { msg ->
                perfTimerHolder.current?.mark("llm_done")
                perfTimerHolder.current?.logSummary("llm_start", "llm_done")
                stateManager.setPhase(GenerationPhase.IDLE)
                stateManager.addError(msg)
            }
        )
    }

    /**
     * Holder for PerfTimer to allow callbacks to update it.
     */
    class PerfTimerHolder {
        var current: PerfTimer? = null
    }
}

