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
        const val MIN_GENERATION_TIME_BEFORE_INTERRUPT_MS = 2000L
    }

    @Volatile
    var isBargeInTurn = false
        private set

    @Volatile
    var isAccumulatingAfterInterrupt = false
        private set

    @Volatile
    var generationStartTime = 0L
        private set

    @Volatile
    private var lastRestartTime = 0L
    private var interruptAccumulationJob: Job? = null

    fun initialize() {
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
                stateManager.isHearingSpeech
            ) { currentPhase, hearingSpeech ->
                isGenerating(currentPhase) to hearingSpeech
            }.collect { (generating, hearingSpeech) ->
                if (generating && hearingSpeech && !isAccumulatingAfterInterrupt) {
                    val generationDuration = System.currentTimeMillis() - generationStartTime
                    if (generationDuration < MIN_GENERATION_TIME_BEFORE_INTERRUPT_MS) {
                        Log.d(TAG, "[VOICE-INTERRUPT] Ignoring early voice (${generationDuration}ms)")
                        return@collect
                    }

                    Log.i(TAG, "[VOICE-ACCUMULATION] User speaking - entering accumulation mode")
                    isBargeInTurn = false
                    isAccumulatingAfterInterrupt = true
                    onInterruption()

                    scope.launch {
                        engine.abort(true)
                        tts.stop()

                        val stt = getStt()
                        if (stt is GeminiLiveSttController) {
                            Log.i(TAG, "[VOICE-INTERRUPT] Switching GeminiLive to TRANSCRIBING mode")
                            stt.start(isAutoListen = false)
                        }

                        startAccumulationWatcher()
                    }
                }
            }
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

                // ✅ FIX: Only count listening/transcribing as busy, not just hearing (noise)
                // This prevents MONITORING mode noise from blocking finalization
                val isBusy = listening || transcribing

                if (System.currentTimeMillis() - lastCheck >= 2000L) {
                    Log.d(
                        TAG,
                        "[ACCUMULATION] elapsed=${elapsed}ms, listening=$listening, hearing=$hearing, transcribing=$transcribing, busy=$isBusy"
                    )
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
            Log.w(TAG, "[ACCUMULATION] Too soon, delaying...")
            scope.launch {
                delay(MIN_RESTART_INTERVAL_MS - timeSinceLastRestart)
                finalizeAccumulation()
            }
            return
        }

        isAccumulatingAfterInterrupt = false
        isBargeInTurn = false
        interruptAccumulationJob?.cancel()

        // ✅ FIX: Don't call stop() which switches to IDLE. Just clear the state flags.
        // The mic should stay in MONITORING mode to allow interruptions during next generation.
        scope.launch {
            delay(200L)
            stateManager.setListening(false)
            stateManager.setHearingSpeech(false)
            stateManager.setTranscribing(false)

            // ✅ Explicitly switch to MONITORING (not IDLE) so interruptions work during next LLM generation
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
    }

    fun reset() {
        interruptAccumulationJob?.cancel()
        isBargeInTurn = false
        isAccumulatingAfterInterrupt = false
        scope.launch {
            stateManager.setListening(false)
            stateManager.setHearingSpeech(false)
            stateManager.setTranscribing(false)
        }
    }
}