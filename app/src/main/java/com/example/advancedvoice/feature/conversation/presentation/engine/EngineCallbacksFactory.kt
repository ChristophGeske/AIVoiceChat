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

object EngineCallbacksFactory {
    private const val TAG = LoggerConfig.TAG_VM
    private const val TAG_ENGINE = LoggerConfig.TAG_ENGINE

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
                Log.i(TAG_ENGINE, "[CALLBACK] ✅ onFirstSentence (len=${text.length}, sources=${sources.size})")
                Log.d(TAG_ENGINE, "[CALLBACK]   Text: '${text.take(100)}...'")
                Log.i(TAG, "[ENGINE_CB] onFirstSentence received (len=${text.length})")
                stateManager.setPhase(GenerationPhase.GENERATING_REMAINDER)

                // ✅ FIX: Check if we're evaluating FIRST, before processing sources
                if (getInterruption().isEvaluatingBargeIn) {
                    Log.w(TAG_ENGINE, "[CALLBACK] ⏸️ Evaluating noise - BUFFERING first sentence (not adding to UI or TTS yet)")
                    Log.w(TAG, "[ENGINE_CB] First sentence buffered due to active interruption evaluation.")

                    // ✅ FIX: Pass sources to buffering
                    val shouldBuffer = getInterruption().maybeHandleAssistantFinalResponse(text, sources)
                    if (shouldBuffer) {
                        Log.d(TAG_ENGINE, "[CALLBACK] Successfully buffered first sentence + ${sources.size} sources")
                    }
                    return@Callbacks
                }

                // ✅ FIX: Only process sources if NOT buffering
                if (sources.isNotEmpty()) {
                    Log.d(TAG_ENGINE, "[CALLBACK] Processing ${sources.size} sources")
                    GroundingUtils.processAndDisplaySources(sources) { html ->
                        stateManager.addSystem(html)
                    }
                }

                val lastEntry = stateManager.conversation.value.lastOrNull()
                if (lastEntry == null || !lastEntry.isAssistant || lastEntry.sentences.isEmpty()) {
                    Log.d(TAG_ENGINE, "[CALLBACK] Adding to conversation + queueing TTS")
                    stateManager.addAssistant(listOf(text))
                    tts.queue(text)
                    Log.d(TAG, "[ENGINE_CB] Added and queued first sentence.")
                } else {
                    Log.w(TAG_ENGINE, "[CALLBACK] ⚠️ Duplicate first sentence, skipping")
                    Log.w(TAG, "[ENGINE_CB] First sentence already exists, skipping duplicate")
                }
            },

            onRemainingSentences = { sentences, sources ->
                Log.i(TAG_ENGINE, "[CALLBACK] ✅ onRemainingSentences (count=${sentences.size}, sources=${sources.size})")
                Log.i(TAG, "[ENGINE_CB] onRemainingSentences received (count=${sentences.size})")
                stateManager.setPhase(GenerationPhase.IDLE)

                // ✅ FIX: Check buffering first
                if (getInterruption().isEvaluatingBargeIn) {
                    Log.w(TAG_ENGINE, "[CALLBACK] ⏸️ Still evaluating - remaining sentences will be included if noise is confirmed")
                    return@Callbacks
                }

                // ✅ FIX: Only process sources if not buffering
                if (sources.isNotEmpty()) {
                    Log.d(TAG_ENGINE, "[CALLBACK] Processing ${sources.size} additional sources from phase 2")
                    GroundingUtils.processAndDisplaySources(sources) { html ->
                        stateManager.addSystem(html)
                    }
                }

                val idx = stateManager.conversation.value.indexOfLast { it.isAssistant }
                if (idx >= 0 && sentences.isNotEmpty()) {
                    val current = stateManager.conversation.value[idx].sentences
                    val actualNew = sentences.filterIndexed { index, sentence ->
                        val globalIndex = current.size + index
                        globalIndex >= current.size
                    }

                    if (actualNew.isNotEmpty()) {
                        Log.d(TAG_ENGINE, "[CALLBACK] Appending ${actualNew.size} new sentences + queueing TTS")
                        Log.d(TAG, "[ENGINE_CB] Appending ${actualNew.size} new sentences")
                        stateManager.appendAssistantSentences(idx, actualNew)
                        tts.queue(actualNew.joinToString(" "))
                    }
                }
            },

            onFinalResponse = { fullText, sources ->
                Log.i(TAG_ENGINE, "[CALLBACK] ✅ onFinalResponse (len=${fullText.length}, sources=${sources.size})")
                perfTimerHolder.current?.mark("llm_done")
                perfTimerHolder.current?.logSummary("llm_start", "llm_done")
                Log.i(TAG, "[ENGINE_CB] onFinalResponse received (len=${fullText.length})")

                val sentences = SentenceSplitter.splitIntoSentences(fullText)
                if (sentences.isNotEmpty()) {
                    val text = sentences.joinToString(" ")

                    // ✅ FIX: Check buffering FIRST, and pass sources
                    val shouldBuffer = getInterruption().maybeHandleAssistantFinalResponse(text, sources)

                    if (shouldBuffer) {
                        Log.d(TAG_ENGINE, "[CALLBACK] ⏸️ Response buffered (text + ${sources.size} sources)")
                        Log.d(TAG, "[ENGINE_CB] ⏸️ Response buffered - NO action")
                    } else {
                        // ✅ FIX: Only process sources if NOT buffering
                        if (sources.isNotEmpty()) {
                            Log.d(TAG_ENGINE, "[CALLBACK] Processing ${sources.size} sources from final response")
                            GroundingUtils.processAndDisplaySources(sources) { html ->
                                stateManager.addSystem(html)
                            }
                        }

                        stateManager.setPhase(GenerationPhase.IDLE)
                        val lastEntry = stateManager.conversation.value.lastOrNull()
                        if (lastEntry == null || !lastEntry.isAssistant || lastEntry.sentences.isEmpty()) {
                            Log.d(TAG_ENGINE, "[CALLBACK] Adding ${sentences.size} sentences + queueing TTS")
                            Log.d(TAG, "[ENGINE_CB] Adding ${sentences.size} sentences from final response")
                            stateManager.addAssistant(sentences)
                            tts.queue(text)
                        } else {
                            Log.w(TAG_ENGINE, "[CALLBACK] ⚠️ Duplicate final response")
                            Log.w(TAG, "[ENGINE_CB] Final response already exists, skipping duplicate")
                        }
                    }
                } else {
                    stateManager.setPhase(GenerationPhase.IDLE)
                }
            },

            onTurnFinish = {
                Log.i(TAG_ENGINE, "[CALLBACK] ✅ onTurnFinish")
                Log.i(TAG, "[ENGINE_CB] onTurnFinish received.")
                perfTimerHolder.current = null
                stateManager.setPhase(GenerationPhase.IDLE)
            },

            onSystem = { msg ->
                stateManager.addSystem(msg)
            },

            onError = { msg ->
                Log.e(TAG_ENGINE, "[CALLBACK] ❌ onError: $msg")
                perfTimerHolder.current?.mark("llm_done")
                perfTimerHolder.current?.logSummary("llm_start", "llm_done")
                stateManager.setPhase(GenerationPhase.IDLE)
                stateManager.addError(msg)
            }
        )
    }

    class PerfTimerHolder {
        var current: PerfTimer? = null
    }
}