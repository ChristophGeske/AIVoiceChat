package com.example.advancedvoice.feature.conversation.presentation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.advancedvoice.core.audio.VadRecorder
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.network.HttpClientProvider
import com.example.advancedvoice.core.prefs.Prefs
import com.example.advancedvoice.core.prefs.SttSystem
import com.example.advancedvoice.core.text.SentenceSplitter
import com.example.advancedvoice.core.util.PerfTimer
import com.example.advancedvoice.data.gemini_live.GeminiLiveClient
import com.example.advancedvoice.data.gemini_live.GeminiLiveTranscriber
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.domain.entities.ConversationEntry
import com.example.advancedvoice.feature.conversation.service.ConversationStore
import com.example.advancedvoice.feature.conversation.service.GeminiLiveSttController
import com.example.advancedvoice.feature.conversation.service.StandardSttController
import com.example.advancedvoice.feature.conversation.service.SttController
import com.example.advancedvoice.feature.conversation.service.TtsController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val http: OkHttpClient = HttpClientProvider.client
    private val appCtx = app.applicationContext
    private val TAG = LoggerConfig.TAG_VM

    private val store = ConversationStore()
    val conversation: StateFlow<List<ConversationEntry>> = store.state

    private val tts = TtsController(app, viewModelScope)
    val isSpeaking: StateFlow<Boolean> = tts.isSpeaking

    private var stt: SttController? = null
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening
    private val _isHearingSpeech = MutableStateFlow(false)
    val isHearingSpeech: StateFlow<Boolean> = _isHearingSpeech
    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing
    private val _phase = MutableStateFlow(GenerationPhase.IDLE)
    val phase: StateFlow<GenerationPhase> = _phase

    val controls: StateFlow<ControlsState> =
        combine(isSpeaking, isListening, isHearingSpeech, isTranscribing, phase) { speaking, listening, hearing, transcribing, p ->
            ControlsLogic.derive(speaking, listening, hearing, transcribing, p)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ControlsLogic.derive(false, false, false, false, GenerationPhase.IDLE)
        )

    private var llmPerf: PerfTimer? = null
    @Volatile private var isBargeInTurn = false

    private val engine: SentenceTurnEngine = SentenceTurnEngine(
        uiScope = viewModelScope,
        http = http,
        geminiKeyProvider = { Prefs.getGeminiKey(appCtx) },
        openAiKeyProvider = { Prefs.getOpenAiKey(appCtx) },
        systemPromptProvider = { getEffectiveSystemPrompt() },
        callbacks = SentenceTurnEngine.Callbacks(
            onStreamDelta = {},
            onStreamSentence = {},
            onFirstSentence = { text ->
                Log.i(TAG, "[ENGINE_CB] onFirstSentence received (len=${text.length})")
                _phase.value = GenerationPhase.GENERATING_REMAINDER
                store.addAssistant(listOf(text))
                tts.queue(text)
            },
            onRemainingSentences = { sentences ->
                Log.i(TAG, "[ENGINE_CB] onRemainingSentences received (count=${sentences.size})")
                _phase.value = GenerationPhase.IDLE

                val idx = conversation.value.indexOfLast { it.isAssistant }
                if (idx >= 0 && sentences.isNotEmpty()) {
                    val max = Prefs.getMaxSentences(appCtx)
                    val current = conversation.value[idx].sentences
                    val combined = (current + sentences).take(max)
                    val delta = combined.drop(current.size)
                    if (delta.isNotEmpty()) {
                        store.appendAssistantSentences(idx, delta)
                        tts.queue(delta.joinToString(" "))
                    }
                }
            },
            onFinalResponse = { full ->
                llmPerf?.mark("llm_done")
                llmPerf?.logSummary("llm_start", "llm_done")
                Log.i(TAG, "[ENGINE_CB] onFinalResponse received (len=${full.length})")
                _phase.value = GenerationPhase.IDLE
                val maxSentences = Prefs.getMaxSentences(appCtx)
                val sentences = SentenceSplitter.splitIntoSentences(full).take(maxSentences)
                if (sentences.isNotEmpty()) {
                    store.addAssistant(sentences)
                    tts.queue(sentences.joinToString(" "))
                }
            },
            onTurnFinish = {
                Log.i(TAG, "[ENGINE_CB] onTurnFinish received.")
                llmPerf = null
                _phase.value = GenerationPhase.IDLE
            },
            onSystem = { msg -> store.addSystem(msg) },
            onError = { msg ->
                llmPerf?.mark("llm_done")
                llmPerf?.logSummary("llm_start", "llm_done")
                _phase.value = GenerationPhase.IDLE
                store.addError(msg)
            }
        )
    )

    init {
        setupSttSystemFromPreferences()

        // AUTO-LISTEN: After TTS finishes speaking
        viewModelScope.launch {
            tts.isSpeaking
                .drop(1)
                .filter { !it }
                .collect {
                    val autoListenEnabled = Prefs.getAutoListen(appCtx)
                    if (!autoListenEnabled) {
                        Log.d(TAG, "[AUTO-LISTEN] Feature disabled in settings.")
                        return@collect
                    }

                    if (phase.value == GenerationPhase.IDLE && !isListening.value) {
                        val delayMs = 750L
                        Log.i(TAG, "[AUTO-LISTEN] TTS stopped. Waiting ${delayMs}ms before listening...")
                        delay(delayMs)

                        if (!isSpeaking.value && !isListening.value && phase.value == GenerationPhase.IDLE) {
                            Log.i(TAG, "[AUTO-LISTEN] Conditions met. Starting listening.")

                            if (isListening.value || isHearingSpeech.value) {
                                Log.w(TAG, "[AUTO-LISTEN] Already listening, ignoring.")
                            } else {
                                isBargeInTurn = false
                                store.addUserStreamingPlaceholder()
                                viewModelScope.launch {
                                    try {
                                        stt?.start(isAutoListen = true)
                                    } catch (se: SecurityException) {
                                        store.addError("Microphone permission denied")
                                    }
                                }
                            }
                        } else {
                            Log.w(TAG, "[AUTO-LISTEN] Conditions changed, aborting.")
                        }
                    } else {
                        Log.d(TAG, "[AUTO-LISTEN] Skipped: phase=${phase.value}, isListening=${isListening.value}")
                    }
                }
        }

        // ✅ VOICE-ACTIVATED INTERRUPTION: Start background listening during generation (BOTH STT systems)
        viewModelScope.launch {
            combine(phase, isHearingSpeech, isSpeaking) { currentPhase, hearingSpeech, speaking ->
                Triple(currentPhase, hearingSpeech, speaking)
            }.collect { (currentPhase, hearingSpeech, speaking) ->
                val isGenerating = currentPhase == GenerationPhase.GENERATING_FIRST ||
                        currentPhase == GenerationPhase.SINGLE_SHOT_GENERATING

                // ✅ REMOVED: stt is GeminiLiveSttController check
                // Start background listening when generation begins (works for both STT systems)
                if (isGenerating && !isListening.value && !speaking) {
                    if (hasRecordAudioPermission()) {
                        Log.i(TAG, "[VOICE-INTERRUPT] Generation started, enabling background voice detection...")
                        viewModelScope.launch {
                            try {
                                stt?.start(isAutoListen = true)
                            } catch (se: SecurityException) {
                                Log.w(TAG, "[VOICE-INTERRUPT] Mic permission denied, voice interruption disabled")
                            }
                        }
                    } else {
                        Log.d(TAG, "[VOICE-INTERRUPT] No mic permission, voice interruption disabled")
                    }
                }

                // Detect when user starts speaking during generation
                if (isGenerating && hearingSpeech && !isBargeInTurn) {
                    Log.i(TAG, "[VOICE-INTERRUPT] User started speaking during generation! Triggering interruption...")
                    isBargeInTurn = true
                    viewModelScope.launch {
                        engine.abort(true)
                        tts.stop()
                        // Don't stop STT - let it finish capturing the user's speech
                    }
                }
            }
        }

        // ✅ CLEANUP: Stop background listening when generation ends naturally
        viewModelScope.launch {
            phase.collect { currentPhase ->
                // ✅ REMOVED: stt is GeminiLiveSttController check
                if (currentPhase == GenerationPhase.IDLE &&
                    isListening.value &&
                    !isBargeInTurn) {

                    // Only stop if no speech was detected
                    if (!isHearingSpeech.value) {
                        Log.d(TAG, "[VOICE-INTERRUPT] Generation ended naturally, stopping background listener")
                        viewModelScope.launch {
                            stt?.stop(false)
                        }
                    }
                }
            }
        }

        applyEngineSettings()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun setupSttSystemFromPreferences() {
        val sttPref = Prefs.getSttSystem(appCtx)
        val geminiKey = Prefs.getGeminiKey(appCtx)
        Log.i(TAG, "[ViewModel] setupSttSystemFromPreferences: stt=$sttPref, hasGeminiKey=${geminiKey.isNotBlank()}")

        val shouldUseGeminiLive = sttPref == SttSystem.GEMINI_LIVE && geminiKey.isNotBlank()
        val currentlyUsingGeminiLive = stt is GeminiLiveSttController

        if (stt != null && currentlyUsingGeminiLive == shouldUseGeminiLive) {
            Log.d(TAG, "[ViewModel] STT system is already correctly configured.")
            return
        }

        Log.i(TAG, "[ViewModel] Need to reconfigure STT. Current: ${stt?.javaClass?.simpleName ?: "null"}, want GeminiLive=$shouldUseGeminiLive")

        stt?.release()

        stt = if (shouldUseGeminiLive) {
            Log.i(TAG, "[ViewModel] Creating and wiring GeminiLiveSttController.")
            val liveModel = "models/gemini-2.5-flash-native-audio-preview-09-2025"

            val listenSeconds = Prefs.getListenSeconds(appCtx)
            val maxSilenceMs = (listenSeconds * 1000L).coerceIn(3000L, 30000L)

            val vad = VadRecorder(
                scope = viewModelScope,
                endOfSpeechMs = 1500L,
                maxSilenceMs = maxSilenceMs
            )

            val client = GeminiLiveClient(scope = viewModelScope)
            val transcriber = GeminiLiveTranscriber(scope = viewModelScope, client = client)
            GeminiLiveSttController(viewModelScope, vad, client, transcriber, geminiKey, liveModel)
        } else {
            Log.i(TAG, "[ViewModel] Creating and wiring StandardSttController.")
            StandardSttController(getApplication(), viewModelScope)
        }

        Log.i(TAG, "[ViewModel] STT configured: ${stt?.javaClass?.simpleName}")

        rewireSttCollectors()
        applyEngineSettings()
    }

    private fun applyEngineSettings() {
        val fasterFirst = Prefs.getFasterFirst(appCtx)
        val maxSentences = Prefs.getMaxSentences(appCtx)
        Log.i(TAG, "[ViewModel] Applying engine settings: fasterFirst=$fasterFirst, maxSentences=$maxSentences")
        engine.setFasterFirst(fasterFirst)
        engine.setMaxSentences(maxSentences)
    }

    private fun rewireSttCollectors() {
        val current = stt ?: return
        Log.d(TAG, "[ViewModel] Rewiring STT collectors for ${current.javaClass.simpleName}")

        viewModelScope.launch {
            current.transcripts.collectLatest { onFinalTranscript(it) }
        }

        if (current is GeminiLiveSttController) {
            viewModelScope.launch {
                current.partialTranscripts.collectLatest { partialText ->
                    store.updateLastUserStreamingText(partialText)
                }
            }
        }

        viewModelScope.launch { current.errors.collectLatest { store.addError(it) } }
        viewModelScope.launch { current.isListening.collectLatest { _isListening.value = it } }
        viewModelScope.launch { current.isHearingSpeech.collectLatest { _isHearingSpeech.value = it } }
        viewModelScope.launch { current.isTranscribing.collectLatest { _isTranscribing.value = it } }
    }

    fun startListening() {
        Log.i(TAG, "[ViewModel] startListening() called. isGenerating=${engine.isActive()}, isTalking=${isSpeaking.value}")

        when {
            engine.isActive() || isSpeaking.value -> handleTapInterruption()
            else -> startNormalListening()
        }
    }

    private fun startNormalListening() {
        if (isListening.value || isHearingSpeech.value) {
            Log.w(TAG, "[ViewModel] Already listening, ignoring startNormalListening call.")
            return
        }
        Log.i(TAG, "[ViewModel] Starting a normal listening session.")

        // ✅ Only add placeholder if this is NOT a voice interruption
        if (!isBargeInTurn) {
            store.addUserStreamingPlaceholder()
        }

        viewModelScope.launch {
            try {
                stt?.start(isAutoListen = false)
            } catch (se: SecurityException) {
                Log.e(TAG, "[ViewModel] SecurityException caught", se)
                store.addError("Microphone permission denied")
            } catch (e: Exception) {
                Log.e(TAG, "[ViewModel] Unexpected exception in start()", e)
                store.addError("STT error: ${e.message}")
            }
        }
    }

    private fun handleTapInterruption() {
        Log.i(TAG, "[INTERRUPT-TAP] User tapped to interrupt.")
        isBargeInTurn = true

        viewModelScope.launch {
            stopAllSuspendable()
            startNormalListening()
        }
    }

    fun stopAll() {
        viewModelScope.launch { stopAllSuspendable() }
    }

    private suspend fun stopAllSuspendable() {
        Log.w(TAG, "[ViewModel] stopAllSuspendable() called - HARD STOP initiated.")
        engine.abort(true)
        tts.stop()
        stt?.stop(false)
        _phase.value = GenerationPhase.IDLE
        // ✅ Don't reset isBargeInTurn here - let onFinalTranscript handle it
        Log.w(TAG, "[ViewModel] stopAllSuspendable() complete. System is now idle.")
    }

    private fun onFinalTranscript(text: String) {
        val input = text.trim()
        if (input.isEmpty()) {
            Log.w(TAG, "[ViewModel] onFinalTranscript received empty text. Ignoring.")
            return
        }

        Log.i(TAG, "[ViewModel] Final Transcript='${input.take(120)}'")

        // ✅ Auto-detect if this was a voice interruption (transcript arrived during generation)
        val wasGenerating = engine.isActive()
        if (wasGenerating && !isBargeInTurn) {
            Log.i(TAG, "[VOICE-INTERRUPT] Transcript arrived during generation - auto-detected as interruption")
            isBargeInTurn = true
        }

        if (isBargeInTurn) {
            Log.i(TAG, "[ViewModel] This was an interruption transcript. Combining...")
            isBargeInTurn = false
            handleInterruptionContinuation(input)
        } else {
            Log.i(TAG, "[ViewModel] This is a normal turn transcript.")
            store.replaceLastUser(input)
            startTurn(input)
        }
    }

    private fun handleInterruptionContinuation(newInput: String) {
        val lastUserEntry = conversation.value.lastOrNull { it.speaker == "You" }
        val previousInput = lastUserEntry?.sentences?.joinToString(" ")?.trim() ?: ""

        val combinedInput = if (previousInput.isNotBlank()) {
            "$previousInput $newInput"
        } else {
            newInput
        }

        Log.i(TAG, "[ViewModel] Combined interruption input (len=${combinedInput.length}): '${combinedInput.take(100)}...'")

        store.replaceLastUser(combinedInput)
        engine.replaceLastUserMessage(combinedInput)
        startTurnWithExistingHistory()
    }

    private fun startTurn(userText: String) {
        _phase.value = if (Prefs.getFasterFirst(appCtx)) GenerationPhase.GENERATING_FIRST else GenerationPhase.SINGLE_SHOT_GENERATING
        val model = Prefs.getSelectedModel(appCtx)
        Log.i(TAG, "[ViewModel] startTurn(model=$model) inputLen=${userText.length}")
        llmPerf = PerfTimer(TAG, "llm").also { it.mark("llm_start") }
        engine.startTurn(userText, model)
    }

    private fun startTurnWithExistingHistory() {
        _phase.value = if (Prefs.getFasterFirst(appCtx)) GenerationPhase.GENERATING_FIRST else GenerationPhase.SINGLE_SHOT_GENERATING
        val model = Prefs.getSelectedModel(appCtx)
        Log.i(TAG, "[ViewModel] startTurnWithExistingHistory(model=$model)")
        llmPerf = PerfTimer(TAG, "llm").also { it.mark("llm_start") }
        engine.startTurnWithCurrentHistory(model)
    }

    override fun onCleared() {
        super.onCleared()
        Log.w(TAG, "[ViewModel] onCleared() - ViewModel is being destroyed.")
        stt?.release()
        tts.shutdown()
    }

    fun clearConversation() {
        engine.abort(true)
        store.clear()
        tts.stop()
        _phase.value = GenerationPhase.IDLE
        viewModelScope.launch { stt?.stop(false) }
    }

    private fun getEffectiveSystemPrompt(): String {
        val base = Prefs.getSystemPrompt(appCtx, DEFAULT_SYSTEM_PROMPT)
        val extensions = Prefs.getSystemPromptExtensions(appCtx)
        return if (extensions.isBlank()) base else "$base\n\n$extensions"
    }

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful assistant. Answer the user's request in clear, complete sentences.
            Do not use JSON or code fences unless explicitly requested.
        """
    }

    fun replayMessage(index: Int) {
        val entry = conversation.value.getOrNull(index)
        if (entry != null && entry.isAssistant) {
            val text = entry.sentences.joinToString(" ")
            Log.i(TAG, "[ViewModel] Replaying assistant message (index=$index, len=${text.length})")
            tts.queue(text)
        } else {
            Log.w(TAG, "[ViewModel] replayMessage($index) - not an assistant message or invalid index")
        }
    }
}