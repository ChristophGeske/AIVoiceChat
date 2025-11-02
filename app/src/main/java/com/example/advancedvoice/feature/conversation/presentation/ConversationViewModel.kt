package com.example.advancedvoice.feature.conversation.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.network.HttpClientProvider
import com.example.advancedvoice.core.prefs.Prefs
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.domain.entities.ConversationEntry
import com.example.advancedvoice.feature.conversation.presentation.engine.EngineCallbacksFactory
import com.example.advancedvoice.feature.conversation.presentation.flow.ConversationFlowController
import com.example.advancedvoice.feature.conversation.presentation.interruption.InterruptionManager
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.presentation.stt.SttManager
import com.example.advancedvoice.feature.conversation.service.ConversationStore
import com.example.advancedvoice.feature.conversation.service.TtsController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = LoggerConfig.TAG_VM
        private const val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful assistant. Answer the user's request in clear, complete sentences.
            Do not use JSON or code fences unless explicitly requested.
        """
    }

    private val http: OkHttpClient = HttpClientProvider.client
    private val appCtx = app.applicationContext

    private val store = ConversationStore()
    private val tts = TtsController(app, viewModelScope)
    private val stateManager = ConversationStateManager(scope = viewModelScope, store = store, tts = tts)
    private val perfTimerHolder = EngineCallbacksFactory.PerfTimerHolder()

    @Volatile private var shouldAutoListenAfterTts = false

    private val engine: SentenceTurnEngine = SentenceTurnEngine(
        uiScope = viewModelScope,
        http = http,
        geminiKeyProvider = { Prefs.getGeminiKey(appCtx) },
        openAiKeyProvider = { Prefs.getOpenAiKey(appCtx) },
        systemPromptProvider = { getEffectiveSystemPrompt() },
        callbacks = EngineCallbacksFactory.create(stateManager, tts, perfTimerHolder)
    )

    // ✅ REVERTED: Back to a single, unified STT Manager.
    private val sttManager: SttManager by lazy {
        SttManager(
            app = app,
            scope = viewModelScope,
            stateManager = stateManager,
            onFinalTranscript = { flowController.onFinalTranscript(it) }
        )
    }

    private val interruption: InterruptionManager by lazy {
        InterruptionManager(
            scope = viewModelScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            getStt = { sttManager.getStt() },
            startTurnWithExistingHistory = { flowController.startTurnWithExistingHistory() },
            onInterruption = { disableAutoListenAfterTts() }
        )
    }

    private val flowController: ConversationFlowController by lazy {
        ConversationFlowController(
            scope = viewModelScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            interruption = interruption,
            getStt = { sttManager.getStt() },
            getPrefs = object : ConversationFlowController.PrefsProvider {
                override fun getFasterFirst() = Prefs.getFasterFirst(appCtx)
                override fun getSelectedModel() = Prefs.getSelectedModel(appCtx)
                override fun getAutoListen() = Prefs.getAutoListen(appCtx)
            },
            onTurnComplete = { enableAutoListenAfterTts() },
            onTapToSpeak = { disableAutoListenAfterTts() }
        )
    }

    val conversation: StateFlow<List<ConversationEntry>> = stateManager.conversation
    val isSpeaking: StateFlow<Boolean> = stateManager.isSpeaking
    val isListening: StateFlow<Boolean> = stateManager.isListening
    val isHearingSpeech: StateFlow<Boolean> = stateManager.isHearingSpeech
    val isTranscribing: StateFlow<Boolean> = stateManager.isTranscribing
    val phase: StateFlow<GenerationPhase> = stateManager.phase
    val controls: StateFlow<ControlsState> = stateManager.controls

    init {
        sttManager.setupFromPreferences()
        interruption.initialize()
        applyEngineSettings()
        setupAutoListen()
        logButtonStateChanges()
    }

    private fun logButtonStateChanges() {
        viewModelScope.launch {
            controls.map { it.speakButtonText }.distinctUntilChanged().collect { buttonText ->
                Log.i(TAG, "[UI BUTTON] Text -> \"$buttonText\"")
            }
        }
    }

    private fun enableAutoListenAfterTts() {
        shouldAutoListenAfterTts = true
        Log.d(TAG, "[AUTO-LISTEN] Auto-listen enabled for next TTS completion")
    }

    private fun disableAutoListenAfterTts() {
        if (shouldAutoListenAfterTts) {
            Log.w(TAG, "[AUTO-LISTEN] Auto-listen DISABLED (user action or interruption)")
        }
        shouldAutoListenAfterTts = false
    }

    private fun setupAutoListen() {
        viewModelScope.launch {
            tts.isSpeaking.drop(1).filter { !it }.collect {
                if (!shouldAutoListenAfterTts) {
                    return@collect
                }
                val autoListenEnabledInSettings = Prefs.getAutoListen(appCtx)
                if (!autoListenEnabledInSettings) {
                    shouldAutoListenAfterTts = false
                    return@collect
                }
                if (phase.value == GenerationPhase.IDLE && !isListening.value) {
                    delay(500L)
                    if (!isSpeaking.value && !isListening.value && phase.value == GenerationPhase.IDLE && shouldAutoListenAfterTts) {
                        flowController.startAutoListening()
                    }
                }
                shouldAutoListenAfterTts = false
            }
        }
    }

    private fun applyEngineSettings() {
        val fasterFirst = Prefs.getFasterFirst(appCtx)
        val maxSentences = Prefs.getMaxSentences(appCtx)
        engine.setFasterFirst(fasterFirst)
        engine.setMaxSentences(maxSentences)
    }

    private fun getEffectiveSystemPrompt(): String {
        val base = Prefs.getSystemPrompt(appCtx, DEFAULT_SYSTEM_PROMPT)
        val extensions = Prefs.getSystemPromptExtensions(appCtx)
        return if (extensions.isBlank()) base else "$base\n\n$extensions"
    }

    fun setupSttSystemFromPreferences() {
        sttManager.setupFromPreferences()
        applyEngineSettings()
    }

    fun startListening() {
        flowController.startListening()
    }

    fun stopAll() {
        disableAutoListenAfterTts()
        viewModelScope.launch {
            flowController.stopAll()
            interruption.reset() // ✅ Changed from stopTemporaryListener()
        }
    }

    fun clearConversation() {
        disableAutoListenAfterTts()
        flowController.clearConversation()
    }

    fun replayMessage(index: Int) {
        val entry = conversation.value.getOrNull(index)
        if (entry != null && entry.isAssistant) {
            disableAutoListenAfterTts()
            tts.queue(entry.sentences.joinToString(" "))
        }
    }

    override fun onCleared() {
        super.onCleared()
        flowController.cleanup()
        sttManager.release()
        interruption.reset() // ✅ Changed from stopTemporaryListener()
        tts.shutdown()
    }
}