package com.example.advancedvoice.feature.conversation.service

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.advancedvoice.core.audio.VadRecorder
import com.example.advancedvoice.core.prefs.Prefs
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
    private val app: Application,
    private val vad: VadRecorder,
    private val client: GeminiLiveClient,
    private val transcriber: GeminiLiveTranscriber,
    private val apiKey: String,
    private val model: String
) : SttController {

    companion object {
        private const val TAG = "GeminiLiveService"
        private const val END_OF_TURN_DELAY_MS = 1200L
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

    @Volatile
    private var turnEnded = false
    private var endTurnJob: Job? = null

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

    private suspend fun ensureConnection(): Boolean {
        if (client.ready.value) {
            Log.d(TAG, "[Connection] Client is already ready.")
            return true
        }

        Log.w(TAG, "[Connection] Client not ready. Attempting to connect with retries...")
        val maxRetries = 3
        var currentRetry = 0

        while (currentRetry < maxRetries) {
            currentRetry++
            Log.i(TAG, "[Connection] Attempt $currentRetry of $maxRetries...")

            releaseAndReconnect()

            if (waitUntilReadyOrTimeout(7000)) {
                Log.i(TAG, "[Connection] Successfully reconnected!")
                return true
            } else {
                Log.e(TAG, "[Connection] Attempt $currentRetry failed to connect within timeout.")
                if (currentRetry < maxRetries) {
                    val delayMs = currentRetry * 1000L
                    Log.i(TAG, "[Connection] Waiting ${delayMs}ms before next retry.")
                    delay(delayMs)
                }
            }
        }

        Log.e(TAG, "[Connection] All reconnection attempts failed.")
        errors.tryEmit("STT service failed to connect.")
        return false
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun start(isAutoListen: Boolean) {
        stateMutex.withLock {
            Log.i(TAG, "[Controller] >>> START Request (autoListen=$isAutoListen)")
            if (isListening.value || isHearingSpeech.value) internalStop(false)
            if (!ensureConnection()) return@withLock
            commonStart(vad)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startWithCustomGracePeriod(gracePeriodMs: Long) {
        stateMutex.withLock {
            Log.i(TAG, "[Controller] >>> START with CUSTOM grace period: ${gracePeriodMs}ms")
            if (isListening.value || isHearingSpeech.value) internalStop(false)
            if (!ensureConnection()) return@withLock

            val customVad = VadRecorder(
                scope = scope,
                maxSilenceMs = (Prefs.getListenSeconds(app) * 1000L).coerceIn(2000L, 30000L),
                startupGracePeriodMs = gracePeriodMs,
                allowMultipleUtterances = false
            )
            commonStart(customVad)
        }
    }

    private fun commonStart(vadInstance: VadRecorder) {
        Log.i(TAG, "[Controller] Setup complete. Starting VAD.")
        _isListening.value = true
        _isHearingSpeech.value = false
        _isTranscribing.value = false
        turnEnded = false
        transcriber.startAccumulating()

        eventJob?.cancel(); audioJob?.cancel(); finalTranscriptJob?.cancel(); endTurnJob?.cancel()

        setupVadEventHandler(vadInstance)

        audioJob = scope.launch {
            vadInstance.audio.collect { frame -> if (isListening.value) client.sendPcm16Le(frame) }
        }

        finalTranscriptJob = scope.launch {
            transcriber.finalTranscripts.collect { final ->
                Log.i(TAG, "[Controller] Collected final transcript (len=${final.length}).")
                _transcripts.emit(final)
                _isTranscribing.value = false
            }
        }

        scope.launch {
            try {
                vadInstance.start()
            } catch (se: SecurityException) {
                Log.e(TAG, "[Controller] Mic permission denied", se)
                errors.tryEmit("Microphone permission denied")
                internalStop(false)
            }
        }
    }

    private fun setupVadEventHandler(vadInstance: VadRecorder) {
        eventJob = scope.launch {
            vadInstance.events.collect { ev ->
                when (ev) {
                    is VadRecorder.VadEvent.SpeechStart -> {
                        endTurnJob?.cancel()
                        Log.d(TAG, "[VAD] SpeechStart. Canceled end-of-turn timer.")
                        if (!_isHearingSpeech.value) {
                            _isHearingSpeech.value = true
                        }
                    }
                    is VadRecorder.VadEvent.SpeechEnd -> {
                        endTurnJob?.cancel()
                        endTurnJob = scope.launch {
                            Log.d(TAG, "[VAD] SpeechEnd. Starting ${END_OF_TURN_DELAY_MS}ms timer...")
                            delay(END_OF_TURN_DELAY_MS)
                            Log.i(TAG, "[VAD] End-of-turn timer finished. Finalizing.")
                            endTurn(vadInstance, "ConversationalPause")
                        }
                    }
                    is VadRecorder.VadEvent.SilenceTimeout -> {
                        endTurn(vadInstance, "InitialSilenceTimeout")
                    }
                    is VadRecorder.VadEvent.Error -> errors.emit(ev.message)
                }
            }
        }
    }

    private fun endTurn(vadInstance: VadRecorder, reason: String) {
        if (turnEnded) return
        turnEnded = true
        Log.i(TAG, "[VAD] Turn ending due to '$reason'. Stopping VAD and entering transcribing state.")
        _isListening.value = false
        _isHearingSpeech.value = false
        _isTranscribing.value = true
        scope.launch { vadInstance.stop() }
        transcriber.endTurn()
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

        endTurnJob?.cancel()

        // It's safer to stop the main 'vad' instance, as custom ones are temporary
        scope.launch { vad.stop() }

        if (transcribe && (isListening.value || isHearingSpeech.value) && !turnEnded) {
            Log.i(TAG, "[Controller] Transcribing on stop.")
            endTurn(vad, "ManualStop")
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