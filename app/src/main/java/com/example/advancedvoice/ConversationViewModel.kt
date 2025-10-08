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
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

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

    // Error messages
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

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

    // SpeechRecognizer is managed by the ViewModel
    private lateinit var speechRecognizer: SpeechRecognizer
    private var currentModelName: String = "gemini-2.5-pro"

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
            val prevId = currentUtteranceId
            currentUtteranceId = utteranceId
            _isSpeaking.postValue(true)
            val current = _currentSpeaking.value
            Log.i(TAG, "[TTS] onStart id=$utteranceId (prev=$prevId) queueSize=${ttsQueue.size} currentSpeaking=${current?.let { "${it.entryIndex}:${it.sentenceIndex}" }}")
        }

        override fun onDone(utteranceId: String?) {
            viewModelScope.launch {
                Log.i(TAG, "[TTS] onDone id=$utteranceId currentId=$currentUtteranceId queueSize=${ttsQueue.size}")

                // For backwards compatibility: if utteranceId doesn't match, check if queue's first item matches
                val shouldProcess = if (utteranceId == currentUtteranceId) {
                    true
                } else {
                    // The utterance might be from the previous item that just finished
                    // Process it if we're not already speaking something else
                    val canProcess = currentUtteranceId == null || currentUtteranceId == utteranceId
                    if (!canProcess) {
                        Log.w(TAG, "[TTS] onDone skipped - utteranceId mismatch (expected=$currentUtteranceId, got=$utteranceId)")
                    }
                    canProcess
                }

                if (!shouldProcess) return@launch

                lastTtsDoneAt = SystemClock.elapsedRealtime()
                val completed = ttsQueue.pollFirst()
                _currentSpeaking.postValue(null)
                currentUtteranceId = null

                Log.i(TAG, "[TTS] Processed done: completed=${completed?.let { "${it.entryIndex}:${it.sentenceIndex}" }} queueRemaining=${ttsQueue.size}")

                if (ttsQueue.isNotEmpty()) {
                    Log.d(TAG, "[TTS] More items in queue, calling speakNext")
                    speakNext()
                } else {
                    _isSpeaking.postValue(false)
                    Log.i(TAG, "[TTS] Queue empty; speaking=false")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.e(TAG, "[TTS] onError id=$utteranceId")
            onDone(utteranceId)
        }
    }

    private val engine: SentenceTurnEngine by lazy {
        val cb = SentenceTurnEngine.Callbacks(
            onStreamDelta = { delta ->
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                val current = entries.getOrNull(idx) ?: return@Callbacks
                entries[idx] = current.copy(streamingText = (current.streamingText ?: "") + delta)
                postList()
            },
            onStreamSentence = { sentence ->
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                val current = entries.getOrNull(idx) ?: return@Callbacks
                val trimmed = sentence.trim()
                if (current.sentences.none { it.trim() == trimmed }) {
                    val newSentences = current.sentences.toMutableList().apply { add(trimmed) }
                    entries[idx] = current.copy(sentences = newSentences)
                    queueTts(SpokenSentence(trimmed, idx, newSentences.size - 1))
                    postList()
                }
            },
            onFirstSentence = { sentence ->
                Log.i(TAG, "[ENGINE] onFirstSentence called: '${sentence.take(80)}...'")
                val newEntry = ConversationEntry("Assistant", listOf(sentence), isAssistant = true)
                entries.add(newEntry)
                currentAssistantEntryIndex = entries.lastIndex
                queueTts(SpokenSentence(sentence, currentAssistantEntryIndex!!, 0))
                postList()
            },
            onRemainingSentences = { sentences ->
                Log.i(TAG, "[ENGINE] onRemainingSentences called: count=${sentences.size}")
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                val current = entries.getOrNull(idx) ?: return@Callbacks
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
            },
            onFinalResponse = { fullText ->
                Log.i(TAG, "[ENGINE] onFinalResponse called: length=${fullText.length}")
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                val current = entries.getOrNull(idx) ?: return@Callbacks
                entries[idx] = current.copy(
                    sentences = SentenceSplitter.splitIntoSentences(fullText),
                    streamingText = null
                )
                postList()
            },
            onTurnFinish = {
                Log.i(TAG, "[ENGINE] onTurnFinish called")
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
            systemPromptProvider = { getCombinedSystemPromptFromPrefs() },
            callbacks = cb
        )
    }

    private var currentAssistantEntryIndex: Int? = null

    init {
        Log.i(TAG, "[INIT] ViewModel created (hashCode=${this.hashCode()})")
        tts = TextToSpeech(getApplication(), this)
        setupSpeechRecognizer()
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(getApplication())) {
            Log.e(TAG, "[STT] Recognition not available")
            _sttError.postValue(Event("Speech recognition not available on this device."))
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(getApplication())
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(TAG, "[STT] onReadyForSpeech")
                _sttIsListening.postValue(true)
            }
            override fun onResults(results: Bundle?) {
                Log.i(TAG, "[STT] onResults")
                _sttIsListening.postValue(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    Log.i(TAG, "[STT] Recognized: '$spokenText'")
                    if (spokenText.isNotBlank()) {
                        addUserEntry(spokenText)
                        startTurn(spokenText, currentModelName)
                    }
                }
            }
            override fun onError(error: Int) {
                Log.e(TAG, "[STT] onError code=$error")
                _sttIsListening.postValue(false)
                if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                    _sttError.postValue(Event("STT Error (code:$error)"))
                }
            }
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "[STT] onBeginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "[STT] onEndOfSpeech")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun startListening() {
        Log.i(TAG, "[STT] startListening requested")
        if (engine.isActive()) {
            Log.i(TAG, "[STT] Engine active, injecting draft and aborting")
            injectDraftIfActive()
            abortEngine()
        }
        val listenSeconds = prefs.getInt("listen_seconds", 3).coerceIn(1, 120)
        val minLenMs = (listenSeconds * 1000L).coerceIn(1000L, 60000L)
        val silenceMs = max(500L, min(10000L, (listenSeconds * 1000L / 2)))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minLenMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
        }
        speechRecognizer.startListening(intent)
        Log.i(TAG, "[STT] Listening started")
    }

    fun stopListening() {
        if (_sttIsListening.value == true) {
            Log.i(TAG, "[STT] Stopping listening")
            speechRecognizer.stopListening()
            _sttIsListening.postValue(false)
        }
    }

    fun setCurrentModel(modelName: String) {
        Log.i(TAG, "[MODEL] Set to: $modelName")
        this.currentModelName = modelName
    }

    fun applyEngineConfigFromPrefs(prefs: SharedPreferences) {
        val maxSent = prefs.getInt("max_sentences", 4).coerceIn(1, 10)
        val faster = prefs.getBoolean("faster_first", true)
        Log.i(TAG, "[CONFIG] Applying: maxSentences=$maxSent, fasterFirst=$faster")
        engine.setMaxSentences(maxSent)
        engine.setFasterFirst(faster)
    }

    fun startTurn(userInput: String, modelName: String) {
        Log.i(TAG, "[TURN] Starting: model=$modelName, input='${userInput.take(80)}...'")
        currentAssistantEntryIndex = null
        engine.startTurn(userInput, modelName)
    }

    fun isEngineActive(): Boolean = engine.isActive()

    fun abortEngine() {
        Log.i(TAG, "[ENGINE] Abort requested")
        engine.abort()
    }

    fun addUserEntry(text: String) {
        Log.i(TAG, "[ENTRY] Adding user entry: '${text.take(80)}...'")
        entries.add(ConversationEntry("You", listOf(text), isAssistant = false))
        postList()
    }

    fun addSystemEntry(text: String) {
        Log.i(TAG, "[ENTRY] Adding system entry: '${text.take(80)}...'")
        entries.add(ConversationEntry("System", listOf(text), isAssistant = false))
        postList()
    }

    fun addErrorEntry(text: String) {
        Log.e(TAG, "[ENTRY] Adding error entry: '${text.take(80)}...'")
        entries.add(ConversationEntry("Error", listOf(text), isAssistant = false))
        postList()
    }

    fun clearConversation() {
        Log.i(TAG, "[CLEAR] Clearing conversation")
        stopAll()
        entries.clear()
        engine.clearHistory()
        currentAssistantEntryIndex = null
        queuedKeys.clear()
        postList()
    }

    fun replayEntry(entryIndex: Int) {
        val entry = entries.getOrNull(entryIndex) ?: return
        if (!entry.isAssistant) return
        Log.i(TAG, "[REPLAY] Entry $entryIndex with ${entry.sentences.size} sentences")
        stopTts()
        clearDedupForEntry(entryIndex)
        entry.sentences.forEachIndexed { idx, s ->
            queueTts(SpokenSentence(s, entryIndex, idx), force = true)
        }
    }

    fun stopAll() {
        Log.i(TAG, "[STOP] Stopping all (engine, TTS, STT)")
        abortEngine()
        stopTts()
        stopListening()
    }

    fun stopTts() {
        Log.i(TAG, "[TTS] Stopping: queueSize=${ttsQueue.size}, currentUtteranceId=$currentUtteranceId")
        try { tts.stop() } catch (_: Exception) {}
        _isSpeaking.postValue(false)
        ttsQueue.clear()
        queuedKeys.clear()
        _currentSpeaking.postValue(null)
        currentUtteranceId = null
    }

    fun injectDraftIfActive() {
        if (!engine.isActive()) return
        buildAssistantDraft()?.let {
            Log.i(TAG, "[INJECT] Injecting draft: length=${it.length}")
            engine.injectAssistantDraft(it)
        }
    }

    fun elapsedSinceTtsDone(): Long {
        if (lastTtsDoneAt == 0L) return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - lastTtsDoneAt
    }

    fun checkAndResumeTts() {
        if (!ttsInitialized) {
            Log.d(TAG, "[TTS] checkAndResumeTts skipped - TTS not yet initialized")
            return
        }

        if (ttsQueue.isNotEmpty() && isSpeaking.value == false && currentUtteranceId == null) {
            Log.i(TAG, "[TTS] Resuming interrupted queue: queueSize=${ttsQueue.size}")
            speakNext()
        } else {
            Log.d(TAG, "[TTS] checkAndResumeTts: queueSize=${ttsQueue.size}, speaking=${isSpeaking.value}, currentUtteranceId=$currentUtteranceId")
        }
    }

    override fun onInit(status: Int) {
        Log.i(TAG, "[TTS] onInit called: status=$status, ttsInitialized=$ttsInitialized")

        // Prevent re-initialization on rotation
        if (ttsInitialized) {
            Log.w(TAG, "[TTS] Already initialized, skipping setup")
            return
        }

        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            ttsInitialized = true
            val locale = Locale.getDefault()
            val result = tts.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            tts.setOnUtteranceProgressListener(utteranceListener)
            Log.i(TAG, "[TTS] Initialized successfully with language $locale")
        } else {
            ttsReady = false
            Log.e(TAG, "[TTS] Initialization failed: status=$status")
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "[LIFECYCLE] ViewModel onCleared")
        try {
            tts.stop()
            tts.shutdown()
            if (this::speechRecognizer.isInitialized) {
                speechRecognizer.destroy()
            }
        } catch (_: Exception) {}
    }

    private fun postList() {
        _conversation.postValue(entries.toList())
        Log.d(TAG, "[STATE] Conversation updated: size=${entries.size}")
    }

    private fun queueTts(spoken: SpokenSentence, force: Boolean = false) {
        val key = "${spoken.entryIndex}:${spoken.sentenceIndex}"
        if (!force && !queuedKeys.add(key)) {
            Log.d(TAG, "[TTS] Skipping duplicate: $key")
            return
        }

        ttsQueue.addLast(spoken)
        Log.i(TAG, "[TTS] Queued: $key, text='${spoken.text.take(60)}...', queueSize=${ttsQueue.size}, speaking=${isSpeaking.value}, currentUtteranceId=$currentUtteranceId")

        // Only start speaking if nothing is currently being spoken AND no current utterance is active
        if (isSpeaking.value != true && currentUtteranceId == null) {
            Log.d(TAG, "[TTS] Queue not active, starting speakNext")
            speakNext()
        } else {
            Log.d(TAG, "[TTS] Already speaking (speaking=${isSpeaking.value}, currentUtteranceId=$currentUtteranceId), will wait")
        }
    }

    private fun clearDedupForEntry(entryIndex: Int) {
        val removed = queuedKeys.removeAll { it.startsWith("$entryIndex:") }
        Log.d(TAG, "[TTS] Cleared dedup for entry $entryIndex: removed=$removed")
    }

    private fun speakNext() {
        if (!ttsReady || ttsQueue.isEmpty()) {
            Log.d(TAG, "[TTS] speakNext skipped: ttsReady=$ttsReady, queueEmpty=${ttsQueue.isEmpty()}")
            _isSpeaking.postValue(false)
            return
        }

        // Safety check: don't start new speech if already speaking
        if (currentUtteranceId != null) {
            Log.w(TAG, "[TTS] speakNext skipped: already have active utterance $currentUtteranceId")
            return
        }

        val next = ttsQueue.first()
        val key = "${next.entryIndex}:${next.sentenceIndex}"
        _currentSpeaking.postValue(next)
        val id = UUID.randomUUID().toString()
        currentUtteranceId = id

        Log.i(TAG, "[TTS] Speaking: $key, id=$id, text='${next.text.take(60)}...', queueRemaining=${ttsQueue.size - 1}")
        tts.speak(next.text, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun getCombinedSystemPromptFromPrefs(): String {
        val base = prefs.getString("system_prompt", "").orEmpty().ifBlank { DEFAULT_BASE_PROMPT }
        val ext = prefs.getString("system_prompt_extension", "").orEmpty()
        return if (ext.isBlank()) base else "$base\n\n$ext"
    }

    private fun buildAssistantDraft(): String? {
        val idx = currentAssistantEntryIndex ?: return null
        val entry = entries.getOrNull(idx) ?: return null
        val base = entry.sentences.joinToString(" ").trim()
        val inStream = entry.streamingText
        return if (!inStream.isNullOrBlank() && inStream.length > base.length) inStream else base
    }

    companion object {
        private val DEFAULT_BASE_PROMPT = """
            You are a helpful assistant. Answer the user's request in clear, complete sentences.
            Return plain text only. Do not use JSON or code fences unless explicitly requested.
        """.trimIndent()
    }
}