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

    // [FIX] Make the UtteranceProgressListener a stable property of the ViewModel.
    // This prevents losing callbacks during configuration changes if onInit is called again.
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _isSpeaking.postValue(true)
            Log.d(TAG, "[TTS] onStart id=$utteranceId speaking=true queueSize=${ttsQueue.size}")
        }

        override fun onDone(utteranceId: String?) {
            viewModelScope.launch {
                lastTtsDoneAt = SystemClock.elapsedRealtime()
                val completed = ttsQueue.pollFirst()
                _currentSpeaking.postValue(null)
                Log.d(TAG, "[TTS] onDone id=$utteranceId completed=${completed != null} queueSize=${ttsQueue.size}")
                if (ttsQueue.isNotEmpty()) {
                    speakNext()
                } else {
                    _isSpeaking.postValue(false)
                    Log.d(TAG, "[TTS] queue empty; speaking=false")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.e(TAG, "[TTS] onError id=$utteranceId")
            onDone(utteranceId) // Treat error as done to advance the queue
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
                val newEntry = ConversationEntry("Assistant", listOf(sentence), isAssistant = true)
                entries.add(newEntry)
                currentAssistantEntryIndex = entries.lastIndex
                queueTts(SpokenSentence(sentence, currentAssistantEntryIndex!!, 0))
                postList()
            },
            onRemainingSentences = { sentences ->
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
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                val current = entries.getOrNull(idx) ?: return@Callbacks
                entries[idx] = current.copy(
                    sentences = SentenceSplitter.splitIntoSentences(fullText),
                    streamingText = null
                )
                postList()
            },
            onTurnFinish = {
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
        tts = TextToSpeech(getApplication(), this)
        setupSpeechRecognizer()
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(getApplication())) {
            Log.e(TAG, "[STT_VM] Recognition not available")
            _sttError.postValue(Event("Speech recognition not available on this device."))
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(getApplication())
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _sttIsListening.postValue(true)
            }
            override fun onResults(results: Bundle?) {
                _sttIsListening.postValue(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    if (spokenText.isNotBlank()) {
                        addUserEntry(spokenText)
                        startTurn(spokenText, currentModelName)
                    }
                }
            }
            override fun onError(error: Int) {
                _sttIsListening.postValue(false)
                if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                    _sttError.postValue(Event("STT Error (code:$error)"))
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun startListening() {
        if (engine.isActive()) {
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
    }

    fun stopListening() {
        if (_sttIsListening.value == true) {
            speechRecognizer.stopListening()
            _sttIsListening.postValue(false)
        }
    }

    fun setCurrentModel(modelName: String) {
        this.currentModelName = modelName
    }

    fun applyEngineConfigFromPrefs(prefs: SharedPreferences) {
        engine.setMaxSentences(prefs.getInt("max_sentences", 4).coerceIn(1, 10))
        engine.setFasterFirst(prefs.getBoolean("faster_first", true))
    }

    fun startTurn(userInput: String, modelName: String) {
        currentAssistantEntryIndex = null
        engine.startTurn(userInput, modelName)
    }

    fun isEngineActive(): Boolean = engine.isActive()
    fun abortEngine() { engine.abort() }
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
        postList()
    }

    fun replayEntry(entryIndex: Int) {
        val entry = entries.getOrNull(entryIndex) ?: return
        if (!entry.isAssistant) return
        stopTts() // Stop current speech before replaying
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
        _currentSpeaking.postValue(null)
    }

    fun injectDraftIfActive() {
        if (!engine.isActive()) return
        buildAssistantDraft()?.let { engine.injectAssistantDraft(it) }
    }

    fun elapsedSinceTtsDone(): Long {
        if (lastTtsDoneAt == 0L) return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - lastTtsDoneAt
    }

    // [FIX] New function called by the Fragment after rotation to resume TTS if it was interrupted.
    fun checkAndResumeTts() {
        if (ttsQueue.isNotEmpty() && isSpeaking.value == false) {
            Log.d(TAG, "[TTS] Resuming interrupted TTS queue.")
            speakNext()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            val locale = Locale.getDefault()
            val result = tts.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            // [FIX] Set the stable listener instance.
            tts.setOnUtteranceProgressListener(utteranceListener)
            Log.i(TAG, "[TTS] Ready with language $locale")
        } else {
            ttsReady = false
            Log.e(TAG, "[TTS] Init failed status=$status")
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            tts.stop()
            tts.shutdown()
            if (this::speechRecognizer.isInitialized) {
                speechRecognizer.destroy()
            }
        } catch (_: Exception) {}
    }

    private fun postList() { _conversation.postValue(entries.toList()) }

    private fun queueTts(spoken: SpokenSentence, force: Boolean = false) {
        val key = "${spoken.entryIndex}:${spoken.sentenceIndex}"
        if (!force && !queuedKeys.add(key)) return
        ttsQueue.addLast(spoken)
        if (isSpeaking.value != true) {
            speakNext()
        }
    }

    private fun clearDedupForEntry(entryIndex: Int) {
        queuedKeys.removeAll { it.startsWith("$entryIndex:") }
    }

    private fun speakNext() {
        if (!ttsReady || ttsQueue.isEmpty()) {
            _isSpeaking.postValue(false)
            return
        }
        val next = ttsQueue.first()
        _currentSpeaking.postValue(next)
        val id = UUID.randomUUID().toString()
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