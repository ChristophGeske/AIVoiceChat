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
    // These parameters are required for the controller to be self-sufficient
    private val apiKey: String,
    private val model: String
) : SttController {

    companion object { private const val TAG = LoggerConfig.TAG_LIVE }

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening

    private val _isHearingSpeech = MutableStateFlow(false)
    override val isHearingSpeech: StateFlow<Boolean> = _isHearingSpeech

    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val transcripts: SharedFlow<String> = _transcripts

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val errors: SharedFlow<String> = _errors

    private var audioJob: Job? = null
    private var eventJob: Job? = null
    private var transcriptJob: Job? = null
    private var clientErrJob: Job? = null

    private val connected: Boolean
        get() = client.ready.value

    private var perf: PerfTimer? = null

    init {
        // Initial connection attempt on creation
        connect()
    }

    // This is now an internal detail of the controller
    private fun connect() {
        Log.i(TAG, "Controller connect(model=$model)")
        transcriber.connect(apiKey, model)
        clientErrJob?.cancel()
        clientErrJob = scope.launch {
            client.errors.collect { msg ->
                Log.e(TAG, "Transport error: $msg")
                _errors.emit(msg)
            }
        }
        perf = PerfTimer(TAG, "live")
        perf?.mark("ws_connect")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun start() {
        if (_isListening.value) return

        if (!connected) {
            Log.w(TAG, "Not connected. Attempting to re-connect before starting.")
            connect()
        }

        Log.i(TAG, "start → waiting for setupComplete...")
        val ok = waitUntilReadyOrTimeout(5000)
        if (!ok) {
            val m = "Gemini Live is not ready. Check API key/model/network."
            Log.e(TAG, m)
            _errors.tryEmit(m)
            return
        }
        perf?.mark("ws_ready")
        Log.i(TAG, "setupComplete received; starting VAD")

        _isListening.value = true
        _isHearingSpeech.value = false

        audioJob = scope.launch {
            vad.audio.collect { frame -> client.sendPcm16Le(frame) }
        }
        eventJob = scope.launch {
            vad.events.collect { ev ->
                when (ev) {
                    is VadRecorder.VadEvent.SpeechStart -> {
                        Log.i(TAG, "[VAD] SpeechStart event received.")
                        _isHearingSpeech.value = true
                        perf?.mark("speech_start")
                    }
                    is VadRecorder.VadEvent.SpeechEnd -> {
                        Log.i(TAG, "[VAD] SpeechEnd event received.")
                        _isHearingSpeech.value = false
                        perf?.mark("speech_end")
                        transcriber.endTurn()
                    }
                    is VadRecorder.VadEvent.SilenceTimeout -> {
                        Log.i(TAG, "[VAD] SilenceTimeout event received.")
                        _isHearingSpeech.value = false
                        perf?.mark("speech_end")
                        transcriber.endTurn()
                    }
                    is VadRecorder.VadEvent.Error -> _errors.emit(ev.message)
                }
            }
        }
        transcriptJob = scope.launch {
            var first = true
            transcriber.transcripts.collect { final ->
                if (first) { perf?.mark("first_transcript"); first = false }
                Log.i(TAG, "Final transcript len=${final.length}")
                _transcripts.emit(final)
                scope.launch { stop(transcribe = false) }
                perf?.mark("transcript_complete")
                perf?.logSummary("ws_connect","ws_ready","speech_start","speech_end","first_transcript","transcript_complete")
            }
        }
        try {
            perf?.mark("vad_start")
            vad.start()
        } catch (se: SecurityException) {
            _errors.tryEmit("Microphone permission denied")
            stop(transcribe = false)
        }
    }

    override suspend fun stop(transcribe: Boolean) {
        if (!_isListening.value) return
        Log.w(TAG, "stop(transcribe=$transcribe) called.")

        if (!transcribe) {
            Log.w(TAG, "HARD STOP: Disconnecting transcriber to prevent late results.")
            transcriber.disconnect()
        } else {
            transcriber.endTurn()
        }

        vad.stop()
        audioJob?.cancel(); audioJob = null
        eventJob?.cancel(); eventJob = null
        transcriptJob?.cancel(); transcriptJob = null
        _isListening.value = false
        _isHearingSpeech.value = false
    }

    override fun release() {
        Log.w(TAG, "release() called. Full cleanup.")
        scope.launch { stop(transcribe = false) }
        transcriber.disconnect()
        client.close()
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