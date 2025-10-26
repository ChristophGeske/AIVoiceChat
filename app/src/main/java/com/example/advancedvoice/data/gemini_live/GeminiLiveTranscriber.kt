package com.example.advancedvoice.data.gemini_live

import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class GeminiLiveTranscriber(
    private val scope: CoroutineScope,
    private val client: GeminiLiveClient
) {
    companion object { private const val TAG = LoggerConfig.TAG_LIVE }

    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val transcripts: SharedFlow<String> = _transcripts

    val ready: StateFlow<Boolean> get() = client.ready

    private var listenJob: Job? = null
    private val sb = StringBuilder()
    private var loggedPreview = false

    fun connect(apiKey: String, model: String) {
        Log.i(TAG, "Transcriber connect(model=$model)")
        client.connect(apiKey, model)
        startListening()
    }

    fun disconnect() {
        Log.i(TAG, "Transcriber disconnect()")
        stopListening()
        client.close()
        loggedPreview = false
        sb.clear()
    }

    fun sendAudioFrame(pcm16le: ByteArray, sampleRate: Int = 16000): Boolean =
        client.sendPcm16Le(pcm16le, sampleRate)

    fun endTurn(): Boolean {
        Log.i(TAG, "endTurn()")
        return client.sendAudioStreamEnd()
    }

    private fun startListening() {
        stopListening()
        listenJob = scope.launch {
            client.incoming.collect { msg -> handle(msg) }
        }
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        sb.clear()
    }

    private fun handle(json: JSONObject) {
        if (!json.has("serverContent")) return
        val sc = json.optJSONObject("serverContent") ?: return

        val inText = sc.optJSONObject("inputTranscription")?.optString("text", "").orEmpty()
        val outText = sc.optJSONObject("outputTranscription")?.optString("text", "").orEmpty()
        val turnComplete = sc.optBoolean("turnComplete", false)

        if (inText.isNotBlank()) {
            sb.append(inText)
            if (!loggedPreview) {
                Log.i(TAG, "inputTranscription(first)='${inText.take(80)}'")
                loggedPreview = true
            }
        }
        if (outText.isNotBlank() && LoggerConfig.LIVE_WS_VERBOSE) {
            Log.d(TAG, "outputTranscription='${outText.take(80)}'")
        }

        if (turnComplete) {
            val final = sb.toString().trim()
            Log.i(TAG, "turnComplete; finalLen=${final.length}")
            if (final.isNotEmpty()) {
                scope.launch { _transcripts.emit(final) }
            }
            sb.clear()
            loggedPreview = false
        }
    }
}
