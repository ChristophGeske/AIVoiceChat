package com.example.advancedvoice.feature.conversation.presentation

import android.app.Application
import android.util.Log
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
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
                _phase.value = GenerationPhase.IDLE
            },
            onFinalResponse = { full ->
                llmPerf?.mark("llm_done")
                llmPerf?.logSummary("llm_start", "llm_done")
                Log.i(TAG, "[ENGINE_CB] onFinalResponse received (len=${full.length})")
                val maxSentences = Prefs.getMaxSentences(appCtx)
                val sentences = SentenceSplitter.splitIntoSentences(full).take(maxSentences)
                Log.d(TAG, "[ENGINE_CB] Split into ${sentences.size} sentences (max=$maxSentences).")
                if (sentences.isNotEmpty()) {
                    store.addAssistant(sentences)
                    val textToSpeak = sentences.joinToString(" ")
                    tts.queue(textToSpeak)
                } else {
                    Log.w(TAG, "[ENGINE_CB] Final response was empty after splitting.")
                }
                _phase.value = GenerationPhase.IDLE
            },
            onTurnFinish = {
                Log.i(TAG, "[ENGINE_CB] onTurnFinish received.")
                llmPerf = null
            },
            onSystem = { msg -> store.addSystem(msg) },
            onError = { msg ->
                llmPerf?.mark("llm_done")
                llmPerf?.logSummary("llm_start", "llm_done")
                store.addError(msg)
                _phase.value = GenerationPhase.IDLE
            }
        )
    )

    init {
        viewModelScope.launch {
            tts.queueFinished.collect {
                if (Prefs.getAutoListen(appCtx)) {
                    val delayMs = 1500L
                    Log.i(TAG, "[AUTO-LISTEN] TTS queue finished. Waiting ${delayMs}ms to restart listening.")
                    delay(delayMs)
                    if (!isSpeaking.value && !isListening.value && phase.value == GenerationPhase.IDLE) {
                        Log.i(TAG, "[AUTO-LISTEN] Conditions met. Starting listening now.")
                        startListening()
                    } else {
                        Log.w(TAG, "[AUTO-LISTEN] Conditions changed, aborting auto-listen.")
                    }
                } else {
                    Log.i(TAG, "[AUTO-LISTEN] Auto-listen is disabled in preferences.")
                }
            }
        }

        // FIX: Apply engine settings from preferences
        applyEngineSettings()
    }

    fun setupSttSystemFromPreferences() {
        val sttPref = Prefs.getSttSystem(appCtx)
        val geminiKey = Prefs.getGeminiKey(appCtx)
        Log.i(TAG, "[ViewModel] setupSttSystemFromPreferences: stt=$sttPref, hasGeminiKey=${geminiKey.isNotBlank()}")

        val shouldUseGeminiLive = sttPref == SttSystem.GEMINI_LIVE && geminiKey.isNotBlank()

        val currentControllerIsLive = stt is GeminiLiveSttController
        if (shouldUseGeminiLive && currentControllerIsLive) {
            Log.d(TAG, "[ViewModel] Already using GeminiLiveSttController. No change needed.")
            return
        }
        val currentControllerIsStandard = stt is StandardSttController
        if (!shouldUseGeminiLive && currentControllerIsStandard) {
            Log.d(TAG, "[ViewModel] Already using StandardSttController. No change needed.")
            return
        }

        stt?.release()

        stt = if (shouldUseGeminiLive) {
            Log.i(TAG, "[ViewModel] Creating and wiring GeminiLiveSttController.")
            val liveModel = "models/gemini-2.5-flash-native-audio-preview-09-2025"
            val vad = VadRecorder(scope = viewModelScope)
            val client = GeminiLiveClient(scope = viewModelScope)
            val transcriber = GeminiLiveTranscriber(scope = viewModelScope, client = client)
            GeminiLiveSttController(viewModelScope, vad, client, transcriber, geminiKey, liveModel)
        } else {
            Log.i(TAG, "[ViewModel] Creating and wiring StandardSttController.")
            StandardSttController(getApplication(), viewModelScope)
        }

        rewireSttCollectors()

        // FIX: Reapply engine settings after STT changes
        applyEngineSettings()
    }

    // FIX: New method to apply engine configuration from preferences
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
        Log.i(TAG, "[ViewModel] startListening() called.")

        // FIX: Handle barge-in during generation
        if (engine.isActive()) {
            Log.w(TAG, "[ViewModel] BARGE-IN detected: User speaking during generation. Aborting current turn.")
            handleBargeIn()
            return
        }

        if (stt is GeminiLiveSttController) {
            store.addUserStreamingPlaceholder()
        }
        val s = stt ?: return
        viewModelScope.launch { s.start() }
    }

    // FIX: Handle interruption/barge-in
    private fun handleBargeIn() {
        Log.i(TAG, "[ViewModel] handleBargeIn: Stopping generation and TTS, preparing for new input.")

        // Get the partial assistant response if any
        val lastAssistantEntry = conversation.value.lastOrNull { it.isAssistant }
        val partialResponse = lastAssistantEntry?.sentences?.joinToString(" ") ?: ""

        if (partialResponse.isNotBlank()) {
            Log.i(TAG, "[ViewModel] Saving partial assistant response to history (len=${partialResponse.length})")
            engine.injectAssistantDraft(partialResponse)
        }

        // Abort generation and TTS
        engine.abort(silent = true)
        tts.stop()
        _phase.value = GenerationPhase.IDLE

        // Start listening for the new/additional input
        if (stt is GeminiLiveSttController) {
            store.addUserStreamingPlaceholder()
        }
        val s = stt ?: return
        viewModelScope.launch { s.start() }
    }

    fun stopListening(transcribe: Boolean) {
        Log.i(TAG, "[ViewModel] stopListening(transcribe=$transcribe) called.")
        val s = stt ?: return
        viewModelScope.launch { s.stop(transcribe) }
    }

    fun clearConversation() {
        Log.i(TAG, "[ViewModel] clearConversation() called.")
        store.clear()
        tts.stop()
        engine.clearHistory()
    }

    fun stopAll() {
        Log.w(TAG, "[ViewModel] stopAll() called - HARD STOP.")
        engine.abort(silent = false)
        stopListening(transcribe = false)
        tts.stop()
        _phase.value = GenerationPhase.IDLE
    }

    private fun onFinalTranscript(text: String) {
        val input = text.trim()

        if (input.isEmpty()) {
            Log.w(TAG, "[ViewModel] onFinalTranscript received empty text. Stopping and aborting turn.")
            stopListening(transcribe = false)
            return
        }

        Log.i(TAG, "[ViewModel] Final Transcript='${input.take(120)}'")

        // FIX: Check if this is a barge-in continuation
        if (engine.isActive()) {
            Log.i(TAG, "[ViewModel] Barge-in continuation: combining with previous input.")
            handleBargeInContinuation(input)
        } else {
            store.replaceLastUser(input)
            startTurn(input)
        }
    }

    // FIX: Handle the new input when user interrupted
    private fun handleBargeInContinuation(newInput: String) {
        // Find the last user message and combine
        val lastUserEntry = conversation.value.lastOrNull { !it.isAssistant && it.speaker == "You" }
        val previousInput = lastUserEntry?.sentences?.joinToString(" ") ?: ""

        val combinedInput = if (previousInput.isNotBlank()) {
            "$previousInput $newInput"
        } else {
            newInput
        }

        Log.i(TAG, "[ViewModel] Combined barge-in input (len=${combinedInput.length}): '${combinedInput.take(100)}...'")

        // Replace the user message with the combined version
        store.replaceLastUser(combinedInput)

        // Update the engine's history with the combined message
        engine.replaceLastUserMessage(combinedInput)

        // Start a new turn with the combined input
        startTurnWithExistingHistory()
    }

    private fun startTurn(userText: String) {
        val fasterFirst = Prefs.getFasterFirst(appCtx)
        _phase.value = if (fasterFirst) GenerationPhase.GENERATING_FIRST else GenerationPhase.SINGLE_SHOT_GENERATING
        val model = Prefs.getSelectedModel(appCtx)
        Log.i(TAG, "[ViewModel] startTurn(model=$model, fasterFirst=$fasterFirst) inputLen=${userText.length}")
        llmPerf = PerfTimer(TAG, "llm").also { it.mark("llm_start") }

        // FIX: Ensure engine settings are current
        applyEngineSettings()

        engine.startTurn(userText, model)
    }

    // FIX: Start turn using existing history (for barge-in)
    private fun startTurnWithExistingHistory() {
        val fasterFirst = Prefs.getFasterFirst(appCtx)
        _phase.value = if (fasterFirst) GenerationPhase.GENERATING_FIRST else GenerationPhase.SINGLE_SHOT_GENERATING
        val model = Prefs.getSelectedModel(appCtx)
        Log.i(TAG, "[ViewModel] startTurnWithExistingHistory(model=$model, fasterFirst=$fasterFirst)")
        llmPerf = PerfTimer(TAG, "llm").also { it.mark("llm_start") }

        // FIX: Ensure engine settings are current
        applyEngineSettings()

        engine.startTurnWithCurrentHistory(model)
    }

    private fun getEffectiveSystemPrompt(): String {
        val base = Prefs.getSystemPrompt(appCtx, DEFAULT_SYSTEM_PROMPT)
        val extensions = Prefs.getSystemPromptExtensions(appCtx)
        return if (extensions.isBlank()) base else "$base\n\n$extensions"
    }

    override fun onCleared() {
        super.onCleared()
        Log.w(TAG, "[ViewModel] onCleared() - ViewModel is being destroyed.")
        stt?.release()
        tts.shutdown()
    }

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful assistant. Answer the user's request in clear, complete sentences.
            Do not use JSON or code fences unless explicitly requested.
        """
    }
}