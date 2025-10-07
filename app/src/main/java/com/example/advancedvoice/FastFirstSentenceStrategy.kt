package com.example.advancedvoice

import android.util.Log
import com.example.advancedvoice.SentenceTurnEngine.Msg
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

class FastFirstSentenceStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IGenerationStrategy {

    @Volatile private var active = false

    companion object {
        // Use the same tag you filter on
        private const val TAG = "SentenceTurnEngine"
    }

    override fun execute(
        scope: CoroutineScope,
        history: List<Msg>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    ) {
        active = true
        val turnId = UUID.randomUUID().toString().substring(0, 8)

        scope.launch(ioDispatcher) {
            try {
                Log.i(TAG, "[FastFirst:$turnId] Start execute model=$modelName maxSentences=$maxSentences")

                // Phase 1: fast first sentence (prefer 'flash' when original is gemini 2.5 pro)
                Log.d(TAG, "[FastFirst:$turnId] Phase 1 begin")
                val t1 = System.currentTimeMillis()
                val firstSentenceText = getFastFirstSentence(turnId, history, modelName, systemPrompt, callbacks)
                if (!active) throw CancellationException("Aborted during Phase 1")
                val t1Dur = System.currentTimeMillis() - t1
                Log.i(TAG, "[FastFirst:$turnId] Phase 1 complete in ${t1Dur}ms")

                if (firstSentenceText.isNullOrBlank()) {
                    throw RuntimeException("Phase 1 failed to generate a first sentence.")
                }
                callbacks.onFirstSentence(firstSentenceText)

                // Phase 2: quality remainder
                Log.d(TAG, "[FastFirst:$turnId] Phase 2 begin")
                val t2 = System.currentTimeMillis()
                val remainingSentences = getRemainingSentences(turnId, history, modelName, systemPrompt, maxSentences, firstSentenceText, callbacks)
                if (!active) throw CancellationException("Aborted during Phase 2")
                val t2Dur = System.currentTimeMillis() - t2
                Log.i(TAG, "[FastFirst:$turnId] Phase 2 complete in ${t2Dur}ms sentences=${remainingSentences.size}")

                if (remainingSentences.isNotEmpty()) {
                    callbacks.onRemainingSentences(remainingSentences)
                    callbacks.onFinalResponse(firstSentenceText + " " + remainingSentences.joinToString(" "))
                } else {
                    callbacks.onFinalResponse(firstSentenceText)
                }
            } catch (e: Exception) {
                if (active && e !is CancellationException) {
                    Log.e(TAG, "[FastFirst:$turnId] Request failed: ${e.message}", e)
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
        if (active) {
            Log.d(TAG, "[FastFirst] Aborted.")
            active = false
        }
    }

    // Phase 1: choose a fast model and retry transient errors
    private fun getFastFirstSentence(
        turnId: String,
        history: List<Msg>,
        originalModel: String,
        systemPrompt: String,
        callbacks: SentenceTurnEngine.Callbacks
    ): String? {
        val isGemini = originalModel.contains("gemini", ignoreCase = true)
        val fastModel = chooseFastModel(originalModel) // flash for gemini-pro, mini for gpt-5
        val temp = 0.2
        val prompt = "Answer the user's previous question with ONLY the first sentence of your response. The sentence must be complete and contain useful information, not just an acknowledgement. Plain text only."

        Log.i(TAG, "[FastFirst:$turnId] Phase1 model=$fastModel (from $originalModel) temp=$temp")

        val response = withRetries("phase1-$fastModel", maxAttempts = 3) {
            if (isGemini) {
                callGeminiNonStream(systemPrompt, history + Msg("user", prompt), fastModel, temp)
            } else {
                callOpenAiNonStream(systemPrompt, history + Msg("user", prompt), fastModel, temp)
            }
        }

        val first = SentenceSplitter.extractFirstSentence(response).first
        return first.ifBlank { null }
    }

    // Phase 2: try quality model with retries; if Gemini Pro still fails with 503/429, fall back to Flash once
    private fun getRemainingSentences(
        turnId: String,
        history: List<Msg>,
        qualityModel: String,
        systemPrompt: String,
        maxSentences: Int,
        firstSentence: String,
        callbacks: SentenceTurnEngine.Callbacks
    ): List<String> {
        val isGemini = qualityModel.contains("gemini", ignoreCase = true)
        val temp = 0.7
        val prompt = "You have already provided the first sentence of your answer: \"$firstSentence\". Now, continue your answer with the next part. Do not repeat the first sentence. Provide up to ${maxSentences - 1} more complete sentences."

        // Include the first sentence in history to make continuation coherent
        val updatedHistory = history + Msg("assistant", firstSentence) + Msg("user", prompt)

        Log.i(TAG, "[FastFirst:$turnId] Phase2 model=$qualityModel temp=$temp")

        val fullResponse = try {
            withRetries("phase2-$qualityModel", maxAttempts = 3) {
                if (isGemini) {
                    callGeminiNonStream(systemPrompt, updatedHistory, qualityModel, temp)
                } else {
                    callOpenAiNonStream(systemPrompt, updatedHistory, qualityModel, temp)
                }
            }
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val shouldFallbackToFlash = isGemini && isGeminiPro(qualityModel) &&
                    (msg.contains("503") || msg.contains("429") || msg.contains("temporarily overloaded", ignoreCase = true))
            if (shouldFallbackToFlash) {
                val fallbackModel = "gemini-2.5-flash"
                Log.w(TAG, "[FastFirst:$turnId] Phase2 fallback to $fallbackModel due to: ${msg.take(140)}")
                callbacks.onSystem("Gemini Pro was overloaded; fell back to Gemini Flash for remainder.")
                withRetries("phase2-$fallbackModel", maxAttempts = 2) {
                    callGeminiNonStream(systemPrompt, updatedHistory, fallbackModel, temp)
                }
            } else {
                throw e
            }
        }

        return if (fullResponse.isNotBlank()) SentenceSplitter.splitIntoSentences(fullResponse) else emptyList()
    }

    private fun chooseFastModel(originalModel: String): String {
        val lower = originalModel.lowercase()
        return when {
            lower.startsWith("gpt-5-turbo") -> "gpt-5-mini"
            lower.startsWith("gemini-2.5-pro") || lower == "gemini-pro-latest" || lower == "gemini-2.5-pro-latest" -> "gemini-2.5-flash"
            else -> originalModel
        }
    }

    private fun isGeminiPro(model: String): Boolean {
        val m = model.lowercase()
        return m.startsWith("gemini-2.5-pro") || m == "gemini-pro-latest" || m == "gemini-2.5-pro-latest"
    }

    // Generic retries with exp backoff and jitter
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
                Log.d(TAG, "[FastFirst] $description attempt=$attempt")
                return block()
            } catch (e: Exception) {
                last = e
                val msg = e.message.orEmpty()
                val retriable = msg.contains("503") || msg.contains("429") || msg.contains("temporarily overloaded", ignoreCase = true)
                if (!retriable || attempt == maxAttempts) break
                val jitter = Random.nextLong(0, 250)
                Log.w(TAG, "[FastFirst] $description failed: ${msg.take(180)}. Retrying in ${delayMs + jitter}ms")
                try { Thread.sleep(delayMs + jitter) } catch (_: InterruptedException) {}
                delayMs = (delayMs * backoffFactor).toLong().coerceAtMost(8000)
                attempt++
            }
        }
        throw last ?: RuntimeException("$description failed")
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
        } catch (e: Exception) { Log.e(TAG, "[FastFirst] Error extracting Gemini text", e); "" }
    }

    private fun throwDescriptiveError(code: Int, body: String, service: String) {
        val userMessage = when (code) {
            503 -> "The model is temporarily overloaded. Please try again in a moment. (Error: 503)"
            429 -> "You have exceeded your API quota. Please check your plan and billing details. (Error: 429)"
            401 -> "Invalid API Key. Please check your credentials. (Error: 401)"
            else -> "A model error occurred. (Error: $code)"
        }
        Log.e(TAG, "[FastFirst] $service Error - Code: $code, Body: ${body.take(500)}")
        throw RuntimeException(userMessage)
    }

    private fun callOpenAiNonStream(systemPrompt: String, history: List<Msg>, modelName: String, temperature: Double): String {
        val apiKey = openAiKeyProvider().trim().ifBlank { throw RuntimeException("OpenAI API key missing") }
        val url = "https://api.openai.com/v1/chat/completions"
        val bodyObj = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                history.forEach { m -> put(JSONObject().apply { put("role", m.role); put("content", m.text) }) }
            })
            if (openAiAllowsTemperature(modelName)) put("temperature", temperature)
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

    private fun callGeminiNonStream(systemPrompt: String, history: List<Msg>, modelName: String, temperature: Double): String {
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
            put("generationConfig", JSONObject().put("temperature", temperature))
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url(url).post(body).build()
        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throwDescriptiveError(resp.code, payload, "Gemini")
            return extractGeminiText(payload)
        }
    }
}