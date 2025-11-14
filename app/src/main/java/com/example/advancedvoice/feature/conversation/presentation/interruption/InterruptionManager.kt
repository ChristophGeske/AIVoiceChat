package com.example.advancedvoice.feature.conversation.presentation.interruption

import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.GeminiLiveSttController
import com.example.advancedvoice.feature.conversation.service.SttController
import com.example.advancedvoice.feature.conversation.service.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class InterruptionManager(
    private val scope: CoroutineScope,
    private val stateManager: ConversationStateManager,
    private val engine: SentenceTurnEngine,
    private val tts: TtsController,
    private val getStt: () -> SttController?,
    private val startTurnWithExistingHistory: () -> Unit,
    private val onInterruption: () -> Unit,
    private val onNoiseConfirmed: () -> Unit  // ✅ NEW: Re-enable auto-listen when noise is confirmed
) {
    private companion object {
        const val TAG = LoggerConfig.TAG_INTERRUPT
        const val INTERRUPT_ACCUMULATION_TIMEOUT = 30000L
        const val MIN_RESTART_INTERVAL_MS = 2000L
        val WORD_REGEX = Regex("[\\p{L}]{3,}")
    }

    @Volatile var isBargeInTurn = false
        private set
    @Volatile var isAccumulatingAfterInterrupt = false
        private set
    @Volatile var generationStartTime = 0L
        private set

    @Volatile var isEvaluatingBargeIn = false
        private set
    @Volatile private var assistantHoldBuffer: String? = null
    @Volatile private var assistantSourcesBuffer: List<Pair<String, String?>>? = null
    @Volatile private var interruptedDuringEvaluation = false

    @Volatile private var lastRestartTime = 0L
    private var interruptAccumulationJob: Job? = null

    fun initialize() {
        Log.i(TAG, "═══════════════════════════════════")
        Log.i(TAG, "InterruptionManager INITIALIZED")
        Log.i(TAG, "Strategy: transcript-based interruption")
        Log.i(TAG, "═══════════════════════════════════")
        monitorVoiceInterrupts()
    }

    private fun isGenerating(phase: GenerationPhase): Boolean {
        return phase == GenerationPhase.GENERATING_FIRST ||
                phase == GenerationPhase.GENERATING_REMAINDER ||
                phase == GenerationPhase.SINGLE_SHOT_GENERATING
    }

    private fun monitorVoiceInterrupts() {
        scope.launch {
            combine(
                stateManager.phase,
                stateManager.isHearingSpeech,
                stateManager.isSpeaking
            ) { currentPhase, hearingSpeech, speaking ->
                Triple(isGenerating(currentPhase), hearingSpeech, speaking)
            }.collect { (generating, hearingSpeech, speaking) ->
                if (hearingSpeech) {
                    val genDuration = if (generationStartTime == 0L) -1 else System.currentTimeMillis() - generationStartTime
                    Log.d(TAG, "[MONITOR] 🎤 Speech detected: gen=$generating, tts=$speaking, phase=${stateManager.phase.value}, eval=$isEvaluatingBargeIn, interrupted=$interruptedDuringEvaluation, genTime=${genDuration}ms")
                }

                // ✅ CORRECT: Always evaluate during generation (never immediate abort)
                // Only difference: during TTS we also stop playback
                if ((generating || speaking) && hearingSpeech && !isAccumulatingAfterInterrupt) {
                    if (isEvaluatingBargeIn) {
                        Log.w(TAG, "[MONITOR] ⚠️ New speech while still evaluating - resetting evaluation")
                        isEvaluatingBargeIn = false
                    }

                    if (!isEvaluatingBargeIn) {
                        val isTtsPlaying = speaking

                        if (isTtsPlaying) {
                            Log.i(TAG, "[MONITOR] 🔊 Noise during TTS playback → stopping TTS, evaluating (LLM continues if still generating)")
                        } else {
                            Log.i(TAG, "[MONITOR] 🔊 Noise during silent generation → evaluating (LLM continues)")
                        }

                        Log.d(TAG, "[MONITOR]   Phase: ${stateManager.phase.value}, TTS: $speaking, GenTime: ${System.currentTimeMillis() - generationStartTime}ms")

                        isEvaluatingBargeIn = true
                        interruptedDuringEvaluation = true

                        // Stop TTS if playing (but DON'T abort LLM)
                        tts.stop()
                        onInterruption()

                        // Start transcribing to evaluate
                        val stt = getStt()
                        if (stt is GeminiLiveSttController) {
                            scope.launch {
                                try {
                                    if (!stateManager.isListening.value && !stateManager.isTranscribing.value) {
                                        Log.d(TAG, "[MONITOR] Starting STT for evaluation")
                                        stt.start(isAutoListen = false)
                                    } else {
                                        Log.d(TAG, "[MONITOR] Switching to TRANSCRIBING mode")
                                        stt.switchMicMode(com.example.advancedvoice.core.audio.MicrophoneSession.Mode.TRANSCRIBING)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "[MONITOR] ❌ Error starting transcription: ${e.message}")
                                }
                            }
                        } else {
                            Log.w(TAG, "[MONITOR] STT not available for evaluation")
                        }
                    }
                }
            }
        }
    }

    fun maybeHandleAssistantFinalResponse(
        text: String,
        sources: List<Pair<String, String?>> = emptyList()
    ): Boolean {
        if (isAccumulatingAfterInterrupt) {
            Log.i(TAG, "[HOLD] ❌ Already accumulating - discarding late LLM response (len=${text.length}, sources=${sources.size})")
            return true
        }

        if (isEvaluatingBargeIn) {
            val stillRecording = stateManager.isListening.value || stateManager.isTranscribing.value

            if (stillRecording) {
                assistantHoldBuffer = text
                assistantSourcesBuffer = sources  // ✅ ADD THIS - Buffer sources too
                Log.i(TAG, "[HOLD] 📦 BUFFERING response (len=${text.length}, sources=${sources.size}) - mic active, awaiting verdict")
                Log.d(TAG, "[HOLD]   Listening: ${stateManager.isListening.value}, Transcribing: ${stateManager.isTranscribing.value}")
                return true
            } else {
                Log.i(TAG, "[HOLD] ✅ Mic inactive when response arrived - likely brief noise, allowing through")
                isEvaluatingBargeIn = false
                interruptedDuringEvaluation = false
                return false
            }
        }

        return false
    }

    fun checkTranscriptForInterruption(transcript: String): Boolean {
        val trimmed = transcript.trim()

        if (!isEvaluatingBargeIn) {
            Log.d(TAG, "[VERDICT] Not evaluating, normal flow")
            return false
        }

        val hasWord = WORD_REGEX.containsMatchIn(trimmed)
        Log.i(TAG, "[VERDICT] 📋 Evaluating transcript: len=${trimmed.length}, hasWord=$hasWord, buffered=${assistantHoldBuffer != null}")

        if (hasWord && assistantHoldBuffer == null && isGenerating(stateManager.phase.value)) {
            Log.w(TAG, "[VERDICT] ⚠️ RACE CONDITION: transcript before LLM response, waiting 500ms...")
            scope.launch {
                delay(500)
                val stillGenerating = isGenerating(stateManager.phase.value)
                val nowHasBuffer = assistantHoldBuffer != null
                Log.d(TAG, "[VERDICT] After wait: generating=$stillGenerating, buffered=$nowHasBuffer")
                handleConfirmedSpeechInterruption(trimmed)
            }
            return true
        }

        isEvaluatingBargeIn = false
        val hadBufferedResponse = assistantHoldBuffer != null

        if (hasWord) {
            Log.i(TAG, "[VERDICT] ✅ REAL SPEECH CONFIRMED")
            handleConfirmedSpeechInterruption(trimmed)
            return true
        } else {
            Log.i(TAG, "[VERDICT] ❌ NOISE CONFIRMED")
            handleConfirmedNoise()
            return true
        }
    }

    private fun handleConfirmedSpeechInterruption(trimmed: String) {
        isEvaluatingBargeIn = false
        interruptedDuringEvaluation = false

        Log.i(TAG, "[SPEECH] 🗣️ Real speech confirmed → discarding buffered response, entering accumulation")

        // ✅ FIX: Properly clean up old assistant response
        val hadBufferedResponse = assistantHoldBuffer != null
        dropBufferedAssistant()

        // ✅ FIX: If there was no buffered response but turn is active,
        // the response may already be in conversation - remove it
        if (!hadBufferedResponse && engine.isActive()) {
            Log.w(TAG, "[SPEECH] No buffered response, but generation active - removing last assistant from conversation")
            val lastEntry = stateManager.conversation.value.lastOrNull()
            if (lastEntry?.isAssistant == true) {
                stateManager.removeLastEntry()
                Log.d(TAG, "[SPEECH] Removed last assistant entry from UI")
            }
        }

        engine.abort(true)

        isBargeInTurn = false
        isAccumulatingAfterInterrupt = true

        scope.launch {
            val combined = combineTranscript(trimmed)
            Log.i(TAG, "[SPEECH] Combined input: '${combined.take(120)}...'")
            stateManager.replaceLastUser(combined)
            startAccumulationWatcher()
        }
    }

    private fun handleConfirmedNoise() {
        isEvaluatingBargeIn = false

        Log.i(TAG, "[NOISE] 🔇 Noise confirmed → flushing buffered response (if any)")

        val held = assistantHoldBuffer
        val heldSources = assistantSourcesBuffer ?: emptyList()  // ✅ ADD THIS

        if (held != null) {
            assistantHoldBuffer = null
            assistantSourcesBuffer = null  // ✅ ADD THIS

            Log.i(TAG, "[NOISE] ✅ Flushing buffered response (len=${held.length}, sources=${heldSources.size}) → adding to UI + TTS")  // ✅ UPDATE THIS

            // ✅ ADD THIS - Process sources first
            if (heldSources.isNotEmpty()) {
                com.example.advancedvoice.domain.util.GroundingUtils.processAndDisplaySources(heldSources) { html ->
                    stateManager.addSystem(html)
                }
            }

            stateManager.addAssistant(listOf(held))
            tts.queue(held)
            interruptedDuringEvaluation = false

            onNoiseConfirmed()
            Log.d(TAG, "[NOISE] Re-enabled auto-listen flag (turn completing normally)")
        } else {
            Log.d(TAG, "[NOISE] No buffered response to flush")

            // Check if we're generating (don't resume if so)
            val isGenerating = stateManager.phase.value != GenerationPhase.IDLE

            if (interruptedDuringEvaluation && !isGenerating) {
                // We interrupted playback, and we're not generating a new response → resume
                Log.d(TAG, "[NOISE] Attempting to resume interrupted TTS (not generating)")
                val lastAssistant = stateManager.conversation.value.lastOrNull { it.isAssistant }
                if (lastAssistant != null && lastAssistant.sentences.isNotEmpty()) {
                    if (!tts.isSpeaking.value) {
                        val fullText = lastAssistant.sentences.joinToString(" ")
                        Log.i(TAG, "[NOISE] ✅ Resuming interrupted TTS (len=${fullText.length})")
                        tts.queue(fullText)

                        // ✅ Re-enable auto-listen flag
                        onNoiseConfirmed()
                        Log.d(TAG, "[NOISE] Re-enabled auto-listen flag (resuming TTS)")
                    } else {
                        Log.d(TAG, "[NOISE] TTS already speaking, not resuming")
                    }
                } else {
                    Log.d(TAG, "[NOISE] No assistant response found to resume")
                }
            } else if (isGenerating) {
                // We're generating a new response → don't resume old one
                Log.i(TAG, "[NOISE] ⏭️ SKIP RESUME - currently generating new response (phase=${stateManager.phase.value})")

                // ✅ FIX: Still re-enable auto-listen flag even though we're skipping resume!
                // The noise was just noise, not a real interruption, so the turn should still complete normally
                if (interruptedDuringEvaluation) {
                    onNoiseConfirmed()
                    Log.d(TAG, "[NOISE] Re-enabled auto-listen flag (skipped resume, but turn will complete normally)")
                }
            } else {
                Log.d(TAG, "[NOISE] No interruption flag set or not in resumable state")
            }

            interruptedDuringEvaluation = false
        }

        val stt = getStt()
        if (stt is GeminiLiveSttController) {
            stt.switchMicMode(com.example.advancedvoice.core.audio.MicrophoneSession.Mode.MONITORING)
        }
    }

    fun flushBufferedAssistantIfAny() {
        val held = assistantHoldBuffer ?: run {
            Log.d(TAG, "[FLUSH] No buffered response")
            return
        }
        val heldSources = assistantSourcesBuffer ?: emptyList()  // ✅ ADD THIS

        assistantHoldBuffer = null
        assistantSourcesBuffer = null  // ✅ ADD THIS

        Log.i(TAG, "[FLUSH] ✅ Flushing buffered response (len=${held.length}, sources=${heldSources.size}) → adding to conversation + TTS")

        if (heldSources.isNotEmpty()) {
            com.example.advancedvoice.domain.util.GroundingUtils.processAndDisplaySources(heldSources) { html ->
                stateManager.addSystem(html)
            }
        }

        stateManager.addAssistant(listOf(held))
        tts.queue(held)
        interruptedDuringEvaluation = false
    }

    fun dropBufferedAssistant() {
        if (assistantHoldBuffer != null || assistantSourcesBuffer != null) {  // ✅ UPDATE THIS
            Log.i(TAG, "[DROP] ❌ Dropping buffered response (len=${assistantHoldBuffer?.length}, sources=${assistantSourcesBuffer?.size})")  // ✅ UPDATE THIS
            assistantHoldBuffer = null
            assistantSourcesBuffer = null  // ✅ ADD THIS

            while (stateManager.conversation.value.lastOrNull()?.speaker == "System") {
                stateManager.removeLastEntry()
            }

            val historySnapshot = engine.getHistorySnapshot()
            if (historySnapshot.lastOrNull()?.role == "assistant") {
                engine.clearHistory()
                engine.seedHistory(historySnapshot.dropLast(1))
                Log.d(TAG, "[DROP] Removed assistant from engine history")
            }
        } else {
            Log.d(TAG, "[DROP] No buffered response to drop")
        }
    }

    private fun startAccumulationWatcher() {
        interruptAccumulationJob?.cancel()
        interruptAccumulationJob = scope.launch {
            Log.i(TAG, "[ACCUMULATION] Starting watcher (max ${INTERRUPT_ACCUMULATION_TIMEOUT}ms)")
            val startTime = System.currentTimeMillis()
            var lastCheck = startTime
            var consecutiveNotBusyCount = 0

            while (isAccumulatingAfterInterrupt) {
                delay(500L)
                val elapsed = System.currentTimeMillis() - startTime
                val listening = stateManager.isListening.value
                val hearing = stateManager.isHearingSpeech.value
                val transcribing = stateManager.isTranscribing.value
                val isBusy = listening || transcribing

                if (System.currentTimeMillis() - lastCheck >= 2000L) {
                    Log.d(TAG, "[ACCUMULATION] elapsed=${elapsed}ms, listening=$listening, hearing=$hearing, transcribing=$transcribing, busy=$isBusy")
                    lastCheck = System.currentTimeMillis()
                }

                if (!isBusy) {
                    consecutiveNotBusyCount++
                    if (consecutiveNotBusyCount >= 3) {
                        Log.i(TAG, "[ACCUMULATION] User stopped (${elapsed}ms), finalizing")
                        finalizeAccumulation()
                        break
                    }
                } else {
                    consecutiveNotBusyCount = 0
                }

                if (elapsed > INTERRUPT_ACCUMULATION_TIMEOUT) {
                    Log.w(TAG, "[ACCUMULATION] Timeout (${elapsed}ms), force finalizing")
                    scope.launch {
                        try {
                            Log.w(TAG, "[ACCUMULATION] Force stopping STT...")
                            getStt()?.stop(false)
                            delay(200L)
                            stateManager.setListening(false)
                            stateManager.setHearingSpeech(false)
                            stateManager.setTranscribing(false)
                        } catch (e: Exception) {
                            Log.e(TAG, "[ACCUMULATION] Error force-stopping STT: ${e.message}")
                        }
                    }
                    finalizeAccumulation()
                    break
                }
            }
        }
    }

    private fun finalizeAccumulation() {
        if (!isAccumulatingAfterInterrupt) {
            Log.d(TAG, "[ACCUMULATION] Already finalized")
            return
        }

        val lastUserEntry = stateManager.conversation.value.lastOrNull { it.speaker == "You" }
        val accumulatedText = lastUserEntry?.sentences?.joinToString(" ")?.trim() ?: ""
        Log.i(TAG, "[ACCUMULATION] Finalizing (len=${accumulatedText.length}): '${accumulatedText.take(100)}...'")

        val now = System.currentTimeMillis()
        val timeSinceLastRestart = now - lastRestartTime

        if (timeSinceLastRestart < MIN_RESTART_INTERVAL_MS) {
            Log.w(TAG, "[ACCUMULATION] Too soon after last restart, delaying...")
            scope.launch {
                delay(MIN_RESTART_INTERVAL_MS - timeSinceLastRestart)
                finalizeAccumulation()
            }
            return
        }

        isAccumulatingAfterInterrupt = false
        isBargeInTurn = false
        interruptAccumulationJob?.cancel()

        scope.launch {
            delay(200L)
            stateManager.setListening(false)
            stateManager.setHearingSpeech(false)
            stateManager.setTranscribing(false)

            val stt = getStt()
            if (stt is GeminiLiveSttController) {
                Log.i(TAG, "[ACCUMULATION] Switching to MONITORING for next turn")
                stt.switchMicMode(com.example.advancedvoice.core.audio.MicrophoneSession.Mode.MONITORING)
            }
        }

        if (accumulatedText.isNotEmpty()) {
            Log.i(TAG, "[ACCUMULATION] Restarting turn with accumulated text")
            lastRestartTime = now
            engine.replaceLastUserMessage(accumulatedText)
            startTurnWithExistingHistory()
        } else {
            Log.w(TAG, "[ACCUMULATION] No text accumulated, cleaning up")
            stateManager.removeLastUserPlaceholderIfEmpty()
        }
    }

    fun combineTranscript(newTranscript: String): String {
        val lastUserEntry = stateManager.conversation.value.lastOrNull { it.speaker == "You" }
        val previousInput = lastUserEntry?.sentences?.joinToString(" ")?.trim() ?: ""
        return if (previousInput.isNotBlank()) {
            if (previousInput.endsWith(" ")) "$previousInput$newTranscript"
            else "$previousInput $newTranscript"
        } else {
            newTranscript
        }
    }

    fun onTurnStart(timestamp: Long) {
        generationStartTime = timestamp
        Log.d(TAG, "[TURN] Generation started at $timestamp")
    }

    fun reset() {
        Log.d(TAG, "[RESET] Resetting all interruption state")
        interruptAccumulationJob?.cancel()
        isEvaluatingBargeIn = false
        isBargeInTurn = false
        isAccumulatingAfterInterrupt = false
        assistantHoldBuffer = null
        assistantSourcesBuffer = null
        interruptedDuringEvaluation = false
        scope.launch {
            stateManager.setListening(false)
            stateManager.setHearingSpeech(false)
            stateManager.setTranscribing(false)
        }
    }
}