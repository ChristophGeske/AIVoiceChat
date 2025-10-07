// RegularGenerationStrategy.kt
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
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

class RegularGenerationStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IGenerationStrategy {

    @Volatile private var active = false
    private val full = StringBuilder()
    private var firstEmitted = false
    private var emitCursor = 0
    private var deliveredSentences = 0
    private var sentenceCapReached = false
    private var deltaCount = 0
    private var geminiNonStreamFallbackUsed = false
    private var geminiSingleChunkStreamDetected = false
    private val overrideCapOnGeminiFallback = false // Respect sentence cap even on Gemini fallback

    companion object {
        private const val TAG = "RegularStrategy"
        private const val MIN_SENTENCE_CHARS = 20
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
        full.clear()
        firstEmitted = false
        emitCursor = 0
        deliveredSentences = 0
        sentenceCapReached = false
        deltaCount = 0
        geminiNonStreamFallbackUsed = false
        geminiSingleChunkStreamDetected = false

        scope.launch(ioDispatcher) {
            try {
                var receivedAny = false
                val isGpt = modelName.startsWith("gpt-", ignoreCase = true)
                val isGemini = modelName.contains("gemini", ignoreCase = true)

                if (isGpt) {
                    Log.d(TAG, "[Regular] Stream OpenAI model=$modelName")
                    withRetries("openai-stream") {
                        streamOpenAi(systemPrompt, history, modelName, maxSentences, callbacks)
                    }
                    receivedAny = true
                } else if (isGemini) {
                    Log.d(TAG, "[Regular] Stream Gemini model=$modelName")
                    receivedAny = withRetries("gemini-stream") {
                        streamGemini(systemPrompt, history, modelName, maxSentences, callbacks)
                    }
                } else {
                    throw RuntimeException("Unknown model type: $modelName")
                }

                if (!receivedAny && active) {
                    Log.w(TAG, "[Regular] No tokens from stream; falling back to non-stream")
                    val text = if (isGpt) callOpenAiNonStream(systemPrompt, history, modelName)
                    else {
                        geminiNonStreamFallbackUsed = true
                        callGeminiNonStream(systemPrompt, history, modelName)
                    }
                    if (text.isNotBlank()) onChunk(text, callbacks, maxSentences)
                } else if (isGemini && deltaCount == 1) {
                    geminiSingleChunkStreamDetected = true
                }
                onStreamEnd(callbacks, maxSentences)

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

    private fun <T> withRetries(
        description: String,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 600,
        backoffFactor: Double = 2.0,
        block: () -> T
    ): T {
        var attempt = 1
        var delayMs = initialDelayMs
        var last: Exception? = null
        while (attempt <= maxAttempts) {
            try {
                Log.d(TAG, "[Regular] $description attempt=$attempt")
                return block()
            } catch (e: Exception) {
                last = e
                val msg = e.message.orEmpty()
                val retriable = msg.contains("503") || msg.contains("429") || msg.contains("temporarily overloaded", ignoreCase = true)
                if (!retriable || attempt == maxAttempts) break
                Log.w(TAG, "[Regular] $description failed: ${msg.take(180)}. Retrying in ${delayMs}ms")
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
                delayMs = (delayMs * backoffFactor).toLong().coerceAtMost(8000)
                attempt++
            }
        }
        throw last ?: RuntimeException("$description failed")
    }

    private fun onChunk(delta: String, callbacks: Callbacks, maxSentences: Int) {
        if (!active || delta.isEmpty()) return
        full.append(delta)
        deltaCount++
        callbacks.onStreamDelta(delta)

        if (!firstEmitted) {
            val (candidate, _) = SentenceSplitter.extractFirstSentence(full.toString())
            if (candidate.length >= MIN_SENTENCE_CHARS && (candidate.endsWith('.') || candidate.endsWith('!') || candidate.endsWith('?'))) {
                firstEmitted = true
                emitCursor = candidate.length
                deliveredSentences = 1
                callbacks.onFirstSentence(candidate)
            }
        }
        if (!sentenceCapReached) {
            emitMoreCompletedSentences(callbacks, maxSentences)
            if (deliveredSentences >= maxSentences) sentenceCapReached = true
        }
    }

    private fun emitMoreCompletedSentences(callbacks: Callbacks, maxSentences: Int) {
        while (active && emitCursor < full.length && deliveredSentences < maxSentences) {
            val remainingText = full.substring(emitCursor)
            val (sentence, _) = SentenceSplitter.extractFirstSentence(remainingText)
            if (sentence.isNotBlank() && (sentence.endsWith('.') || sentence.endsWith('!') || sentence.endsWith('?'))) {
                val newPart = full.substring(emitCursor, emitCursor + sentence.length)
                emitCursor += sentence.length
                val trimmedSentence = newPart.trim()
                if (trimmedSentence.isNotBlank()) {
                    deliveredSentences++
                    callbacks.onStreamSentence(trimmedSentence)
                }
            } else break
        }
    }

    private fun onStreamEnd(callbacks: Callbacks, maxSentences: Int) {
        if (!active) return
        val all = full.toString().trim()
        if (all.isEmpty()) {
            if (active) callbacks.onError("Empty response from model.")
            return
        }
        callbacks.onFinalResponse(all)

        val allSentences = SentenceSplitter.splitIntoSentences(all)
        if (!firstEmitted && allSentences.isNotEmpty()) {
            callbacks.onFirstSentence(allSentences.first())
            deliveredSentences = 1
        }

        val allowedLeft = (maxSentences - deliveredSentences).coerceAtLeast(0)
        val remaining = allSentences.drop(deliveredSentences)
        if (allowedLeft > 0 && remaining.isNotEmpty()) {
            val deliver = remaining.take(allowedLeft)
            Log.d(TAG, "[Regular] onStreamEnd deliverRemaining=${deliver.size} (allowedLeft=$allowedLeft, totalLeft=${remaining.size})")
            callbacks.onRemainingSentences(deliver)
        } else {
            Log.d(TAG, "[Regular] onStreamEnd no remaining sentences to deliver (allowedLeft=$allowedLeft)")
        }
    }

    private fun effectiveGeminiModel(name: String): String = when (name.lowercase()) {
        "gemini-pro-latest", "gemini-2.5-pro-latest" -> "gemini-2.5-pro"
        "gemini-flash-latest", "gemini-2.5-flash-latest" -> "gemini-2.5-flash"
        else -> name
    }
    private fun openAiAllowsTemperature(model: String): Boolean = !model.startsWith("gpt-5", ignoreCase = true)
    private fun parseError(body: String): String? = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
    private fun extractGeminiText(payload: String): String {
        return try {
            val root = JSONObject(payload)
            root.optJSONObject("promptFeedback")?.optString("blockReason")?.let { if (it.isNotBlank()) return "Blocked: $it" }
            val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            (0 until parts.length()).joinToString("") { i -> parts.optJSONObject(i)?.optString("text").orEmpty() }.trim()
        } catch (e: Exception) { Log.e(TAG, "[Regular] Error extracting Gemini text", e); "" }
    }

    private fun throwDescriptiveError(code: Int, body: String, service: String) {
        val userMessage = when (code) {
            503 -> "The model is temporarily overloaded. Please try again in a moment. (Error: 503)"
            429 -> "You have exceeded your API quota. Please check your plan and billing details. (Error: 429)"
            401 -> "Invalid API Key. Please check your credentials. (Error: 401)"
            else -> "A model error occurred. (Error: $code)"
        }
        Log.e(TAG, "[Regular] $service Error - Code: $code, Body: ${body.take(500)}")
        throw RuntimeException(userMessage)
    }

    private fun streamOpenAi(systemPrompt: String, history: List<Msg>, modelName: String, maxSentences: Int, callbacks: Callbacks) {
        val apiKey = openAiKeyProvider().trim().ifBlank { throw RuntimeException("OpenAI API key missing") }
        val url = "https://api.openai.com/v1/chat/completions"
        val bodyObj = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                history.forEach { m -> put(JSONObject().apply { put("role", m.role); put("content", m.text) }) }
            })
            put("stream", true)
            if (openAiAllowsTemperature(modelName)) put("temperature", 0.7)
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
                    if (!token.isNullOrEmpty()) onChunk(token, callbacks, maxSentences)
                } catch (_: Exception) {}
            }
        }
    }

    private fun callOpenAiNonStream(systemPrompt: String, history: List<Msg>, modelName: String): String {
        val apiKey = openAiKeyProvider().trim().ifBlank { throw RuntimeException("OpenAI API key missing") }
        val url = "https://api.openai.com/v1/chat/completions"
        val bodyObj = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                history.forEach { m -> put(JSONObject().apply { put("role", m.role); put("content", m.text) }) }
            })
            if (openAiAllowsTemperature(modelName)) put("temperature", 0.7)
        }
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType())).build()

        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throwDescriptiveError(resp.code, payload, "OpenAI")
            return JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "").orEmpty().trim()
        }
    }

    private fun streamGemini(systemPrompt: String, history: List<Msg>, modelName: String, maxSentences: Int, callbacks: Callbacks): Boolean {
        val apiKey = geminiKeyProvider().trim().ifBlank { throw RuntimeException("Gemini API key missing") }
        val effModel = effectiveGeminiModel(modelName)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$effModel:streamGenerateContent?key=$apiKey"
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))) })
                put(JSONObject().apply { put("role", "model"); put("parts", JSONArray().put(JSONObject().put("text", "Understood."))) })
                history.forEach { m ->
                    val role = if (m.role == "assistant") "model" else "user"
                    put(JSONObject().apply { put("role", role); put("parts", JSONArray().put(JSONObject().put("text", m.text))) })
                }
            })
            put("generationConfig", JSONObject().put("temperature", 0.7))
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url(url).post(body).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throwDescriptiveError(resp.code, resp.body?.string().orEmpty(), "Gemini")
            val source = resp.body?.source() ?: return false
            var sawAny = false
            while (active) {
                val line = source.readUtf8Line() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val jsonStr = when {
                    trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
                    trimmed.firstOrNull() == '{' -> trimmed
                    else -> continue
                }
                try {
                    val root = JSONObject(jsonStr)
                    root.optJSONObject("promptFeedback")?.optString("blockReason")?.let {
                        if (it.isNotBlank()) {
                            onChunk("Blocked: $it", callbacks, maxSentences)
                            sawAny = true
                        }
                    }
                    val parts = root.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val text = parts.optJSONObject(i)?.optString("text").orEmpty()
                            if (text.isNotEmpty()) {
                                onChunk(text, callbacks, maxSentences)
                                sawAny = true
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore parse errors for non-JSON lines
                }
            }
            return sawAny
        }
    }

    private fun callGeminiNonStream(systemPrompt: String, history: List<Msg>, modelName: String): String {
        val apiKey = geminiKeyProvider().trim().ifBlank { throw RuntimeException("Gemini API key missing") }
        val effModel = effectiveGeminiModel(modelName)
        val url = "https://generativelanguage.googleapis.com/v1/models/$effModel:generateContent?key=$apiKey"
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))) })
                put(JSONObject().apply { put("role", "model"); put("parts", JSONArray().put(JSONObject().put("text", "Understood."))) })
                history.forEach { m ->
                    val role = if (m.role == "assistant") "model" else "user"
                    put(JSONObject().apply { put("role", role); put("parts", JSONArray().put(JSONObject().put("text", m.text))) })
                }
            })
            put("generationConfig", JSONObject().put("temperature", 0.7))
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url(url).post(body).build()
        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throwDescriptiveError(resp.code, payload, "Gemini")
            return extractGeminiText(payload)
        }
    }
}