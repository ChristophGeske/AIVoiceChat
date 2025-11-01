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
    val ready: StateFlow<Boolean> get() = _ready
    private val _ready = MutableStateFlow(false)
    val incoming: SharedFlow<JSONObject> get() = _incoming
    private val _incoming = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val errors: SharedFlow<String> get() = _errors
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun connect(apiKey: String, model: String) {
        if (socket != null) {
            return
        }
        Log.i(TAG, "connect -> Creating new WebSocket for model=$model")

        val url = "$WS_URL?key=${apiKey.trim()}"
        socket = WebSocketProvider.newWebSocket(url, headers = emptyMap(), listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS OPEN (code=${response.code}) -> send setup")
                _ready.value = false
                webSocket.send(GeminiLiveProtocol.setup(model).toString())
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

            // ✅ Only log if verbose mode OR important messages
            val isAudioChunk = serverContent?.has("modelTurn") ?: false
            val isTranscription = serverContent?.has("inputTranscription") ?: false

            if (!isAudioChunk && !isTranscription) {
                // Important messages (setupComplete, turnComplete, errors)
                Log.d(TAG, "[Client] ${json.keys().asSequence().joinToString()}")
            }

            if (json.has("setupComplete")) {
                _ready.value = true
            }
            if (json.has("error")) {
                val e = json.optJSONObject("error")
                val msg = e?.optString("message") ?: "Unknown error"
                Log.e(TAG, "ERROR: $msg")
                scope.launch { _errors.emit(msg) }
            }
            scope.launch(Dispatchers.Default) { _incoming.emit(json) }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid JSON: ${e.message}")
        }
    }

    fun sendPcm16Le(bytes: ByteArray, sampleRate: Int = 16000): Boolean {
        // This is fine, audio frame logging is already disabled by LoggerConfig
        return try {
            socket?.send(GeminiLiveProtocol.audioPcm16LeFrame(Base64.encodeToString(bytes, Base64.NO_WRAP), sampleRate).toString()) ?: false
        } catch (e: Exception) {
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
    }
}