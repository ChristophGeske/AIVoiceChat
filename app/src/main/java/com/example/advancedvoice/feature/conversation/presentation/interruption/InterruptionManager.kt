package com.example.advancedvoice.feature.conversation.presentation.interruption

import android.util.Log
import com.example.advancedvoice.core.audio.VadRecorder
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
import java.lang.Exception

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
    private var backgroundRecorder: VadRecorder? = null

    fun initialize() {
        monitorVoiceInterrupts()
        monitorBackgroundListenerCleanup()
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
                Triple(currentPhase, hearingSpeech, speaking)
            }.collect { (currentPhase, hearingSpeech, speaking) ->
                val generating = isGenerating(currentPhase)

                if (speaking && backgroundRecorder != null && !isAccumulatingAfterInterrupt) {
                    Log.i(TAG, "[VOICE-INTERRUPT] TTS started, stopping background listener IMMEDIATELY")
                    disableBackgroundListener()
                }

                if (generating && backgroundRecorder == null && !speaking && !isAccumulatingAfterInterrupt) {
                    Log.i(TAG, "[VOICE-INTERRUPT] Generation started, scheduling background VAD...")
                    scope.launch {
                        delay(800L)
                        if (isGenerating(stateManager.phase.value) &&
                            !stateManager.isSpeaking.value &&
                            backgroundRecorder == null &&
                            !isAccumulatingAfterInterrupt
                        ) {
                            Log.i(TAG, "[VOICE-INTERRUPT] Enabling background voice detection (after delay)")
                            enableBackgroundListener()
                        } else {
                            Log.d(
                                TAG,
                                "[VOICE-INTERRUPT] Cancelled VAD enable (TTS started or conditions changed)"
                            )
                        }
                    }
                }

                // ✅ FIX 2: Add a crucial guard clause.
                // The entire voice accumulation logic is ONLY supported by GeminiLiveSttController.
                // This check prevents the crash when using Standard STT.
                if (generating && hearingSpeech && getStt() is GeminiLiveSttController && !isBargeInTurn && !isAccumulatingAfterInterrupt) {
                    val generationDuration = System.currentTimeMillis() - generationStartTime
                    if (generationDuration < MIN_GENERATION_TIME_BEFORE_INTERRUPT_MS) {
                        Log.d(
                            TAG,
                            "[VOICE-INTERRUPT] Ignoring voice at ${generationDuration}ms (too early, likely noise)"
                        )
                        return@collect
                    }

                    Log.i(TAG, "[VOICE-ACCUMULATION] User speaking at ${generationDuration}ms - entering accumulation mode")
                    isBargeInTurn = false
                    isAccumulatingAfterInterrupt = true
                    onInterruption()
                    scope.launch {
                        engine.abort(true)
                        tts.stop()
                        disableBackgroundListener()
                        Log.i(TAG, "[VOICE-INTERRUPT] Stopping any existing STT session...")
                        try {
                            getStt()?.stop(false)
                            delay(500L)
                        } catch (e: Exception) {
                            Log.e(TAG, "[VOICE-INTERRUPT] Error stopping STT: ${e.message}")
                        }
                        Log.i(TAG, "[VOICE-INTERRUPT] Starting STT with NO grace period (user already speaking)...")
                        try {
                            val stt = getStt()
                            if (stt is GeminiLiveSttController) {
                                stt.startWithCustomGracePeriod(0L)
                            } else {
                                stt?.start(isAutoListen = false)
                            }
                            delay(500L)
                            val sttState = stateManager.isListening.value || stateManager.isHearingSpeech.value
                            if (!sttState && isAccumulatingAfterInterrupt) {
                                Log.e(TAG, "[VOICE-INTERRUPT] STT failed to start! Aborting accumulation.")
                                isAccumulatingAfterInterrupt = false
                                stateManager.removeLastUserPlaceholderIfEmpty()
                                stateManager.addError("Could not start speech recognition")
                                return@launch
                            }
                        } catch (se: SecurityException) {
                            Log.e(TAG, "[VOICE-INTERRUPT] Permission denied on STT start: ${se.message}")
                            isAccumulatingAfterInterrupt = false
                            stateManager.removeLastUserPlaceholderIfEmpty()
                            stateManager.addError("Microphone permission is required to speak.")
                            return@launch
                        } catch (e: Exception) {
                            Log.e(TAG, "[VOICE-INTERRUPT] Error starting STT: ${e.message}")
                            isAccumulatingAfterInterrupt = false
                            stateManager.removeLastUserPlaceholderIfEmpty()
                            stateManager.addError("Could not start speech recognition")
                            return@launch
                        }
                    }
                    startAccumulationWatcher()
                }
            }
        }
    }

    private suspend fun enableBackgroundListener() {
        if (backgroundRecorder != null) {
            Log.d(TAG, "[VOICE-INTERRUPT] Background listener already active")
            return
        }
        Log.i(TAG, "[VOICE-INTERRUPT] Enabling background voice detection (infinite timeout)")

        // ✅ FIX 1: The background VAD must be in multi-utterance mode to properly detect interruptions.
        val recorder = VadRecorder(
            scope = scope,
            sampleRate = 16_000,
            silenceThresholdRms = 300.0,
            endOfSpeechMs = 300L,
            maxSilenceMs = null,
            minSpeechDurationMs = 300L,
            startupGracePeriodMs = 300L,
            allowMultipleUtterances = true // This ensures the VAD keeps listening
        )

        backgroundRecorder = recorder
        scope.launch {
            recorder.events.collect { event ->
                when (event) {
                    is VadRecorder.VadEvent.SpeechStart -> {
                        val elapsed = System.currentTimeMillis() - generationStartTime
                        Log.i(TAG, "[VOICE-INTERRUPT] Voice detected at ${elapsed}ms")
                        stateManager.setHearingSpeech(true)
                    }
                    is VadRecorder.VadEvent.SpeechEnd -> {
                        Log.i(TAG, "[VOICE-INTERRUPT] Voice ended (${event.durationMs}ms)")
                        // In this mode, we let the user manually clear the 'hearing' state by stopping speech
                        // Or by the VAD eventually timing out if we added a max duration.
                        // For barge-in, we want it to stay 'hearing' until the user stops for a bit.
                        // For simplicity, we can just set it to false here.
                        stateManager.setHearingSpeech(false)
                    }
                    is VadRecorder.VadEvent.SilenceTimeout -> {
                        Log.d(TAG, "[VOICE-INTERRUPT] Unexpected silence timeout")
                        disableBackgroundListener()
                    }
                    is VadRecorder.VadEvent.Error -> {
                        Log.e(TAG, "[VOICE-INTERRUPT] VAD error: ${event.message}")
                        disableBackgroundListener()
                    }
                }
            }
        }
        try {
            recorder.start()
        } catch (se: SecurityException) {
            Log.w(TAG, "[VOICE-INTERRUPT] Permission denied for background VAD: ${se.message}")
            backgroundRecorder = null
        }
    }

    private fun disableBackgroundListener() {
        val recorder = backgroundRecorder ?: return
        backgroundRecorder = null
        Log.d(TAG, "[VOICE-INTERRUPT] Disabling background listener")
        scope.launch {
            recorder.stop()
        }
    }

    private fun monitorBackgroundListenerCleanup() {
        scope.launch {
            stateManager.phase.collect { currentPhase ->
                if (currentPhase == GenerationPhase.IDLE &&
                    backgroundRecorder != null &&
                    !isBargeInTurn &&
                    !isAccumulatingAfterInterrupt
                ) {
                    delay(300L)
                    if (stateManager.phase.value == GenerationPhase.IDLE &&
                        !isBargeInTurn &&
                        !isAccumulatingAfterInterrupt &&
                        backgroundRecorder != null
                    ) {
                        Log.d(TAG, "[VOICE-INTERRUPT] Generation ended, stopping background listener")
                        scope.launch {
                            disableBackgroundListener()
                        }
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
                val isBusy = listening || hearing || transcribing
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
        scope.launch {
            disableBackgroundListener()
            getStt()?.stop(false)
            delay(200L)
            stateManager.setListening(false)
            stateManager.setHearingSpeech(false)
            stateManager.setTranscribing(false)
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
            disableBackgroundListener()
            stateManager.setListening(false)
            stateManager.setHearingSpeech(false)
            stateManager.setTranscribing(false)
        }
    }
}