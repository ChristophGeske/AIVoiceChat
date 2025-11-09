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
    private val onTurnComplete: () -> Unit,
    private val onTapToSpeak: () -> Unit
) {
    private companion object {
        const val TAG = LoggerConfig.TAG_VM
        const val MAX_AUTO_LISTEN_RETRIES = 2
    }

    var perfTimerHolder: PerfTimer? = null
    @Volatile private var isCurrentSessionAutoListen = false
    @Volatile private var autoListenRetryCount = 0

    fun startListening() {
        Log.i(TAG, "[ViewModel] startListening() called. isGenerating=${engine.isActive()}, isTalking=${stateManager.isSpeaking.value}")
        isCurrentSessionAutoListen = false
        onTapToSpeak()

        // Clear the hard-stop flag whenever the user initiates an action
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

        // Also clear the hard-stop flag for auto-listen
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
        // Set the hard-stop flag to prevent TTS race conditions
        stateManager.setHardStop(true)

        engine.abort(true)
        tts.stop() // This is now reinforced by the hard-stop flag
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

        if (interruption.isAccumulatingAfterInterrupt) {
            handleAccumulationTranscript(input)
        } else {
            handleNormalTranscript(input)
        }
    }

    private fun handleEmptyTranscript() {
        Log.w(TAG, "[ViewModel] onFinalTranscript received empty text.")

        if (interruption.isAccumulatingAfterInterrupt) {
            Log.i(TAG, "[ACCUMULATION] Empty transcript during accumulation - watcher will handle it.")
            return
        }

        // ✅ FIX: Check if this was an auto-listen session
        if (isCurrentSessionAutoListen) {
            Log.i(TAG, "[AUTO-LISTEN] Empty transcript from auto-listen session")
            stateManager.removeLastUserPlaceholderIfEmpty()

            // ✅ If auto-listen is still enabled, try again (with a limit to prevent infinite loops)
            if (getPrefs.getAutoListen() && autoListenRetryCount < MAX_AUTO_LISTEN_RETRIES) {
                autoListenRetryCount++
                Log.i(TAG, "[AUTO-LISTEN] Retrying auto-listen (attempt $autoListenRetryCount/$MAX_AUTO_LISTEN_RETRIES)")
                scope.launch {
                    delay(500L) // Short delay before retry
                    if (stateManager.phase.value == GenerationPhase.IDLE &&
                        !stateManager.isListening.value &&
                        !stateManager.isSpeaking.value) {
                        startAutoListening()
                    }
                }
            } else {
                // ✅ Max retries reached or auto-listen disabled, switch to IDLE
                Log.w(TAG, "[AUTO-LISTEN] Max retries reached or disabled, switching to IDLE")
                autoListenRetryCount = 0
                scope.launch {
                    getStt()?.stop(false)
                    delay(100L)
                    stateManager.setListening(false)
                    stateManager.setHearingSpeech(false)
                }
            }
        } else {
            // ✅ Manual session with no speech - just clean up
            Log.i(TAG, "[MANUAL-LISTEN] No speech detected in manual session")
            stateManager.removeLastUserPlaceholderIfEmpty()
        }
    }

    private fun handleAccumulationTranscript(input: String) {
        val combinedInput = interruption.combineTranscript(input)
        Log.i(TAG, "[ACCUMULATION] Transcript added. Combined text is now: '$combinedInput'")
        stateManager.replaceLastUser(combinedInput)
    }

    private fun handleNormalTranscript(input: String) {
        Log.i(TAG, "[IMMEDIATE-SEND] Sending transcript (len=${input.length})")
        autoListenRetryCount = 0  // ✅ Reset on successful input
        stateManager.replaceLastUser(input)
        startTurn(input)
    }

    private fun startTurn(userText: String) {
        // Clear the hard-stop flag when starting a new turn
        stateManager.setHardStop(false)

        stateManager.setPhase(
            if (getPrefs.getFasterFirst()) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )

        val model = getPrefs.getSelectedModel()
        Log.i(TAG, "[ViewModel] startTurn(model=$model) inputLen=${userText.length}")
        perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }

        onTurnComplete()
        engine.startTurn(userText, model)
    }

    fun startTurnWithExistingHistory() {
        // Clear the hard-stop flag when restarting a turn
        stateManager.setHardStop(false)

        stateManager.setPhase(
            if (getPrefs.getFasterFirst()) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )
        val model = getPrefs.getSelectedModel()
        Log.i(TAG, "[ViewModel] startTurnWithExistingHistory(model=$model)")
        perfTimerHolder = PerfTimer(TAG, "llm").also { it.mark("llm_start") }

        onTurnComplete()
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