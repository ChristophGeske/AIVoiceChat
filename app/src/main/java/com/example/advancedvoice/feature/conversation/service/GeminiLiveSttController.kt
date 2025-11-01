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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
        private const val MAX_SESSIONS_BEFORE_RECONNECT = 5
    }

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening

    private val _isHearingSpeech = MutableStateFlow(false)
    override val isHearingSpeech: StateFlow<Boolean> = _isHearingSpeech

    private val _isTranscribing = MutableStateFlow(false)
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing

    // FIX: Removed 'override' - this is a property specific to GeminiLiveSttController
    val partialTranscripts: SharedFlow<String> = transcriber.partialTranscripts

    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val transcripts: SharedFlow<String> = _transcripts

    override val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var audioJob: Job? = null
    private var eventJob: Job? = null
    private var clientErrJob: Job? = null
    private var finalTranscriptJob: Job? = null

    @Volatile private var turnEnded = false
    @Volatile private var sessionCount = 0

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
    override suspend fun start() {
        Log.i(TAG, "[Controller] >>> START Request. Current state: listening=${isListening.value}, hearing=${isHearingSpeech.value}")

        if (isListening.value || isHearingSpeech.value) {
            Log.w(TAG, "[Controller] START called while active. Forcing full stop before restart.")
            internalStop(transcribe = false)
            delay(300)
        }

        if (sessionCount >= MAX_SESSIONS_BEFORE_RECONNECT || !client.ready.value) {
            Log.w(TAG, "[Controller] Session count ($sessionCount) or disconnected state requires reconnection.")
            releaseAndReconnect()
            delay(1500)
        }

        Log.i(TAG, "[Controller] start -> waiting for setupComplete...")
        val ok = waitUntilReadyOrTimeout(5000)
        if (!ok) {
            Log.e(TAG, "[Controller] Client failed to become ready.")
            errors.tryEmit("STT service failed to connect. Please check network/API key.")
            return
        }

        Log.i(TAG, "[Controller] Setup complete. Starting VAD for session #${sessionCount + 1}")
        _isListening.value = true
        _isHearingSpeech.value = false
        _isTranscribing.value = false
        turnEnded = false

        transcriber.startAccumulating()

        eventJob?.cancel()
        audioJob?.cancel()
        finalTranscriptJob?.cancel()

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
                        if (turnEnded) {
                            Log.w(TAG, "[VAD] Ignoring duplicate SpeechEnd event.")
                            return@collect
                        }
                        turnEnded = true
                        Log.i(TAG, "[VAD] Speech ended. Finalizing transcript.")
                        vad.stop()
                        transcriber.endTurn()
                    }
                    is VadRecorder.VadEvent.Error -> errors.emit(ev.message)
                }
            }
        }

        audioJob = scope.launch {
            vad.audio.collect { frame ->
                if (isListening.value) {
                    client.sendPcm16Le(frame)
                }
            }
        }

        finalTranscriptJob = scope.launch {
            transcriber.finalTranscripts.collect { final ->
                Log.i(TAG, "[Controller] Collected final transcript (len=${final.length}).")
                sessionCount++
                _transcripts.emit(final)
                Log.i(TAG, "[Controller] Session #$sessionCount completed.")
                internalStop(false)
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

    override suspend fun stop(transcribe: Boolean) {
        Log.w(TAG, "[Controller] >>> STOP Request (transcribe=$transcribe).")
        internalStop(transcribe)
    }

    private fun internalStop(transcribe: Boolean) {
        Log.i(TAG, "[Controller] internalStop: Cleaning up state (transcribe=$transcribe)")

        scope.launch { vad.stop() }

        if (transcribe && (isListening.value || isHearingSpeech.value) && !turnEnded) {
            Log.i(TAG, "[Controller] Transcribing on stop.")
            turnEnded = true
            transcriber.endTurn()
        }

        eventJob?.cancel(); eventJob = null
        audioJob?.cancel(); audioJob = null

        _isListening.value = false
        _isHearingSpeech.value = false
        _isTranscribing.value = false
        turnEnded = false

        Log.i(TAG, "[Controller] internalStop complete. States are now clean.")
    }

    private suspend fun releaseAndReconnect() {
        Log.w(TAG, "[Controller] Releasing and reconnecting WebSocket...")
        internalStop(false)
        finalTranscriptJob?.cancel(); finalTranscriptJob = null
        clientErrJob?.cancel(); clientErrJob = null
        transcriber.disconnect()
        sessionCount = 0
        delay(500)
        connect()
    }

    override fun release() {
        Log.w(TAG, "[Controller] >>> RELEASE Request. Full cleanup and disconnect.")
        scope.launch {
            internalStop(false)
            releaseAndReconnect()
        }
    }

    private suspend fun waitUntilReadyOrTimeout(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (client.ready.value) return true
            delay(50)
        }
        return client.ready.value
    }
}