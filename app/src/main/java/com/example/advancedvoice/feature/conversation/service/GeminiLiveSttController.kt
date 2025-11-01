package com.example.advancedvoice.feature.conversation.service

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.advancedvoice.core.audio.VadRecorder
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.data.gemini_live.GeminiLiveClient
import com.example.advancedvoice.data.gemini_live.GeminiLiveTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GeminiLiveSttController(
    private val scope: CoroutineScope,
    private val vad: VadRecorder,
    private val client: GeminiLiveClient,
    private val transcriber: GeminiLiveTranscriber,
    private val apiKey: String,
    private val model: String
) : SttController {

    companion object {
        private const val TAG = "GeminiLiveService"
    }

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening

    private val _isHearingSpeech = MutableStateFlow(false)
    override val isHearingSpeech: StateFlow<Boolean> = _isHearingSpeech

    private val _isTranscribing = MutableStateFlow(false)
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing

    val partialTranscripts: SharedFlow<String> = transcriber.partialTranscripts

    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val transcripts: SharedFlow<String> = _transcripts

    override val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var audioJob: Job? = null
    private var eventJob: Job? = null
    private var clientErrJob: Job? = null
    private var finalTranscriptJob: Job? = null

    private val stateMutex = Mutex()

    @Volatile private var turnEnded = false

    init {
        connect()
    }

    private fun connect() {
        Log.i(TAG, "[Controller] Ensuring client is connected.")
        transcriber.connect(apiKey, model)

        clientErrJob?.cancel()
        clientErrJob = scope.launch {
            client.errors.collect { msg ->
                Log.e(TAG, "[Controller] Transport error: $msg")
                errors.emit(msg)
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun start(isAutoListen: Boolean) {
        stateMutex.withLock {
            Log.i(TAG, "[Controller] >>> START Request processing (autoListen=$isAutoListen). Current state: listening=${isListening.value}, hearing=${isHearingSpeech.value}")

            if (isListening.value || isHearingSpeech.value) {
                Log.w(TAG, "[Controller] START called while active. Forcing full stop before restart.")
                internalStop(transcribe = false)
            }

            if (!client.ready.value) {
                Log.w(TAG, "[Controller] Client not ready. Reconnecting.")
                releaseAndReconnect()
                delay(1500)
            }

            if (!waitUntilReadyOrTimeout(5000)) {
                Log.e(TAG, "[Controller] Client failed to become ready.")
                errors.tryEmit("STT service failed to connect.")
                return@withLock
            }

            Log.i(TAG, "[Controller] Setup complete. Starting VAD.")
            _isListening.value = true
            _isHearingSpeech.value = false
            _isTranscribing.value = false
            turnEnded = false
            transcriber.startAccumulating()

            eventJob?.cancel(); audioJob?.cancel(); finalTranscriptJob?.cancel()

            eventJob = scope.launch {
                vad.events.collect { ev ->
                    when (ev) {
                        is VadRecorder.VadEvent.SpeechStart -> {
                            if (!_isHearingSpeech.value) {
                                Log.i(TAG, "[VAD] SpeechStart event received.")
                                _isHearingSpeech.value = true
                            }
                        }
                        is VadRecorder.VadEvent.SpeechEnd, is VadRecorder.VadEvent.SilenceTimeout -> {
                            if (turnEnded) return@collect
                            turnEnded = true
                            Log.i(TAG, "[VAD] Speech ended. Stopping VAD and entering transcribing state.")
                            _isListening.value = false
                            _isHearingSpeech.value = false
                            _isTranscribing.value = true
                            vad.stop()

                            // Wait briefly for final transcription chunks
                            scope.launch {
                                delay(800L)
                                transcriber.endTurn()
                            }
                        }
                        is VadRecorder.VadEvent.Error -> errors.emit(ev.message)
                    }
                }
            }

            audioJob = scope.launch {
                vad.audio.collect { frame ->
                    if (isListening.value) client.sendPcm16Le(frame)
                }
            }

            finalTranscriptJob = scope.launch {
                transcriber.finalTranscripts.collect { final ->
                    Log.i(TAG, "[Controller] Collected final transcript (len=${final.length}).")
                    _transcripts.emit(final)
                    _isTranscribing.value = false
                }
            }

            try {
                vad.start()
            } catch (se: SecurityException) {
                Log.e(TAG, "[Controller] Microphone permission denied on start.", se)
                errors.tryEmit("Microphone permission denied")
                internalStop(false)
            }
        }
    }

    override suspend fun stop(transcribe: Boolean) {
        stateMutex.withLock {
            Log.w(TAG, "[Controller] >>> STOP Request (transcribe=$transcribe).")
            internalStop(transcribe)
        }
    }

    private fun internalStop(transcribe: Boolean) {
        if (!isListening.value && !isHearingSpeech.value && !_isTranscribing.value) {
            return
        }

        Log.i(TAG, "[Controller] internalStop: Cleaning up state (transcribe=$transcribe)")

        scope.launch { vad.stop() }

        if (transcribe && (isListening.value || isHearingSpeech.value) && !turnEnded) {
            Log.i(TAG, "[Controller] Transcribing on stop.")
            turnEnded = true
            _isListening.value = false
            _isHearingSpeech.value = false
            _isTranscribing.value = true
            transcriber.endTurn()
        } else {
            _isListening.value = false
            _isHearingSpeech.value = false
            _isTranscribing.value = false
        }

        eventJob?.cancel(); eventJob = null
        audioJob?.cancel(); audioJob = null
        finalTranscriptJob?.cancel(); finalTranscriptJob = null
        turnEnded = false

        Log.i(TAG, "[Controller] internalStop complete. All states are clean.")
    }

    private suspend fun releaseAndReconnect() {
        Log.w(TAG, "[Controller] Releasing and reconnecting WebSocket...")
        internalStop(false)
        clientErrJob?.cancel(); clientErrJob = null
        transcriber.disconnect()
        delay(500)
        connect()
    }

    override fun release() {
        scope.launch {
            stateMutex.withLock {
                internalStop(false)
                releaseAndReconnect()
            }
        }
    }

    private suspend fun waitUntilReadyOrTimeout(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (client.ready.value) return true
            delay(50)
        }
        return false
    }
}