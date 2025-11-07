package com.example.advancedvoice.feature.conversation.service

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.advancedvoice.core.audio.MicrophoneSession
import com.example.advancedvoice.core.audio.VadRecorder
import com.example.advancedvoice.data.gemini_live.GeminiLiveClient
import com.example.advancedvoice.data.gemini_live.GeminiLiveTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GeminiLiveSttController(
    private val scope: CoroutineScope,
    private val app: Application,
    private val client: GeminiLiveClient,
    private val transcriber: GeminiLiveTranscriber,
    private val apiKey: String,
    private val model: String
) : SttController {

    companion object {
        private const val TAG = "GeminiLiveSTT"
        private const val END_OF_TURN_DELAY_MS = 250L
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

    private var eventJob: Job? = null
    private var clientErrJob: Job? = null
    private var finalTranscriptJob: Job? = null
    private var endTurnJob: Job? = null

    // ✅ NEW: Single continuous microphone session
    private var micSession: MicrophoneSession? = null

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

    private suspend fun ensureConnection(): Boolean {
        if (client.ready.value) {
            Log.d(TAG, "[Connection] Client is already ready.")
            return true
        }

        Log.w(TAG, "[Connection] Client not ready. Attempting to connect...")
        releaseAndReconnect()

        if (waitUntilReadyOrTimeout(7000)) {
            Log.i(TAG, "[Connection] Successfully reconnected!")
            return true
        }

        Log.e(TAG, "[Connection] Connection failed.")
        errors.tryEmit("STT service failed to connect.")
        return false
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun start(isAutoListen: Boolean) {
        Log.i(TAG, "[Controller] >>> START Request (autoListen=$isAutoListen)")

        if (!ensureConnection()) return

        // ✅ Start continuous mic session if not already running
        if (micSession == null) {
            micSession = MicrophoneSession(scope, app, client, transcriber)
            try {
                micSession?.start()
                setupVadEventHandler()
            } catch (se: SecurityException) {
                Log.e(TAG, "[Controller] Mic permission denied", se)
                errors.tryEmit("Microphone permission denied")
                micSession = null
                return
            }
        }

        _isListening.value = true
        _isHearingSpeech.value = false
        _isTranscribing.value = false
        turnEnded = false

        // ✅ Switch mode instead of starting/stopping
        micSession?.switchMode(MicrophoneSession.Mode.TRANSCRIBING)

        finalTranscriptJob?.cancel()
        finalTranscriptJob = scope.launch {
            transcriber.finalTranscripts.collect { final ->
                Log.i(TAG, "[Controller] Final transcript (len=${final.length})")
                _transcripts.emit(final)
                _isTranscribing.value = false
            }
        }
    }

    private fun setupVadEventHandler() {
        eventJob?.cancel()
        eventJob = scope.launch {
            micSession?.vadEvents?.collect { ev ->
                val currentMode = micSession?.currentVadMode?.value

                when (ev) {
                    is VadRecorder.VadEvent.SpeechStart -> {
                        endTurnJob?.cancel()
                        Log.d(TAG, "[VAD] SpeechStart (mode=$currentMode)")

                        // ✅ Process in TRANSCRIBING and MONITORING modes
                        // ❌ Ignore only in IDLE mode (during TTS)
                        if (currentMode != MicrophoneSession.Mode.IDLE) {
                            if (!_isHearingSpeech.value) {
                                _isHearingSpeech.value = true
                            }
                        } else {
                            Log.d(TAG, "[VAD] Ignoring SpeechStart in IDLE mode (TTS echo)")
                        }
                    }

                    is VadRecorder.VadEvent.SpeechEnd -> {
                        Log.d(TAG, "[VAD] SpeechEnd (mode=$currentMode)")

                        // ✅ Only finalize turn if in TRANSCRIBING mode
                        if (currentMode == MicrophoneSession.Mode.TRANSCRIBING) {
                            endTurnJob?.cancel()
                            endTurnJob = scope.launch {
                                Log.d(TAG, "[VAD] SpeechEnd. Starting ${END_OF_TURN_DELAY_MS}ms timer...")
                                delay(END_OF_TURN_DELAY_MS)
                                Log.i(TAG, "[VAD] Timer finished. Finalizing.")
                                endTurn("ConversationalPause")
                            }
                        } else if (currentMode == MicrophoneSession.Mode.MONITORING) {
                            // In monitoring mode, just update the state flag
                            _isHearingSpeech.value = false
                            Log.d(TAG, "[VAD] Speech ended in MONITORING mode")
                        } else {
                            Log.d(TAG, "[VAD] Ignoring SpeechEnd in IDLE mode")
                        }
                    }

                    is VadRecorder.VadEvent.SilenceTimeout -> {
                        if (currentMode == MicrophoneSession.Mode.TRANSCRIBING) {
                            endTurn("SilenceTimeout")
                        }
                    }

                    is VadRecorder.VadEvent.Error -> errors.emit(ev.message)
                }
            }
        }
    }

    private fun endTurn(reason: String) {
        if (turnEnded) return
        turnEnded = true

        Log.i(TAG, "[VAD] Turn ending: '$reason'")
        _isListening.value = false
        _isHearingSpeech.value = false
        _isTranscribing.value = true

        // ✅ Don't stop mic - just end transcription and switch to monitoring
        micSession?.endCurrentTranscription()
        micSession?.switchMode(MicrophoneSession.Mode.MONITORING)
    }

    override suspend fun stop(transcribe: Boolean) {
        Log.w(TAG, "[Controller] >>> STOP Request (transcribe=$transcribe)")

        endTurnJob?.cancel()

        if (transcribe && (isListening.value || isHearingSpeech.value) && !turnEnded) {
            Log.i(TAG, "[Controller] Transcribing on stop.")
            endTurn("ManualStop")
        } else {
            _isListening.value = false
            _isHearingSpeech.value = false
            _isTranscribing.value = false

            // ✅ Switch to IDLE instead of stopping
            micSession?.switchMode(MicrophoneSession.Mode.IDLE)
        }

        turnEnded = false
    }

    private suspend fun releaseAndReconnect() {
        Log.w(TAG, "[Controller] Releasing and reconnecting WebSocket...")

        micSession?.stop()
        micSession = null

        eventJob?.cancel()
        clientErrJob?.cancel()
        finalTranscriptJob?.cancel()

        transcriber.disconnect()
        delay(500)
        connect()
    }

    override fun release() {
        scope.launch {
            Log.w(TAG, "[Controller] Release called")
            micSession?.stop()
            micSession = null
            transcriber.disconnect()
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

    fun switchMicMode(mode: MicrophoneSession.Mode) {
        Log.i(TAG, "[Controller] Switching mic mode to: $mode")
        micSession?.switchMode(mode)

        // ✅ Force-clear state flags when switching modes
        when (mode) {
            MicrophoneSession.Mode.IDLE -> {
                _isListening.value = false
                _isHearingSpeech.value = false
                _isTranscribing.value = false
                endTurnJob?.cancel()
                Log.d(TAG, "[Controller] All listening states cleared for IDLE mode")
            }
            MicrophoneSession.Mode.MONITORING -> {
                _isListening.value = false
                _isHearingSpeech.value = false
                _isTranscribing.value = false
                endTurnJob?.cancel()
                Log.d(TAG, "[Controller] Listening states cleared for MONITORING mode")
            }
            MicrophoneSession.Mode.TRANSCRIBING -> {
                // States will be set by start()
                Log.d(TAG, "[Controller] TRANSCRIBING mode activated")
            }
        }
    }
}