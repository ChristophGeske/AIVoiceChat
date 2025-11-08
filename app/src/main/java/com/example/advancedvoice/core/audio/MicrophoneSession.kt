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

class MicrophoneSession(
    private val scope: CoroutineScope,
    private val app: Application,
    private val client: GeminiLiveClient,
    private val transcriber: GeminiLiveTranscriber
) {
    companion object {
        private const val TAG = "MicSession"
    }

    private var vad: VadRecorder? = null
    private var audioJob: Job? = null
    private var eventsJob: Job? = null

    enum class Mode {
        TRANSCRIBING,  // Send to Gemini Live
        MONITORING,    // VAD-only for interruption
        IDLE          // Discard audio
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

        // ✅ OPTIMIZED VAD PARAMETERS
        vad = VadRecorder(
            scope = scope,
            sampleRate = 16_000,                    // Standard rate for speech
            silenceThresholdRms = 350.0,            // ✅ Slightly higher to reduce noise false positives
            maxSilenceMs = null,                    // Never timeout - keep listening
            endOfSpeechMs = 1200L,                  // ✅ REDUCED from 1500L for faster response
            allowMultipleUtterances = true,         // Keep going indefinitely
            startupGracePeriodMs = 0L,              // ✅ ALREADY CORRECT - no startup delay
            minSpeechDurationMs = 150L              // ✅ REDUCED from 300L to catch first words faster
        )

        // Audio stream handler
        audioJob = scope.launch {
            vad?.audio?.collect { frame ->
                when (currentMode) {
                    Mode.TRANSCRIBING -> {
                        client.sendPcm16Le(frame)
                    }
                    Mode.MONITORING -> {
                        // Just let VAD detect voice, don't send anywhere
                    }
                    Mode.IDLE -> {
                        // Discard audio
                    }
                }
            }
        }

        // VAD events are exposed via vadEvents flow for external monitoring

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

        currentMode = newMode
        _currentVadMode.value = newMode

        when (newMode) {
            Mode.TRANSCRIBING -> {
                transcriber.startAccumulating()
                Log.d(TAG, "[Mode] Audio → Gemini Live Transcriber")
            }
            Mode.MONITORING -> {
                Log.d(TAG, "[Mode] Audio → Discarded (VAD events only)")
            }
            Mode.IDLE -> {
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

        currentMode = Mode.IDLE
        _currentVadMode.value = Mode.IDLE
        _isActive.value = false

        Log.i(TAG, "Session cleanup complete")
    }
}