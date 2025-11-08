package com.example.advancedvoice.feature.conversation.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.advancedvoice.core.audio.MicrophoneSession
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
import com.example.advancedvoice.feature.conversation.service.GeminiLiveSttController
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
        private const val TAG_AUTO_LISTEN = "AutoListen"
        private const val TAG_INIT = "VM_Init"

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
        logInitialConfiguration()
        setupTtsMonitoring()
    }

    private fun logInitialConfiguration() {
        viewModelScope.launch {
            Log.i(TAG_INIT, "═══════════════════════════════════════")
            Log.i(TAG_INIT, "Conversation ViewModel Initialized")
            Log.i(TAG_INIT, "STT Type: ${sttManager.getStt()?.javaClass?.simpleName ?: "null"}")
            Log.i(TAG_INIT, "Selected Model: ${Prefs.getSelectedModel(appCtx)}")
            Log.i(TAG_INIT, "Faster First: ${Prefs.getFasterFirst(appCtx)}")
            Log.i(TAG_INIT, "Auto-Listen: ${Prefs.getAutoListen(appCtx)}")
            Log.i(TAG_INIT, "Max Sentences: ${Prefs.getMaxSentences(appCtx)}")
            Log.i(TAG_INIT, "System Prompt Length: ${getEffectiveSystemPrompt().length}")
            Log.i(TAG_INIT, "═══════════════════════════════════════")
        }
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
        Log.i(TAG_AUTO_LISTEN, "✅ Flag ENABLED for next TTS completion (TTS state: ${isSpeaking.value})")
    }

    private fun disableAutoListenAfterTts() {
        if (shouldAutoListenAfterTts) {
            Log.w(TAG_AUTO_LISTEN, "❌ Flag DISABLED (was enabled)")
        }
        shouldAutoListenAfterTts = false
    }

    private fun setupAutoListen() {
        viewModelScope.launch {
            tts.isSpeaking.drop(1).filter { !it }.collect {
                val autoListenFlag = shouldAutoListenAfterTts
                val settingEnabled = Prefs.getAutoListen(appCtx)
                val currentPhase = phase.value
                val listening = isListening.value
                val speaking = isSpeaking.value

                Log.i(TAG_AUTO_LISTEN, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.i(TAG_AUTO_LISTEN, "TTS STOPPED - Auto-Listen Check")
                Log.i(TAG_AUTO_LISTEN, "  Flag: $autoListenFlag")
                Log.i(TAG_AUTO_LISTEN, "  Setting: $settingEnabled")
                Log.i(TAG_AUTO_LISTEN, "  Phase: $currentPhase")
                Log.i(TAG_AUTO_LISTEN, "  Listening: $listening")
                Log.i(TAG_AUTO_LISTEN, "  Speaking: $speaking")

                if (!shouldAutoListenAfterTts) {
                    Log.d(TAG_AUTO_LISTEN, "❌ Flag disabled, skipping")
                    return@collect
                }

                if (!settingEnabled) {
                    Log.w(TAG_AUTO_LISTEN, "❌ Setting disabled, clearing flag")
                    shouldAutoListenAfterTts = false
                    return@collect
                }

                if (phase.value == GenerationPhase.IDLE && !isListening.value) {
                    // --- THE FIX ---
                    // 500ms is too long and creates a race condition with user noise.
                    // A very short delay is enough to let the state settle.
                    Log.i(TAG_AUTO_LISTEN, "⏳ Conditions met, waiting 150ms...")
                    delay(150L) // Changed from 500L

                    val stillIdle = !isSpeaking.value &&
                            !isListening.value &&
                            phase.value == GenerationPhase.IDLE &&
                            shouldAutoListenAfterTts

                    Log.i(TAG_AUTO_LISTEN, "After delay:")
                    Log.i(TAG_AUTO_LISTEN, "  Speaking: ${isSpeaking.value}")
                    Log.i(TAG_AUTO_LISTEN, "  Listening: ${isListening.value}")
                    Log.i(TAG_AUTO_LISTEN, "  Phase: ${phase.value}")
                    Log.i(TAG_AUTO_LISTEN, "  Flag: $shouldAutoListenAfterTts")
                    Log.i(TAG_AUTO_LISTEN, "  Still Idle: $stillIdle")

                    if (stillIdle) {
                        Log.i(TAG_AUTO_LISTEN, "✅✅✅ STARTING AUTO-LISTEN NOW ✅✅✅")
                        flowController.startAutoListening()
                    } else {
                        Log.w(TAG_AUTO_LISTEN, "❌ Conditions changed, aborting")
                    }
                } else {
                    Log.w(TAG_AUTO_LISTEN, "❌ Conditions not met")
                }

                shouldAutoListenAfterTts = false
                Log.d(TAG_AUTO_LISTEN, "Flag reset to false")
                Log.i(TAG_AUTO_LISTEN, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
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
        Log.d(TAG, "[SYSTEM-PROMPT] Base: ${base.length} chars, Extensions: ${extensions.length} chars")
        return if (extensions.isBlank()) base else "$base\n\n$extensions"
    }

    fun setupSttSystemFromPreferences() {
        Log.i(TAG, "[CONFIG] Setting up STT from preferences")
        sttManager.setupFromPreferences()
        applyEngineSettings()
        logInitialConfiguration()
    }

    fun startListening() {
        flowController.startListening()
    }

    fun stopAll() {
        disableAutoListenAfterTts()
        viewModelScope.launch {
            flowController.stopAll()
            interruption.reset()
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
        Log.w(TAG, "ViewModel clearing...")
        flowController.cleanup()
        sttManager.release()
        interruption.reset()
        tts.shutdown()
    }

    private fun setupTtsMonitoring() {
        viewModelScope.launch {
            tts.isSpeaking.collect { speaking ->
                val stt = sttManager.getStt()
                if (stt is GeminiLiveSttController) {
                    if (speaking) {
                        Log.i(TAG, "[TTS] Started speaking - switching mic to IDLE")
                        stt.switchMicMode(MicrophoneSession.Mode.IDLE)
                    } else {
                        Log.i(TAG, "[TTS] Stopped speaking - switching mic to MONITORING")
                        stt.switchMicMode(MicrophoneSession.Mode.MONITORING)
                    }
                }
            }
        }
    }
}