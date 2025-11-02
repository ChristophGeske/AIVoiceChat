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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Main ViewModel for the conversation feature.
 * Coordinates between state management, STT, interruption handling, and conversation flow.
 */
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

    // Components
    private val store = ConversationStore()
    private val tts = TtsController(app, viewModelScope)
    private val stateManager = ConversationStateManager(
        scope = viewModelScope,
        store = store,
        tts = tts
    )
    private val perfTimerHolder = EngineCallbacksFactory.PerfTimerHolder()

    // Engine
    private val engine: SentenceTurnEngine = SentenceTurnEngine(
        uiScope = viewModelScope,
        http = http,
        geminiKeyProvider = { Prefs.getGeminiKey(appCtx) },
        openAiKeyProvider = { Prefs.getOpenAiKey(appCtx) },
        systemPromptProvider = { getEffectiveSystemPrompt() },
        callbacks = EngineCallbacksFactory.create(stateManager, tts, perfTimerHolder)
    )

    // Managers (lazy initialization to break circular dependencies)
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
            startTurnWithExistingHistory = { flowController.startTurnWithExistingHistory() }
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
            }
        )
    }

    // Public API
    val conversation: StateFlow<List<ConversationEntry>> = stateManager.conversation
    val isSpeaking: StateFlow<Boolean> = stateManager.isSpeaking
    val isListening: StateFlow<Boolean> = stateManager.isListening
    val isHearingSpeech: StateFlow<Boolean> = stateManager.isHearingSpeech
    val isTranscribing: StateFlow<Boolean> = stateManager.isTranscribing
    val phase: StateFlow<GenerationPhase> = stateManager.phase
    val controls: StateFlow<ControlsState> = stateManager.controls

    init {
        // Force lazy initialization in correct order
        sttManager.setupFromPreferences()
        interruption.initialize()
        applyEngineSettings()
        setupAutoListen()
    }

    /**
     * Setup auto-listen after TTS stops.
     */
    private fun setupAutoListen() {
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
                        delay(750L)

                        if (!isSpeaking.value && !isListening.value && phase.value == GenerationPhase.IDLE) {
                            Log.i(TAG, "[AUTO-LISTEN] Conditions met. Starting auto-listening.")
                            flowController.startAutoListening()
                        } else {
                            Log.w(TAG, "[AUTO-LISTEN] Conditions changed, aborting.")
                        }
                    } else {
                        Log.d(TAG, "[AUTO-LISTEN] Skipped: phase=${phase.value}, isListening=${isListening.value}")
                    }
                }
        }
    }

    /**
     * Apply engine settings from preferences.
     */
    private fun applyEngineSettings() {
        val fasterFirst = Prefs.getFasterFirst(appCtx)
        val maxSentences = Prefs.getMaxSentences(appCtx)
        Log.i(TAG, "[ViewModel] Applying engine settings: fasterFirst=$fasterFirst, maxSentences=$maxSentences")
        engine.setFasterFirst(fasterFirst)
        engine.setMaxSentences(maxSentences)
    }

    /**
     * Get effective system prompt.
     */
    private fun getEffectiveSystemPrompt(): String {
        val base = Prefs.getSystemPrompt(appCtx, DEFAULT_SYSTEM_PROMPT)
        val extensions = Prefs.getSystemPromptExtensions(appCtx)
        return if (extensions.isBlank()) base else "$base\n\n$extensions"
    }

    // Public API methods
    fun setupSttSystemFromPreferences() {
        Log.i(TAG, "[ViewModel] setupSttSystemFromPreferences() called")
        sttManager.setupFromPreferences()
    }

    fun startListening() {
        Log.i(TAG, "[ViewModel] startListening() called from UI")
        flowController.startListening()
    }

    fun stopAll() {
        Log.w(TAG, "[ViewModel] stopAll() called from UI")
        viewModelScope.launch {
            flowController.stopAll()
        }
    }

    fun clearConversation() {
        Log.w(TAG, "[ViewModel] clearConversation() called from UI")
        flowController.clearConversation()
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

    override fun onCleared() {
        super.onCleared()
        Log.w(TAG, "[ViewModel] onCleared() - ViewModel is being destroyed.")
        flowController.cleanup()
        sttManager.release()
        tts.shutdown()
    }
}