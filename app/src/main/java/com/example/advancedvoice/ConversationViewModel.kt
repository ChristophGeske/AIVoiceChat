package com.example.advancedvoice

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class GenerationPhase {
    IDLE,
    GENERATING_FIRST,
    GENERATING_REMAINDER,
    SINGLE_SHOT_GENERATING
}

class ConversationViewModel(app: Application) : AndroidViewModel(app), TextToSpeech.OnInitListener {
    private val TAG = "ConversationVM"

    // Conversation state
    private val entries = mutableListOf<ConversationEntry>()
    private val _conversation = MutableLiveData<List<ConversationEntry>>(emptyList())
    val conversation: LiveData<List<ConversationEntry>> = _conversation

    // Speaking highlight
    private val _currentSpeaking = MutableLiveData<SpokenSentence?>(null)
    val currentSpeaking: LiveData<SpokenSentence?> = _currentSpeaking

    // Speaking flag
    private val _isSpeaking = MutableLiveData(false)
    val isSpeaking: LiveData<Boolean> = _isSpeaking

    // Engine turn finished (one-shot event)
    private val _turnFinishedEvent = MutableLiveData<Event<Unit>>()
    val turnFinishedEvent: LiveData<Event<Unit>> = _turnFinishedEvent

    // Event for when the TTS queue is fully processed.
    private val _ttsQueueFinishedEvent = MutableLiveData<Event<Unit>>()
    val ttsQueueFinishedEvent: LiveData<Event<Unit>> = _ttsQueueFinishedEvent

    // Error messages
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // Controls orchestrator inputs
    private val _engineActive = MutableLiveData(false)
    val engineActive: LiveData<Boolean> = _engineActive

    private val _settingsVisible = MutableLiveData(false)
    val settingsVisible: LiveData<Boolean> = _settingsVisible

    // Generation phase for accurate UI
    private val _generationPhase = MutableLiveData(GenerationPhase.IDLE)
    val generationPhase: LiveData<GenerationPhase> = _generationPhase

    // Track if LLM has delivered response (for blocking STT during TTS-only phase)
    private val _llmDelivered = MutableLiveData(false)
    val llmDelivered: LiveData<Boolean> = _llmDelivered

    // Single derived state for UI controls
    private val _uiControls = MediatorLiveData<ControlsState>()
    val uiControls: LiveData<ControlsState> = _uiControls

    // TTS
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val ttsQueue = ArrayDeque<SpokenSentence>()
    private var lastTtsDoneAt = 0L
    private var ttsInitialized = false
    private var currentUtteranceId: String? = null

    // STT (Speech-To-Text) State LiveData
    private val _sttIsListening = MutableLiveData(false)
    val sttIsListening: LiveData<Boolean> = _sttIsListening

    private val _sttError = MutableLiveData<Event<String>>()
    val sttError: LiveData<Event<String>> = _sttError

    // One-shot event for NO_MATCH
    private val _sttNoMatch = MutableLiveData<Event<Unit>>()
    val sttNoMatch: LiveData<Event<Unit>> = _sttNoMatch

    // Track last user input for combining when interrupted
    private var lastUserInput: String? = null

    // Track if user started speaking during generation (to abort LLM)
    private var userInterruptedDuringGeneration = false

    // SpeechRecognizer
    private lateinit var speechRecognizer: SpeechRecognizer
    private var currentModelName: String = "gemini-2.5-pro"

    // Config flags
    private var fasterFirstEnabled: Boolean = false

    // Dedup keys for queued sentences
    private val queuedKeys = mutableSetOf<String>()

    // Engine
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }
    private val prefs by lazy { app.getSharedPreferences("ai_prefs", Application.MODE_PRIVATE) }

    // Stable UtteranceProgressListener
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _isSpeaking.postValue(true)
        }

        override fun onDone(utteranceId: String?) {
            viewModelScope.launch {
                val wasCurrent = utteranceId == currentUtteranceId
                if (!wasCurrent) {
                    Log.w(TAG, "[TTS] onDone skipped - utteranceId mismatch (expected=$currentUtteranceId, got=$utteranceId)")
                    return@launch
                }

                lastTtsDoneAt = SystemClock.elapsedRealtime()
                ttsQueue.pollFirst()
                _currentSpeaking.postValue(null)
                currentUtteranceId = null

                if (ttsQueue.isNotEmpty()) {
                    speakNext()
                } else {
                    _isSpeaking.postValue(false)
                    _ttsQueueFinishedEvent.postValue(Event(Unit))
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onDone(utteranceId)
        }
    }

    private val engine: SentenceTurnEngine by lazy {
        val cb = SentenceTurnEngine.Callbacks(
            onStreamDelta = { delta ->
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                entries.getOrNull(idx)?.let { current ->
                    entries[idx] = current.copy(streamingText = (current.streamingText ?: "") + delta)
                    postList()
                }
            },
            onStreamSentence = { sentence ->
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                entries.getOrNull(idx)?.let { current ->
                    val trimmed = sentence.trim()
                    if (current.sentences.none { it.trim() == trimmed }) {
                        val newSentences = current.sentences + trimmed
                        entries[idx] = current.copy(sentences = newSentences)
                        queueTts(SpokenSentence(trimmed, idx, newSentences.size - 1))
                        postList()
                    }
                }
            },
            onFirstSentence = { sentence ->
                // Phase 1 finished, Phase 2 starts now
                _generationPhase.postValue(GenerationPhase.GENERATING_REMAINDER)
                val newEntry = ConversationEntry("Assistant", listOf(sentence), isAssistant = true)
                entries.add(newEntry)
                currentAssistantEntryIndex = entries.lastIndex
                queueTts(SpokenSentence(sentence, currentAssistantEntryIndex!!, 0))
                postList()
            },
            onRemainingSentences = { sentences ->
                // Phase 2 finished generating
                _generationPhase.postValue(GenerationPhase.IDLE)
                _llmDelivered.postValue(true)
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                entries.getOrNull(idx)?.let { current ->
                    val ns = current.sentences.toMutableList()
                    var added = false
                    sentences.forEach { s ->
                        val t = s.trim()
                        if (ns.none { it.trim() == t }) {
                            ns.add(t)
                            queueTts(SpokenSentence(t, idx, ns.size - 1))
                            added = true
                        }
                    }
                    if (added) {
                        entries[idx] = current.copy(sentences = ns)
                        postList()
                    }
                }
            },
            onFinalResponse = { fullText ->
                _generationPhase.postValue(GenerationPhase.IDLE)
                _llmDelivered.postValue(true)
                val sentences = dedupeSentences(SentenceSplitter.splitIntoSentences(fullText))
                val idx = currentAssistantEntryIndex
                if (idx == null || entries.getOrNull(idx)?.isAssistant != true) {
                    val newEntry = ConversationEntry("Assistant", sentences, isAssistant = true)
                    entries.add(newEntry)
                    currentAssistantEntryIndex = entries.lastIndex
                    sentences.forEachIndexed { i, s -> queueTts(SpokenSentence(s, currentAssistantEntryIndex!!, i)) }
                } else {
                    entries[idx] = entries[idx].copy(sentences = sentences, streamingText = null)
                }
                postList()
            },
            onTurnFinish = {
                _engineActive.postValue(false)
                _generationPhase.postValue(GenerationPhase.IDLE)
                _turnFinishedEvent.postValue(Event(Unit))
            },
            onSystem = { msg -> addSystemEntry(msg) },
            onError = { msg ->
                addErrorEntry(msg.take(700))
                _errorMessage.postValue(msg.take(700))
            }
        )
        SentenceTurnEngine(
            uiScope = viewModelScope,
            http = httpClient,
            geminiKeyProvider = { prefs.getString("gemini_key", "").orEmpty() },
            openAiKeyProvider = { prefs.getString("openai_key", "").orEmpty() },
            systemPromptProvider = { getEffectiveSystemPromptFromPrefs() },
            openAiOptionsProvider = { getOpenAiOptionsFromPrefs() },
            callbacks = cb
        )
    }

    private var currentAssistantEntryIndex: Int? = null

    init {
        Log.i(TAG, "[INIT] ViewModel created (hashCode=${this.hashCode()})")
        tts = TextToSpeech(getApplication(), this)
        setupSpeechRecognizer()
        setupControlsMediator()
    }

    private fun setupControlsMediator() {
        fun recompute() {
            val state = ControlsLogic.derive(
                isSpeaking = isSpeaking.value == true,
                isListening = sttIsListening.value == true,
                engineActive = engineActive.value == true,
                settingsVisible = settingsVisible.value == true
            )
            _uiControls.postValue(state)
        }
        _uiControls.value = ControlsLogic.derive(false, false, false, false)
        _uiControls.addSource(isSpeaking) { recompute() }
        _uiControls.addSource(sttIsListening) { recompute() }
        _uiControls.addSource(engineActive) { recompute() }
        _uiControls.addSource(settingsVisible) { recompute() }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(getApplication())) {
            _sttError.postValue(Event("Speech recognition not available on this device."))
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(getApplication())
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _sttIsListening.postValue(true)
            }

            override fun onBeginningOfSpeech() {
                Log.i(TAG, "[STT] onBeginningOfSpeech - user started speaking")

                // If LLM is generating (not yet delivered), abort it immediately
                val generating = engine.isActive() && _llmDelivered.value != true
                if (generating) {
                    Log.i(TAG, "[STT] User interrupted generation - aborting LLM")
                    userInterruptedDuringGeneration = true
                    injectDraftIfActive()
                    abortEngine()
                }
            }

            override fun onResults(results: Bundle?) {
                _sttIsListening.postValue(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                    handleSttResult(matches[0])
                }
                userInterruptedDuringGeneration = false
            }

            override fun onError(error: Int) {
                _sttIsListening.postValue(false)
                userInterruptedDuringGeneration = false
                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    _sttNoMatch.postValue(Event(Unit))
                } else {
                    _sttError.postValue(Event("STT Error (code:$error)"))
                }
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun handleSttResult(newInput: String) {
        val delivered = _llmDelivered.value == true

        // If LLM has delivered and TTS is speaking, ignore the speech
        if (delivered && _isSpeaking.value == true) {
            Log.i(TAG, "[STT] Ignoring speech - LLM delivered, TTS is speaking. User must use buttons.")
            addSystemEntry("Speech detected but ignored. Please use Stop button to interrupt.")
            return
        }

        // If user interrupted during generation, combine inputs
        if (userInterruptedDuringGeneration) {
            Log.i(TAG, "[STT] Processing interrupted generation - combining inputs")
            val oldInput = lastUserInput ?: ""
            val combined = if (oldInput.isNotBlank()) {
                "$oldInput $newInput"
            } else {
                newInput
            }

            addUserEntry(combined)
            lastUserInput = combined
            _llmDelivered.postValue(false)
            startTurn(combined, currentModelName)
        } else {
            // Normal case: start new turn
            addUserEntry(newInput)
            lastUserInput = newInput
            _llmDelivered.postValue(false)
            startTurn(newInput, currentModelName)
        }
    }

    fun startListening() {
        // Block if LLM has delivered and TTS is speaking
        if (_llmDelivered.value == true && _isSpeaking.value == true) {
            Log.i(TAG, "[STT] Blocked - LLM delivered, TTS speaking. Use buttons to interrupt.")
            return
        }

        val listenSeconds = prefs.getInt("listen_seconds", 5).coerceIn(1, 120)
        val silenceMs = (listenSeconds * 1000L).coerceIn(1000L, 30000L)
        val minLengthMs = 2000L

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minLengthMs)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer.startListening(intent)
    }

    fun stopListening() {
        if (_sttIsListening.value == true) {
            speechRecognizer.stopListening()
            _sttIsListening.postValue(false)
            userInterruptedDuringGeneration = false
        }
    }

    fun setCurrentModel(modelName: String) { this.currentModelName = modelName }

    fun applyEngineConfigFromPrefs(prefs: SharedPreferences) {
        val maxSent = prefs.getInt("max_sentences", 4).coerceIn(1, 10)
        val faster = prefs.getBoolean("faster_first", true)
        fasterFirstEnabled = faster
        engine.setMaxSentences(maxSent)
        engine.setFasterFirst(faster)
    }

    fun startTurn(userInput: String, modelName: String) {
        currentAssistantEntryIndex = null
        _engineActive.postValue(true)
        _llmDelivered.postValue(false)
        userInterruptedDuringGeneration = false
        _generationPhase.postValue(
            if (fasterFirstEnabled) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )
        engine.startTurn(userInput, modelName)
    }

    fun isEngineActive(): Boolean = engine.isActive()

    fun abortEngine() {
        engine.abort()
        _engineActive.postValue(false)
        _generationPhase.postValue(GenerationPhase.IDLE)
        _llmDelivered.postValue(false)
    }

    fun addUserEntry(text: String) {
        entries.add(ConversationEntry("You", listOf(text), isAssistant = false))
        postList()
    }

    fun addSystemEntry(text: String) {
        entries.add(ConversationEntry("System", listOf(text), isAssistant = false))
        postList()
    }

    fun addErrorEntry(text: String) {
        entries.add(ConversationEntry("Error", listOf(text), isAssistant = false))
        postList()
    }

    fun clearConversation() {
        stopAll()
        entries.clear()
        engine.clearHistory()
        currentAssistantEntryIndex = null
        queuedKeys.clear()
        lastUserInput = null
        userInterruptedDuringGeneration = false
        postList()
        _generationPhase.postValue(GenerationPhase.IDLE)
        _llmDelivered.postValue(false)
    }

    fun replayEntry(entryIndex: Int) {
        val entry = entries.getOrNull(entryIndex) ?: return
        if (!entry.isAssistant) return
        stopTts()
        lastTtsDoneAt = 0L
        clearDedupForEntry(entryIndex)
        entry.sentences.forEachIndexed { idx, s ->
            queueTts(SpokenSentence(s, entryIndex, idx), force = true)
        }
    }

    fun stopAll() {
        abortEngine()
        stopTts()
        stopListening()
    }

    fun stopTts() {
        try { tts.stop() } catch (_: Exception) {}
        _isSpeaking.postValue(false)
        ttsQueue.clear()
        queuedKeys.clear()
        _currentSpeaking.postValue(null)
        currentUtteranceId = null
        lastTtsDoneAt = 0L
    }

    fun injectDraftIfActive() {
        if (!engine.isActive()) return
        buildAssistantDraft()?.let { engine.injectAssistantDraft(it) }
    }

    fun checkAndResumeTts() {
        if (!ttsInitialized) return
        if (ttsQueue.isNotEmpty() && isSpeaking.value == false && currentUtteranceId == null) {
            speakNext()
        }
    }

    override fun onInit(status: Int) {
        if (ttsInitialized) return
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            ttsInitialized = true
            val locale = Locale.getDefault()
            val result = tts.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            tts.setOnUtteranceProgressListener(utteranceListener)
        } else {
            ttsReady = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            tts.stop()
            tts.shutdown()
            if (this::speechRecognizer.isInitialized) speechRecognizer.destroy()
        } catch (_: Exception) {}
    }

    private fun postList() { _conversation.postValue(entries.toList()) }

    private fun queueTts(spoken: SpokenSentence, force: Boolean = false) {
        val key = "${spoken.entryIndex}:${spoken.sentenceIndex}"
        if (!force && !queuedKeys.add(key)) return
        ttsQueue.addLast(spoken)
        if (isSpeaking.value != true && currentUtteranceId == null) {
            speakNext()
        }
    }

    private fun clearDedupForEntry(entryIndex: Int) {
        queuedKeys.removeAll { it.startsWith("$entryIndex:") }
    }

    private fun speakNext() {
        if (!ttsReady || ttsQueue.isEmpty() || currentUtteranceId != null) return
        val next = ttsQueue.first()
        _currentSpeaking.postValue(next)
        val id = UUID.randomUUID().toString()
        currentUtteranceId = id
        tts.speak(next.text, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun getEffectiveSystemPromptFromPrefs(): String {
        val base = prefs.getString("system_prompt", "").orEmpty().ifBlank { DEFAULT_BASE_PROMPT }
        val maxSent = prefs.getInt("max_sentences", 4).coerceIn(1, 10)
        val faster = prefs.getBoolean("faster_first", true)
        val extras = buildList {
            add("Return plain text only.")
            if (maxSent >= 1) add("When appropriate, keep answers to at most $maxSent complete sentences.")
            if (faster) add("Begin with a complete first sentence promptly, then continue.")
        }.joinToString(" ")
        return if (extras.isBlank()) base else "$base\n\n$extras"
    }

    private fun getOpenAiOptionsFromPrefs(): OpenAiOptions {
        val effort = prefs.getString("gpt5_effort", "low") ?: "low"
        val verbosity = prefs.getString("gpt5_verbosity", "low") ?: "low"
        return OpenAiOptions(effort = effort, verbosity = verbosity)
    }

    private fun buildAssistantDraft(): String? {
        val idx = currentAssistantEntryIndex ?: return null
        val entry = entries.getOrNull(idx) ?: return null
        val base = entry.sentences.joinToString(" ").trim()
        val inStream = entry.streamingText
        return if (!inStream.isNullOrBlank() && inStream.length > base.length) inStream else base
    }

    private fun dedupeSentences(input: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        input.forEach { s ->
            val t = s.trim()
            if (t.isNotEmpty()) seen.add(t)
        }
        return seen.toList()
    }

    fun setSettingsVisible(visible: Boolean) { _settingsVisible.postValue(visible) }

    companion object {
        private val DEFAULT_BASE_PROMPT = """
            You are a helpful assistant. Answer the user's request in clear, complete sentences.
            Do not use JSON or code fences unless explicitly requested.
        """.trimIndent()
    }
}