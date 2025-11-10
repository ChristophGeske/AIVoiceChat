package com.example.advancedvoice.feature.conversation.presentation.engine

import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.text.SentenceSplitter
import com.example.advancedvoice.core.util.PerfTimer
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.domain.util.GroundingUtils
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
import com.example.advancedvoice.feature.conversation.presentation.interruption.InterruptionManager
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.TtsController

/**
 * Factory for creating SentenceTurnEngine callbacks.
 */
object EngineCallbacksFactory {
    private const val TAG = LoggerConfig.TAG_VM

    @Volatile private var lastResponseWasBuffered = false

    fun create(
        stateManager: ConversationStateManager,
        tts: TtsController,
        perfTimerHolder: PerfTimerHolder,
        getInterruption: () -> InterruptionManager
    ): SentenceTurnEngine.Callbacks {
        return SentenceTurnEngine.Callbacks(
            onStreamDelta = {},
            onStreamSentence = {},

            onFirstSentence = { text, sources ->
                Log.i(TAG, "[ENGINE_CB] onFirstSentence received (len=${text.length}, sources=${sources.size})")
                stateManager.setPhase(GenerationPhase.GENERATING_REMAINDER)

                val lastEntry = stateManager.conversation.value.lastOrNull()
                if (lastEntry == null || !lastEntry.isAssistant || lastEntry.sentences.isEmpty()) {
                    stateManager.addAssistant(listOf(text))
                    tts.queue(text)
                    // ✅ Display grounding for first sentence (never buffered)
                    if (sources.isNotEmpty()) {
                        GroundingUtils.processAndDisplaySources(sources, stateManager::addSystem)
                    }
                    Log.d(TAG, "[ENGINE_CB] Added first sentence to conversation and TTS")
                } else {
                    Log.w(TAG, "[ENGINE_CB] First sentence already exists, skipping duplicate")
                }
            },

            onRemainingSentences = { sentences, sources ->
                Log.i(TAG, "[ENGINE_CB] onRemainingSentences received (count=${sentences.size}, sources=${sources.size})")
                stateManager.setPhase(GenerationPhase.IDLE)

                val idx = stateManager.conversation.value.indexOfLast { it.isAssistant }
                if (idx >= 0 && sentences.isNotEmpty()) {
                    val current = stateManager.conversation.value[idx].sentences
                    val actualNew = sentences.filterIndexed { index, sentence ->
                        val globalIndex = current.size + index
                        globalIndex >= current.size
                    }

                    if (actualNew.isNotEmpty()) {
                        Log.d(TAG, "[ENGINE_CB] Appending ${actualNew.size} new sentences")
                        stateManager.appendAssistantSentences(idx, actualNew)
                        tts.queue(actualNew.joinToString(" "))

                        // ✅ Display grounding for remainder (never buffered in fast-first mode)
                        if (sources.isNotEmpty()) {
                            GroundingUtils.processAndDisplaySources(sources, stateManager::addSystem)
                        }
                    }
                }
            },

            onFinalResponse = { fullText, sources ->
                perfTimerHolder.current?.mark("llm_done")
                perfTimerHolder.current?.logSummary("llm_start", "llm_done")
                Log.i(TAG, "[ENGINE_CB] onFinalResponse received (len=${fullText.length}, sources=${sources.size})")

                val sentences = SentenceSplitter.splitIntoSentences(fullText)
                if (sentences.isNotEmpty()) {
                    val text = sentences.joinToString(" ")
                    val shouldBuffer = getInterruption().maybeHandleAssistantFinalResponse(text)

                    if (shouldBuffer) {
                        Log.d(TAG, "[ENGINE_CB] ⏸️ Response buffered - NO grounding display")
                        // ✅ DO NOT display grounding for buffered responses
                    } else {
                        stateManager.setPhase(GenerationPhase.IDLE)
                        val lastEntry = stateManager.conversation.value.lastOrNull()
                        if (lastEntry == null || !lastEntry.isAssistant || lastEntry.sentences.isEmpty()) {
                            Log.d(TAG, "[ENGINE_CB] Adding ${sentences.size} sentences from final response")
                            stateManager.addAssistant(sentences)
                            tts.queue(text)

                            // ✅ Only display grounding for non-buffered responses
                            if (sources.isNotEmpty()) {
                                GroundingUtils.processAndDisplaySources(sources, stateManager::addSystem)
                            }
                        }
                    }
                } else {
                    stateManager.setPhase(GenerationPhase.IDLE)
                }
            },

            onTurnFinish = {
                Log.i(TAG, "[ENGINE_CB] onTurnFinish received.")
                perfTimerHolder.current = null
                lastResponseWasBuffered = false  // ✅ Reset for next turn
                stateManager.setPhase(GenerationPhase.IDLE)
            },

            onSystem = { msg ->
                stateManager.addSystem(msg)
            },

            onError = { msg ->
                perfTimerHolder.current?.mark("llm_done")
                perfTimerHolder.current?.logSummary("llm_start", "llm_done")
                lastResponseWasBuffered = false  // ✅ Reset on error
                stateManager.setPhase(GenerationPhase.IDLE)
                stateManager.addError(msg)
            }
        )
    }

    class PerfTimerHolder {
        var current: PerfTimer? = null
    }
}