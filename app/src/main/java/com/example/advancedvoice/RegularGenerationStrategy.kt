package com.example.advancedvoice

import android.util.Log
import com.example.advancedvoice.SentenceTurnEngine.Callbacks
import com.example.advancedvoice.SentenceTurnEngine.Msg
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class RegularGenerationStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IGenerationStrategy {

    @Volatile private var active = false
    private val fullResponse = StringBuilder()

    companion object {
        private const val TAG = "RegularStrategy"
    }

    override fun execute(
        scope: CoroutineScope,
        history: List<Msg>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: Callbacks
    ) {
        active = true
        fullResponse.clear()

        // [FIX] Create an updated history that includes the sentence limit instruction for the LLM.
        val finalHistory = history.toMutableList()
        val lastUserMsg = finalHistory.lastOrNull { it.role == "user" }
        if (lastUserMsg != null) {
            val lastMsgIndex = finalHistory.lastIndexOf(lastUserMsg)
            finalHistory[lastMsgIndex] = lastUserMsg.copy(
                text = "${lastUserMsg.text}\n\nIMPORTANT: Your response must not exceed $maxSentences sentences."
            )
        }

        scope.launch(ioDispatcher) {
            try {
                val isGpt = modelName.startsWith("gpt-", ignoreCase = true)
                val isGemini = modelName.contains("gemini", ignoreCase = true)

                if (isGpt) {
                    streamOpenAi(systemPrompt, finalHistory, modelName, callbacks)
                } else if (isGemini) {
                    streamGemini(systemPrompt, finalHistory, modelName, callbacks)
                } else {
                    throw RuntimeException("Unknown model type: $modelName")
                }

                // [FIX] All sentence processing is now centralized here, after the stream is complete.
                // This prevents race conditions and duplicate processing.
                onStreamEnd(callbacks)

            } catch (e: Exception) {
                if (active) {
                    Log.e(TAG, "[Regular] Request failed: ${e.message}", e)
                    scope.launch { callbacks.onError(e.message ?: "Request failed") }
                }
            } finally {
                if (active) {
                    active = false
                    scope.launch { callbacks.onTurnFinish() }
                }
            }
        }
    }

    override fun abort() {
        if (active) Log.d(TAG, "[Regular] Aborted.")
        active = false
    }

    // [FIX] onChunk is now simplified. It only accumulates text and reports the delta.
    // All sentence splitting is deferred until the stream is complete.
    private fun onChunk(delta: String, callbacks: Callbacks) {
        if (!active || delta.isEmpty()) return
        fullResponse.append(delta)
        callbacks.onStreamDelta(delta)
    }

    // [FIX] onStreamEnd now handles all sentence logic reliably.
    private fun onStreamEnd(callbacks: Callbacks) {
        if (!active) return
        val finalText = fullResponse.toString().trim()
        if (finalText.isEmpty()) {
            if (active) callbacks.onError("Empty response from model.")
            return
        }

        callbacks.onFinalResponse(finalText)

        val allSentences = SentenceSplitter.splitIntoSentences(finalText)
        if (allSentences.isNotEmpty()) {
            callbacks.onFirstSentence(allSentences.first())
            if (allSentences.size > 1) {
                callbacks.onRemainingSentences(allSentences.drop(1))
            }
        }
    }

    private fun throwDescriptiveError(code: Int, body: String, service: String) {
        val userMessage = when (code) {
            503 -> "The model is temporarily overloaded. Please try again. (Error: 503)"
            429 -> "API quota exceeded. Please check your plan. (Error: 429)"
            401 -> "Invalid API Key. Please check your credentials. (Error: 401)"
            else -> "A model error occurred. (Error: $code)"
        }
        Log.e(TAG, "[Regular] $service Error - Code: $code, Body: ${body.take(500)}")
        throw RuntimeException(userMessage)
    }

    private fun streamOpenAi(systemPrompt: String, history: List<Msg>, modelName: String, callbacks: Callbacks) {
        val apiKey = openAiKeyProvider().trim().ifBlank { throw RuntimeException("OpenAI API key missing") }
        val url = "https://api.openai.com/v1/chat/completions"
        val bodyObj = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                history.forEach { m -> put(JSONObject().apply { put("role", m.role); put("content", m.text) }) }
            })
            put("stream", true)
            if (!modelName.startsWith("gpt-5", ignoreCase = true)) put("temperature", 0.7)
        }
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey").addHeader("Accept", "text/event-stream")
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType())).build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throwDescriptiveError(resp.code, resp.body?.string().orEmpty(), "OpenAI")
            val source = resp.body!!.source()
            while (active) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                try {
                    val token = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content", null)
                    if (!token.isNullOrEmpty()) onChunk(token, callbacks)
                } catch (_: Exception) {}
            }
        }
    }

    private fun streamGemini(systemPrompt: String, history: List<Msg>, modelName: String, callbacks: Callbacks) {
        val apiKey = geminiKeyProvider().trim().ifBlank { throw RuntimeException("Gemini API key missing") }
        val effModel = if (modelName.contains("2.5", ignoreCase = true)) modelName else "gemini-pro"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$effModel:streamGenerateContent?key=$apiKey"
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                // Gemini works better with the system prompt integrated this way
                val combinedHistory = mutableListOf<Msg>()
                combinedHistory.add(Msg("user", systemPrompt))
                combinedHistory.add(Msg("model", "Understood."))
                combinedHistory.addAll(history)

                combinedHistory.forEach { m ->
                    val role = if (m.role == "assistant") "model" else "user"
                    put(JSONObject().apply { put("role", role); put("parts", JSONArray().put(JSONObject().put("text", m.text))) })
                }
            })
            put("generationConfig", JSONObject().put("temperature", 0.7))
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url(url).post(body).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throwDescriptiveError(resp.code, resp.body?.string().orEmpty(), "Gemini")
            val source = resp.body?.source() ?: return
            while (active) {
                val line = source.readUtf8Line() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("\"text\"")) continue
                try {
                    // Quick and dirty parse for the text field to avoid full JSON parsing on every line
                    val text = trimmed.substringAfter(":").trim().removePrefix("\"").removeSuffix("\"")
                    onChunk(text.replace("\\n", "\n").replace("\\\"", "\""), callbacks)
                } catch (_: Exception) {}
            }
        }
    }
}