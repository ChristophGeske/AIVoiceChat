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

    private val _partialTranscripts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val partialTranscripts: SharedFlow<String> = _partialTranscripts

    private val _finalTranscripts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val finalTranscripts: SharedFlow<String> = _finalTranscripts

    val ready: StateFlow<Boolean> get() = client.ready

    private var listenJob: Job? = null
    private val accumulatedTranscript = StringBuilder()
    private var isAccumulating = false

    fun connect(apiKey: String, model: String) {
        Log.i(TAG, "[Transcriber] connect(model=$model)")
        client.connect(apiKey, model)
        startListening()
    }

    fun disconnect() {
        Log.i(TAG, "[Transcriber] disconnect()")
        stopListening()
        client.close()
    }

    fun sendAudioFrame(pcm16le: ByteArray, sampleRate: Int = 16000): Boolean =
        client.sendPcm16Le(pcm16le, sampleRate)

    fun startAccumulating() {
        accumulatedTranscript.clear()
        isAccumulating = true
    }

    fun endTurn(): Boolean {
        if (!isAccumulating) {
            Log.w(TAG, "[Transcriber] endTurn() called but not accumulating.")
            return false
        }

        Log.i(TAG, "[Transcriber] endTurn() called - finalizing (accumulated ${accumulatedTranscript.length} chars)")
        isAccumulating = false

        val finalText = accumulatedTranscript.toString().trim()

        if (finalText.isNotEmpty()) {
            Log.i(TAG, "[Transcriber] Emitting FINAL: '${finalText.take(100)}...'")
            scope.launch { _finalTranscripts.emit(finalText) }
        } else {
            Log.w(TAG, "[Transcriber] Buffer empty, emitting empty string")
            scope.launch { _finalTranscripts.emit("") }
        }

        val ok = client.sendAudioStreamEnd()
        accumulatedTranscript.clear()

        return ok
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
        accumulatedTranscript.clear()
        isAccumulating = false
    }

    private fun handle(json: JSONObject) {
        val serverContent = json.optJSONObject("serverContent") ?: return

        val inTranscription = serverContent.optJSONObject("inputTranscription")
        if (inTranscription != null && isAccumulating) {
            val text = inTranscription.optString("text", "")
            if (text.isNotBlank()) {
                accumulatedTranscript.append(text)
                val previewText = accumulatedTranscript.toString()

                // ✅ Log accumulated text (but only every 20 chars to reduce spam)
                if (previewText.length % 20 < text.length || previewText.length < 20) {
                    Log.d(TAG, "[Transcriber] Accumulated (len=${previewText.length}): '${previewText.take(80)}...'")
                }

                scope.launch { _partialTranscripts.emit(previewText) }
            }
        }

        val turnComplete = serverContent.optBoolean("turnComplete", false)
        if (turnComplete) {
            Log.i(TAG, "[Transcriber] turnComplete received")
        }
    }
}