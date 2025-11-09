package com.example.advancedvoice.core.audio

import android.app.Application
import android.util.Log
import com.example.advancedvoice.data.gemini_live.GeminiLiveClient
import com.example.advancedvoice.data.gemini_live.GeminiLiveTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

class MicrophoneSession(
    private val scope: CoroutineScope,
    private val app: Application,
    private val client: GeminiLiveClient,
    private val transcriber: GeminiLiveTranscriber
) {
    companion object {
        private const val TAG = "MicSession"
        private const val BUFFER_SIZE_MS = 1000L
        private const val FRAME_SIZE_MS = 20L
        private const val MAX_BUFFERED_FRAMES = (BUFFER_SIZE_MS / FRAME_SIZE_MS).toInt()
    }

    private var vad: VadRecorder? = null
    private var audioJob: Job? = null
    private var eventsJob: Job? = null

    private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()

    enum class Mode {
        TRANSCRIBING,
        MONITORING,
        IDLE
    }

    @Volatile private var currentMode = Mode.IDLE

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    private val _currentVadMode = MutableStateFlow(Mode.IDLE)
    val currentVadMode: StateFlow<Mode> = _currentVadMode

    val vadEvents: SharedFlow<VadRecorder.VadEvent>?
        get() = vad?.events

    suspend fun start() {
        if (vad != null) {
            Log.w(TAG, "Session already started")
            return
        }

        Log.i(TAG, "Starting continuous microphone session")

        // ✅ FIXED: Don't use VAD silence timeout for continuous sessions
        // The timeout is handled by GeminiLiveSttController.sessionTimeoutJob instead
        Log.i(TAG, "VAD configured with NO silence timeout (continuous session mode)")

        vad = VadRecorder(
            scope = scope,
            sampleRate = 16_000,
            silenceThresholdRms = 350.0,
            maxSilenceMs = null, // ✅ FIXED: null = infinite, no automatic timeout
            endOfSpeechMs = 1200L,
            allowMultipleUtterances = true,
            startupGracePeriodMs = 0L,
            minSpeechDurationMs = 150L
        )

        audioJob = scope.launch {
            vad?.audio?.collect { frame ->
                when (currentMode) {
                    Mode.TRANSCRIBING -> {
                        client.sendPcm16Le(frame)
                    }
                    Mode.MONITORING -> {
                        bufferAudio(frame)
                    }
                    Mode.IDLE -> {
                        // Discard audio
                    }
                }
            }
        }

        try {
            vad?.start()
            _isActive.value = true
            Log.i(TAG, "Microphone session started successfully")
        } catch (se: SecurityException) {
            Log.e(TAG, "Permission denied: ${se.message}")
            cleanup()
            throw se
        }
    }

    private fun bufferAudio(frame: ByteArray) {
        audioBuffer.offer(frame.copyOf())

        while (audioBuffer.size > MAX_BUFFERED_FRAMES) {
            audioBuffer.poll()
        }
    }

    private fun flushBufferToTranscriber() {
        val bufferedFrames = audioBuffer.toList()
        audioBuffer.clear()

        if (bufferedFrames.isNotEmpty()) {
            Log.i(TAG, "Flushing ${bufferedFrames.size} buffered audio frames to transcriber")
            scope.launch {
                bufferedFrames.forEach { frame ->
                    client.sendPcm16Le(frame)
                }
            }
        }
    }

    fun switchMode(newMode: Mode) {
        if (vad == null) {
            Log.w(TAG, "Cannot switch mode - VAD not started")
            return
        }

        if (currentMode == newMode) {
            Log.d(TAG, "Already in mode $newMode")
            return
        }

        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "MODE SWITCH: $currentMode → $newMode")
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val previousMode = currentMode
        currentMode = newMode
        _currentVadMode.value = newMode

        when (newMode) {
            Mode.TRANSCRIBING -> {
                transcriber.startAccumulating()

                if (previousMode == Mode.MONITORING) {
                    flushBufferToTranscriber()
                } else if (previousMode == Mode.IDLE) {
                    Log.w(TAG, "[Mode] Clearing buffer from IDLE (may contain TTS echo)")
                    audioBuffer.clear()
                }

                Log.d(TAG, "[Mode] Audio → Gemini Live Transcriber")
            }
            Mode.MONITORING -> {
                if (previousMode == Mode.IDLE) {
                    audioBuffer.clear()
                    Log.d(TAG, "[Mode] Cleared buffer after IDLE (TTS echo prevention)")
                }
                Log.d(TAG, "[Mode] Audio → Buffered (VAD events only)")
            }
            Mode.IDLE -> {
                audioBuffer.clear()
                Log.d(TAG, "[Mode] Audio → Discarded (Fully idle)")
            }
        }
    }

    fun endCurrentTranscription() {
        if (currentMode == Mode.TRANSCRIBING) {
            Log.i(TAG, "Ending transcription, switching to MONITORING")
            transcriber.endTurn()
            currentMode = Mode.MONITORING
            _currentVadMode.value = Mode.MONITORING
            audioBuffer.clear()
        }
    }

    suspend fun stop() {
        Log.i(TAG, "Stopping microphone session")
        cleanup()
    }

    private suspend fun cleanup() {
        audioJob?.cancel()
        eventsJob?.cancel()

        vad?.stop()
        vad = null

        audioBuffer.clear()
        currentMode = Mode.IDLE
        _currentVadMode.value = Mode.IDLE
        _isActive.value = false

        Log.i(TAG, "Session cleanup complete")
    }
}