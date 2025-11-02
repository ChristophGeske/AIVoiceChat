package com.example.advancedvoice.feature.conversation.presentation.flow

import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.util.PerfTimer
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
import com.example.advancedvoice.feature.conversation.presentation.interruption.InterruptionManager
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.SttController
import com.example.advancedvoice.feature.conversation.service.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controls the conversation flow: listening, transcription, and turn management.
 */
class ConversationFlowController(
    private val scope: CoroutineScope,
    private val stateManager: ConversationStateManager,
    private val engine: SentenceTurnEngine,
    private val tts: TtsController,
    private val interruption: InterruptionManager,
    private val getStt: () -> SttController?,
    private val getPrefs: PrefsProvider,
    private val onTurnComplete: () -> Unit
) {
    private companion object {
        const val TAG = LoggerConfig.TAG_VM
        const val TRANSCRIPT_DEBOUNCE_MS = 600L
    }

    private val pendingTranscript = StringBuilder()
    private var debounceJob: Job? = null
    var perfTimerHolder: PerfTimer? = null

    // ✅ NEW: Track if current session is auto-listen
    @Volatile private var isCurrentSessionAutoListen = false

    /**
     * Start listening session (manual - user pressed button).
     */
    fun startListening() {
        Log.i(TAG, "[ViewModel] startListening() called. isGenerating=${engine.isActive()}, isTalking=${stateManager.isSpeaking.value}")

        // ✅ Mark as manual listen
        isCurrentSessionAutoListen = false

        when {
            engine.isActive() || stateManager.isSpeaking.value -> handleTapInterruption()
            else -> startNormalListening()
        }
    }

    /**
     * Start auto-listen session (automatic after TTS finishes).
     */
    fun startAutoListening() {
        Log.i(TAG, "[AUTO-LISTEN] startAutoListening() called.")

        // ✅ Mark as auto-listen
        isCurrentSessionAutoListen = true

        if (stateManager.isListening.value || stateManager.isHearingSpeech.value) {
            Log.w(TAG, "[AUTO-LISTEN] Already listening, ignoring.")
            return
        }

        interruption.reset()
        stateManager.addUserStreamingPlaceholder()

        scope.launch {
            try {
                getStt()?.start(isAutoListen = true)
            } catch (se: SecurityException) {
                Log.w(TAG, "[AUTO-LISTEN] Mic permission denied")
                stateManager.addError("Microphone permission denied")
            }
        }
    }

    /**
     * Start normal listening session.
     */
    private fun startNormalListening() {
        if (stateManager.isListening.value || stateManager.isHearingSpeech.value) {
            Log.w(TAG, "[ViewModel] Already listening, ignoring startNormalListening call.")
            return
        }

        Log.i(TAG, "[ViewModel] Starting a normal listening session (autoListen=$isCurrentSessionAutoListen).")

        if (!interruption.isBargeInTurn && !interruption.isAccumulatingAfterInterrupt) {
            stateManager.addUserStreamingPlaceholder()
        }

        scope.launch {
            try {
                getStt()?.start(isAutoListen = isCurrentSessionAutoListen)
            } catch (se: SecurityException) {
                Log.e(TAG, "[ViewModel] SecurityException caught", se)
                stateManager.addError("Microphone permission denied")
            } catch (e: Exception) {
                Log.e(TAG, "[ViewModel] Unexpected exception in start()", e)
                stateManager.addError("STT error: ${e.message}")
            }
        }
    }

    /**
     * Handle tap interruption during generation or TTS.
     */
    private fun handleTapInterruption() {
        val interruptingGeneration = engine.isActive()
        val interruptingTts = stateManager.isSpeaking.value && !engine.isActive()

        Log.i(TAG, "[INTERRUPT-TAP] User tapped to interrupt. (generation=$interruptingGeneration, tts=$interruptingTts)")

        interruption.reset()

        scope.launch {
            stopAll()
            startNormalListening()
        }
    }

    /**
     * Stop all activities.
     */
    suspend fun stopAll() {
        Log.w(TAG, "[ViewModel] stopAll() called - HARD STOP initiated.")
        engine.abort(true)
        tts.stop()
        getStt()?.stop(false)
        stateManager.setPhase(GenerationPhase.IDLE)
        Log.w(TAG, "[ViewModel] stopAll() complete. System is now idle.")
    }

    /**
     * Handle final transcript from STT.
     */
    fun onFinalTranscript(text: String) {
        val input = text.trim()

        // Empty transcript handling
        if (input.isEmpty()) {
            handleEmptyTranscript()
            return
        }

        Log.i(TAG, "[ViewModel] Final Transcript='${input.take(120)}'")

        // During accumulation (interruption mode)
        if (interruption.isAccumulatingAfterInterrupt) {
            handleAccumulationTranscript(input)
            return
        }

        // Old barge-in logic (backward compatibility)
        if (interruption.isBargeInTurn) {
            handleBargeInTranscript(input)
            return
        }

        // Normal transcript flow
        handleNormalTranscript(input)
    }

    /**
     * Handle empty transcript.
     */
    private fun handleEmptyTranscript() {
        Log.w(TAG, "[ViewModel] onFinalTranscript received empty text.")

        if (interruption.isAccumulatingAfterInterrupt) {
            Log.i(TAG, "[ACCUMULATION] Empty transcript during accumulation - waiting for real input")
            return
        }

        if (interruption.isBargeInTurn) {
            Log.i(TAG, "[VOICE-INTERRUPT] Empty after abort - false alarm, ignoring")
            interruption.reset()
            stateManager.removeLastUserPlaceholderIfEmpty()
            return
        }

        val stillGenerating = engine.isActive()
        if (stillGenerating) {
            Log.i(TAG, "[VOICE-INTERRUPT] Empty transcript during generation - likely TTS self-hearing, ignoring")
            stateManager.removeLastUserPlaceholderIfEmpty()
            return
        }

        // ✅ IMPROVED: Use explicit flag instead of heuristics
        if (isCurrentSessionAutoListen) {
            // Auto-listen false alarm - silent cleanup
            Log.i(TAG, "[AUTO-LISTEN] Empty transcript - likely background noise, ignoring silently")
            stateManager.removeLastUserPlaceholderIfEmpty()
        } else {
            // Manual listening failed - show error
            Log.w(TAG, "[ViewModel] Empty transcript during manual listening - transcription failed")
            stateManager.removeLastUserPlaceholderIfEmpty()
            stateManager.addError("Could not transcribe audio. Please try again.")
        }

        // ✅ Reset flag after handling
        isCurrentSessionAutoListen = false
    }

    /**
     * Handle transcript during accumulation.
     */
    private fun handleAccumulationTranscript(input: String) {
        Log.i(TAG, "[ACCUMULATION] Received transcript (len=${input.length})")

        debounceJob?.cancel()

        val combinedInput = interruption.combineTranscript(input)

        Log.i(TAG, "[ACCUMULATION] Combined: total(${combinedInput.length})")
        Log.i(TAG, "[ACCUMULATION] Text: '${combinedInput.take(150)}...'")

        stateManager.replaceLastUser(combinedInput)

        Log.d(TAG, "[ACCUMULATION] Waiting for user to finish speaking...")
    }

    /**
     * Handle transcript after barge-in.
     */
    private fun handleBargeInTranscript(input: String) {
        Log.i(TAG, "[VOICE-INTERRUPT] Real transcript after abort! Combining...")

        scope.launch {
            delay(150L)

            debounceJob?.cancel()
            pendingTranscript.clear()

            interruption.reset()

            val combinedInput = interruption.combineTranscript(input)
            Log.i(TAG, "[ViewModel] Combined interruption input (len=${combinedInput.length}): '${combinedInput.take(100)}...'")

            stateManager.replaceLastUser(combinedInput)
            engine.replaceLastUserMessage(combinedInput)
            startTurnWithExistingHistory()
        }
    }

    /**
     * Handle normal transcript (no interruption).
     */
    private fun handleNormalTranscript(input: String) {
        val hasPendingText = pendingTranscript.isNotEmpty()

        if (hasPendingText) {
            pendingTranscript.append(" ").append(input)
            Log.i(TAG, "[DEBOUNCE] Appending fragment. Combined: ${pendingTranscript.length} chars")
        } else {
            pendingTranscript.append(input)
            Log.i(TAG, "[DEBOUNCE] Starting accumulation")
        }

        stateManager.replaceLastUser(pendingTranscript.toString())

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(TRANSCRIPT_DEBOUNCE_MS)

            val finalText = pendingTranscript.toString().trim()
            pendingTranscript.clear()

            if (finalText.isNotEmpty()) {
                Log.i(TAG, "[DEBOUNCE] Sending transcript (len=${finalText.length})")
                startTurn(finalText)
            }
        }
    }

    /**
     * Start a new turn with user text.
     */
    private fun startTurn(userText: String) {
        stateManager.setPhase(
            if (getPrefs.getFasterFirst()) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )

        val model = getPrefs.getSelectedModel()
        Log.i(TAG, "[ViewModel] startTurn(model=$model) inputLen=${userText.length}")

        perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }
        interruption.onTurnStart(System.currentTimeMillis())

        // ✅ Enable auto-listen for this turn
        onTurnComplete()

        engine.startTurn(userText, model)
    }

    /**
     * Start turn with existing history (after interruption).
     */
    fun startTurnWithExistingHistory() {
        stateManager.setPhase(
            if (getPrefs.getFasterFirst()) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )

        val model = getPrefs.getSelectedModel()
        Log.i(TAG, "[ViewModel] startTurnWithExistingHistory(model=$model)")

        perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }
        interruption.onTurnStart(System.currentTimeMillis())

        // ✅ Enable auto-listen for this turn
        onTurnComplete()

        engine.startTurnWithCurrentHistory(model)
    }

    /**
     * Clear conversation.
     */
    fun clearConversation() {
        debounceJob?.cancel()
        pendingTranscript.clear()
        interruption.reset()
        engine.abort(true)
        stateManager.clear()
        tts.stop()
        stateManager.setPhase(GenerationPhase.IDLE)
        scope.launch { getStt()?.stop(false) }
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        debounceJob?.cancel()
        interruption.reset()
    }

    /**
     * Interface for accessing preferences.
     */
    interface PrefsProvider {
        fun getFasterFirst(): Boolean
        fun getSelectedModel(): String
        fun getAutoListen(): Boolean
    }
}