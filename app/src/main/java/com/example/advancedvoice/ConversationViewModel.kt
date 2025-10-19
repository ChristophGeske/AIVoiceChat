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
import android.os.SystemClock
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
import com.example.advancedvoice.whisper.WhisperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
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

class ConversationViewModel(app: Application) : AndroidViewModel(app), TextToSpeech.OnInitListener {
    private val TAG = "ConversationVM"
    private fun now(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    val whisperService: WhisperService by lazy { WhisperService(getApplication(), viewModelScope) }
    private var useWhisperStt = false

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val audioBuffer = ByteArrayOutputStream()
    private val audioSampleRate = 16000
    private val audioChannel = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val VAD_SILENCE_THRESHOLD_RMS = 250.0
    private val VAD_SPEECH_TIMEOUT_MS = 1000L
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
    private var userInterruptedDuringGeneration = false
    private var wasInterruptedByStt = false

    private val MIN_INPUT_LENGTH = 8
    private lateinit var speechRecognizer: SpeechRecognizer
    private var currentModelName: String = "gemini-2.5-pro"
    private var fasterFirstEnabled: Boolean = true

    private val httpClient by lazy { OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build() }
    private val prefs by lazy { app.getSharedPreferences("ai_prefs", Application.MODE_PRIVATE) }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _isSpeaking.postValue(true)
        }

        override fun onDone(utteranceId: String?) {
            viewModelScope.launch {
                if (utteranceId != currentUtteranceId) return@launch
                ttsQueue.pollFirst()
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
                        val newSentences = cur.sentences + trimmed
                        entries[idx] = cur.copy(sentences = newSentences)
                        postList()
                    }
                }
            },
            onFirstSentence = { textBlock ->
                _generationPhase.postValue(GenerationPhase.GENERATING_REMAINDER)
                if (textBlock.isBlank()) return@Callbacks
                val sentences = SentenceSplitter.splitIntoSentences(textBlock)
                val newEntry = ConversationEntry("Assistant", sentences, isAssistant = true)
                entries.add(newEntry)
                currentAssistantEntryIndex = entries.lastIndex
                queueTts(textBlock, currentAssistantEntryIndex!!)
                postList()
            },
            onRemainingSentences = { sentences ->
                _generationPhase.postValue(GenerationPhase.IDLE)
                if (sentences.isEmpty()) return@Callbacks
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                entries.getOrNull(idx)?.let { cur ->
                    val updatedSentences = cur.sentences + sentences
                    entries[idx] = cur.copy(sentences = updatedSentences)
                    queueTts(sentences.joinToString(" "), idx)
                    postList()
                }
            },
            onFinalResponse = { fullText ->
                _generationPhase.postValue(GenerationPhase.IDLE)
                _llmDelivered.postValue(true)
                if (_sttIsListening.value == true) stopListeningOnly(transcribe = false)
                val sentences = dedupeSentences(SentenceSplitter.splitIntoSentences(fullText))
                val idx = currentAssistantEntryIndex
                if (idx != null && entries.getOrNull(idx)?.isAssistant == true) {
                    val existingText = entries[idx].sentences.joinToString(" ")
                    if (fullText.trim() != existingText.trim()) {
                        entries[idx] = entries[idx].copy(sentences = sentences, streamingText = null)
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
            callbacks = cb
        )
    }

    init {
        Log.i(TAG, "[${now()}][INIT] ViewModel created (hashCode=${this.hashCode()})")
        tts = TextToSpeech(getApplication(), this)
        setupSpeechRecognizer()
        setupControlsMediator()
        whisperService.transcriptionResult.observeForever { event ->
            event.getContentIfNotHandled()?.let { result ->
                _isTranscribing.postValue(false)
                if (result.isNotBlank()) {
                    handleSttResult(result)
                } else {
                    Log.w(TAG, "[${now()}][Whisper] Empty transcription result")
                    if (wasInterruptedByStt) {
                        Log.i(TAG, "[${now()}][Whisper] Interruption resulted in NO_MATCH. Retrying original query.")
                        wasInterruptedByStt = false
                        lastUserInput?.let { startTurn(it, currentModelName) }
                    } else {
                        _sttNoMatch.postValue(Event(Unit))
                    }
                }
            }
        }
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
            override fun onReadyForSpeech(params: Bundle?) { _sttIsListening.postValue(true); Log.d(TAG, "[${now()}][STT] onReadyForSpeech") }
            override fun onBeginningOfSpeech() {
                Log.i(TAG, "[${now()}][STT] onBeginningOfSpeech")
                if (_isSpeaking.value == true) {
                    Log.w(TAG, "[${now()}][STT] TTS is active. Stopping listening.")
                    stopListeningOnly(transcribe = false)
                    return
                }
                if (engine.isActive() && _llmDelivered.value != true) {
                    Log.i(TAG, "[${now()}][STT] User interrupted generation before final, aborting")
                    userInterruptedDuringGeneration = true
                    wasInterruptedByStt = true
                    abortEngineSilently()
                }
            }
            override fun onResults(results: Bundle?) {
                _sttIsListening.postValue(false)
                wasInterruptedByStt = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "[${now()}][STT] onResults: ${matches?.firstOrNull()?.take(50)}")
                if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                    handleSttResult(matches[0])
                }
            }
            override fun onError(error: Int) {
                _sttIsListening.postValue(false)
                val wasInterrupting = userInterruptedDuringGeneration
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"; SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"; SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"; SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"; SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"; else -> "UNKNOWN_$error"
                }
                Log.w(TAG, "[${now()}][STT] onError: $errorName")
                if (error == SpeechRecognizer.ERROR_CLIENT) { Log.d(TAG, "[${now()}][STT] ERROR_CLIENT (from stopListening), ignoring"); return }
                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    if (wasInterrupting) Log.i(TAG, "[${now()}][STT] Interruption resulted in NO_MATCH. Ignoring.")
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
            override fun onEndOfSpeech() { _sttIsListening.postValue(false); Log.d(TAG, "[${now()}][STT] onEndOfSpeech") }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun handleSttResult(newInput: String) {
        val trimmed = newInput.trim()
        if (trimmed.length < MIN_INPUT_LENGTH) {
            Log.w(TAG, "[${now()}][STT] FILTERED - input too short (${trimmed.length} < $MIN_INPUT_LENGTH)")
            if (wasInterruptedByStt) {
                Log.i(TAG, "[${now()}][STT] Interruption result was too short. Retrying original query.")
                wasInterruptedByStt = false
                lastUserInput?.let { startTurn(it, currentModelName) }
            }
            return
        }
        if (_isSpeaking.value == true) {
            Log.i(TAG, "[${now()}][STT] Ignored speech - TTS is speaking")
            return
        }
        if (userInterruptedDuringGeneration) {
            Log.i(TAG, "[${now()}][STT] Combining with previous and replacing")
            userInterruptedDuringGeneration = false
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
        if (_isSpeaking.value == true) { Log.i(TAG, "[${now()}][STT] Blocked - TTS is speaking"); return }
        if (_sttIsListening.value == true || isRecording) { Log.w(TAG, "[${now()}][STT] Already listening, ignoring request."); return }
        Log.i(TAG, "[${now()}][STT] startListening() request received.")
        wasInterruptedByStt = false
        if (useWhisperStt) {
            if (whisperService.isModelReady.value != true) { addSystemEntry("Whisper model is not ready. Please check settings."); return }
            isBargeInMode = false
            startWhisperRecordingWithVAD()
        } else {
            startStandardSTT()
        }
    }

    fun startListeningForBargeIn() {
        if (_llmDelivered.value == true) { Log.d(TAG, "[BargeIn] Blocked - LLM already delivered response"); return }
        if (_sttIsListening.value == true || isRecording) { Log.d(TAG, "[BargeIn] Already listening"); return }
        Log.i(TAG, "[BargeIn] Starting listening to detect user interruption")
        isBargeInMode = true
        startWhisperRecordingWithVAD()
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

    private fun startWhisperRecordingWithVAD() {
        if (ActivityCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { _sttError.postValue(Event("RECORD_AUDIO permission not granted.")); return }
        val bufferSize = AudioRecord.getMinBufferSize(audioSampleRate, audioChannel, audioFormat)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE) { _sttError.postValue(Event("AudioRecord parameters are not supported on this device.")); return }
        val mode = if (isBargeInMode) "BARGE-IN" else "NORMAL"
        Log.i(TAG, "[WhisperVAD] ━━━ Starting Whisper with custom VAD | Mode=$mode | Timeout=${VAD_SPEECH_TIMEOUT_MS}ms | bufferSize=$bufferSize")
        audioBuffer.reset()
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, audioSampleRate, audioChannel, audioFormat, bufferSize)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { _sttError.postValue(Event("AudioRecord failed to initialize.")); return }
        audioRecord?.startRecording()
        _sttIsListening.postValue(true)
        isRecording = true
        silenceStartTimestamp = 0
        speechDetectedTimestamp = 0
        Log.i(TAG, "[WhisperVAD] AudioRecord started, isRecording=$isRecording")
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            Log.i(TAG, "[WhisperVAD] ━━━ Background thread started | isRecording=$isRecording | BargeIn=$isBargeInMode")
            val data = ShortArray(bufferSize / 2)
            while (isRecording) {
                val read = audioRecord?.read(data, 0, data.size) ?: -1
                if (read <= 0) {
                    if (isRecording) Log.e(TAG, "[WhisperVAD] AudioRecord.read() error or end of stream: $read")
                    break
                }
                val rms = calculateRms(data, read)
                val isSilent = rms < VAD_SILENCE_THRESHOLD_RMS
                val now = System.currentTimeMillis()
                if (isSilent) {
                    if (silenceStartTimestamp == 0L && speechDetectedTimestamp > 0L) {
                        silenceStartTimestamp = now
                        Log.i(TAG, "[WhisperVAD] ━━━ Silence started | Will timeout in ${VAD_SPEECH_TIMEOUT_MS}ms")
                    }
                    if (silenceStartTimestamp > 0L && now - silenceStartTimestamp > VAD_SPEECH_TIMEOUT_MS) {
                        Log.i(TAG, "[WhisperVAD] ━━━ Silence timeout reached (${now - silenceStartTimestamp}ms) | Stopping to transcribe")
                        launch(Dispatchers.Main) { stopAndTranscribe() }
                        break
                    }
                } else {
                    if (speechDetectedTimestamp == 0L) {
                        speechDetectedTimestamp = now
                        Log.i(TAG, "[WhisperVAD] ━━━ SPEECH DETECTED! | RMS=$rms (threshold=$VAD_SILENCE_THRESHOLD_RMS)")
                        if (isBargeInMode && engine.isActive() && _llmDelivered.value != true) {
                            Log.i(TAG, "[WhisperVAD] ━━━ BARGE-IN! User interrupted generation - will abort and combine")
                            launch(Dispatchers.Main) { userInterruptedDuringGeneration = true; wasInterruptedByStt = true; abortEngineSilently() }
                            isBargeInMode = false
                        }
                    }
                    if (silenceStartTimestamp > 0L) {
                        Log.d(TAG, "[WhisperVAD] Speech resumed after ${now - silenceStartTimestamp}ms of silence")
                        silenceStartTimestamp = 0
                    }
                    val byteData = data.to16BitPcm()
                    audioBuffer.write(byteData, 0, read * 2)
                }
            }
            Log.i(TAG, "[WhisperVAD] ━━━ Recording loop finished | isRecording=$isRecording")
        }
    }

    private fun stopAndTranscribe() {
        if (!isRecording) return
        isBargeInMode = false
        _sttIsListening.postValue(false)
        isRecording = false
        recordingJob?.cancel(); recordingJob = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        val capturedDuration = if (speechDetectedTimestamp > 0L) System.currentTimeMillis() - speechDetectedTimestamp else 0L
        val audioSize = audioBuffer.size()
        Log.i(TAG, "[WhisperVAD] ━━━ StopAndTranscribe | AudioSize=${audioSize} bytes | SpeechDuration=${capturedDuration}ms")
        if (speechDetectedTimestamp > 0L && capturedDuration > VAD_MIN_SPEECH_DURATION_MS) {
            val audioData = audioBuffer.toByteArray()
            _isTranscribing.postValue(true)
            Log.i(TAG, "[Whisper] ━━━ Transcribing ${audioData.size} bytes.")
            whisperService.transcribeAudio(audioData)
        } else {
            Log.i(TAG, "[Whisper] ━━━ No significant speech detected, not transcribing.")
            if (wasInterruptedByStt) {
                Log.i(TAG, "[Whisper] Interruption resulted in NO_MATCH. Retrying original query.")
                wasInterruptedByStt = false
                lastUserInput?.let { startTurn(it, currentModelName) }
            } else {
                _sttNoMatch.postValue(Event(Unit))
            }
        }
    }

    fun stopListeningOnly(transcribe: Boolean) {
        if (!_sttIsListening.value!! && !isRecording) { return }
        Log.i(TAG, "[${now()}][STT] ━━━ stopListeningOnly() called | useWhisper=$useWhisperStt | isRecording=$isRecording | transcribe=$transcribe")
        _sttIsListening.postValue(false)
        if (useWhisperStt) {
            isRecording = false
            recordingJob?.cancel(); recordingJob = null
            audioRecord?.stop(); audioRecord?.release(); audioRecord = null
            if (transcribe) {
                if (speechDetectedTimestamp > 0L && System.currentTimeMillis() - speechDetectedTimestamp > VAD_MIN_SPEECH_DURATION_MS) {
                    val audioData = audioBuffer.toByteArray()
                    _isTranscribing.postValue(true)
                    whisperService.transcribeAudio(audioData)
                } else {
                    _sttNoMatch.postValue(Event(Unit))
                }
            }
        } else {
            try { speechRecognizer.stopListening() } catch (_: Exception) {}
        }
    }

    private fun calculateRms(data: ShortArray, readSize: Int): Double {
        var sum = 0.0
        for (i in 0 until readSize) { sum += data[i] * data[i] }
        return if (readSize > 0) sqrt(sum / readSize) else 0.0
    }

    private fun ShortArray.to16BitPcm(): ByteArray {
        val bytes = ByteArray(this.size * 2)
        for (i in this.indices) {
            bytes[i * 2] = (this[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (this[i].toInt() shr 8).toByte()
        }
        return bytes
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
        _generationPhase.postValue(if (fasterFirstEnabled) GenerationPhase.GENERATING_FIRST else GenerationPhase.SINGLE_SHOT_GENERATING)
        engine.startTurn(userInput, modelName)
    }

    private fun startTurnReplacingLast(userInput: String, modelName: String) {
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
        engine.abort(silent = false)
        _engineActive.postValue(false)
        _generationPhase.postValue(GenerationPhase.IDLE)
        _llmDelivered.postValue(false)
    }
    private fun abortEngineSilently() {
        engine.abort(silent = true)
        _engineActive.postValue(false)
        _generationPhase.postValue(GenerationPhase.IDLE)
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
        stopAll()
        entries.clear()
        engine.clearHistory()
        currentAssistantEntryIndex = null
        lastUserEntryIndex = null
        lastUserInput = null
        userInterruptedDuringGeneration = false
        wasInterruptedByStt = false
        postList()
        _generationPhase.postValue(GenerationPhase.IDLE)
        _llmDelivered.postValue(false)
    }

    private fun queueTts(text: String, entryIndex: Int) {
        if (text.isBlank()) return
        ttsQueue.addLast(text to entryIndex)
        if (isSpeaking.value != true && currentUtteranceId == null) {
            speakNext()
        }
    }

    private fun speakNext() {
        if (!ttsReady || ttsQueue.isEmpty() || currentUtteranceId != null) return
        val (textToSpeak, entryIndex) = ttsQueue.first()
        val id = UUID.randomUUID().toString()
        currentUtteranceId = id
        Log.d(TAG, "[${now()}][TTS Command] Speaking text for entry #${entryIndex}: '${textToSpeak.take(50)}...'")
        tts.speak(textToSpeak, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun replayEntry(entryIndex: Int) {
        val entry = entries.getOrNull(entryIndex) ?: return
        if (!entry.isAssistant) return
        stopTts()
        val fullText = entry.sentences.joinToString(" ")
        queueTts(fullText, entryIndex)
    }

    fun stopAll() {
        abortEngine()
        stopTts()
        stopListeningOnly(transcribe = false)
    }
    fun stopTts() {
        try { tts.stop() } catch (_: Exception) {}
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
        } else {
            ttsReady = false
        }
    }
    override fun onCleared() {
        super.onCleared()
        whisperService.release()
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.release()
        try {
            tts.stop()
            tts.shutdown()
            if (this::speechRecognizer.isInitialized) speechRecognizer.destroy()
        } catch (_: Exception) {}
    }
    private fun postList() { _conversation.postValue(entries.toList()) }

    private fun removeTtsForEntry(entryIndex: Int) {
        val it = ttsQueue.iterator()
        var wasSpeakingThisEntry = ttsQueue.firstOrNull()?.second == entryIndex
        while (it.hasNext()) {
            if (it.next().second == entryIndex) it.remove()
        }
        if (wasSpeakingThisEntry) {
            stopTts()
        }
    }

    private fun getEffectiveSystemPromptFromPrefs(): String {
        val base = prefs.getString("system_prompt", "").orEmpty().ifBlank { DEFAULT_SYSTEM_PROMPT }
        val maxSent = prefs.getInt("max_sentences", 4).coerceIn(1, 10)
        val faster = prefs.getBoolean("faster_first", true)

        // --- KORREKTUR: Strengere Anweisungen ---
        val extras = buildList {
            add("Return plain text only.")
            if (maxSent >= 1) {
                // This instruction is now much more forceful.
                add("IMPORTANT: Your response MUST be AT MOST $maxSent sentences long, unless the user explicitly asks for a longer/shorter response (e.g., 'in detail', 'explain thoroughly'). Prioritize the user's explicit length requests over this general rule.")
            }
            if (faster) {
                add("Begin with a complete first sentence promptly, then continue.")
            }
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

    fun setUseWhisper(enabled: Boolean) {
        useWhisperStt = enabled
        if (enabled) {
            whisperService.initialize(whisperService.multilingualModel)
        } else {
            whisperService.release()
        }
    }

    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful assistant. Answer the user's request in clear, complete sentences.
            Do not use JSON or code fences unless explicitly requested.
        """.trimIndent()
    }
}