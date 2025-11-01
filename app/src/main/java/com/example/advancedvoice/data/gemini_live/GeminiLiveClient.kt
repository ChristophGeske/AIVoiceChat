package com.example.advancedvoice.data.gemini_live

import android.util.Base64
import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.network.WebSocketProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

class GeminiLiveClient(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = LoggerConfig.TAG_LIVE
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private var socket: WebSocket? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _incoming = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val incoming: SharedFlow<JSONObject> = _incoming

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errors: SharedFlow<String> = _errors

    fun connect(apiKey: String, model: String) {
        if (socket != null) {
            Log.i(TAG, "connect() called but socket already exists. Ignoring.")
            return
        }
        Log.i(TAG, "connect -> Creating new WebSocket for model=$model")

        val url = "$WS_URL?key=${apiKey.trim()}"
        socket = WebSocketProvider.newWebSocket(url, headers = emptyMap(), listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS OPEN (code=${response.code}) -> send setup")
                _ready.value = false
                val setup = GeminiLiveProtocol.setup(model)
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WS CLOSING code=$code reason=$reason")
                _ready.value = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS CLOSED code=$code reason=$reason")
                _ready.value = false
                socket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS FAILURE: ${t.javaClass.simpleName}: ${t.message}")
                response?.let { Log.e(TAG, "WS FAILURE resp=${it.code} ${it.message}") }
                _ready.value = false
                scope.launch { _errors.emit("WebSocket error: ${t.message ?: "unknown"}") }
                socket = null
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val serverContent = json.optJSONObject("serverContent")

            // FIX: Only log unimportant messages if verbose mode is on.
            val isAudioChunk = serverContent?.has("modelTurn") ?: false
            if (!LoggerConfig.LIVE_WS_VERBOSE && isAudioChunk) {
                // This is a model audio response chunk, and we're not in verbose mode.
                // Just emit and return to avoid spamming the log.
                scope.launch(Dispatchers.Default) { _incoming.emit(json) }
                return
            }

            val hasSetup = json.has("setupComplete")
            val hasError = json.has("error")
            val hasTranscription = serverContent?.has("inputTranscription") == true
            val hasTurnComplete = serverContent?.optBoolean("turnComplete") == true
            Log.d(TAG, "[Client] Message received: setup=$hasSetup, error=$hasError, transcription=$hasTranscription, turnComplete=$hasTurnComplete")


            if (hasSetup) {
                Log.i(TAG, "setupComplete=true")
                _ready.value = true
            }
            if (hasError) {
                val e = json.optJSONObject("error")
                val msg = e?.optString("message") ?: "Unknown server error"
                Log.e(TAG, "SERVER ERROR: $msg code=${e?.optInt("code")}")
                scope.launch { _errors.emit(msg) }
            }
            scope.launch(Dispatchers.Default) { _incoming.emit(json) }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid WS JSON: ${e.message}")
        }
    }

    private var frameCount = 0

    fun sendPcm16Le(bytes: ByteArray, sampleRate: Int = 16000): Boolean {
        val ws = socket ?: return false
        return try {
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val msg = GeminiLiveProtocol.audioPcm16LeFrame(b64, sampleRate)
            val ok = ws.send(msg.toString())

            // FIX: This logging is now controlled by LoggerConfig
            if (LoggerConfig.LIVE_FRAME_SAMPLING > 0) {
                frameCount++
                if (frameCount % LoggerConfig.LIVE_FRAME_SAMPLING == 0) {
                    Log.d(TAG, "audio frame sent (n=$frameCount, len=${bytes.size})")
                }
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "send audio error: ${e.message}")
            scope.launch { _errors.emit("Send audio error: ${e.message}") }
            false
        }
    }

    fun sendAudioStreamEnd(): Boolean {
        val ws = socket ?: return false
        val ok = ws.send(GeminiLiveProtocol.audioStreamEnd().toString())
        Log.i(TAG, "audioStreamEnd sent (ok=$ok)")
        return ok
    }

    fun close() {
        Log.i(TAG, "WS CLOSE requested by client.")
        try {
            socket?.close(1000, "Client initiated close")
        } catch (_: Exception) {}
        socket = null
        _ready.value = false
        frameCount = 0
    }
}