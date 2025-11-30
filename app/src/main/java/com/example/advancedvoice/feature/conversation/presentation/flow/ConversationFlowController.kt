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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.advancedvoice.feature.conversation.service.TranscriptRefinementService


class ConversationFlowController(
    private val scope: CoroutineScope,
    private val stateManager: ConversationStateManager,
    private val engine: SentenceTurnEngine,
    private val tts: TtsController,
    private val interruption: InterruptionManager,
    private val getStt: () -> SttController?,
    private var lastAudioSegments: List<ByteArray> = emptyList(),
    private var lastUserEntryIndex: Int = -1,
    private val getPrefs: PrefsProvider,
    private val onTurnComplete: () -> Unit,
    private val onTapToSpeak: () -> Unit
) {
    private companion object {
        const val TAG = LoggerConfig.TAG_VM
        // You can remove MAX_AUTO_LISTEN_RETRIES as we are no longer using it.
        // const val MAX_AUTO_LISTEN_RETRIES = 2
    }

    var perfTimerHolder: PerfTimer? = null
    @Volatile private var isCurrentSessionAutoListen = false
    // You can remove autoListenRetryCount as well.
    // @Volatile private var autoListenRetryCount = 0

    fun startListening() {
        Log.i(TAG, "[ViewModel] startListening() called. isGenerating=${engine.isActive()}, isTalking=${stateManager.isSpeaking.value}")
        isCurrentSessionAutoListen = false
        onTapToSpeak()

        stateManager.setHardStop(false)

        if (engine.isActive() || stateManager.isSpeaking.value) {
            handleTapInterruption()
        } else {
            startListeningSession(isAutoListen = false)
        }
    }

    fun startAutoListening() {
        Log.i(TAG, "[AUTO-LISTEN] 🎙️ startAutoListening() called. Current state: listening=${stateManager.isListening.value}, speaking=${stateManager.isSpeaking.value}, phase=${stateManager.phase.value}")
        isCurrentSessionAutoListen = true

        stateManager.setHardStop(false)
        startListeningSession(isAutoListen = true)
    }

    private fun startListeningSession(isAutoListen: Boolean) {
        if (stateManager.isListening.value || stateManager.isHearingSpeech.value) {
            Log.w(TAG, "[LISTEN-SESSION] ⚠️ Already listening, ignoring startListeningSession call.")
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
        Log.w(TAG, "[ViewModel] stopAll() complete. System is now idle.")
    }


    fun onFinalTranscript(text: String) {
        // Check for our special timeout signal first.
        if (text == "::TIMEOUT::") {
            Log.i(TAG, "[STT RESULT] Received TIMEOUT signal. Stopping session now.")

            // ✅   If we're evaluating, flush buffered response (treat timeout as noise)
            if (interruption.isEvaluatingBargeIn) {
                Log.i(TAG, "[TIMEOUT] Evaluation active - treating timeout as noise, flushing buffered response")
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
            return
        }

        val input = text.trim()
        Log.i(TAG, "[STT RESULT] Raw transcript received: '$input'")

        // Ask InterruptionManager whether this is a barge-in transcript to handle (noise/real speech).
        val wasHandledHere = interruption.checkTranscriptForInterruption(input)
        if (wasHandledHere) {
            // If it was noise, InterruptionManager flushes the held assistant.
            // If it was real speech, it entered accumulation.
            Log.i(TAG, "[STT RESULT] Barge-in path handled by InterruptionManager")
            return
        } else {
            // Not a barge-in evaluation transcript (or evaluation concluded as noise and we returned false).
            // Ensure any held assistant gets flushed now.
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


    private fun handleEmptyTranscript() {
        Log.w(TAG, "[ViewModel] onFinalTranscript received empty text (VAD noise).")

        if (interruption.isAccumulatingAfterInterrupt) {
            Log.i(TAG, "[ACCUMULATION] Empty transcript during accumulation - watcher will handle it.")
            return
        }

        // ✅ FIX: Noise doesn't end the session - reset turn state and keep listening
        Log.i(TAG, "[EMPTY] VAD noise detected - resetting turn and continuing to listen")
        stateManager.removeLastUserPlaceholderIfEmpty()

        val stt = getStt()
        if (stt is GeminiLiveSttController) {
            scope.launch {
                stt.resetTurnAfterNoise()  // ✅ This calls the new method!
            }
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

        // Get audio segments from STT controller
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
            startTurnWithAudio(audioSegments, input)
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

    private fun startTurnWithAudio(audioSegments: List<ByteArray>, streamingTranscript: String) {
        stateManager.setHardStop(false)

        stateManager.setPhase(
            if (getPrefs.getFasterFirst()) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )

        val model = getPrefs.getSelectedModel()
        Log.i(TAG, "[ViewModel] startTurnWithAudio(model=$model) segments=${audioSegments.size}")
        perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }

        interruption.onTurnStart(System.currentTimeMillis())

        onTurnComplete()
        engine.startTurnWithAudio(audioSegments, streamingTranscript, model)
    }

    fun startTurnWithExistingHistory() {
        stateManager.setHardStop(false)

        stateManager.setPhase(
            if (getPrefs.getFasterFirst()) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )
        val model = getPrefs.getSelectedModel()

        // Get combined audio from interruption manager
        val combinedAudio = interruption.getCombinedAudioSegments()

        if (combinedAudio.isNotEmpty()) {
            Log.i(TAG, "[ViewModel] startTurnWithExistingHistory using ${combinedAudio.size} combined audio segments")
            perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }
            interruption.onTurnStart(System.currentTimeMillis())
            onTurnComplete()

            val lastUserText = stateManager.conversation.value
                .lastOrNull { it.speaker == "You" }
                ?.sentences?.joinToString(" ") ?: ""

            engine.startTurnWithAudio(combinedAudio, lastUserText, model)
        } else {
            Log.i(TAG, "[ViewModel] startTurnWithExistingHistory (text-only)")
            perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }
            interruption.onTurnStart(System.currentTimeMillis())
            onTurnComplete()
            engine.startTurnWithCurrentHistory(model)
        }
    }

    /**
     * Get the last audio segments for transcript refinement.
     */
    fun getLastAudioForRefinement(): Pair<List<ByteArray>, Int> {
        return lastAudioSegments to lastUserEntryIndex
    }

    fun clearConversation() {
        interruption.reset()
        engine.abort(true)
        tts.stop()
        stateManager.clear()
        stateManager.setPhase(GenerationPhase.IDLE)
        scope.launch { getStt()?.stop(false) }
    }

    fun cleanup() {
        interruption.reset()
    }

    interface PrefsProvider {
        fun getFasterFirst(): Boolean
        fun getSelectedModel(): String
        fun getAutoListen(): Boolean
    }
}