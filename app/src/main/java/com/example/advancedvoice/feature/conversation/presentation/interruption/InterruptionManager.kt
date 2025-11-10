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
    private val onInterruption: () -> Unit
) {
    private companion object {
        const val TAG = LoggerConfig.TAG_VM
        const val INTERRUPT_ACCUMULATION_TIMEOUT = 30000L
        const val MIN_RESTART_INTERVAL_MS = 2000L
        // Heuristic: at least one word-like token (3+ letters) = real speech
        val WORD_REGEX = Regex("[\\p{L}]{3,}")
    }

    @Volatile var isBargeInTurn = false
        private set
    @Volatile var isAccumulatingAfterInterrupt = false
        private set
    @Volatile var generationStartTime = 0L
        private set

    // Evaluation/hold state
    @Volatile private var isEvaluatingBargeIn = false
    @Volatile private var assistantHoldBuffer: String? = null

    @Volatile private var lastRestartTime = 0L
    private var interruptAccumulationJob: Job? = null

    fun initialize() {
        Log.i(TAG, "═══════════════════════════════════")
        Log.i(TAG, "InterruptionManager INITIALIZED")
        Log.i(TAG, "Strategy: transcript-based interruption (no hard-coded delays)")
        Log.i(TAG, "Word heuristic: ${WORD_REGEX.pattern}")
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
                    Log.d(TAG, "[INTERRUPT-MONITOR] Speech: gen=$generating, tts=$speaking, phase=${stateManager.phase.value}, eval=$isEvaluatingBargeIn, accum=$isAccumulatingAfterInterrupt, genTime=${genDuration}ms")
                }

                // NEW: When speech is detected during generation/TTS, stop TTS and start evaluating
                // in parallel. Let the LLM continue generating - we'll decide what to do with its
                // response once we know if this was real speech or just noise.
                if ((generating || speaking) && hearingSpeech && !isAccumulatingAfterInterrupt) {
                    // If we were already evaluating a previous sound that turned out to be nothing,
                    // reset and start a fresh evaluation for this new speech
                    if (isEvaluatingBargeIn) {
                        Log.w(TAG, "[VOICE-INTERRUPT] ⚠️ New speech detected while still evaluating previous sound")
                        Log.w(TAG, "[VOICE-INTERRUPT] Resetting evaluation for new speech event")
                        isEvaluatingBargeIn = false
                    }

                    if (!isEvaluatingBargeIn) {
                        Log.i(TAG, "[VOICE-INTERRUPT] 🔊 Potential interruption detected - stopping TTS, evaluating in parallel (LLM continues)")

                        // Mark that we're evaluating a potential barge-in
                        isEvaluatingBargeIn = true

                        // Stop TTS immediately so we don't talk over the user
                        // But DO NOT abort the engine - let it finish generating in the background
                        tts.stop()
                        onInterruption() // Disables auto-listen flag

                        // Start transcribing to evaluate if this is real speech or noise
                        val stt = getStt()
                        if (stt is GeminiLiveSttController) {
                            scope.launch {
                                try {
                                    if (!stateManager.isListening.value && !stateManager.isTranscribing.value) {
                                        stt.start(isAutoListen = false)
                                    } else {
                                        stt.switchMicMode(com.example.advancedvoice.core.audio.MicrophoneSession.Mode.TRANSCRIBING)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "[VOICE-INTERRUPT] Error starting transcription: ${e.message}")
                                }
                            }
                        } else {
                            Log.w(TAG, "[VOICE-INTERRUPT] STT not available for evaluation")
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the LLM produces a final response.
     * Decides whether to buffer it (if we're still evaluating an interruption)
     * or allow it through (if the interruption was just noise that already finished).
     * Returns true if the response was buffered (caller should not play it).
     */
    fun maybeHandleAssistantFinalResponse(text: String): Boolean {
        // If we're already accumulating after a confirmed interruption, discard this late response
        if (isAccumulatingAfterInterrupt) {
            Log.i(TAG, "[HOLD] ❌ Already accumulating after interrupt - discarding late LLM response (len=${text.length})")
            return true
        }

        // If we're evaluating a potential barge-in
        if (isEvaluatingBargeIn) {
            val stillRecording = stateManager.isListening.value || stateManager.isTranscribing.value

            if (stillRecording) {
                // Mic is still active - buffer the response until transcript arrives to determine if noise or speech
                assistantHoldBuffer = text
                Log.i(TAG, "[HOLD] 📦 Buffering LLM response (len=${text.length}) - mic still active, awaiting transcript verdict")
                return true
            } else {
                // Mic already stopped - likely just brief noise that ended quickly
                // Allow the response through and clear the evaluation flag
                Log.i(TAG, "[HOLD] ✅ Mic inactive when response arrived - treating as noise, allowing response to play")
                isEvaluatingBargeIn = false
                return false
            }
        }

        // Not evaluating or accumulating - normal flow
        return false
    }

    /**
     * Called when a final transcript arrives during barge-in evaluation.
     * Decides whether this was real speech (discard buffered LLM response and accumulate)
     * or just noise (flush buffered LLM response and resume).
     */
    fun checkTranscriptForInterruption(transcript: String): Boolean {
        val trimmed = transcript.trim()

        if (!isEvaluatingBargeIn) {
            // Not a barge-in evaluation transcript; let normal flow handle it
            return false
        }

        val hasWord = WORD_REGEX.containsMatchIn(trimmed)

        // If the transcript has words but the LLM response hasn't arrived yet,
        // we have a race condition. Wait briefly for the response.
        if (hasWord && assistantHoldBuffer == null && isGenerating(stateManager.phase.value)) {
            Log.w(TAG, "[VOICE-INTERRUPT] ⚠️ Race condition detected: transcript arrived before LLM response")
            Log.w(TAG, "[VOICE-INTERRUPT] Waiting 500ms for LLM response to arrive...")

            scope.launch {
                delay(500)
                // Re-check after delay
                val stillGenerating = isGenerating(stateManager.phase.value)
                val nowHasBuffer = assistantHoldBuffer != null

                Log.d(TAG, "[VOICE-INTERRUPT] After wait: generating=$stillGenerating, buffered=$nowHasBuffer")

                // Now proceed with the interruption handling
                handleConfirmedSpeechInterruption(trimmed)
            }
            return true
        }

        // Evaluation is complete - clear the flag
        isEvaluatingBargeIn = false

        val hadBufferedResponse = assistantHoldBuffer != null
        Log.d(TAG, "[VOICE-INTERRUPT] 📋 Transcript verdict: len=${trimmed.length}, hasWord=$hasWord, buffered=$hadBufferedResponse")

        if (hasWord) {
            handleConfirmedSpeechInterruption(trimmed)
            return true
        } else {
            handleConfirmedNoise()
            return true
        }
    }

    private fun handleConfirmedSpeechInterruption(trimmed: String) {
        // Clear the evaluation flag now
        isEvaluatingBargeIn = false

        Log.i(TAG, "[VOICE-INTERRUPT] ✅ REAL SPEECH CONFIRMED")
        Log.i(TAG, "[VOICE-INTERRUPT]    → Discarding buffered LLM response (triggers nothing)")
        Log.i(TAG, "[VOICE-INTERRUPT]    → Starting accumulation for user's request")

        // Discard any buffered LLM response
        dropBufferedAssistant()

        // Abort engine if it's still generating
        engine.abort(true)

        // Enter accumulation mode
        isBargeInTurn = false
        isAccumulatingAfterInterrupt = true

        scope.launch {
            val combined = combineTranscript(trimmed)
            Log.i(TAG, "[VOICE-INTERRUPT] Combined user input: '${combined.take(120)}...'")
            stateManager.replaceLastUser(combined)
            startAccumulationWatcher()
        }
    }

    private fun handleConfirmedNoise() {
        // Clear the evaluation flag
        isEvaluatingBargeIn = false

        Log.i(TAG, "[VOICE-INTERRUPT] ❌ NOISE CONFIRMED")
        Log.i(TAG, "[VOICE-INTERRUPT]    → Flushing buffered LLM response (if any)")
        Log.i(TAG, "[VOICE-INTERRUPT]    → Resuming normal operation")

        flushBufferedAssistantIfAny()

        val stt = getStt()
        if (stt is GeminiLiveSttController) {
            stt.switchMicMode(com.example.advancedvoice.core.audio.MicrophoneSession.Mode.MONITORING)
        }
    }

    fun flushBufferedAssistantIfAny() {
        val held = assistantHoldBuffer ?: run {
            Log.d(TAG, "[HOLD] No buffered response to flush")
            return
        }
        assistantHoldBuffer = null
        Log.i(TAG, "[HOLD] ✅ Flushing buffered LLM response (len=${held.length}) - adding to conversation and triggering TTS")
        // Commit to conversation and speak now (triggers all normal effects)
        stateManager.addAssistant(listOf(held))
        tts.queue(held)
    }

    fun dropBufferedAssistant() {
        if (assistantHoldBuffer != null) {
            Log.i(TAG, "[HOLD] ❌ Dropping buffered LLM response (len=${assistantHoldBuffer?.length}) - triggers NOTHING")
            assistantHoldBuffer = null

            // Remove System entries (grounding links) from conversation UI
            while (stateManager.conversation.value.lastOrNull()?.speaker == "System") {
                stateManager.removeLastEntry()
            }

            // Remove the assistant response from engine's history to prevent corruption
            val historySnapshot = engine.getHistorySnapshot()
            if (historySnapshot.lastOrNull()?.role == "assistant") {
                engine.clearHistory()
                engine.seedHistory(historySnapshot.dropLast(1))
                Log.d(TAG, "[HOLD] Removed assistant entry from engine history")
            }
        } else {
            Log.d(TAG, "[HOLD] No buffered response to drop")
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
            // Let UI catch up, then clear flags and ensure monitoring
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
        Log.d(TAG, "[INTERRUPT] Generation started at $timestamp")
    }

    fun reset() {
        Log.d(TAG, "[Interruption] Resetting state")
        interruptAccumulationJob?.cancel()
        isEvaluatingBargeIn = false
        isBargeInTurn = false
        isAccumulatingAfterInterrupt = false
        assistantHoldBuffer = null
        scope.launch {
            stateManager.setListening(false)
            stateManager.setHearingSpeech(false)
            stateManager.setTranscribing(false)
        }
    }
}