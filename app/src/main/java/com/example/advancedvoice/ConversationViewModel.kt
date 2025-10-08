package com.example.advancedvoice

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.ArrayDeque
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    // SpeechRecognizer
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
            try {
                val prevId = currentUtteranceId
                currentUtteranceId = utteranceId
                _isSpeaking.postValue(true)
                val current = _currentSpeaking.value
                Log.i(TAG, "[TTS] onStart id=$utteranceId (prev=$prevId) queueSize=${ttsQueue.size} currentSpeaking=${current?.let { "${it.entryIndex}:${it.sentenceIndex}" }}")
            } catch (e: Exception) {
                Log.e(TAG, "[TTS] onStart crashed: ${e.message}", e)
            }
        }

        override fun onDone(utteranceId: String?) {
            try {
                viewModelScope.launch {
                    Log.i(TAG, "[TTS] onDone id=$utteranceId currentId=$currentUtteranceId queueSize=${ttsQueue.size}")

                    val shouldProcess = if (utteranceId == currentUtteranceId) true
                    else {
                        val canProcess = currentUtteranceId == null || currentUtteranceId == utteranceId
                        if (!canProcess) {
                            Log.w(TAG, "[TTS] onDone skipped - utteranceId mismatch (expected=$currentUtteranceId, got=$utteranceId)")
                        }
                        canProcess
                    }
                    if (!shouldProcess) return@launch

                    lastTtsDoneAt = SystemClock.elapsedRealtime()
                    Log.i(TAG, "[TTS] Setting lastTtsDoneAt=${lastTtsDoneAt}")
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
            } catch (e: Exception) {
                Log.e(TAG, "[TTS] onDone crashed: ${e.message}", e)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            try {
                Log.e(TAG, "[TTS] onError id=$utteranceId")
                onDone(utteranceId)
            } catch (e: Exception) {
                Log.e(TAG, "[TTS] onError crashed: ${e.message}", e)
            }
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
                // Deduplicate sentences to avoid speaking duplicates
                val sentences = dedupeSentences(SentenceSplitter.splitIntoSentences(fullText))
                val idx = currentAssistantEntryIndex
                if (idx == null || entries.getOrNull(idx)?.isAssistant != true) {
                    val newEntry = ConversationEntry("Assistant", sentences, isAssistant = true)
                    entries.add(newEntry)
                    currentAssistantEntryIndex = entries.lastIndex
                    sentences.forEachIndexed { i, s ->
                        queueTts(SpokenSentence(s, currentAssistantEntryIndex!!, i))
                    }
                    postList()
                } else {
                    val current = entries[idx]
                    entries[idx] = current.copy(sentences = sentences, streamingText = null)
                    postList()
                }
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
                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    _sttNoMatch.postValue(Event(Unit))
                } else {
                    _sttError.postValue(Event("STT Error (code:$error)"))
                }
            }
            override fun onBeginningOfSpeech() { Log.d(TAG, "[STT] onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d(TAG, "[STT] onEndOfSpeech") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun startListening() {
        Log.i(TAG, "[STT] startListening requested")
        if (engine.isActive()) {
            injectDraftIfActive()
            abortEngine()
        }
        if (_isSpeaking.value == true) return

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

        Log.i(TAG, "[STT] Starting listener with config: silenceMs=$silenceMs (from $listenSeconds seconds setting), minLengthMs=$minLengthMs")
        speechRecognizer.startListening(intent)
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

    fun elapsedSinceTtsDone(): Long {
        if (lastTtsDoneAt == 0L) return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - lastTtsDoneAt
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
            Log.e(TAG, "[TTS] Initialization failed: status=$status")
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

    private fun postList() {
        _conversation.postValue(entries.toList())
    }

    private fun queueTts(spoken: SpokenSentence, force: Boolean = false) {
        val key = "${spoken.entryIndex}:${spoken.sentenceIndex}"
        if (!force && !queuedKeys.add(key)) {
            Log.d(TAG, "[TTS] Skipping duplicate: $key")
            return
        }
        ttsQueue.addLast(spoken)
        if (isSpeaking.value != true && currentUtteranceId == null) {
            speakNext()
        }
    }

    private fun clearDedupForEntry(entryIndex: Int) {
        queuedKeys.removeAll { it.startsWith("$entryIndex:") }
    }

    private fun speakNext() {
        if (!ttsReady || ttsQueue.isEmpty()) {
            if (ttsQueue.isEmpty()) {
                _isSpeaking.postValue(false)
                _ttsQueueFinishedEvent.postValue(Event(Unit))
            }
            return
        }
        if (currentUtteranceId != null) return

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
        val effort = prefs.getString("gpt5_effort", "low") ?: "low"       // minimal|low|medium|high
        val verbosity = prefs.getString("gpt5_verbosity", "low") ?: "low" // low|medium|high

        // Try to build approximate user location:
        // 1) from stored prefs,
        // 2) else from device last-known location (if permission granted),
        // 3) else from device defaults (timezone/country),
        // 4) else null (omit).
        val userLoc = resolveApproxUserLocation()

        // Domain filters can be added later via prefs/UI if needed.
        return OpenAiOptions(
            effort = effort,
            verbosity = verbosity,
            filters = null,
            userLocation = userLoc
        )
    }

    // RESTORED: used by injectDraftIfActive()
    private fun buildAssistantDraft(): String? {
        val idx = currentAssistantEntryIndex ?: return null
        val entry = entries.getOrNull(idx) ?: return null
        val base = entry.sentences.joinToString(" ").trim()
        val inStream = entry.streamingText
        return if (!inStream.isNullOrBlank() && inStream.length > base.length) inStream else base
    }

    // Remove duplicate sentences (trim) while preserving order
    private fun dedupeSentences(input: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        input.forEach { s ->
            val t = s.trim()
            if (t.isNotEmpty()) seen.add(t)
        }
        return seen.toList()
    }

    // Build/refresh approximate user location for OpenAI web_search tool.
    // Will persist to prefs so it's available across app restarts.
    private fun resolveApproxUserLocation(): OpenAiOptions.UserLocation? {
        // 1) Prefs
        var country = prefs.getString("user_loc_country", null)
        var city = prefs.getString("user_loc_city", null)
        var region = prefs.getString("user_loc_region", null)
        var timezone = prefs.getString("user_loc_timezone", null)

        // 2) Device fallback (timezone, country)
        if (timezone.isNullOrBlank()) {
            timezone = TimeZone.getDefault().id
        }
        if (country.isNullOrBlank()) {
            val lc = Locale.getDefault()
            if (!lc.country.isNullOrBlank()) country = lc.country // ISO-2
        }

        // 3) Try device last known location + reverse geocode if we don't already have city/region/country
        val ctx = getApplication<Application>()
        val hasCoarse = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if ((city.isNullOrBlank() || region.isNullOrBlank() || country.isNullOrBlank()) && (hasCoarse || hasFine)) {
            try {
                val lm = ctx.getSystemService(Application.LOCATION_SERVICE) as? LocationManager
                val providers = lm?.getProviders(true).orEmpty()
                var best: android.location.Location? = null
                for (prov in providers) {
                    val l = try { lm?.getLastKnownLocation(prov) } catch (_: SecurityException) { null }
                    if (l != null && (best == null || l.time > best!!.time)) best = l
                }
                if (best != null) {
                    try {
                        val geocoder = Geocoder(ctx, Locale.getDefault())
                        val results = geocoder.getFromLocation(best.latitude, best.longitude, 1)
                        if (!results.isNullOrEmpty()) {
                            val addr = results[0]
                            if (country.isNullOrBlank()) country = addr.countryCode ?: addr.countryName
                            if (city.isNullOrBlank()) city = addr.locality ?: addr.subAdminArea ?: addr.subLocality
                            if (region.isNullOrBlank()) region = addr.adminArea ?: addr.subAdminArea
                        }
                    } catch (ge: Exception) {
                        Log.w(TAG, "[LOC] Reverse geocode failed: ${ge.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[LOC] Unable to read last known location: ${e.message}")
            }
        }

        val any = !country.isNullOrBlank() || !city.isNullOrBlank() || !region.isNullOrBlank() || !timezone.isNullOrBlank()
        if (!any) return null

        // Persist for future runs
        prefs.edit().apply {
            country?.let { putString("user_loc_country", it) }
            city?.let { putString("user_loc_city", it) }
            region?.let { putString("user_loc_region", it) }
            timezone?.let { putString("user_loc_timezone", it) }
            apply()
        }

        return OpenAiOptions.UserLocation(
            country = country,
            city = city,
            region = region,
            timezone = timezone
        )
    }

    companion object {
        private val DEFAULT_BASE_PROMPT = """
            You are a helpful assistant. Answer the user's request in clear, complete sentences.
            Do not use JSON or code fences unless explicitly requested.
        """.trimIndent()
    }
}