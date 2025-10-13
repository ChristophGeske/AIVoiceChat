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
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
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

    private fun now(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

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

    // Mark only when the final answer is delivered
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

    // STT State
    private val _sttIsListening = MutableLiveData(false)
    val sttIsListening: LiveData<Boolean> = _sttIsListening

    private val _sttError = MutableLiveData<Event<String>>()
    val sttError: LiveData<Event<String>> = _sttError

    private val _sttNoMatch = MutableLiveData<Event<Unit>>()
    val sttNoMatch: LiveData<Event<Unit>> = _sttNoMatch

    // Combine/replace helpers
    private var lastUserInput: String? = null
    private var lastUserEntryIndex: Int? = null
    private var userInterruptedDuringGeneration = false

    // Filters
    private val MIN_INPUT_LENGTH = 8

    // SpeechRecognizer
    private lateinit var speechRecognizer: SpeechRecognizer
    private var currentModelName: String = "gemini-1.5-pro"

    // Config flags
    private var fasterFirstEnabled: Boolean = true

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

    // Utterance listener
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _isSpeaking.postValue(true)
        }

        override fun onDone(utteranceId: String?) {
            viewModelScope.launch {
                if (utteranceId != currentUtteranceId) return@launch
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
        override fun onError(utteranceId: String?) { onDone(utteranceId) }
    }

    private var currentAssistantEntryIndex: Int? = null

    private val engine: SentenceTurnEngine by lazy {
        val cb = SentenceTurnEngine.Callbacks(
            onStreamDelta = { delta ->
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                entries.getOrNull(idx)?.let { cur ->
                    entries[idx] = cur.copy(streamingText = (cur.streamingText ?: "") + delta)
                    postList()
                }
            },
            onStreamSentence = { sentence ->
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                entries.getOrNull(idx)?.let { cur ->
                    val trimmed = sentence.trim()
                    if (cur.sentences.none { it.trim() == trimmed }) {
                        val ns = cur.sentences + trimmed
                        entries[idx] = cur.copy(sentences = ns)
                        queueTts(SpokenSentence(trimmed, idx, ns.size - 1))
                        postList()
                    }
                }
            },
            onFirstSentence = { sentence ->
                // Do NOT mark delivered here; allow barge-in during remainder
                _generationPhase.postValue(GenerationPhase.GENERATING_REMAINDER)
                val newEntry = ConversationEntry("Assistant", listOf(sentence), isAssistant = true)
                entries.add(newEntry)
                currentAssistantEntryIndex = entries.lastIndex
                queueTts(SpokenSentence(sentence, currentAssistantEntryIndex!!, 0))
                postList()
            },
            onRemainingSentences = { sentences ->
                // Remainder text; still not final delivered
                _generationPhase.postValue(GenerationPhase.IDLE)
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                entries.getOrNull(idx)?.let { cur ->
                    val ns = cur.sentences.toMutableList()
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
                        entries[idx] = cur.copy(sentences = ns)
                        postList()
                    }
                }
            },
            onFinalResponse = { fullText ->
                // Final delivered - stop any ongoing STT immediately
                _generationPhase.postValue(GenerationPhase.IDLE)
                _llmDelivered.postValue(true)
                if (_sttIsListening.value == true) stopListening()

                val sentences = dedupeSentences(SentenceSplitter.splitIntoSentences(fullText))
                val idx = currentAssistantEntryIndex
                if (idx == null || entries.getOrNull(idx)?.isAssistant != true) {
                    val newEntry = ConversationEntry("Assistant", sentences, isAssistant = true)
                    entries.add(newEntry)
                    currentAssistantEntryIndex = entries.lastIndex
                    sentences.forEachIndexed { i, s ->
                        queueTts(SpokenSentence(s, currentAssistantEntryIndex!!, i))
                    }
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

    init {
        Log.i(TAG, "[${now()}][INIT] ViewModel created (hashCode=${this.hashCode()})")
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
                Log.d(TAG, "[${now()}][STT] onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() {
                Log.i(TAG, "[${now()}][STT] onBeginningOfSpeech")

                // If TTS is already speaking, this speech input is invalid.
                // Stop listening immediately and ignore this interruption attempt.
                if (_isSpeaking.value == true) {
                    Log.w(TAG, "[${now()}][STT] Ignored onBeginningOfSpeech because TTS is active. Stopping listening.")
                    stopListening()
                    return
                }

                // Interrupt only before final delivery
                val generating = engine.isActive()
                val delivered = _llmDelivered.value == true
                if (generating && !delivered) {
                    Log.i(TAG, "[${now()}][STT] User interrupted generation (before final) - cleaning up and aborting")
                    userInterruptedDuringGeneration = true
                    removeAssistantEntryIfCurrent()
                    abortEngineSilently()
                }
            }

            override fun onResults(results: Bundle?) {
                _sttIsListening.postValue(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "[${now()}][STT] onResults: ${matches?.firstOrNull()?.take(50)}")
                if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                    handleSttResult(matches[0])
                }
                userInterruptedDuringGeneration = false
            }

            override fun onError(error: Int) {
                _sttIsListening.postValue(false)
                val wasInterrupting = userInterruptedDuringGeneration
                userInterruptedDuringGeneration = false // Reset flag immediately

                val errorName = when(error) {
                    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                    else -> "UNKNOWN_$error"
                }
                Log.w(TAG, "[${now()}][STT] onError: $errorName")

                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    // If an interruption attempt fails with NO_MATCH, restart the turn with the last valid input to prevent a stall.
                    if (wasInterrupting) {
                        Log.i(TAG, "[${now()}][STT] Interruption resulted in NO_MATCH. Restarting turn with previous input.")
                        lastUserInput?.let {
                            startTurnReplacingLast(it, currentModelName)
                        }
                    } else {
                        // Original behavior for non-interruption NO_MATCH
                        _sttNoMatch.postValue(Event(Unit))
                    }
                } else {
                    _sttError.postValue(Event("STT Error: $errorName"))
                }
            }


            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d(TAG, "[${now()}][STT] onEndOfSpeech") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun handleSttResult(newInput: String) {
        val trimmed = newInput.trim()
        if (trimmed.length < MIN_INPUT_LENGTH) {
            Log.w(TAG, "[${now()}][STT] FILTERED - input too short (${trimmed.length} < $MIN_INPUT_LENGTH)")
            return
        }

        // If TTS is speaking, do not process (silent ignore)
        if (_isSpeaking.value == true) {
            Log.i(TAG, "[${now()}][STT] Ignored speech - TTS is speaking")
            return
        }

        if (userInterruptedDuringGeneration) {
            Log.i(TAG, "[${now()}][STT] Combining with previous and replacing")
            val old = lastUserInput.orEmpty()
            val combined = if (old.isNotBlank()) "$old $trimmed" else trimmed
            replaceLastUserEntry(combined)
            lastUserInput = combined
            _llmDelivered.postValue(false)
            startTurnReplacingLast(combined, currentModelName)
        } else {
            Log.i(TAG, "[${now()}][STT] Normal input: '$trimmed'")
            addUserEntry(trimmed)
            lastUserInput = trimmed
            _llmDelivered.postValue(false)
            startTurn(trimmed, currentModelName)
        }
    }

    fun startListening() {
        // Block listening if the assistant is currently speaking.
        if (isSpeaking.value == true) {
            Log.i(TAG, "[${now()}][STT] Blocked - TTS is speaking")
            return
        }
        Log.i(TAG, "[${now()}][STT] startListening()")

        // Use a fixed, reasonable silence timeout for STT to end promptly after speech.
        val silenceMs = 1500L
        val minLengthMs = 1000L

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
            Log.i(TAG, "[${now()}][STT] stopListening()")
            try { speechRecognizer.stopListening() } catch (_: Exception) {}
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

    private fun startTurnReplacingLast(userInput: String, modelName: String) {
        currentAssistantEntryIndex = null
        _engineActive.postValue(true)
        _llmDelivered.postValue(false)
        userInterruptedDuringGeneration = false
        _generationPhase.postValue(
            if (fasterFirstEnabled) GenerationPhase.GENERATING_FIRST
            else GenerationPhase.SINGLE_SHOT_GENERATING
        )
        engine.replaceLastUserMessage(userInput)
        engine.startTurnWithCurrentHistory(modelName)
    }

    fun isEngineActive(): Boolean = engine.isActive()

    fun abortEngine() {
        engine.abort(silent = true) // Silent abort always
        _engineActive.postValue(false)
        _generationPhase.postValue(GenerationPhase.IDLE)
        _llmDelivered.postValue(false)
    }

    private fun abortEngineSilently() {
        engine.abort(silent = true)
        _engineActive.postValue(false)
        _generationPhase.postValue(GenerationPhase.IDLE)
        // do not touch _llmDelivered here
    }

    fun addUserEntry(text: String) {
        val entry = ConversationEntry("You", listOf(text), isAssistant = false)
        entries.add(entry)
        lastUserEntryIndex = entries.lastIndex
        Log.d(TAG, "[${now()}][Conversation] Added user entry at index $lastUserEntryIndex: '${text.take(50)}...'")
        postList()
    }

    private fun replaceLastUserEntry(text: String) {
        val idx = lastUserEntryIndex
        if (idx != null && idx < entries.size && entries[idx].speaker == "You") {
            entries[idx] = ConversationEntry("You", listOf(text), isAssistant = false)
            Log.d(TAG, "[${now()}][Conversation] Replaced user entry at index $idx: '${text.take(50)}...'")
            postList()
        } else {
            Log.w(TAG, "[${now()}][Conversation] Replace failed - adding new user entry")
            addUserEntry(text)
        }
    }

    fun addSystemEntry(text: String) {
        // Keep only relevant system entries (e.g., web sources, model switches).
        // Do NOT add interruption or speech-ignored messages anymore.
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
        lastUserEntryIndex = null
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
        if (isSpeaking.value != true && currentUtteranceId == null) speakNext()
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

    // Remove queued/ongoing TTS for a given assistant entry
    private fun removeTtsForEntry(entryIndex: Int) {
        val it = ttsQueue.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.entryIndex == entryIndex) it.remove()
        }
        if (_currentSpeaking.value?.entryIndex == entryIndex) {
            try { tts.stop() } catch (_: Exception) {}
            _isSpeaking.postValue(false)
            currentUtteranceId = null
            _currentSpeaking.postValue(null)
        }
        clearDedupForEntry(entryIndex)
    }

    // Remove current assistant partial entry (for clean barge-in)
    private fun removeAssistantEntryIfCurrent() {
        val idx = currentAssistantEntryIndex
        if (idx != null && idx in entries.indices) {
            val entry = entries[idx]
            if (entry.isAssistant) {
                removeTtsForEntry(idx)
                entries.removeAt(idx)
                currentAssistantEntryIndex = null
                postList()
            }
        }
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
        val effort = prefs.getString("gpt4_effort", "low") ?: "low"
        val verbosity = prefs.getString("gpt4_verbosity", "low") ?: "low"
        return OpenAiOptions(effort = effort, verbosity = verbosity)
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