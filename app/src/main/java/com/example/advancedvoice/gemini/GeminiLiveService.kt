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

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)  // Changed from 0
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var setupComplete = false
    private var accumulatedTranscript = StringBuilder()
    private var messageCount = 0

    fun initialize(apiKey: String) {
        val trimmedKey = apiKey.trim()
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "━━━ Initializing Gemini Live WebSocket ━━━")
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "API Key length: ${trimmedKey.length} (should be 39)")
        Log.d(TAG, "API Key starts with: ${trimmedKey.take(10)}...")

        if (webSocket != null) {
            Log.w(TAG, "⚠️ WebSocket already exists, releasing first")
            release()
        }

        setupComplete = false
        accumulatedTranscript.clear()
        messageCount = 0
        _isModelReady.postValue(false)

        val requestUrl = "$WS_URL?key=$trimmedKey"
        val request = Request.Builder()
            .url(requestUrl)
            .build()

        Log.d(TAG, "🌐 Connecting to: $WS_URL")
        Log.d(TAG, "📡 Request URL length: ${requestUrl.length}")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.i(TAG, "✅ WebSocket OPENED Successfully!")
                Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d(TAG, "HTTP Response code: ${response.code}")
                Log.d(TAG, "Protocol: ${response.protocol}")
                sendSetupMessage(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                messageCount++
                Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.i(TAG, "📨 MESSAGE #$messageCount RECEIVED (TEXT)")
                Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d(TAG, "Length: ${text.length}")
                Log.d(TAG, "Full content:\n$text")

                try {
                    handleServerMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ EXCEPTION in handleServerMessage", e)
                    Log.e(TAG, "Stack trace:", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                messageCount++
                Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.i(TAG, "📨 MESSAGE #$messageCount RECEIVED (BYTES)")
                Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                // Log.d(TAG, "Byte length: ${bytes.size()}")
                // Try to decode as UTF-8 text
                try {
                    val text = bytes.utf8()
                    Log.d(TAG, "Decoded as text:\n$text")
                    handleServerMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Could not decode binary message as UTF-8", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.e(TAG, "❌❌❌ WebSocket FAILURE ❌❌❌")
                Log.e(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.e(TAG, "Exception type: ${t.javaClass.simpleName}")
                Log.e(TAG, "Exception message: ${t.message}")
                Log.e(TAG, "Full stack trace:", t)

                if (response != null) {
                    Log.e(TAG, "HTTP ${response.code}: ${response.message}")
                    Log.e(TAG, "Response protocol: ${response.protocol}")
                    response.body?.let { body ->
                        try {
                            val bodyString = body.string()
                            Log.e(TAG, "Response body:\n$bodyString")
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not read response body", e)
                        }
                    }
                }

                setupComplete = false
                _isModelReady.postValue(false)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.w(TAG, "⚠️ WebSocket CLOSING")
                Log.w(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.w(TAG, "Code: $code")
                Log.w(TAG, "Reason: $reason")
                setupComplete = false
                _isModelReady.postValue(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.i(TAG, "🔌 WebSocket CLOSED")
                Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.i(TAG, "Code: $code")
                Log.i(TAG, "Reason: $reason")
                Log.i(TAG, "Total messages received: $messageCount")
                setupComplete = false
                _isModelReady.postValue(false)
            }
        })

        Log.d(TAG, "🔄 WebSocket creation initiated (async)")
    }

    private fun sendSetupMessage(ws: WebSocket) {
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "📤 Preparing to send SETUP message")
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

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

        val jsonString = setupJson.toString()
        Log.d(TAG, "Setup message content:\n${setupJson.toString(2)}")
        Log.d(TAG, "Message length: ${jsonString.length} bytes")

        val sent = ws.send(jsonString)

        if (sent) {
            Log.i(TAG, "✅ Setup message SENT successfully")
            Log.d(TAG, "⏳ Now waiting for server response...")
        } else {
            Log.e(TAG, "❌ Setup message FAILED to send!")
        }
    }

    fun transcribeAudio(audioData: ByteArray) {
        if (!setupComplete) {
            Log.w(TAG, "⚠️ Setup not complete, skipping audio chunk")
            return
        }

        val ws = webSocket
        if (ws == null) {
            Log.e(TAG, "❌ Cannot transcribe: WebSocket is null")
            return
        }

        try {
            val audioMessage = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", Base64.encodeToString(audioData, Base64.NO_WRAP))
                    })
                })
            }

            val sent = ws.send(audioMessage.toString())
            if (sent) {
                Log.v(TAG, "📤 Audio chunk sent (${audioData.size} bytes)")
            } else {
                Log.e(TAG, "❌ Failed to send audio chunk")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending audio", e)
        }
    }

    fun sendAudioStreamEnd() {
        val sent = webSocket?.send("""{"realtimeInput":{"audioStreamEnd":true}}""") ?: false
        Log.d(TAG, "📤 audioStreamEnd signal sent: $sent")
    }

    private fun handleServerMessage(text: String) {
        Log.d(TAG, "🔍 Parsing server message...")

        val json = JSONObject(text)
        val keys = json.keys().asSequence().toList()
        Log.d(TAG, "🔑 JSON keys: $keys")

        // Check for setupComplete
        if (json.has("setupComplete")) {
            setupComplete = true
            Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.i(TAG, "✅✅✅ SETUP COMPLETED SUCCESSFULLY! ✅✅✅")
            Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            _isModelReady.postValue(true)
            return
        }

        // Handle serverContent
        if (json.has("serverContent")) {
            val serverContent = json.getJSONObject("serverContent")
            Log.d(TAG, "📦 serverContent keys: ${serverContent.keys().asSequence().toList()}")

            // Input transcription
            if (serverContent.has("inputTranscription")) {
                val transcript = serverContent.getJSONObject("inputTranscription").optString("text", "")
                if (transcript.isNotBlank()) {
                    accumulatedTranscript.append(transcript)
                    Log.i(TAG, "📝 Input: '$transcript'")
                }
            }

            // Output transcription
            if (serverContent.has("outputTranscription")) {
                val transcript = serverContent.getJSONObject("outputTranscription").optString("text", "")
                if (transcript.isNotBlank()) {
                    Log.d(TAG, "🤖 Output: '$transcript'")
                }
            }

            // Turn complete
            if (serverContent.optBoolean("turnComplete", false)) {
                Log.i(TAG, "━━━ Turn complete ━━━")
                val final = accumulatedTranscript.toString().trim()
                if (final.isNotBlank()) {
                    Log.i(TAG, "✅ Final: '$final'")
                    _transcriptionResult.postValue(Event(final))
                } else {
                    Log.w(TAG, "⚠️ No transcription")
                }
                accumulatedTranscript.clear()
            }
        }

        // Check for errors
        if (json.has("error")) {
            val error = json.getJSONObject("error")
            Log.e(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.e(TAG, "❌ SERVER ERROR")
            Log.e(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.e(TAG, "Code: ${error.optInt("code", -1)}")
            Log.e(TAG, "Message: ${error.optString("message", "")}")
            Log.e(TAG, "Status: ${error.optString("status", "")}")
            Log.e(TAG, "Full error:\n${error.toString(2)}")
        }
    }

    fun release() {
        Log.i(TAG, "🔌 Releasing WebSocket")
        setupComplete = false
        accumulatedTranscript.clear()
        messageCount = 0
        webSocket?.close(1000, "User switched mode")
        webSocket = null
        _isModelReady.postValue(false)
    }
}