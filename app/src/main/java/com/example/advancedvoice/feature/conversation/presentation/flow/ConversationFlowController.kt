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
    private val onTurnComplete: () -> Unit, // Callback to enable auto-listen
    private val onTapToSpeak: () -> Unit      // Callback to disable auto-listen
) {
    private companion object {
        const val TAG = LoggerConfig.TAG_VM
    }

    var perfTimerHolder: PerfTimer? = null
    @Volatile private var isCurrentSessionAutoListen = false

    fun startListening() {
        Log.i(TAG, "[ViewModel] startListening() called. isGenerating=${engine.isActive()}, isTalking=${stateManager.isSpeaking.value}")
        isCurrentSessionAutoListen = false
        onTapToSpeak() // Signal a manual tap, which disables auto-listen

        // If user taps while system is busy, it's an interruption.
        if (engine.isActive() || stateManager.isSpeaking.value) {
            handleTapInterruption()
        } else {
            startListeningSession(isAutoListen = false)
        }
    }

    fun startAutoListening() {
        Log.i(TAG, "[AUTO-LISTEN] startAutoListening() called.")
        isCurrentSessionAutoListen = true
        startListeningSession(isAutoListen = true)
    }

    private fun startListeningSession(isAutoListen: Boolean) {
        if (stateManager.isListening.value || stateManager.isHearingSpeech.value) {
            Log.w(TAG, "[ViewModel] Already listening, ignoring startListeningSession call.")
            return
        }

        Log.i(TAG, "[ViewModel] Starting a listening session (autoListen=$isAutoListen).")
        interruption.reset()
        stateManager.addUserStreamingPlaceholder()

        scope.launch {
            try {
                getStt()?.start(isAutoListen)
            } catch (se: SecurityException) {
                Log.e(TAG, "[ViewModel] SecurityException for STT", se)
                stateManager.addError("Microphone permission denied.")
            } catch (e: Exception) {
                Log.e(TAG, "[ViewModel] Unexpected exception in STT start()", e)
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
        engine.abort(true)
        tts.stop()
        getStt()?.stop(false)
        stateManager.setPhase(GenerationPhase.IDLE)
        interruption.reset()
        Log.w(TAG, "[ViewModel] stopAll() complete. System is now idle.")
    }

    fun onFinalTranscript(text: String) {
        val input = text.trim()
        Log.i(TAG, "[STT RESULT] Raw transcript received: '$input'")

        if (input.isEmpty()) {
            handleEmptyTranscript()
            return
        }

        if (interruption.isAccumulatingAfterInterrupt) { // ✅ FIXED: was isAccumulating
            handleAccumulationTranscript(input)
        } else {
            handleNormalTranscript(input)
        }
    }

    private fun handleEmptyTranscript() {
        Log.w(TAG, "[ViewModel] onFinalTranscript received empty text.")

        if (interruption.isAccumulatingAfterInterrupt) { // ✅ Fixed property name
            Log.i(TAG, "[ACCUMULATION] Empty transcript during accumulation - watcher will handle it.")
            return
        }

        // If it was a normal listening session (not auto) that resulted in nothing,
        // just clean up the placeholder. The user might have tapped by mistake.
        if (!isCurrentSessionAutoListen) {
            stateManager.removeLastUserPlaceholderIfEmpty()
        }
        // If it was an auto-listen session, it's expected to get empty transcripts if the user doesn't speak.
        // The STT controller should handle this silently. We just clean up the UI.
        else {
            stateManager.removeLastUserPlaceholderIfEmpty()
        }
    }

    private fun handleAccumulationTranscript(input: String) {
        val combinedInput = interruption.combineTranscript(input) // ✅ Fixed method name
        Log.i(TAG, "[ACCUMULATION] Transcript added. Combined text is now: '$combinedInput'")
        stateManager.replaceLastUser(combinedInput)
    }

    private fun handleNormalTranscript(input: String) {
        // No more debounce. Send the transcript immediately.
        Log.i(TAG, "[IMMEDIATE-SEND] Sending transcript (len=${input.length})")
        stateManager.replaceLastUser(input)
        startTurn(input)
    }

    private fun startTurn(userText: String) {
        stateManager.setPhase(
            if (getPrefs.getFasterFirst()) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )

        val model = getPrefs.getSelectedModel()
        Log.i(TAG, "[ViewModel] startTurn(model=$model) inputLen=${userText.length}")
        perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }

        onTurnComplete() // Signal that this turn is eligible for auto-listen upon completion
        engine.startTurn(userText, model)
    }

    fun startTurnWithExistingHistory() {
        stateManager.setPhase(
            if (getPrefs.getFasterFirst()) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )
        val model = getPrefs.getSelectedModel()
        Log.i(TAG, "[ViewModel] startTurnWithExistingHistory(model=$model)")
        perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }

        onTurnComplete() // Also eligible for auto-listen
        engine.startTurnWithCurrentHistory(model)
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