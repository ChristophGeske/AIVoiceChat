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

                // NEW: Immediately abort LLM/TTS and start accumulating when we hear speech during generation or TTS.
                if ((generating || speaking) && hearingSpeech && !isAccumulatingAfterInterrupt) {
                    Log.i(TAG, "[VOICE-INTERRUPT] ✅ Speech while generating/TTS → abort output, accumulate, and restart after pause")

                    // Stop current output immediately
                    engine.abort(true)
                    tts.stop()
                    onInterruption()

                    // Enter accumulation mode now
                    isEvaluatingBargeIn = true
                    isAccumulatingAfterInterrupt = true

                    // Ensure STT is actively transcribing to capture the rest of the utterance
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
                                Log.e(TAG, "[VOICE-INTERRUPT] Error ensuring STT is transcribing: ${e.message}")
                            }
                        }
                    } else {
                        Log.w(TAG, "[VOICE-INTERRUPT] STT not available")
                    }

                    // Watch for end of user speech and then restart LLM with combined text
                    startAccumulationWatcher()
                }
            }
        }
    }

    /**
     * Gate assistant output while evaluating barge-in.
     * Call this from your engine callbacks before committing assistant final to conversation/TTS.
     * Returns true if this response was buffered (do not output yet).
     */
    fun maybeHandleAssistantFinalResponse(text: String): Boolean {
        if (isEvaluatingBargeIn || isAccumulatingAfterInterrupt) {
            assistantHoldBuffer = text
            Log.i(TAG, "[HOLD] Assistant final buffered (len=${text.length}) until barge-in evaluation resolves")
            return true
        }
        return false
    }

    /**
     * Called by FlowController when a final transcript arrives.
     * Returns true if this transcript was handled here (don’t process it as a normal turn).
     */
    fun checkTranscriptForInterruption(transcript: String): Boolean {
        val trimmed = transcript.trim()

        if (!isEvaluatingBargeIn) {
            // Not a barge-in evaluation transcript; let normal flow handle it.
            return false
        }

        // End the evaluation scope for this candidate.
        isEvaluatingBargeIn = false

        val nowGenerating = isGenerating(stateManager.phase.value)
        val nowSpeaking = stateManager.isSpeaking.value
        val hasWord = WORD_REGEX.containsMatchIn(trimmed)

        Log.d(TAG, "[VOICE-INTERRUPT] Transcript check: len=${trimmed.length}, hasWord=$hasWord, phase=${stateManager.phase.value}, speaking=$nowSpeaking")

        if (!hasWord) {
            Log.d(TAG, "[VOICE-INTERRUPT] ❌ NOISE: no word-like token - flushing any held assistant output")
            flushBufferedAssistantIfAny()
            return true // swallow transcript
        }

        // Real speech detected during generation/TTS -> commit interruption
        if (nowGenerating || nowSpeaking) {
            Log.i(TAG, "[VOICE-INTERRUPT] ✅ REAL SPEECH during generation/TTS - entering ACCUMULATION mode and DROPPING held assistant output")
            dropBufferedAssistant()

            isBargeInTurn = false
            isAccumulatingAfterInterrupt = true
            onInterruption()

            scope.launch {
                // Stop current output immediately
                engine.abort(true)
                tts.stop()

                // Combine with previous user input immediately
                val combined = combineTranscript(trimmed)
                Log.i(TAG, "[VOICE-INTERRUPT] Combined (initial): '${combined.take(120)}'")
                stateManager.replaceLastUser(combined)

                // Watch for more speech
                startAccumulationWatcher()
            }

            return true
        }

        // Generation already finished; allow normal processing as a new turn, and flush held LLM output if any.
        Log.d(TAG, "[VOICE-INTERRUPT] Generation finished - treating as normal input and flushing held assistant (if any)")
        flushBufferedAssistantIfAny()
        return false
    }

    fun flushBufferedAssistantIfAny() {
        val held = assistantHoldBuffer ?: return
        assistantHoldBuffer = null
        Log.i(TAG, "[HOLD] Flushing held assistant final (len=${held.length})")
        // Commit to conversation and speak now
        stateManager.addAssistant(listOf(held))
        tts.queue(held)
    }

    fun dropBufferedAssistant() {
        if (assistantHoldBuffer != null) {
            Log.i(TAG, "[HOLD] Dropping held assistant final (interruption committed)")
        }
        assistantHoldBuffer = null
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