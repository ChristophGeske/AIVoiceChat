package com.example.advancedvoice.feature.conversation.service

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.advancedvoice.core.audio.VadRecorder
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.util.PerfTimer
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

    companion object { private const val TAG = "GeminiLiveService" }

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening

    private val _isHearingSpeech = MutableStateFlow(false)
    override val isHearingSpeech: StateFlow<Boolean> = _isHearingSpeech

    private val _isTranscribing = MutableStateFlow(false)
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing

    val partialTranscripts: SharedFlow<String> = transcriber.partialTranscripts

    // FIX: The private, mutable flow that we can emit to.
    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val transcripts: SharedFlow<String> = _transcripts

    override val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var audioJob: Job? = null
    private var eventJob: Job? = null
    private var clientErrJob: Job? = null
    private var finalTranscriptJob: Job? = null

    private val connected: Boolean get() = client.ready.value
    private var perf: PerfTimer? = null

    init {
        connect()
    }

    private fun connect() {
        Log.i(TAG, "[Controller] connect(model=$model)")
        transcriber.connect(apiKey, model)
        clientErrJob?.cancel()
        clientErrJob = scope.launch {
            client.errors.collect { msg ->
                Log.e(TAG, "[Controller] Transport error: $msg")
                errors.emit(msg)
            }
        }
        perf = PerfTimer(TAG, "live")
        perf?.mark("ws_connect")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun start() {
        if (_isListening.value || _isHearingSpeech.value || _isTranscribing.value) {
            Log.w(TAG, "[Controller] start() called but already active. Ignoring.")
            return
        }

        if (!connected) {
            Log.w(TAG, "[Controller] Not connected. Attempting to re-connect before starting.")
            connect()
        }

        Log.i(TAG, "[Controller] start → waiting for setupComplete...")
        val ok = waitUntilReadyOrTimeout(5000)
        if (!ok) {
            val m = "Gemini Live is not ready. Check API key/model/network."
            Log.e(TAG, "[Controller] $m")
            errors.tryEmit(m)
            return
        }

        Log.i(TAG, "[Controller] setupComplete received; starting VAD and listening for events.")
        _isListening.value = true
        _isHearingSpeech.value = false
        _isTranscribing.value = false

        // Start accumulating transcription for this turn
        transcriber.startAccumulating()

        eventJob = scope.launch {
            vad.events.collect { ev ->
                when (ev) {
                    is VadRecorder.VadEvent.SpeechStart -> {
                        Log.i(TAG, "[VAD] SpeechStart event received.")
                        _isHearingSpeech.value = true
                    }
                    is VadRecorder.VadEvent.SpeechEnd, is VadRecorder.VadEvent.SilenceTimeout -> {
                        Log.i(TAG, "[VAD] Speech ended. Finalizing transcript immediately...")
                        _isListening.value = false
                        _isHearingSpeech.value = false
                        // Don't set _isTranscribing - the transcript is emitted immediately

                        vad.stop()
                        transcriber.endTurn()  // This emits the final transcript NOW

                        Log.i(TAG, "[VAD] Final transcript emitted, waiting for LLM to start...")
                    }
                    is VadRecorder.VadEvent.Error -> errors.emit(ev.message)
                }
            }
        }

        audioJob = scope.launch {
            vad.audio.collect { frame -> client.sendPcm16Le(frame) }
        }

        finalTranscriptJob?.cancel()
        finalTranscriptJob = scope.launch {
            transcriber.finalTranscripts.collect { final ->
                Log.i(TAG, "[Controller] Collected final transcript (len=${final.length}). Emitting to ViewModel.")
                _transcripts.emit(final)
                // State will transition to GENERATING when ViewModel processes this
            }
        }

        try {
            vad.start()
        } catch (se: SecurityException) {
            Log.e(TAG, "[Controller] Microphone permission denied on start.", se)
            errors.tryEmit("Microphone permission denied")
            stop(transcribe = false)
        }
    }

    override suspend fun stop(transcribe: Boolean) {
        if (!_isListening.value && !_isHearingSpeech.value && !_isTranscribing.value) return
        Log.w(TAG, "[Controller] stop(transcribe=$transcribe) called.")

        if (!transcribe) {
            Log.w(TAG, "[Controller] HARD STOP: Disconnecting transcriber.")
            transcriber.disconnect()
        } else if (_isListening.value || _isHearingSpeech.value) {
            _isListening.value = false
            _isHearingSpeech.value = false
            transcriber.endTurn()  // This emits immediately
        }

        vad.stop()
        audioJob?.cancel(); audioJob = null
        eventJob?.cancel(); eventJob = null

        _isListening.value = false
        _isHearingSpeech.value = false
        _isTranscribing.value = false
    }

    override fun release() {
        Log.w(TAG, "[Controller] release() called. Full cleanup.")
        scope.launch { stop(transcribe = false) }
        finalTranscriptJob?.cancel(); finalTranscriptJob = null
        clientErrJob?.cancel(); clientErrJob = null
        perf = null
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