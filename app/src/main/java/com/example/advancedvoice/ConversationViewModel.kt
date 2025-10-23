package com.example.advancedvoice

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.advancedvoice.gemini.GeminiLiveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

enum class GenerationPhase {
    IDLE,
    GENERATING_FIRST,
    GENERATING_REMAINDER,
    SINGLE_SHOT_GENERATING
}

enum class SttSystem {
    STANDARD,
    GEMINI_LIVE
}

class ConversationViewModel(app: Application) : AndroidViewModel(app), TextToSpeech.OnInitListener {
    private val TAG = "ConversationVM"
    private fun now(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    val geminiLiveService: GeminiLiveService by lazy { GeminiLiveService(viewModelScope) }
    private var currentSttSystem = SttSystem.STANDARD

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val audioSampleRate = 16000
    private val audioChannel = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Increased timeout to allow multiple sentences with pauses
    private val VAD_SILENCE_THRESHOLD_RMS = 250.0
    private val VAD_SPEECH_TIMEOUT_MS = 3000L  // Increased from 1500ms
    private val VAD_MIN_SPEECH_DURATION_MS = 400L
    private var silenceStartTimestamp: Long = 0
    private var speechDetectedTimestamp: Long = 0
    @Volatile private var isRecording = false
    @Volatile private var isBargeInMode = false

    private val entries = mutableListOf<ConversationEntry>()
    private val _conversation = MutableLiveData<List<ConversationEntry>>(emptyList())
    val conversation: LiveData<List<ConversationEntry>> = _conversation

    private val _isSpeaking = MutableLiveData(false)
    val isSpeaking: LiveData<Boolean> = _isSpeaking
    private val _isTranscribing = MutableLiveData(false)
    val isTranscribing: LiveData<Boolean> = _isTranscribing
    private val _turnFinishedEvent = MutableLiveData<Event<Unit>>()
    val turnFinishedEvent: LiveData<Event<Unit>> = _turnFinishedEvent
    private val _ttsQueueFinishedEvent = MutableLiveData<Event<Unit>>()
    val ttsQueueFinishedEvent: LiveData<Event<Unit>> = _ttsQueueFinishedEvent
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage
    private val _engineActive = MutableLiveData(false)
    val engineActive: LiveData<Boolean> = _engineActive
    private val _settingsVisible = MutableLiveData(false)
    val settingsVisible: LiveData<Boolean> = _settingsVisible
    private val _generationPhase = MutableLiveData(GenerationPhase.IDLE)
    val generationPhase: LiveData<GenerationPhase> = _generationPhase
    private val _llmDelivered = MutableLiveData(false)
    val llmDelivered: LiveData<Boolean> = _llmDelivered
    private val _uiControls = MediatorLiveData<ControlsState>()
    val uiControls: LiveData<ControlsState> = _uiControls

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val ttsQueue = ArrayDeque<Pair<String, Int>>()
    private var ttsInitialized = false
    private var currentUtteranceId: String? = null

    private val _sttIsListening = MutableLiveData(false)
    val sttIsListening: LiveData<Boolean> = _sttIsListening
    private val _sttError = MutableLiveData<Event<String>>()
    val sttError: LiveData<Event<String>> = _sttError
    private val _sttNoMatch = MutableLiveData<Event<Unit>>()
    val sttNoMatch: LiveData<Event<Unit>> = _sttNoMatch

    private var lastUserInput: String? = null
    private var lastUserEntryIndex: Int? = null
    private var accumulatedUserInput = StringBuilder()  // Accumulate multiple transcripts
    private var userInterruptedDuringGeneration = false
    private var wasInterruptedByStt = false

    private val MIN_INPUT_LENGTH = 2
    private lateinit var speechRecognizer: SpeechRecognizer
    private var currentModelName: String = "gemini-2.5-pro"
    private var fasterFirstEnabled: Boolean = true

    private val httpClient by lazy { OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build() }
    private val prefs by lazy { app.getSharedPreferences("ai_prefs", Application.MODE_PRIVATE) }

    private var hasDetectedSpeechInTurn = false

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            Log.i(TAG, "[${now()}][TTS] ━━━ Started speaking: utteranceId=$utteranceId")
            _isSpeaking.postValue(true)

            // Stop any active listening when TTS starts
            if (_sttIsListening.value == true) {
                Log.i(TAG, "[${now()}][TTS] ━━━ Stopping listening because TTS started")
                stopListeningOnly(transcribe = false)
            }
        }

        override fun onDone(utteranceId: String?) {
            Log.i(TAG, "[${now()}][TTS] ━━━ Finished speaking: utteranceId=$utteranceId")
            viewModelScope.launch {
                if (utteranceId != currentUtteranceId) {
                    Log.d(TAG, "[${now()}][TTS] Utterance ID mismatch, ignoring")
                    return@launch
                }
                ttsQueue.pollFirst()
                currentUtteranceId = null
                if (ttsQueue.isNotEmpty()) {
                    Log.i(TAG, "[${now()}][TTS] ━━━ Speaking next in queue (${ttsQueue.size} remaining)")
                    speakNext()
                } else {
                    Log.i(TAG, "[${now()}][TTS] ━━━ Queue finished, TTS complete")
                    _isSpeaking.postValue(false)
                    _ttsQueueFinishedEvent.postValue(Event(Unit))
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.e(TAG, "[${now()}][TTS] ━━━ Error speaking: utteranceId=$utteranceId")
            onDone(utteranceId)
        }
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
                        val newSentences = cur.sentences + trimmed
                        entries[idx] = cur.copy(sentences = newSentences)
                        postList()
                    }
                }
            },
            onFirstSentence = { textBlock ->
                Log.i(TAG, "[${now()}][ENGINE] ━━━ First sentence received: '${textBlock.take(100)}'")
                _generationPhase.postValue(GenerationPhase.GENERATING_REMAINDER)

                // Continue barge-in listening during remainder generation
                if (currentSttSystem == SttSystem.GEMINI_LIVE && !isRecording) {
                    Log.i(TAG, "[${now()}][ENGINE] ━━━ Starting barge-in for remainder phase")
                    startListeningForBargeIn()
                }

                if (textBlock.isBlank()) return@Callbacks
                val sentences = SentenceSplitter.splitIntoSentences(textBlock)
                val newEntry = ConversationEntry("Assistant", sentences, isAssistant = true)
                entries.add(newEntry)
                currentAssistantEntryIndex = entries.lastIndex
                queueTts(textBlock, currentAssistantEntryIndex!!)
                postList()
            },
            onRemainingSentences = { sentences ->
                Log.i(TAG, "[${now()}][ENGINE] ━━━ Remaining sentences received: count=${sentences.size}")
                _generationPhase.postValue(GenerationPhase.IDLE)
                if (sentences.isEmpty()) return@Callbacks
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                entries.getOrNull(idx)?.let { cur ->
                    val updatedSentences = cur.sentences + sentences
                    entries[idx] = cur.copy(sentences = updatedSentences)
                    postList()
                    queueTts(sentences.joinToString(" "), idx)
                }
            },
            onFinalResponse = { fullText ->
                Log.i(TAG, "[${now()}][ENGINE] ━━━ Final response received: length=${fullText.length}")
                _generationPhase.postValue(GenerationPhase.IDLE)
                _llmDelivered.postValue(true)

                // DON'T stop listening here if in barge-in mode
                // Let the TTS onStart handle stopping the listener
                if (_sttIsListening.value == true && !isBargeInMode) {
                    Log.i(TAG, "[${now()}][ENGINE] ━━━ Stopping normal STT session")
                    stopListeningOnly(transcribe = false)
                } else if (isBargeInMode) {
                    Log.i(TAG, "[${now()}][ENGINE] ━━━ Barge-in mode active, keeping listener active until TTS starts")
                }

                val sentences = dedupeSentences(SentenceSplitter.splitIntoSentences(fullText))
                val idx = currentAssistantEntryIndex

                if (idx != null && entries.getOrNull(idx)?.isAssistant == true) {
                    val existingSentences = entries[idx].sentences
                    val newSentences = sentences.filter { s -> existingSentences.none { it.trim() == s.trim() } }
                    entries[idx] = entries[idx].copy(sentences = existingSentences + newSentences, streamingText = null)
                    if (newSentences.isNotEmpty()) {
                        queueTts(newSentences.joinToString(" "), idx)
                    }
                } else {
                    val newEntry = ConversationEntry("Assistant", sentences, isAssistant = true)
                    entries.add(newEntry)
                    currentAssistantEntryIndex = entries.lastIndex
                    queueTts(fullText, currentAssistantEntryIndex!!)
                }
                postList()
            },
            onTurnFinish = {
                Log.i(TAG, "[${now()}][ENGINE] ━━━ Turn finished")
                _engineActive.postValue(false)
                _generationPhase.postValue(GenerationPhase.IDLE)
                _turnFinishedEvent.postValue(Event(Unit))
            },
            onSystem = { msg -> addSystemEntry(msg) },
            onError = { msg -> addErrorEntry(msg.take(700)) }
        )
        SentenceTurnEngine(
            uiScope = viewModelScope,
            http = httpClient,
            geminiKeyProvider = { prefs.getString("gemini_key", "").orEmpty() },
            openAiKeyProvider = { prefs.getString("openai_key", "").orEmpty() },
            systemPromptProvider = { getEffectiveSystemPromptFromPrefs() },
            openAiOptionsProvider = { getOpenAiOptionsFromPrefs() },
            prefsProvider = { prefs },
            callbacks = cb
        )
    }

    init {
        Log.i(TAG, "[${now()}][INIT] ━━━ ViewModel created (hashCode=${this.hashCode()})")
        tts = TextToSpeech(getApplication(), this)
        setupSpeechRecognizer()
        setupControlsMediator()

        geminiLiveService.transcriptionResult.observeForever { event ->
            event.getContentIfNotHandled()?.let { result ->
                Log.i(TAG, "[${now()}][GEMINI-LIVE] ━━━ Transcription result received: '$result'")
                handleTranscriptionResult(result, "Gemini Live")
            }
        }
        geminiLiveService.error.observeForever { event ->
            event.getContentIfNotHandled()?.let { errorMessage ->
                Log.e(TAG, "[${now()}][GEMINI-LIVE] ━━━ Error: $errorMessage")
                addErrorEntry(errorMessage)
                stopAll()
            }
        }

        // Start barge-in when TTS starts or when generation starts
        isSpeaking.observeForever { speaking ->
            if (speaking) {
                Log.d(TAG, "[${now()}][BARGE-IN-TRIGGER] TTS started speaking")
            }
        }

        generationPhase.observeForever { phase ->
            if (phase != GenerationPhase.IDLE && currentSttSystem == SttSystem.GEMINI_LIVE && !isRecording) {
                Log.i(TAG, "[${now()}][BARGE-IN-TRIGGER] ━━━ Generation started: $phase, starting barge-in listener")
                startListeningForBargeIn()
            }
        }
    }

    private fun handleTranscriptionResult(result: String, source: String) {
        _isTranscribing.postValue(false)

        Log.i(TAG, "[${now()}][$source] ━━━ Transcription received: '$result' | isRecording=$isRecording | engineActive=${_engineActive.value}")

        if (result.isBlank()) {
            Log.w(TAG, "[${now()}][$source] ━━━ Empty transcription result")
            if (wasInterruptedByStt) {
                Log.i(TAG, "[${now()}][$source] ━━━ Interruption resulted in NO_MATCH. Retrying original query.")
                wasInterruptedByStt = false
                lastUserInput?.let { startTurn(it, currentModelName) }
            } else if (!isRecording) {
                // Only trigger no-match if recording is actually stopped
                _sttNoMatch.postValue(Event(Unit))
            }
            return
        }

        // *** CRITICAL: Check if we're STILL RECORDING ***
        if (isRecording) {
            // User is still speaking - accumulate this transcription, DON'T start LLM yet
            if (accumulatedUserInput.isNotEmpty()) {
                accumulatedUserInput.append(" ")
            }
            accumulatedUserInput.append(result.trim())

            Log.i(TAG, "[${now()}][$source] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.i(TAG, "[${now()}][$source] ━━━ STILL RECORDING - Accumulating")
            Log.i(TAG, "[${now()}][$source] ━━━ New fragment: '$result'")
            Log.i(TAG, "[${now()}][$source] ━━━ Accumulated so far: '${accumulatedUserInput}'")
            Log.i(TAG, "[${now()}][$source] ━━━ Waiting for VAD timeout...")
            Log.i(TAG, "[${now()}][$source] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            // If LLM already started (shouldn't happen, but safety check), abort it
            if (_engineActive.value == true) {
                Log.w(TAG, "[${now()}][$source] ━━━ LLM started prematurely while still recording! Aborting.")
                abortEngineSilently()
            }

            return // Don't start LLM - wait for recording to finish
        }

        // *** Recording has stopped - this is the FINAL input ***

        // Accumulate this last piece
        if (accumulatedUserInput.isNotEmpty()) {
            accumulatedUserInput.append(" ")
        }
        accumulatedUserInput.append(result.trim())

        val fullInput = accumulatedUserInput.toString().trim()

        Log.i(TAG, "[${now()}][$source] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "[${now()}][$source] ━━━ RECORDING STOPPED - Final Input")
        Log.i(TAG, "[${now()}][$source] ━━━ Complete input: '$fullInput'")
        Log.i(TAG, "[${now()}][$source] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // Check if this is an interruption (LLM already generating)
        if (_engineActive.value == true && !_llmDelivered.value!!) {
            Log.i(TAG, "[${now()}][$source] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.i(TAG, "[${now()}][$source] ━━━ USER INTERRUPTED GENERATION")
            Log.i(TAG, "[${now()}][$source] ━━━ Aborting current LLM call")
            Log.i(TAG, "[${now()}][$source] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            userInterruptedDuringGeneration = true
            wasInterruptedByStt = true
            abortEngineSilently()
            stopTts()

            replaceLastUserEntry(fullInput)
            lastUserInput = fullInput
            accumulatedUserInput.clear()

            userInterruptedDuringGeneration = false
            wasInterruptedByStt = false
            _llmDelivered.postValue(false)
            startTurnReplacingLast(fullInput, currentModelName)
            return
        }

        // Normal flow - start LLM with complete input
        handleSttResult(fullInput)
    }

    private fun setupControlsMediator() {
        fun recompute() {
            val state = ControlsLogic.derive(
                isSpeaking = isSpeaking.value == true,
                isListening = sttIsListening.value == true,
                isTranscribing = isTranscribing.value == true,
                generationPhase = generationPhase.value ?: GenerationPhase.IDLE,
                settingsVisible = settingsVisible.value == true
            )
            _uiControls.postValue(state)
        }

        _uiControls.value = ControlsLogic.derive(false, false, false, GenerationPhase.IDLE, false)
        _uiControls.addSource(isSpeaking) { recompute() }
        _uiControls.addSource(sttIsListening) { recompute() }
        _uiControls.addSource(isTranscribing) { recompute() }
        _uiControls.addSource(generationPhase) { recompute() }
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
                Log.d(TAG, "[${now()}][STT] ━━━ onReadyForSpeech")
            }
            override fun onBeginningOfSpeech() {
                Log.i(TAG, "[${now()}][STT] ━━━ onBeginningOfSpeech")
                if (engine.isActive() && _llmDelivered.value != true) {
                    Log.i(TAG, "[${now()}][STT] ━━━ User interrupted generation before final, aborting")
                    userInterruptedDuringGeneration = true
                    wasInterruptedByStt = true
                    abortEngineSilently()
                }
            }
            override fun onResults(results: Bundle?) {
                _sttIsListening.postValue(false)
                wasInterruptedByStt = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "[${now()}][STT] ━━━ onResults: ${matches?.firstOrNull()?.take(50)}")
                if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                    handleSttResult(matches[0])
                }
            }
            override fun onError(error: Int) {
                _sttIsListening.postValue(false)
                val wasInterrupting = userInterruptedDuringGeneration
                userInterruptedDuringGeneration = false
                val errorName = when (error) {
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
                Log.w(TAG, "[${now()}][STT] ━━━ onError: $errorName")

                if (error == SpeechRecognizer.ERROR_CLIENT) {
                    Log.d(TAG, "[${now()}][STT] ERROR_CLIENT (from stopListening), ignoring")
                    return
                }

                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    if (wasInterrupting) {
                        Log.i(TAG, "[${now()}][STT] Interruption resulted in NO_MATCH. Ignoring.")
                    }
                    if (wasInterruptedByStt) {
                        Log.i(TAG, "[${now()}][STT] Interruption resulted in NO_MATCH. Retrying original query.")
                        wasInterruptedByStt = false
                        lastUserInput?.let { startTurn(it, currentModelName) }
                    } else {
                        _sttNoMatch.postValue(Event(Unit))
                    }
                } else {
                    wasInterruptedByStt = false
                    _sttError.postValue(Event("STT Error: $errorName"))
                }
            }
            override fun onEndOfSpeech() {
                _sttIsListening.postValue(false)
                Log.d(TAG, "[${now()}][STT] ━━━ onEndOfSpeech")
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun handleSttResult(newInput: String) {
        val trimmed = newInput.trim()
        if (trimmed.length < MIN_INPUT_LENGTH) {
            Log.w(TAG, "[${now()}][STT] ━━━ FILTERED - input too short (${trimmed.length} < $MIN_INPUT_LENGTH)")
            return
        }

        Log.i(TAG, "[${now()}][STT] ━━━ STARTING LLM with complete input: '$trimmed'")
        addUserEntry(trimmed)
        lastUserInput = trimmed
        accumulatedUserInput.clear()
        _llmDelivered.postValue(false)
        startTurn(trimmed, currentModelName)
    }

    fun startListening() {
        if (_sttIsListening.value == true || isRecording) {
            Log.w(TAG, "[${now()}][STT] ━━━ Already listening, ignoring request.")
            return
        }
        if (_isSpeaking.value == true && currentSttSystem != SttSystem.GEMINI_LIVE) {
            Log.i(TAG, "[${now()}][STT] ━━━ Blocked - TTS is speaking")
            return
        }
        Log.i(TAG, "[${now()}][STT] ━━━ startListening() request received for system: $currentSttSystem")
        wasInterruptedByStt = false
        accumulatedUserInput.clear()  // Start fresh

        when (currentSttSystem) {
            SttSystem.STANDARD -> startStandardSTT()
            SttSystem.GEMINI_LIVE -> {
                if (geminiLiveService.isModelReady.value != true) {
                    addSystemEntry("Gemini Live service not ready. Check API Key or network.")
                    return
                }
                isBargeInMode = false
                startCustomVadRecording()
            }
        }
    }

    fun startListeningForBargeIn() {
        if (_llmDelivered.value == true) {
            Log.d(TAG, "[${now()}][BARGE-IN] ━━━ Blocked - LLM already delivered response")
            return
        }
        if (isRecording) {
            Log.d(TAG, "[${now()}][BARGE-IN] ━━━ Already listening")
            return
        }
        if (currentSttSystem != SttSystem.GEMINI_LIVE) {
            Log.d(TAG, "[${now()}][BARGE-IN] ━━━ Skipped - not using Gemini Live")
            return
        }
        Log.i(TAG, "[${now()}][BARGE-IN] ━━━ Starting barge-in listener to detect user interruption")
        isBargeInMode = true
        startCustomVadRecording()
    }

    private fun startStandardSTT() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer.startListening(intent)
    }

    private fun startCustomVadRecording() {
        if (ActivityCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _sttError.postValue(Event("RECORD_AUDIO permission not granted."))
            return
        }
        val bufferSize = AudioRecord.getMinBufferSize(audioSampleRate, audioChannel, audioFormat)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            _sttError.postValue(Event("AudioRecord parameters are not supported on this device."))
            return
        }
        val mode = if (isBargeInMode) "BARGE-IN" else "NORMAL"
        Log.i(TAG, "[${now()}][VAD] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "[${now()}][VAD] ━━━ Starting VAD Recording Session")
        Log.i(TAG, "[${now()}][VAD] ━━━ Mode: $mode")
        Log.i(TAG, "[${now()}][VAD] ━━━ Timeout: ${VAD_SPEECH_TIMEOUT_MS}ms")
        Log.i(TAG, "[${now()}][VAD] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, audioSampleRate, audioChannel, audioFormat, bufferSize)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            _sttError.postValue(Event("AudioRecord failed to initialize."))
            return
        }

        audioRecord?.startRecording()
        _sttIsListening.postValue(true)
        isRecording = true
        silenceStartTimestamp = 0
        speechDetectedTimestamp = 0
        hasDetectedSpeechInTurn = false

        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            val data = ShortArray(bufferSize / 2)
            var loopIteration = 0
            while (isRecording) {
                val read = audioRecord?.read(data, 0, data.size) ?: -1
                if (read <= 0) {
                    if (isRecording) Log.e(TAG, "[${now()}][VAD] ━━━ AudioRecord read error: $read")
                    break
                }

                loopIteration++

                // Send audio to Gemini Live after speech is detected
                if (currentSttSystem == SttSystem.GEMINI_LIVE && hasDetectedSpeechInTurn) {
                    val byteData = data.to16BitPcm(read)
                    geminiLiveService.transcribeAudio(byteData)
                }

                val rms = calculateRms(data, read)
                val isSilent = rms < VAD_SILENCE_THRESHOLD_RMS
                val now = System.currentTimeMillis()

                if (isSilent) {
                    if (silenceStartTimestamp == 0L && speechDetectedTimestamp > 0L) {
                        silenceStartTimestamp = now
                        Log.i(TAG, "[${now()}][VAD] ━━━ Silence detected, starting ${VAD_SPEECH_TIMEOUT_MS}ms timeout. | Iteration=$loopIteration")
                    }
                    if (silenceStartTimestamp > 0L && now - silenceStartTimestamp > VAD_SPEECH_TIMEOUT_MS) {
                        if (isBargeInMode) {
                            Log.i(TAG, "[${now()}][VAD] ━━━ Barge-in listener timeout, stopping silently. | Iteration=$loopIteration")
                        } else {
                            Log.i(TAG, "[${now()}][VAD] ━━━ Silence timeout reached. Stopping and transcribing. | Iteration=$loopIteration")
                            launch(Dispatchers.Main) { stopAndTranscribe() }
                        }
                        break
                    }
                } else {
                    if (speechDetectedTimestamp == 0L) {
                        speechDetectedTimestamp = now
                        hasDetectedSpeechInTurn = true
                        Log.i(TAG, "[${now()}][VAD] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        Log.i(TAG, "[${now()}][VAD] ━━━ SPEECH DETECTED!")
                        Log.i(TAG, "[${now()}][VAD] ━━━ RMS: $rms")
                        Log.i(TAG, "[${now()}][VAD] ━━━ Mode: $mode")
                        Log.i(TAG, "[${now()}][VAD] ━━━ Iteration: $loopIteration")
                        Log.i(TAG, "[${now()}][VAD] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                        if (isBargeInMode) {
                            Log.i(TAG, "[${now()}][VAD] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                            Log.i(TAG, "[${now()}][VAD] ━━━ BARGE-IN ACTIVATED!")
                            Log.i(TAG, "[${now()}][VAD] ━━━ User interrupted assistant")
                            Log.i(TAG, "[${now()}][VAD] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                            launch(Dispatchers.Main) {
                                userInterruptedDuringGeneration = true
                                wasInterruptedByStt = true
                                abortEngineSilently()
                                stopTts()
                            }
                            isBargeInMode = false
                        }
                    }
                    if (silenceStartTimestamp > 0L) {
                        val pauseDuration = now - silenceStartTimestamp
                        Log.d(TAG, "[${now()}][VAD] ━━━ Speech resumed after ${pauseDuration}ms of silence. | Iteration=$loopIteration")
                        silenceStartTimestamp = 0
                    }
                }
            }
            Log.i(TAG, "[${now()}][VAD] ━━━ VAD recording loop finished. | TotalIterations=$loopIteration")
        }
    }

    private fun stopAndTranscribe() {
        if (!isRecording) {
            Log.d(TAG, "[${now()}][VAD] ━━━ stopAndTranscribe called but not recording")
            return
        }

        Log.i(TAG, "[${now()}][VAD] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "[${now()}][VAD] ━━━ Stopping Recording Session")
        Log.i(TAG, "[${now()}][VAD] ━━━ Accumulated input so far: '${accumulatedUserInput}'")
        Log.i(TAG, "[${now()}][VAD] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        _sttIsListening.postValue(false)
        isRecording = false
        isBargeInMode = false

        viewModelScope.launch {
            recordingJob?.join()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            val capturedDuration = if (speechDetectedTimestamp > 0L) System.currentTimeMillis() - speechDetectedTimestamp else 0L
            Log.i(TAG, "[${now()}][VAD] ━━━ Recording stopped")
            Log.i(TAG, "[${now()}][VAD] ━━━ Speech duration: ${capturedDuration}ms")
            Log.i(TAG, "[${now()}][VAD] ━━━ Min required: ${VAD_MIN_SPEECH_DURATION_MS}ms")
            Log.i(TAG, "[${now()}][VAD] ━━━ Has accumulated input: ${accumulatedUserInput.isNotEmpty()}")

            if (speechDetectedTimestamp > 0L && capturedDuration > VAD_MIN_SPEECH_DURATION_MS) {
                if (currentSttSystem == SttSystem.GEMINI_LIVE) {
                    _isTranscribing.postValue(true)
                    Log.i(TAG, "[${now()}][STT] ━━━ Sending audioStreamEnd to Gemini Live")
                    geminiLiveService.sendAudioStreamEnd()

                    // If we already have accumulated input, we might get one more transcription
                    // or we might need to process what we have
                    if (accumulatedUserInput.isEmpty()) {
                        Log.i(TAG, "[${now()}][STT] ━━━ Waiting for final transcription from Gemini Live...")
                    } else {
                        Log.i(TAG, "[${now()}][STT] ━━━ Already have accumulated input, will combine with any final transcription")
                    }
                }
            } else {
                // No significant NEW speech, but check if we have accumulated input
                if (accumulatedUserInput.isNotEmpty()) {
                    Log.i(TAG, "[${now()}][VAD] ━━━ No new speech, but using accumulated input: '${accumulatedUserInput}'")
                    handleTranscriptionResult(accumulatedUserInput.toString(), "Accumulated")
                } else {
                    Log.i(TAG, "[${now()}][VAD] ━━━ No significant speech detected")
                    handleNoSignificantSpeech()
                }
            }
        }
    }

    private fun handleNoSignificantSpeech() {
        Log.i(TAG, "[${now()}][STT] ━━━ No significant speech detected, not transcribing.")
        if (wasInterruptedByStt) {
            Log.i(TAG, "[${now()}][STT] Interruption resulted in NO_MATCH. Retrying original query.")
            wasInterruptedByStt = false
            lastUserInput?.let { startTurn(it, currentModelName) }
        } else {
            _sttNoMatch.postValue(Event(Unit))
        }
    }

    fun stopListeningOnly(transcribe: Boolean) {
        if (!_sttIsListening.value!! && !isRecording) {
            return
        }
        Log.i(TAG, "[${now()}][STT] ━━━ stopListeningOnly() called | System=$currentSttSystem | transcribe=$transcribe")

        when (currentSttSystem) {
            SttSystem.STANDARD -> {
                _sttIsListening.postValue(false)
                try {
                    speechRecognizer.stopListening()
                } catch (_: Exception) {}
            }
            SttSystem.GEMINI_LIVE -> {
                if (transcribe) {
                    stopAndTranscribe()
                } else {
                    isRecording = false
                    recordingJob?.cancel()
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null
                    _sttIsListening.postValue(false)
                    Log.i(TAG, "[${now()}][STT] ━━━ Stopped listening without transcription")
                }
            }
        }
    }

    private fun calculateRms(data: ShortArray, readSize: Int): Double {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += data[i] * data[i]
        }
        return if (readSize > 0) sqrt(sum / readSize) else 0.0
    }

    private fun ShortArray.to16BitPcm(readSize: Int): ByteArray {
        val bytes = ByteArray(readSize * 2)
        for (i in 0 until readSize) {
            bytes[i * 2] = (this[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (this[i].toInt() shr 8).toByte()
        }
        return bytes
    }

    fun setCurrentModel(modelName: String) {
        this.currentModelName = modelName
    }

    fun applyEngineConfigFromPrefs(prefs: SharedPreferences) {
        val maxSent = prefs.getInt("max_sentences", 4).coerceIn(1, 10)
        val faster = prefs.getBoolean("faster_first", true)
        fasterFirstEnabled = faster
        engine.setMaxSentences(maxSent)
        engine.setFasterFirst(faster)
    }

    fun startTurn(userInput: String, modelName: String) {
        Log.i(TAG, "[${now()}][TURN] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "[${now()}][TURN] ━━━ Starting new turn")
        Log.i(TAG, "[${now()}][TURN] ━━━ Input: '$userInput'")
        Log.i(TAG, "[${now()}][TURN] ━━━ Model: $modelName")
        Log.i(TAG, "[${now()}][TURN] ━━━ isRecording: $isRecording")
        Log.i(TAG, "[${now()}][TURN] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // DON'T stop recording here - it should already be stopped by VAD timeout
        if (isRecording) {
            Log.e(TAG, "[${now()}][TURN] ⚠️⚠️⚠️ WARNING: Recording still active when starting LLM! This shouldn't happen!")
        }

        currentAssistantEntryIndex = null
        _engineActive.postValue(true)
        _llmDelivered.postValue(false)
        userInterruptedDuringGeneration = false
        _generationPhase.postValue(if (fasterFirstEnabled) GenerationPhase.GENERATING_FIRST else GenerationPhase.SINGLE_SHOT_GENERATING)
        engine.startTurn(userInput, modelName)
    }

    private fun startTurnReplacingLast(userInput: String, modelName: String) {
        Log.i(TAG, "[${now()}][TURN] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "[${now()}][TURN] ━━━ Replacing last turn")
        Log.i(TAG, "[${now()}][TURN] ━━━ New Input: '$userInput'")
        Log.i(TAG, "[${now()}][TURN] ━━━ Model: $modelName")
        Log.i(TAG, "[${now()}][TURN] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        currentAssistantEntryIndex = null
        _engineActive.postValue(true)
        _llmDelivered.postValue(false)
        userInterruptedDuringGeneration = false
        _generationPhase.postValue(if (fasterFirstEnabled) GenerationPhase.GENERATING_FIRST else GenerationPhase.SINGLE_SHOT_GENERATING)
        engine.replaceLastUserMessage(userInput)
        engine.startTurnWithCurrentHistory(modelName)
    }

    fun isEngineActive(): Boolean = engine.isActive()

    fun abortEngine() {
        Log.i(TAG, "[${now()}][ENGINE] ━━━ Aborting engine (with notification)")
        engine.abort(silent = false)
        _engineActive.postValue(false)
        _generationPhase.postValue(GenerationPhase.IDLE)
        _llmDelivered.postValue(false)
    }

    private fun abortEngineSilently() {
        Log.i(TAG, "[${now()}][ENGINE] ━━━ Aborting engine (silently)")
        engine.abort(silent = true)
        _engineActive.postValue(false)
        _generationPhase.postValue(GenerationPhase.IDLE)
    }

    fun addUserEntry(text: String) {
        val entry = ConversationEntry("You", listOf(text), isAssistant = false)
        entries.add(entry)
        lastUserEntryIndex = entries.lastIndex
        postList()
    }

    private fun replaceLastUserEntry(text: String) {
        val idx = lastUserEntryIndex
        if (idx != null && idx < entries.size && entries[idx].speaker == "You") {
            Log.i(TAG, "[${now()}][ENTRY] ━━━ Replacing last user entry at index $idx")
            entries[idx] = ConversationEntry("You", listOf(text), isAssistant = false)
            postList()
        } else {
            Log.i(TAG, "[${now()}][ENTRY] ━━━ Adding new user entry (no previous entry to replace)")
            addUserEntry(text)
        }
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
        Log.i(TAG, "[${now()}][CLEAR] ━━━ Clearing conversation")
        stopAll()
        entries.clear()
        engine.clearHistory()
        currentAssistantEntryIndex = null
        lastUserEntryIndex = null
        lastUserInput = null
        userInterruptedDuringGeneration = false
        wasInterruptedByStt = false
        accumulatedUserInput.clear()
        postList()
        _generationPhase.postValue(GenerationPhase.IDLE)
        _llmDelivered.postValue(false)
    }

    private fun queueTts(text: String, entryIndex: Int) {
        if (text.isBlank()) return
        Log.i(TAG, "[${now()}][TTS] ━━━ Queueing text for TTS | entryIndex=$entryIndex | length=${text.length}")
        ttsQueue.addLast(text to entryIndex)
        if (isSpeaking.value != true && currentUtteranceId == null) {
            Log.i(TAG, "[${now()}][TTS] ━━━ TTS idle, speaking immediately")
            speakNext()
        } else {
            Log.i(TAG, "[${now()}][TTS] ━━━ TTS busy, added to queue (queue size: ${ttsQueue.size})")
        }
    }

    private fun speakNext() {
        if (!ttsReady || ttsQueue.isEmpty() || currentUtteranceId != null) {
            Log.d(TAG, "[${now()}][TTS] ━━━ Cannot speak next | ready=$ttsReady | queueEmpty=${ttsQueue.isEmpty()} | currentId=$currentUtteranceId")
            return
        }
        val (textToSpeak, entryIndex) = ttsQueue.first()
        val id = UUID.randomUUID().toString()
        currentUtteranceId = id
        Log.i(TAG, "[${now()}][TTS] ━━━ Speaking next utterance | id=$id | entryIndex=$entryIndex | text='${textToSpeak.take(50)}...'")
        tts.speak(textToSpeak, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun replayEntry(entryIndex: Int) {
        val entry = entries.getOrNull(entryIndex) ?: return
        if (!entry.isAssistant) return
        Log.i(TAG, "[${now()}][REPLAY] ━━━ Replaying entry $entryIndex")
        stopTts()
        val fullText = entry.sentences.joinToString(" ")
        queueTts(fullText, entryIndex)
    }

    fun stopAll() {
        Log.i(TAG, "[${now()}][STOP] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "[${now()}][STOP] ━━━ STOPPING ALL OPERATIONS")
        Log.i(TAG, "[${now()}][STOP] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        abortEngine()
        stopTts()
        stopListeningOnly(transcribe = false)
        accumulatedUserInput.clear()
        isBargeInMode = false
    }

    fun stopTts() {
        Log.i(TAG, "[${now()}][TTS] ━━━ Stopping TTS | queueSize=${ttsQueue.size}")
        try {
            tts.stop()
        } catch (e: Exception) {
            Log.e(TAG, "[${now()}][TTS] ━━━ Error stopping TTS", e)
        }
        _isSpeaking.postValue(false)
        ttsQueue.clear()
        currentUtteranceId = null
    }

    override fun onInit(status: Int) {
        if (ttsInitialized) return
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            ttsInitialized = true
            tts.setLanguage(Locale.getDefault())
            tts.setOnUtteranceProgressListener(utteranceListener)
            Log.i(TAG, "[${now()}][TTS] ━━━ TTS initialized successfully | language=${Locale.getDefault()}")
        } else {
            ttsReady = false
            Log.e(TAG, "[${now()}][TTS] ━━━ TTS initialization failed | status=$status")
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "[${now()}][CLEANUP] ━━━ ViewModel being cleared")
        geminiLiveService.release()
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.release()
        try {
            tts.stop()
            tts.shutdown()
            if (this::speechRecognizer.isInitialized) speechRecognizer.destroy()
            Log.i(TAG, "[${now()}][CLEANUP] ━━━ Cleanup complete")
        } catch (e: Exception) {
            Log.e(TAG, "[${now()}][CLEANUP] ━━━ Error during cleanup", e)
        }
    }

    private fun postList() {
        _conversation.postValue(entries.toList())
    }

    private fun getEffectiveSystemPromptFromPrefs(): String {
        val base = prefs.getString("system_prompt", "").orEmpty().ifBlank { DEFAULT_SYSTEM_PROMPT }
        val extensions = prefs.getString("system_prompt_extensions", "").orEmpty()
        return if (extensions.isBlank()) base else "$base\n\n$extensions"
    }

    private fun getOpenAiOptionsFromPrefs(): OpenAiOptions {
        val effort = prefs.getString("gpt5_effort", "low") ?: "low"
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

    fun setSettingsVisible(visible: Boolean) {
        _settingsVisible.postValue(visible)
    }

    fun setSttSystem(system: SttSystem) {
        if (currentSttSystem == system && system != SttSystem.GEMINI_LIVE) return

        Log.i(TAG, "[${now()}][STT] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "[${now()}][STT] ━━━ Switching STT system to: $system")
        Log.i(TAG, "[${now()}][STT] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        currentSttSystem = system

        if (system != SttSystem.GEMINI_LIVE) geminiLiveService.release()

        when (system) {
            SttSystem.GEMINI_LIVE -> {
                val apiKey = prefs.getString("gemini_key", "").orEmpty()
                if (apiKey.isBlank()) {
                    addSystemEntry("Gemini API Key is missing. Please add it in the settings.")
                    return
                }
                geminiLiveService.initialize(apiKey)
            }
            SttSystem.STANDARD -> {
                Log.i(TAG, "[${now()}][STT] ━━━ Using standard Android STT")
            }
        }
    }

    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful assistant. Answer the user's request in clear, complete sentences.
            Do not use JSON or code fences unless explicitly requested.
        """.trimIndent()
    }
}