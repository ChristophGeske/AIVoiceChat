package com.example.advancedvoice.feature.conversation.presentation.interruption

import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.SttController
import com.example.advancedvoice.feature.conversation.service.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Manages voice interruption, barge-in, and accumulation logic.
 */
class InterruptionManager(
    private val scope: CoroutineScope,
    private val stateManager: ConversationStateManager,
    private val engine: SentenceTurnEngine,
    private val tts: TtsController,
    private val getStt: () -> SttController?,
    private val startTurnWithExistingHistory: () -> Unit
) {
    private companion object {
        const val TAG = LoggerConfig.TAG_VM
        const val INTERRUPT_ACCUMULATION_TIMEOUT = 10000L
        const val MIN_RESTART_INTERVAL_MS = 2000L
    }

    @Volatile var isBargeInTurn = false
        private set

    @Volatile var isAccumulatingAfterInterrupt = false
        private set

    @Volatile var generationStartTime = 0L
        private set

    @Volatile private var lastRestartTime = 0L

    private var interruptAccumulationJob: Job? = null

    /**
     * Initialize voice interruption monitoring.
     */
    fun initialize() {
        monitorVoiceInterrupts()
        monitorBackgroundListenerCleanup()
    }

    /**
     * Check if currently in a generation phase.
     */
    private fun isGenerating(phase: GenerationPhase): Boolean {
        return phase == GenerationPhase.GENERATING_FIRST ||
                phase == GenerationPhase.GENERATING_REMAINDER ||  // ✅ ADDED
                phase == GenerationPhase.SINGLE_SHOT_GENERATING
    }

    /**
     * Monitor for voice interruptions during generation.
     */
    private fun monitorVoiceInterrupts() {
        scope.launch {
            combine(
                stateManager.phase,
                stateManager.isHearingSpeech,
                stateManager.isSpeaking  // ✅ ADD TTS state
            ) { currentPhase, hearingSpeech, speaking ->
                Triple(currentPhase, hearingSpeech, speaking)
            }.collect { (currentPhase, hearingSpeech, speaking) ->
                val generating = isGenerating(currentPhase)

                // ✅ FIXED: Don't start listener if TTS is speaking (prevents self-hearing)
                if (generating && !stateManager.isListening.value && !speaking && !isAccumulatingAfterInterrupt) {
                    Log.i(TAG, "[VOICE-INTERRUPT] Generation started, enabling background voice detection...")
                    scope.launch {
                        try {
                            getStt()?.start(isAutoListen = true)
                        } catch (se: SecurityException) {
                            Log.w(TAG, "[VOICE-INTERRUPT] Mic permission denied")
                        }
                    }
                }

                // ✅ FIXED: Stop listener if TTS starts (prevents self-hearing during playback)
                if (speaking && stateManager.isListening.value && !isAccumulatingAfterInterrupt) {
                    Log.i(TAG, "[VOICE-INTERRUPT] TTS started, stopping background listener to prevent self-hearing")
                    scope.launch {
                        getStt()?.stop(false)
                    }
                }

                // Handle speech detection during generation
                if (generating && hearingSpeech && !isBargeInTurn && !isAccumulatingAfterInterrupt) {
                    val generationDuration = System.currentTimeMillis() - generationStartTime

                    when {
                        // Very early speech (< 500ms) = User continuation (VAD cut them off)
                        generationDuration < 500L -> {
                            Log.i(TAG, "[VOICE-CONTINUATION] Speech at ${generationDuration}ms - treating as continuation of user input")
                            handleInterruption(isContinuation = true)
                        }

                        // Early speech (500-1500ms) = Likely false alarm, ignore
                        generationDuration < 1500L -> {
                            Log.d(TAG, "[VOICE-INTERRUPT] Speech detected at ${generationDuration}ms - ignoring as potential false alarm")
                        }

                        // Later speech (> 1500ms) = Real interruption
                        else -> {
                            Log.i(TAG, "[VOICE-INTERRUPT] User interrupting (after ${generationDuration}ms)! Aborting...")
                            handleInterruption(isContinuation = false)
                        }
                    }
                }
            }
        }
    }

    /**
     * Monitor for cleaning up background listener after generation.
     */
    private fun monitorBackgroundListenerCleanup() {
        scope.launch {
            stateManager.phase.collect { currentPhase ->
                if (currentPhase == GenerationPhase.IDLE &&
                    (stateManager.isListening.value || stateManager.isHearingSpeech.value) &&
                    !isBargeInTurn &&
                    !isAccumulatingAfterInterrupt) {

                    delay(300L)

                    if (stateManager.phase.value == GenerationPhase.IDLE &&
                        !isBargeInTurn &&
                        !isAccumulatingAfterInterrupt &&
                        (stateManager.isListening.value || stateManager.isHearingSpeech.value)) {

                        Log.d(TAG, "[VOICE-INTERRUPT] Generation ended, stopping background listener")
                        scope.launch {
                            getStt()?.stop(false)
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle interruption or continuation.
     */
    private fun handleInterruption(isContinuation: Boolean) {
        isBargeInTurn = !isContinuation
        isAccumulatingAfterInterrupt = true

        scope.launch {
            engine.abort(true)
            tts.stop()
        }

        startAccumulationWatcher()
    }

    /**
     * Start smart accumulation watcher - only finalizes when user truly stopped.
     */
    private fun startAccumulationWatcher() {
        interruptAccumulationJob?.cancel()
        interruptAccumulationJob = scope.launch {
            Log.i(TAG, "[ACCUMULATION] Starting smart watcher (max ${INTERRUPT_ACCUMULATION_TIMEOUT}ms)")

            val startTime = System.currentTimeMillis()
            var lastCheck = startTime

            while (isAccumulatingAfterInterrupt) {
                delay(500L)

                val elapsed = System.currentTimeMillis() - startTime
                val isBusy = stateManager.isListening.value ||
                        stateManager.isHearingSpeech.value ||
                        stateManager.isTranscribing.value

                if (System.currentTimeMillis() - lastCheck >= 2000L) {
                    Log.d(TAG, "[ACCUMULATION] Watcher: elapsed=${elapsed}ms, listening=${stateManager.isListening.value}, hearing=${stateManager.isHearingSpeech.value}, transcribing=${stateManager.isTranscribing.value}")
                    lastCheck = System.currentTimeMillis()
                }

                if (!isBusy) {
                    delay(500L)

                    if (!stateManager.isListening.value &&
                        !stateManager.isHearingSpeech.value &&
                        !stateManager.isTranscribing.value &&
                        isAccumulatingAfterInterrupt) {

                        Log.i(TAG, "[ACCUMULATION] User stopped (after ${elapsed}ms), finalizing input")
                        finalizeAccumulation()
                        break
                    }
                }

                if (elapsed > INTERRUPT_ACCUMULATION_TIMEOUT) {
                    Log.w(TAG, "[ACCUMULATION] Timeout reached (${elapsed}ms), force finalizing")
                    finalizeAccumulation()
                    break
                }
            }
        }
    }

    /**
     * Finalize accumulated input and restart generation.
     */
    private fun finalizeAccumulation() {
        if (!isAccumulatingAfterInterrupt) {
            Log.d(TAG, "[ACCUMULATION] Already finalized, ignoring")
            return
        }

        val lastUserEntry = stateManager.conversation.value.lastOrNull { it.speaker == "You" }
        val accumulatedText = lastUserEntry?.sentences?.joinToString(" ")?.trim() ?: ""

        Log.i(TAG, "[ACCUMULATION] Finalizing (len=${accumulatedText.length}): '${accumulatedText.take(100)}...'")

        // ✅ NEW: Rate limiting to prevent restart loops
        val now = System.currentTimeMillis()
        val timeSinceLastRestart = now - lastRestartTime

        if (timeSinceLastRestart < MIN_RESTART_INTERVAL_MS) {
            Log.w(TAG, "[ACCUMULATION] Restart too soon (${timeSinceLastRestart}ms), delaying...")
            scope.launch {
                delay(MIN_RESTART_INTERVAL_MS - timeSinceLastRestart)
                finalizeAccumulation()
            }
            return
        }

        isAccumulatingAfterInterrupt = false
        isBargeInTurn = false
        interruptAccumulationJob?.cancel()

        if (accumulatedText.isNotEmpty()) {
            Log.i(TAG, "[ACCUMULATION] Restarting turn with accumulated text")
            lastRestartTime = now  // ✅ NEW: Update restart time
            engine.replaceLastUserMessage(accumulatedText)
            startTurnWithExistingHistory()
        } else {
            Log.w(TAG, "[ACCUMULATION] No text accumulated, cleaning up")
            stateManager.removeLastUserPlaceholderIfEmpty()
            scope.launch {
                getStt()?.stop(false)
            }
        }
    }


    /**
     * Combine transcript with accumulated input.
     */
    fun combineTranscript(newTranscript: String): String {
        val lastUserEntry = stateManager.conversation.value.lastOrNull { it.speaker == "You" }
        val previousInput = lastUserEntry?.sentences?.joinToString(" ")?.trim() ?: ""

        return if (previousInput.isNotBlank()) {
            if (previousInput.endsWith(" ")) {
                "$previousInput$newTranscript"
            } else {
                "$previousInput $newTranscript"
            }
        } else {
            newTranscript
        }
    }

    /**
     * Reset for new turn start.
     */
    fun onTurnStart(timestamp: Long) {
        generationStartTime = timestamp
    }

    /**
     * Reset all state.
     */
    fun reset() {
        interruptAccumulationJob?.cancel()
        isBargeInTurn = false
        isAccumulatingAfterInterrupt = false
    }
}