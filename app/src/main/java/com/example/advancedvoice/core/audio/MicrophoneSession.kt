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

        // Focused debug tags (use these in Logcat filter)
        private const val TAG_MODE = "MODE"
        private const val TAG_VAD_RESET = "VAD_RESET"
        private const val TAG_PRE_ROLL = "PRE_ROLL"

        private const val BUFFER_SIZE_MS = 1000L // keep up to 1s of pre-roll in MONITORING
        private const val FRAME_SIZE_MS = 20L
        private const val MAX_BUFFERED_FRAMES = (BUFFER_SIZE_MS / FRAME_SIZE_MS).toInt()
    }

    private var vad: VadRecorder? = null
    private var audioJob: Job? = null
    private var eventsJob: Job? = null

    private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()

    enum class Mode { TRANSCRIBING, MONITORING, IDLE }

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
        Log.i(TAG, "VAD configured with NO silence timeout (continuous session mode)")

        vad = VadRecorder(
            scope = scope,
            sampleRate = 16_000,
            silenceThresholdRms = 350.0,
            maxSilenceMs = null, // continuous
            endOfSpeechMs = 800L, // how long silence before end-of-speech
            allowMultipleUtterances = true,
            startupGracePeriodMs = 0L,
            minSpeechDurationMs = 150L
        )

        audioJob = scope.launch {
            vad?.audio?.collect { frame ->
                when (currentMode) {
                    Mode.TRANSCRIBING -> client.sendPcm16Le(frame)
                    Mode.MONITORING -> bufferAudio(frame)
                    Mode.IDLE -> { /* discard */ }
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

    fun resetVadDetection() {
        Log.i(TAG_VAD_RESET, "resetDetection() requested by MicrophoneSession")
        vad?.resetDetection()
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
            // 20 ms per frame
            val approxMs = bufferedFrames.size * FRAME_SIZE_MS
            Log.i(TAG_PRE_ROLL, "Flushing ${bufferedFrames.size} buffered frames (~${approxMs}ms) to transcriber")
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

        Log.i(TAG_MODE, "MODE SWITCH: $currentMode → $newMode")

        val previousMode = currentMode
        currentMode = newMode
        _currentVadMode.value = newMode

        when (newMode) {
            Mode.TRANSCRIBING -> {
                // KEY FIX: Preserve VAD state when coming from MONITORING (barge-in)
                if (previousMode != Mode.MONITORING) {
                    Log.i(TAG_VAD_RESET, "Resetting VAD (previousMode=$previousMode)")
                    resetVadDetection()
                } else {
                    Log.i(TAG_VAD_RESET, "Preserving VAD (previousMode=MONITORING) to keep in-flight speech")
                }

                transcriber.startAccumulating()
                if (previousMode == Mode.MONITORING) {
                    flushBufferToTranscriber()
                } else if (previousMode == Mode.IDLE) {
                    // Coming from TTS → do not use any stale buffer that may contain echo
                    Log.w(TAG, "[Mode] Clearing buffer from IDLE (may contain TTS echo)")
                    audioBuffer.clear()
                }
                Log.d(TAG, "[Mode] Audio → Gemini Live Transcriber")
            }
            Mode.MONITORING -> {
                // Keep pre-roll buffer while monitoring so we can flush first words.
                // But if we just came from IDLE (post-TTS), clear it to avoid echo.
                if (previousMode == Mode.IDLE) {
                    audioBuffer.clear()
                    Log.d(TAG, "[Mode] MONITORING after IDLE → cleared buffer (echo prevention)")
                } else {
                    Log.d(TAG, "[Mode] MONITORING → keeping pre-roll buffer")
                }
            }
            Mode.IDLE -> {
                Log.i(TAG_VAD_RESET, "Resetting VAD (entering IDLE)")
                resetVadDetection()
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
            // Keep buffer in MONITORING (we already cleared on entering from IDLE)
        }
    }

    suspend fun stop() {
        Log.i(TAG, "Stopping microphone session")
        cleanup()
    }

    private suspend fun cleanup() {
        audioJob?.cancel()
        eventsJob?.cancel()
        try { vad?.stop() } catch (_: Throwable) {}
        vad = null
        audioBuffer.clear()
        currentMode = Mode.IDLE
        _currentVadMode.value = Mode.IDLE
        _isActive.value = false
        Log.i(TAG, "Session cleanup complete")
    }
}