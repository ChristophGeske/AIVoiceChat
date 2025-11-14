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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        private const val POST_TTS_GRACE_PERIOD_MS = 400L
    }

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening

    private val _isHearingSpeech = MutableStateFlow(false)
    override val isHearingSpeech: StateFlow<Boolean> = _isHearingSpeech

    private val _isTranscribing = MutableStateFlow(false)
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing

    val partialTranscripts = transcriber.partialTranscripts

    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val transcripts = _transcripts

    override val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var eventJob: Job? = null
    private var clientErrJob: Job? = null
    private var finalTranscriptJob: Job? = null
    private var partialJob: Job? = null
    private var endTurnJob: Job? = null
    private var sessionTimeoutJob: Job? = null

    private var micSession: MicrophoneSession? = null

    @Volatile private var turnEnded = false
    @Volatile private var speechStartedInCurrentSession = false
    @Volatile private var lastTtsStopTime = 0L

    @Volatile private var lastPartialLen = 0
    @Volatile private var lastPartialTimeMs = 0L

    @Volatile private var sessionStartTime = 0L
    @Volatile private var sessionTimeoutMs = 0L

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
        Log.i("STT_CTRL", "start(autoListen=$isAutoListen)")
        if (!ensureConnection()) return

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

        val speechAlreadyInProgress = _isHearingSpeech.value
        Log.i("STT_CTRL", "speechAlreadyInProgress=$speechAlreadyInProgress")

        _isListening.value = true
        _isHearingSpeech.value = speechAlreadyInProgress
        _isTranscribing.value = false
        turnEnded = false

        if (!speechAlreadyInProgress) {
            Log.i("VAD_RESET", "Resetting VAD (reason=STT.start, speechAlreadyInProgress=false)")
            micSession?.resetVadDetection()
        } else {
            Log.i("VAD_RESET", "Skipping VAD reset (reason=STT.start, speechAlreadyInProgress=true)")
        }

        partialJob?.cancel()
        partialJob = scope.launch {
            partialTranscripts.collect { txt ->
                lastPartialLen = txt.length
                lastPartialTimeMs = System.currentTimeMillis()

                if (txt.isNotBlank()) {
                    sessionTimeoutJob?.cancel()
                    Log.d(TAG, "[Controller] Partials detected - cancelling session timeout (real words)")
                }
            }
        }

        speechStartedInCurrentSession = if (speechAlreadyInProgress) {
            Log.i(TAG, "[Controller] Starting with speech already in progress - marking as started")
            true
        } else {
            false
        }

        micSession?.switchMode(MicrophoneSession.Mode.TRANSCRIBING)

        finalTranscriptJob?.cancel()
        finalTranscriptJob = scope.launch {
            transcriber.finalTranscripts.collect { final ->
                Log.i(TAG, "[Controller] Final transcript (len=${final.length})")

                if (final.isNotBlank()) {
                    sessionTimeoutJob?.cancel()
                    Log.d(TAG, "[Controller] Real words detected - cancelling session timeout, switching to MONITORING")
                    micSession?.switchMode(MicrophoneSession.Mode.MONITORING)
                } else {
                    Log.d(TAG, "[Controller] Empty transcript (noise) - staying in TRANSCRIBING")
                }

                _transcripts.emit(final)
                _isTranscribing.value = false
            }
        }

        sessionTimeoutJob?.cancel()
        if (speechAlreadyInProgress) {
            Log.i(TAG, "[Controller] Skipping session timeout because speech is already in progress.")
        } else {
            // ✅ NEW: Track session start time
            val listenSeconds = com.example.advancedvoice.core.prefs.Prefs.getListenSeconds(app)
            sessionTimeoutMs = listenSeconds * 1000L
            sessionStartTime = System.currentTimeMillis()

            Log.i(TAG, "[Controller] Starting ${listenSeconds}s session timeout at $sessionStartTime")
            sessionTimeoutJob = scope.launch {
                delay(sessionTimeoutMs)
                Log.w(TAG, "[Controller] ⏱️ Session timeout after ${listenSeconds}s - no speech detected")
                if (_isListening.value && !turnEnded) {
                    endTurn("SessionTimeout")
                }
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
                        val timeSinceTts = System.currentTimeMillis() - lastTtsStopTime

                        val shouldSuppressEcho = (currentMode == MicrophoneSession.Mode.IDLE ||
                                currentMode == MicrophoneSession.Mode.MONITORING) &&
                                lastTtsStopTime > 0 &&
                                timeSinceTts < POST_TTS_GRACE_PERIOD_MS

                        if (shouldSuppressEcho) {
                            Log.w(TAG, "[VAD] Ignoring SpeechStart (${timeSinceTts}ms after TTS - likely echo) in mode=$currentMode")
                            return@collect
                        }

                        endTurnJob?.cancel()
                        sessionTimeoutJob?.cancel()
                        Log.d(TAG, "[VAD] Timeout cancelled - speech detected")

                        Log.d(TAG, "[VAD] SpeechStart (mode=$currentMode)")

                        if (currentMode != MicrophoneSession.Mode.IDLE) {
                            if (!_isHearingSpeech.value) _isHearingSpeech.value = true

                            // ✅ NEW: Switch from MONITORING to TRANSCRIBING when speech detected
                            if (currentMode == MicrophoneSession.Mode.MONITORING) {
                                Log.i(TAG, "[VAD] Speech in MONITORING - switching to TRANSCRIBING")
                                micSession?.switchMode(MicrophoneSession.Mode.TRANSCRIBING)
                                speechStartedInCurrentSession = true
                                _isListening.value = true

                                // Start a session timeout for the new transcription
                                val listenSeconds = com.example.advancedvoice.core.prefs.Prefs.getListenSeconds(app)
                                val timeoutMs = listenSeconds * 1000L
                                sessionTimeoutJob?.cancel()
                                sessionTimeoutJob = scope.launch {
                                    delay(timeoutMs)
                                    Log.w(TAG, "[Controller] ⏱️ Session timeout after ${listenSeconds}s in TRANSCRIBING")
                                    if (!turnEnded) {
                                        endTurn("SessionTimeout")
                                    }
                                }
                            }

                            if (currentMode == MicrophoneSession.Mode.TRANSCRIBING) {
                                speechStartedInCurrentSession = true
                                Log.d(TAG, "[VAD] Speech started in current TRANSCRIBING session")
                            }
                        } else {
                            Log.d(TAG, "[VAD] Ignoring SpeechStart in IDLE mode (TTS echo)")
                        }
                    }

                    // ✅ ADD THIS: The missing SpeechEnd branch
                    is VadRecorder.VadEvent.SpeechEnd -> {
                        Log.d(TAG, "[VAD] SpeechEnd (mode=$currentMode)")
                        if (_isHearingSpeech.value) _isHearingSpeech.value = false

                        if (currentMode == MicrophoneSession.Mode.TRANSCRIBING) {
                            if (!speechStartedInCurrentSession && lastPartialLen > 0) {
                                Log.w(TAG, "[VAD] Promoting orphaned SpeechEnd due to ASR partials (len=$lastPartialLen)")
                                speechStartedInCurrentSession = true
                            }

                            if (!speechStartedInCurrentSession) {
                                Log.w(TAG, "[VAD] Ignoring orphaned SpeechEnd (no SpeechStart and no ASR partials)")
                                return@collect
                            }

                            endTurnJob?.cancel()
                            endTurnJob = scope.launch {
                                Log.d(TAG, "[VAD] SpeechEnd. Starting ${END_OF_TURN_DELAY_MS}ms timer...")
                                delay(END_OF_TURN_DELAY_MS)
                                Log.i(TAG, "[VAD] Timer finished. Finalizing.")
                                endTurn("ConversationalPause")
                            }
                        } else {
                            Log.d(TAG, "[VAD] Ignoring SpeechEnd in mode: $currentMode")
                        }
                    }

                    is VadRecorder.VadEvent.SilenceTimeout -> {
                        if (currentMode == MicrophoneSession.Mode.TRANSCRIBING) {
                            Log.i(TAG, "[VAD] Silence timeout - user didn't speak")
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

        // ✅ FIX: For timeouts, we know there's no speech
        if (reason == "SessionTimeout" || reason == "SilenceTimeout") {
            Log.i(TAG, "[VAD] Timeout - no speech, switching to MONITORING")
            _isTranscribing.value = false
            scope.launch {
                _transcripts.emit("::TIMEOUT::")
            }
            micSession?.switchMode(MicrophoneSession.Mode.MONITORING)
        }
        // ✅ FIX: For conversational pause, request transcript but DON'T switch mode yet
        else if (speechStartedInCurrentSession) {
            Log.i(TAG, "[VAD] Speech detected by VAD - requesting transcript")
            _isTranscribing.value = true
            micSession?.endCurrentTranscription()
            // DON'T switch to MONITORING - let the transcript callback decide
            // If it's real words, the callback will switch to MONITORING
            // If it's noise, resetTurnAfterNoise will keep it in TRANSCRIBING
        }
        // ✅ FIX: No speech at all - emit empty but stay in TRANSCRIBING
        else {
            Log.i(TAG, "[VAD] No speech detected, emitting empty transcript")
            _isTranscribing.value = false
            scope.launch {
                _transcripts.emit("")
            }
            // DON'T switch to MONITORING - let resetTurnAfterNoise handle it
        }

        speechStartedInCurrentSession = false
        lastPartialLen = 0
        lastPartialTimeMs = 0L
        sessionTimeoutJob?.cancel()
    }

    override suspend fun stop(transcribe: Boolean) {
        Log.w(TAG, "[Controller] >>> STOP Request (transcribe=$transcribe)")
        endTurnJob?.cancel()
        sessionTimeoutJob?.cancel()

        if (transcribe && (isListening.value || isHearingSpeech.value) && !turnEnded) {
            Log.i(TAG, "[Controller] Transcribing on stop.")
            endTurn("ManualStop")
        } else {
            _isListening.value = false
            _isHearingSpeech.value = false
            _isTranscribing.value = false

            Log.i(TAG, "[Controller] Stopping microphone session completely")
            val sessionToStop = micSession
            micSession = null

            scope.launch {
                sessionToStop?.stop()
            }

            eventJob?.cancel()
            finalTranscriptJob?.cancel()
            partialJob?.cancel()
        }

        turnEnded = false
        speechStartedInCurrentSession = false
        lastPartialLen = 0
        lastPartialTimeMs = 0L
    }

    private suspend fun releaseAndReconnect() {
        Log.w(TAG, "[Controller] Releasing and reconnecting WebSocket...")

        micSession?.stop()
        micSession = null

        eventJob?.cancel()
        clientErrJob?.cancel()
        finalTranscriptJob?.cancel()
        partialJob?.cancel()
        sessionTimeoutJob?.cancel()

        transcriber.disconnect()
        delay(500)
        connect()
    }

    override fun release() {
        scope.launch {
            Log.w(TAG, "[Controller] Release called")
            try { micSession?.stop() } catch (_: Throwable) {}
            micSession = null
            transcriber.disconnect()
            partialJob?.cancel()
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

        when (mode) {
            MicrophoneSession.Mode.IDLE -> {
                Log.i(TAG, "[Controller] IDLE mode - stopping mic session")

                // ✅ FIX: Set to null FIRST (synchronously), then stop in background
                val sessionToStop = micSession
                micSession = null  // Immediately null so start() will create new session

                if (sessionToStop != null) {
                    scope.launch {
                        sessionToStop.stop()
                    }
                } else {
                    Log.d(TAG, "[Controller] No mic session to stop (already null)")
                }

                _isListening.value = false
                _isHearingSpeech.value = false
                _isTranscribing.value = false
                endTurnJob?.cancel()
                sessionTimeoutJob?.cancel()
                eventJob?.cancel()
                finalTranscriptJob?.cancel()
                partialJob?.cancel()
                speechStartedInCurrentSession = false
                lastPartialLen = 0
                lastPartialTimeMs = 0L
                Log.d(TAG, "[Controller] All listening states cleared for IDLE mode")
            }
            MicrophoneSession.Mode.MONITORING -> {
                micSession?.switchMode(mode)
                _isListening.value = false
                _isHearingSpeech.value = false
                _isTranscribing.value = false
                endTurnJob?.cancel()
                sessionTimeoutJob?.cancel()
                speechStartedInCurrentSession = false
                lastPartialLen = 0
                lastPartialTimeMs = 0L
                Log.d(TAG, "[Controller] Listening states cleared for MONITORING mode")
            }
            MicrophoneSession.Mode.TRANSCRIBING -> {
                micSession?.switchMode(mode)
                Log.d(TAG, "[Controller] TRANSCRIBING mode activated")
            }
        }
    }

    fun resetVadDetection() {
        micSession?.resetVadDetection()
    }

    fun notifyTtsStopped() {
        lastTtsStopTime = System.currentTimeMillis()
        Log.d(TAG, "[Controller] TTS stopped at ${lastTtsStopTime}, grace period: ${POST_TTS_GRACE_PERIOD_MS}ms")
    }

    fun resetTurnAfterNoise() {
        Log.i(TAG, "[Controller] Resetting turn state after VAD noise - continuing to listen")

        turnEnded = false
        speechStartedInCurrentSession = false
        lastPartialLen = 0
        lastPartialTimeMs = 0L

        _isTranscribing.value = false
        _isHearingSpeech.value = false
        _isListening.value = true

        // ✅ FIX: Restart timeout with REMAINING time from original session start
        val elapsedTime = System.currentTimeMillis() - sessionStartTime
        val remainingTime = sessionTimeoutMs - elapsedTime

        sessionTimeoutJob?.cancel()

        if (remainingTime > 0) {
            Log.d(TAG, "[Controller] Restarting session timeout with ${remainingTime}ms remaining")
            sessionTimeoutJob = scope.launch {
                delay(remainingTime)
                Log.w(TAG, "[Controller] ⏱️ Session timeout - total time limit reached")
                if (_isListening.value && !turnEnded) {
                    endTurn("SessionTimeout")
                }
            }
        } else {
            Log.w(TAG, "[Controller] Session timeout already exceeded, ending turn immediately")
            endTurn("SessionTimeout")
        }

        micSession?.switchMode(MicrophoneSession.Mode.TRANSCRIBING)
    }
}