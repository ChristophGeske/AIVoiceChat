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
    private val onInterruption: () -> Unit,
    private val onNoiseConfirmed: () -> Unit,
    private val getLastTurnAudio: () -> List<ByteArray>
) {
    private companion object {
        const val TAG = LoggerConfig.TAG_INTERRUPT
        const val INTERRUPT_ACCUMULATION_TIMEOUT = 30000L
        const val MIN_RESTART_INTERVAL_MS = 2000L
        val WORD_REGEX = Regex("[\\p{L}]{3,}")
    }

    @Volatile var isBargeInTurn = false
        private set
    @Volatile var isAccumulatingAfterInterrupt = false
        private set
    @Volatile var generationStartTime = 0L
        private set

    @Volatile var isEvaluatingBargeIn = false
        private set
    @Volatile private var assistantHoldBuffer: String? = null
    @Volatile private var assistantSourcesBuffer: List<Pair<String, String?>>? = null
    @Volatile private var interruptedDuringEvaluation = false

    @Volatile private var lastRestartTime = 0L
    private var interruptAccumulationJob: Job? = null

    // Buffer to hold audio from the interrupted turn + new speech
    private val accumulatedAudioSegments = mutableListOf<ByteArray>()

    fun initialize() {
        Log.i(TAG, "═══════════════════════════════════")
        Log.i(TAG, "InterruptionManager INITIALIZED")
        Log.i(TAG, "Strategy: transcript-based interruption")
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
                    Log.d(TAG, "[MONITOR] 🎤 Speech detected: gen=$generating, tts=$speaking, phase=${stateManager.phase.value}, eval=$isEvaluatingBargeIn, interrupted=$interruptedDuringEvaluation, genTime=${genDuration}ms")
                }

                if ((generating || speaking) && hearingSpeech && !isAccumulatingAfterInterrupt) {
                    if (isEvaluatingBargeIn) {
                        Log.w(TAG, "[MONITOR] ⚠️ New speech while still evaluating - resetting evaluation")
                        isEvaluatingBargeIn = false
                    }

                    if (!isEvaluatingBargeIn) {
                        val isTtsPlaying = speaking

                        if (isTtsPlaying) {
                            Log.i(TAG, "[MONITOR] 🔊 Noise during TTS playback → stopping TTS, evaluating")
                        } else {
                            Log.i(TAG, "[MONITOR] 🔊 Noise during silent generation → evaluating")
                        }

                        isEvaluatingBargeIn = true
                        interruptedDuringEvaluation = true

                        tts.stop()
                        onInterruption()

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
                                    Log.e(TAG, "[MONITOR] ❌ Error starting transcription: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun maybeHandleAssistantFinalResponse(
        text: String,
        sources: List<Pair<String, String?>> = emptyList()
    ): Boolean {
        if (isAccumulatingAfterInterrupt) {
            Log.i(TAG, "[HOLD] ❌ Already accumulating - discarding late LLM response")
            return true
        }

        if (isEvaluatingBargeIn) {
            val stillRecording = stateManager.isListening.value || stateManager.isTranscribing.value

            if (stillRecording) {
                assistantHoldBuffer = text
                assistantSourcesBuffer = sources
                Log.i(TAG, "[HOLD] 📦 BUFFERING response (len=${text.length}, sources=${sources.size})")
                return true
            } else {
                Log.i(TAG, "[HOLD] ✅ Mic inactive - allowing through")
                isEvaluatingBargeIn = false
                interruptedDuringEvaluation = false
                return false
            }
        }

        return false
    }

    fun checkTranscriptForInterruption(transcript: String): Boolean {
        val trimmed = transcript.trim()

        if (!isEvaluatingBargeIn) {
            return false
        }

        val hasWord = WORD_REGEX.containsMatchIn(trimmed)
        Log.i(TAG, "[VERDICT] 📋 Evaluating transcript: len=${trimmed.length}, hasWord=$hasWord")

        if (hasWord && assistantHoldBuffer == null && isGenerating(stateManager.phase.value)) {
            Log.w(TAG, "[VERDICT] ⚠️ RACE CONDITION: transcript before LLM response, waiting 500ms...")
            scope.launch {
                delay(500)
                handleConfirmedSpeechInterruption(trimmed)
            }
            return true
        }

        isEvaluatingBargeIn = false

        if (hasWord) {
            Log.i(TAG, "[VERDICT] ✅ REAL SPEECH CONFIRMED")
            handleConfirmedSpeechInterruption(trimmed)
            return true
        } else {
            Log.i(TAG, "[VERDICT] ❌ NOISE CONFIRMED")
            handleConfirmedNoise()
            return true
        }
    }

    private fun handleConfirmedSpeechInterruption(trimmed: String) {
        isEvaluatingBargeIn = false
        interruptedDuringEvaluation = false

        Log.i(TAG, "[SPEECH] 🗣️ Real speech confirmed → discarding buffered response, entering accumulation")

        accumulatedAudioSegments.clear()

        // ✅ FIX: ALWAYS rescue previous audio.
        // Removed the 'if (engine.isActive())' check.
        // Even if the engine finished generation, if we are here, it means the user
        // interrupted the TTS or responded immediately, so we want to append.
        val previousAudio = getLastTurnAudio()
        if (previousAudio.isNotEmpty()) {
            accumulatedAudioSegments.addAll(previousAudio)
            Log.i(TAG, "[SPEECH] Rescued ${previousAudio.size} segments from previous turn")
        } else {
            Log.w(TAG, "[SPEECH] No previous audio to rescue!")
        }

        // 2. ADD NEW AUDIO FROM CURRENT SPEECH
        val stt = getStt()
        if (stt is GeminiLiveSttController) {
            val segments = stt.getCollectedAudioSegments()
            if (segments.isNotEmpty()) {
                accumulatedAudioSegments.addAll(segments)
                Log.i(TAG, "[SPEECH] Added ${segments.size} new segments from interruption")
            }
        }

        val hadBufferedResponse = assistantHoldBuffer != null
        dropBufferedAssistant()

        if (!hadBufferedResponse && engine.isActive()) {
            Log.w(TAG, "[SPEECH] No buffered response, removing last assistant")
            val lastEntry = stateManager.conversation.value.lastOrNull()
            if (lastEntry?.isAssistant == true) {
                stateManager.removeLastEntry()
            }
        }

        engine.abort(true)

        isBargeInTurn = false
        isAccumulatingAfterInterrupt = true

        scope.launch {
            val combined = combineTranscript(trimmed)
            Log.i(TAG, "[SPEECH] Combined input: '${combined.take(120)}...'")
            stateManager.replaceLastUser(combined)
            startAccumulationWatcher()
        }
    }

    private fun handleConfirmedNoise() {
        isEvaluatingBargeIn = false

        // Clear audio collected during noise detection (it was just noise)
        accumulatedAudioSegments.clear()
        val stt = getStt()
        if (stt is GeminiLiveSttController) {
            stt.clearCollectedAudio()
        }

        Log.i(TAG, "[NOISE] 🔇 Noise confirmed → flushing buffered response (if any)")

        val held = assistantHoldBuffer
        val heldSources = assistantSourcesBuffer ?: emptyList()

        if (held != null) {
            assistantHoldBuffer = null
            assistantSourcesBuffer = null

            Log.i(TAG, "[NOISE] ✅ Flushing buffered response")

            if (heldSources.isNotEmpty()) {
                com.example.advancedvoice.domain.util.GroundingUtils.processAndDisplaySources(heldSources) { html ->
                    stateManager.addSystem(html)
                }
            }

            stateManager.addAssistant(listOf(held))
            tts.queue(held)
            interruptedDuringEvaluation = false

            onNoiseConfirmed()
        } else {
            Log.d(TAG, "[NOISE] No buffered response to flush")

            val isGenerating = stateManager.phase.value != GenerationPhase.IDLE

            if (interruptedDuringEvaluation && !isGenerating) {
                Log.d(TAG, "[NOISE] Attempting to resume interrupted TTS")
                val lastAssistant = stateManager.conversation.value.lastOrNull { it.isAssistant }
                if (lastAssistant != null && lastAssistant.sentences.isNotEmpty()) {
                    if (!tts.isSpeaking.value) {
                        val fullText = lastAssistant.sentences.joinToString(" ")
                        tts.queue(fullText)
                        onNoiseConfirmed()
                    }
                }
            } else if (isGenerating) {
                if (interruptedDuringEvaluation) {
                    onNoiseConfirmed()
                }
            }

            interruptedDuringEvaluation = false
        }
    }

    fun flushBufferedAssistantIfAny() {
        val held = assistantHoldBuffer ?: return
        val heldSources = assistantSourcesBuffer ?: emptyList()

        assistantHoldBuffer = null
        assistantSourcesBuffer = null

        if (heldSources.isNotEmpty()) {
            com.example.advancedvoice.domain.util.GroundingUtils.processAndDisplaySources(heldSources) { html ->
                stateManager.addSystem(html)
            }
        }

        stateManager.addAssistant(listOf(held))
        tts.queue(held)
        interruptedDuringEvaluation = false
    }

    fun dropBufferedAssistant() {
        if (assistantHoldBuffer != null || assistantSourcesBuffer != null) {
            Log.i(TAG, "[DROP] ❌ Dropping buffered response")
            assistantHoldBuffer = null
            assistantSourcesBuffer = null

            while (stateManager.conversation.value.lastOrNull()?.speaker == "System") {
                stateManager.removeLastEntry()
            }

            val historySnapshot = engine.getHistorySnapshot()
            if (historySnapshot.lastOrNull()?.role == "assistant") {
                engine.clearHistory()
                engine.seedHistory(historySnapshot.dropLast(1))
            }
        }
    }

    private fun startAccumulationWatcher() {
        interruptAccumulationJob?.cancel()
        interruptAccumulationJob = scope.launch {
            Log.i(TAG, "[ACCUMULATION] Starting watcher")
            val startTime = System.currentTimeMillis()
            var lastCheck = startTime
            var consecutiveNotBusyCount = 0

            while (isAccumulatingAfterInterrupt) {
                delay(500L)
                val elapsed = System.currentTimeMillis() - startTime
                val isBusy = stateManager.isListening.value || stateManager.isTranscribing.value

                if (!isBusy) {
                    consecutiveNotBusyCount++
                    if (consecutiveNotBusyCount >= 3) {
                        Log.i(TAG, "[ACCUMULATION] User stopped, finalizing")
                        finalizeAccumulation()
                        break
                    }
                } else {
                    consecutiveNotBusyCount = 0
                }

                if (elapsed > INTERRUPT_ACCUMULATION_TIMEOUT) {
                    Log.w(TAG, "[ACCUMULATION] Timeout, force finalizing")
                    finalizeAccumulation()
                    break
                }
            }
        }
    }

    private fun finalizeAccumulation() {
        if (!isAccumulatingAfterInterrupt) return

        val lastUserEntry = stateManager.conversation.value.lastOrNull { it.speaker == "You" }
        val accumulatedText = lastUserEntry?.sentences?.joinToString(" ")?.trim() ?: ""
        Log.i(TAG, "[ACCUMULATION] Finalizing: '${accumulatedText.take(100)}...'")

        val now = System.currentTimeMillis()
        if (now - lastRestartTime < MIN_RESTART_INTERVAL_MS) {
            scope.launch {
                delay(MIN_RESTART_INTERVAL_MS - (now - lastRestartTime))
                finalizeAccumulation()
            }
            return
        }

        isAccumulatingAfterInterrupt = false
        isBargeInTurn = false
        interruptAccumulationJob?.cancel()

        scope.launch {
            delay(200L)
            stateManager.setListening(false)
            stateManager.setHearingSpeech(false)
            stateManager.setTranscribing(false)

            // Collect any remaining audio before clearing STT
            val stt = getStt()
            if (stt is GeminiLiveSttController) {
                val finalSegments = stt.getCollectedAudioSegments()
                if (finalSegments.isNotEmpty()) {
                    accumulatedAudioSegments.addAll(finalSegments)
                    stt.clearCollectedAudio()
                }
                stt.switchMicMode(com.example.advancedvoice.core.audio.MicrophoneSession.Mode.MONITORING)
            }
        }

        if (accumulatedText.isNotEmpty()) {
            Log.i(TAG, "[ACCUMULATION] Restarting turn with accumulated text and ${accumulatedAudioSegments.size} audio segments")
            lastRestartTime = now

            // Note: We already collected the audio above in the scope.launch block,
            // but we do it safely inside the lock logic.
            // The audio is now fully in accumulatedAudioSegments.

            engine.replaceLastUserMessage(accumulatedText)
            startTurnWithExistingHistory()
        } else {
            stateManager.removeLastUserPlaceholderIfEmpty()
        }
    }

    /**
     * Get all accumulated audio segments for interruption handling.
     */
    fun getCombinedAudioSegments(): List<ByteArray> {
        return accumulatedAudioSegments.toList()
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
        Log.d(TAG, "[RESET] Resetting interruption state")
        interruptAccumulationJob?.cancel()
        isEvaluatingBargeIn = false
        isBargeInTurn = false
        isAccumulatingAfterInterrupt = false
        assistantHoldBuffer = null
        assistantSourcesBuffer = null
        interruptedDuringEvaluation = false
        accumulatedAudioSegments.clear() // Clear audio
        scope.launch {
            stateManager.setListening(false)
            stateManager.setHearingSpeech(false)
            stateManager.setTranscribing(false)
        }
    }

    fun handleTimeoutDuringEvaluation() {
        if (isEvaluatingBargeIn) {
            Log.i(TAG, "[TIMEOUT] Treating as noise")
            handleConfirmedNoise()
        }
    }
}