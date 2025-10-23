package com.example.advancedvoice.gemini

import android.util.Base64
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.advancedvoice.Event
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveService(
    private val scope: CoroutineScope
) {
    private val TAG = "GeminiLiveService"

    private val WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    private val _transcriptionResult = MutableLiveData<Event<String>>()
    val transcriptionResult: LiveData<Event<String>> = _transcriptionResult

    private val _isModelReady = MutableLiveData(false)
    val isModelReady: LiveData<Boolean> = _isModelReady

    private val _error = MutableLiveData<Event<String>>()
    val error: LiveData<Event<String>> = _error

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var setupComplete = false
    private var accumulatedTranscript = StringBuilder()

    fun initialize(apiKey: String) {
        val trimmedKey = apiKey.trim()
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "━━━ Initializing Gemini Live WebSocket ━━━")
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        if (webSocket != null) {
            Log.w(TAG, "WebSocket already exists, releasing first.")
            release()
        }

        setupComplete = false
        accumulatedTranscript.clear()
        _isModelReady.postValue(false)

        val requestUrl = "$WS_URL?key=$trimmedKey"
        val request = Request.Builder().url(requestUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "✅ WebSocket OPENED Successfully!")
                sendSetupMessage(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleServerMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ EXCEPTION in handleServerMessage", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌❌❌ WebSocket FAILURE: ${t.javaClass.simpleName} - ${t.message}")
                val friendlyMessage = when (t) {
                    is java.net.UnknownHostException -> "Network error. Please check your internet connection."
                    is java.net.SocketTimeoutException -> "Connection timed out. Please try again."
                    else -> "WebSocket error: ${t.message}"
                }
                _error.postValue(Event(friendlyMessage))
                setupComplete = false
                _isModelReady.postValue(false)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "⚠️ WebSocket CLOSING - Code: $code, Reason: $reason")
                setupComplete = false
                _isModelReady.postValue(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "🔌 WebSocket CLOSED - Code: $code, Reason: $reason")
                setupComplete = false
                _isModelReady.postValue(false)
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/gemini-2.5-flash-native-audio-preview-09-2025")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
            })
        }
        ws.send(setupJson.toString())
        Log.i(TAG, "✅ Setup message SENT.")
    }

    fun transcribeAudio(audioData: ByteArray) {
        if (!setupComplete || webSocket == null) return
        try {
            val audioMessage = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", Base64.encodeToString(audioData, Base64.NO_WRAP))
                    })
                })
            }
            webSocket?.send(audioMessage.toString())
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending audio", e)
        }
    }

    fun sendAudioStreamEnd() {
        val sent = webSocket?.send("""{"realtimeInput":{"audioStreamEnd":true}}""") ?: false
        if (sent) Log.i(TAG, "📤 audioStreamEnd signal sent.")
    }

    private fun handleServerMessage(text: String) {
        val json = JSONObject(text)

        if (json.has("setupComplete")) {
            setupComplete = true
            _isModelReady.postValue(true)
            Log.i(TAG, "✅✅✅ SETUP COMPLETED SUCCESSFULLY! ✅✅✅")
            return
        }

        if (json.has("serverContent")) {
            val serverContent = json.getJSONObject("serverContent")
            if (serverContent.has("inputTranscription")) {
                val transcript = serverContent.getJSONObject("inputTranscription").optString("text", "")
                if (transcript.isNotBlank()) {
                    accumulatedTranscript.append(transcript)
                    Log.i(TAG, "📝 Transcript Part: '$transcript'")
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                val final = accumulatedTranscript.toString().trim()
                if (final.isNotBlank()) {
                    Log.i(TAG, "✅ Final Transcript: '$final'")
                    _transcriptionResult.postValue(Event(final))
                } else {
                    Log.w(TAG, "⚠️ No transcription received for this turn.")
                    _transcriptionResult.postValue(Event(""))
                }
                accumulatedTranscript.clear()
            }
        }

        if (json.has("error")) {
            val error = json.getJSONObject("error")
            val errorMessage = "Server Error: ${error.optString("message", "Unknown error")}"
            Log.e(TAG, "❌ SERVER ERROR: $errorMessage")
            _error.postValue(Event(errorMessage))
        }
    }

    fun release() {
        Log.i(TAG, "🔌 Releasing WebSocket")
        setupComplete = false
        accumulatedTranscript.clear()
        webSocket?.close(1000, "User released resources")
        webSocket = null
        _isModelReady.postValue(false)
    }
}