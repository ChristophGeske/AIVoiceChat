package com.example.advancedvoice.feature.conversation.presentation.flow

import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.util.PerfTimer
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
import com.example.advancedvoice.feature.conversation.presentation.interruption.InterruptionManager
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.GeminiLiveSttController
import com.example.advancedvoice.feature.conversation.service.SttController
import com.example.advancedvoice.feature.conversation.service.TtsController
import com.example.advancedvoice.feature.conversation.service.TranscriptRefinementService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ConversationFlowController(
    private val scope: CoroutineScope,
    private val stateManager: ConversationStateManager,
    private val engine: SentenceTurnEngine,
    private val tts: TtsController,
    private val interruption: InterruptionManager,
    private val getStt: () -> SttController?,
    private val getPrefs: PrefsProvider,
    private val getRefinementService: () -> TranscriptRefinementService,
    private val onTurnComplete: () -> Unit,
    private val onTapToSpeak: () -> Unit
) {
    private companion object {
        const val TAG = LoggerConfig.TAG_VM
    }

    var perfTimerHolder: PerfTimer? = null
    private var lastAudioSegments: List<ByteArray> = emptyList()
    private var lastUserEntryIndex: Int = -1

    fun startListening() {
        Log.i(TAG, "[ViewModel] startListening() called. isGenerating=${engine.isActive()}, isTalking=${stateManager.isSpeaking.value}")
        onTapToSpeak()

        stateManager.setHardStop(false)

        if (engine.isActive() || stateManager.isSpeaking.value) {
            handleTapInterruption()
        } else {
            startListeningSession(isAutoListen = false)
        }
    }

    fun startAutoListening() {
        Log.i(TAG, "[AUTO-LISTEN] 🎙️ startAutoListening() called.")
        stateManager.setHardStop(false)
        startListeningSession(isAutoListen = true)
    }

    private fun startListeningSession(isAutoListen: Boolean) {
        if (stateManager.isListening.value || stateManager.isHearingSpeech.value) {
            Log.w(TAG, "[LISTEN-SESSION] ⚠️ Already listening")
            return
        }

        Log.i(TAG, "[LISTEN-SESSION] 🎤 Starting listening session (autoListen=$isAutoListen).")
        interruption.reset()
        stateManager.addUserStreamingPlaceholder()

        scope.launch {
            try {
                Log.d(TAG, "[LISTEN-SESSION] Calling STT start()...")
                getStt()?.start(isAutoListen)
                Log.d(TAG, "[LISTEN-SESSION] STT start() returned")
            } catch (se: SecurityException) {
                Log.e(TAG, "[LISTEN-SESSION] ❌ SecurityException for STT", se)
                stateManager.addError("Microphone permission denied.")
            } catch (e: Exception) {
                Log.e(TAG, "[LISTEN-SESSION] ❌ Unexpected exception in STT start()", e)
                stateManager.addError("STT error: ${e.message}")
            }
        }
    }

    private fun handleTapInterruption() {
        Log.i(TAG, "[INTERRUPT-TAP] User tapped to interrupt. Stopping all and starting new session.")
        interruption.reset()
        scope.launch {
            stopAll()
            startListeningSession(isAutoListen = false)
        }
    }

    suspend fun stopAll() {
        Log.w(TAG, "[ViewModel] stopAll() called - HARD STOP initiated.")
        stateManager.setHardStop(true)

        engine.abort(true)
        tts.stop()
        getStt()?.stop(false)
        stateManager.setPhase(GenerationPhase.IDLE)
        interruption.reset()
        getRefinementService().cancel()
    }

    fun onFinalTranscript(text: String) {
        if (text == "::TIMEOUT::") {
            handleTimeout()
            return
        }

        val input = text.trim()
        Log.i(TAG, "[STT RESULT] Raw transcript received: '$input'")

        val wasHandledHere = interruption.checkTranscriptForInterruption(input)
        if (wasHandledHere) {
            Log.i(TAG, "[STT RESULT] Barge-in path handled by InterruptionManager")
            return
        } else {
            interruption.flushBufferedAssistantIfAny()
        }

        if (input.isEmpty()) {
            handleEmptyTranscript()
            return
        }

        if (interruption.isAccumulatingAfterInterrupt) {
            handleAccumulationTranscript(input)
        } else {
            handleNormalTranscript(input)
        }
    }

    private fun handleTimeout() {
        Log.i(TAG, "[STT RESULT] Received TIMEOUT signal. Stopping session now.")
        if (interruption.isEvaluatingBargeIn) {
            Log.i(TAG, "[TIMEOUT] Evaluation active - treating timeout as noise")
            interruption.handleTimeoutDuringEvaluation()
        } else {
            stateManager.removeLastUserPlaceholderIfEmpty()
        }

        scope.launch {
            getStt()?.stop(false)
            delay(100L)
            stateManager.setListening(false)
            stateManager.setHearingSpeech(false)
            stateManager.setTranscribing(false)

            val stt = getStt()
            if (stt is GeminiLiveSttController) {
                stt.switchMicMode(com.example.advancedvoice.core.audio.MicrophoneSession.Mode.IDLE)
            }
        }
    }

    private fun handleEmptyTranscript() {
        Log.w(TAG, "[ViewModel] onFinalTranscript received empty text (VAD noise).")

        if (interruption.isAccumulatingAfterInterrupt) {
            Log.i(TAG, "[ACCUMULATION] Empty transcript during accumulation")
            return
        }

        Log.i(TAG, "[EMPTY] VAD noise detected - resetting turn and continuing to listen")
        stateManager.removeLastUserPlaceholderIfEmpty()

        val stt = getStt()
        if (stt is GeminiLiveSttController) {
            scope.launch { stt.resetTurnAfterNoise() }
        }
    }

    private fun handleAccumulationTranscript(input: String) {
        val combinedInput = interruption.combineTranscript(input)
        Log.i(TAG, "[ACCUMULATION] Transcript added. Combined text is now: '$combinedInput'")
        stateManager.replaceLastUser(combinedInput)
    }

    private fun handleNormalTranscript(input: String) {
        Log.i(TAG, "[IMMEDIATE-SEND] Preparing turn with audio")
        stateManager.replaceLastUser(input)

        val stt = getStt()
        val audioSegments = when (stt) {
            is GeminiLiveSttController -> {
                val segments = stt.getCollectedAudioSegments()
                stt.clearCollectedAudio()
                segments
            }
            else -> emptyList()
        }

        lastAudioSegments = audioSegments
        lastUserEntryIndex = stateManager.getLastUserEntryIndex()

        if (audioSegments.isNotEmpty()) {
            Log.i(TAG, "[IMMEDIATE-SEND] Starting turn with ${audioSegments.size} audio segments")
            startTurnWithAudioParallel(audioSegments, input)
        } else {
            Log.w(TAG, "[IMMEDIATE-SEND] No audio segments, falling back to text")
            startTurn(input)
        }
    }

    private fun startTurn(userText: String) {
        stateManager.setHardStop(false)
        stateManager.setPhase(
            if (getPrefs.getFasterFirst()) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )

        val model = getPrefs.getSelectedModel()
        Log.i(TAG, "[ViewModel] startTurn(model=$model) inputLen=${userText.length}")
        perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }

        interruption.onTurnStart(System.currentTimeMillis())
        onTurnComplete()
        engine.startTurn(userText, model)
    }

    /**
     * Start both paths in parallel:
     * - PATH A: Transcription refinement (Flash model)
     * - PATH B: Response generation (User's chosen model)
     */
    private fun startTurnWithAudioParallel(audioSegments: List<ByteArray>, streamingTranscript: String) {
        stateManager.setHardStop(false)
        stateManager.setPhase(
            if (getPrefs.getFasterFirst()) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )

        val model = getPrefs.getSelectedModel()

        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "[PARALLEL] 🚀 Starting BOTH paths simultaneously")
        Log.i(TAG, "  PATH A: Flash transcription (refinement)")
        Log.i(TAG, "  PATH B: $model (response generation)")
        Log.i(TAG, "  Audio segments: ${audioSegments.size}")
        Log.i(TAG, "  User entry index: $lastUserEntryIndex")
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }
        interruption.onTurnStart(System.currentTimeMillis())
        onTurnComplete()

        // ═══════════════════════════════════════════════════════════════
        // PATH A: Transcription Refinement (runs in parallel)
        // Uses Flash model regardless of user's chosen model
        // ═══════════════════════════════════════════════════════════════
        if (lastUserEntryIndex >= 0) {
            Log.i(TAG, "[PARALLEL] 🔵 Launching PATH A (Flash transcription)...")
            getRefinementService().startRefinement(
                audioSegments = audioSegments,
                userEntryIndex = lastUserEntryIndex,
                streamingTranscript = streamingTranscript
            )
        } else {
            Log.w(TAG, "[PARALLEL] ⚠️ Skipping PATH A - no valid user entry index")
        }

        // ═══════════════════════════════════════════════════════════════
        // PATH B: Response Generation (runs in parallel)
        // Uses user's chosen model
        // ═══════════════════════════════════════════════════════════════
        Log.i(TAG, "[PARALLEL] 🟢 Launching PATH B ($model generation)...")
        engine.startTurnWithAudio(audioSegments, streamingTranscript, model)
    }

    fun startTurnWithExistingHistory() {
        stateManager.setHardStop(false)
        stateManager.setPhase(
            if (getPrefs.getFasterFirst()) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )
        val model = getPrefs.getSelectedModel()

        val combinedAudio = interruption.getCombinedAudioSegments()

        if (combinedAudio.isNotEmpty()) {
            Log.i(TAG, "[ViewModel] startTurnWithExistingHistory using ${combinedAudio.size} combined audio segments")
            perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }
            interruption.onTurnStart(System.currentTimeMillis())
            onTurnComplete()

            val lastUserText = stateManager.conversation.value
                .lastOrNull { it.speaker == "You" }
                ?.sentences?.joinToString(" ") ?: ""

            // Update these so refinement uses the correct COMBINED audio
            lastAudioSegments = combinedAudio
            lastUserEntryIndex = stateManager.getLastUserEntryIndex()

            // ═══════════════════════════════════════════════════════════════
            // PARALLEL PATHS for barge-in scenario
            // ═══════════════════════════════════════════════════════════════
            Log.i(TAG, "[PARALLEL-BARGEIN] Starting both paths for combined audio")

            // PATH A: Refinement
            if (lastUserEntryIndex >= 0) {
                getRefinementService().startRefinement(
                    audioSegments = combinedAudio,
                    userEntryIndex = lastUserEntryIndex,
                    streamingTranscript = lastUserText
                )
            }

            // PATH B: Generation
            engine.startTurnWithAudio(combinedAudio, lastUserText, model)
        } else {
            Log.i(TAG, "[ViewModel] startTurnWithExistingHistory (text-only)")
            perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }
            interruption.onTurnStart(System.currentTimeMillis())
            onTurnComplete()
            engine.startTurnWithCurrentHistory(model)
        }
    }

    fun getLastAudioForRefinement(): Pair<List<ByteArray>, Int> {
        return lastAudioSegments to lastUserEntryIndex
    }

    fun clearConversation() {
        interruption.reset()
        engine.abort(true)
        tts.stop()
        stateManager.clear()
        stateManager.setPhase(GenerationPhase.IDLE)
        getRefinementService().cancel()
        scope.launch { getStt()?.stop(false) }
    }

    fun cleanup() {
        interruption.reset()
        getRefinementService().cancel()
    }

    interface PrefsProvider {
        fun getFasterFirst(): Boolean
        fun getSelectedModel(): String
        fun getAutoListen(): Boolean
    }
}