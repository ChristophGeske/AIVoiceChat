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
import com.example.advancedvoice.feature.conversation.service.TranscriptRefinementService
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

    // Refinement Service - uses Flash model for fast transcription
    private val refinementService: TranscriptRefinementService by lazy {
        TranscriptRefinementService(
            scope = viewModelScope,
            geminiKeyProvider = { Prefs.getGeminiKey(appCtx) },
            stateManager = stateManager
        )
    }

    private val engine: SentenceTurnEngine = SentenceTurnEngine(
        uiScope = viewModelScope,
        http = http,
        geminiKeyProvider = { Prefs.getGeminiKey(appCtx) },
        openAiKeyProvider = { Prefs.getOpenAiKey(appCtx) },
        systemPromptProvider = { getEffectiveSystemPrompt() },
        // ✅ UPDATED: Removed refinement-related parameters from callbacks
        callbacks = EngineCallbacksFactory.create(
            stateManager = stateManager,
            tts = tts,
            perfTimerHolder = perfTimerHolder,
            getInterruption = { interruption }
        )
    )

    private val sttManager: SttManager by lazy {
        SttManager(
            app = app,
            scope = viewModelScope,
            stateManager = stateManager,
            tts = tts,
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
            onInterruption = { disableAutoListenAfterTts() },
            onNoiseConfirmed = { enableAutoListenAfterTts() },
            getLastTurnAudio = { flowController.getLastAudioForRefinement().first }
        )
    }

    // ✅ UPDATED: FlowController now gets refinement service
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
            getRefinementService = { refinementService },  // ✅ NEW
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
            Log.i(TAG_INIT, "Conversation ViewModel Initialized")
            Log.i(TAG_INIT, "Selected Model: ${Prefs.getSelectedModel(appCtx)}")
            Log.i(TAG_INIT, "Parallel Processing: PATH A (Flash transcription) + PATH B (User model generation)")
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

                Log.i(TAG_AUTO_LISTEN, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.i(TAG_AUTO_LISTEN, "TTS STOPPED - Auto-Listen Check")
                Log.i(TAG_AUTO_LISTEN, "  Flag: $autoListenFlag")
                Log.i(TAG_AUTO_LISTEN, "  Setting: $settingEnabled")
                Log.i(TAG_AUTO_LISTEN, "  Phase: ${phase.value}")
                Log.i(TAG_AUTO_LISTEN, "  Listening: ${isListening.value}")
                Log.i(TAG_AUTO_LISTEN, "  Speaking: ${isSpeaking.value}")

                if (!shouldAutoListenAfterTts) return@collect
                if (!settingEnabled) {
                    shouldAutoListenAfterTts = false
                    return@collect
                }

                if (phase.value == GenerationPhase.IDLE && !isListening.value) {
                    Log.i(TAG_AUTO_LISTEN, "⏳ Conditions met, waiting 150ms...")
                    delay(150L)

                    val stillIdle = !isSpeaking.value && !isListening.value && phase.value == GenerationPhase.IDLE

                    Log.i(TAG_AUTO_LISTEN, "After delay:")
                    Log.i(TAG_AUTO_LISTEN, "  Speaking: ${isSpeaking.value}")
                    Log.i(TAG_AUTO_LISTEN, "  Listening: ${isListening.value}")
                    Log.i(TAG_AUTO_LISTEN, "  Phase: ${phase.value}")
                    Log.i(TAG_AUTO_LISTEN, "  Flag: $shouldAutoListenAfterTts")
                    Log.i(TAG_AUTO_LISTEN, "  Still Idle: $stillIdle")

                    if (stillIdle && shouldAutoListenAfterTts) {
                        Log.i(TAG_AUTO_LISTEN, "✅✅✅ STARTING AUTO-LISTEN NOW ✅✅✅")
                        flowController.startAutoListening()
                    }
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
        val userExtensions = Prefs.getSystemPromptExtensions(appCtx)
        val dynamicInstructions = Prefs.getDynamicInstructions(appCtx)
            .ifBlank { buildDynamicInstructions() }

        val fullPrompt = buildString {
            append(base)
            if (userExtensions.isNotBlank()) {
                append("\n\n")
                append(userExtensions)
            }
            append("\n\n")
            append(dynamicInstructions)
        }

        Log.d(TAG, "[SYSTEM-PROMPT] Base: ${base.length}, Extensions: ${userExtensions.length}, Dynamic: ${dynamicInstructions.length}")
        return fullPrompt
    }

    private fun buildDynamicInstructions(): String {
        val maxSentences = Prefs.getMaxSentences(appCtx)
        val fasterFirst = Prefs.getFasterFirst(appCtx)

        return buildString {
            append("RESPONSE REQUIREMENTS:\n")
            append("• Your response MUST be AT MOST $maxSentences sentences long.\n")
            append("• Return plain text only, no markdown or formatting.\n")
            if (fasterFirst) {
                append("• Start with a complete, informative first sentence immediately.\n")
            }
            append("• The user speaks via audio. Listen carefully and respond naturally.")
        }
    }

    fun setupSttSystemFromPreferences() {
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

            val stt = sttManager.getStt()
            if (stt is GeminiLiveSttController) {
                stt.switchMicMode(MicrophoneSession.Mode.MONITORING)
                Log.i(TAG, "[STOP] Live: Switched to MONITORING mode")
            }
        }
    }

    fun clearConversation() {
        disableAutoListenAfterTts()
        flowController.clearConversation()

        val stt = sttManager.getStt()
        if (stt is GeminiLiveSttController) {
            stt.switchMicMode(MicrophoneSession.Mode.MONITORING)
            Log.i(TAG, "[CLEAR] Live: Switched to MONITORING mode")
        }
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
        interruption.reset()
        tts.shutdown()
    }

    private fun setupTtsMonitoring() {
        viewModelScope.launch {
            tts.isSpeaking.collect { speaking ->
                val stt = sttManager.getStt()
                if (stt is GeminiLiveSttController) {
                    if (speaking) {
                        Log.i(TAG, "[TTS] Started speaking - switching mic to MONITORING")
                        stt.switchMicMode(MicrophoneSession.Mode.MONITORING)
                    } else {
                        Log.i(TAG, "[TTS] Stopped speaking")
                        stt.notifyTtsStopped()

                        if (isListening.value) {
                            Log.i(TAG, "[TTS] Keeping current mode (user is actively listening)")
                            return@collect
                        }

                        if (Prefs.getAutoListen(appCtx) && shouldAutoListenAfterTts) {
                            Log.i(TAG, "[TTS] Switching to MONITORING (auto-listen enabled)")
                            stt.switchMicMode(MicrophoneSession.Mode.MONITORING)
                        } else {
                            Log.i(TAG, "[TTS] Switching to IDLE (no auto-listen)")
                            stt.switchMicMode(MicrophoneSession.Mode.IDLE)
                        }
                    }
                }
            }
        }
    }
}