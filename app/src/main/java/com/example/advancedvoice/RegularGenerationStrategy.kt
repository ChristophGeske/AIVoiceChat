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
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/**
 * Regular strategy: one model call (no "fast first") and NO retries.
 * - If the call succeeds, emit onFinalResponse(fullText).
 * - If the call fails with 429/503, emit an informative system note (no popup) and finish.
 * - For other errors (401/404/etc.), propagate onError to show the error dialog.
 * - OpenAI legacy names are mapped to widely available models:
 *     gpt-5-turbo -> gpt-4o, gpt-5-mini -> gpt-4o-mini
 */
class RegularGenerationStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IGenerationStrategy {

    @Volatile private var active = false

    companion object {
        private const val TAG = "RegularStrategy"
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
                Log.i(TAG, "[Regular:$turnId] Start execute model=$modelName maxSentences=$maxSentences")

                val isGemini = modelName.contains("gemini", ignoreCase = true)
                val temperature = 0.7

                // Single attempt, no retries
                val fullText = if (isGemini) {
                    callGeminiOnce(systemPrompt, history, modelName, temperature)
                } else {
                    callOpenAiOnce(systemPrompt, history, modelName, temperature)
                }

                val finalText = fullText.trim()
                if (finalText.isNotEmpty()) {
                    callbacks.onFinalResponse(finalText)
                } else {
                    // Empty response (rare) – surface as system note
                    callbacks.onSystem("Service note: The model returned an empty response. Please try again.")
                }
            } catch (e: Exception) {
                when (e) {
                    is ModelServiceException -> {
                        val reason = when (e.httpCode) {
                            429 -> "rate limit/quota exceeded"
                            503 -> "temporarily overloaded/unavailable"
                            404 -> "model not found or not accessible"
                            401 -> "invalid API key"
                            else -> "service error"
                        }
                        val retryStr = e.retryAfterSec?.let { "~${String.format(Locale.US, "%.1f", it)}s" } ?: "a moment"
                        val altModel = if (modelName.contains("gemini", true)) "gemini-2.5-flash" else "gpt-4o-mini"

                        if (e.httpCode == 429 || e.httpCode == 503) {
                            // Soft-fail: system note, no error dialog
                            val msg = "Service note: ${e.service} is $reason for ${e.model}. Please try again in $retryStr or switch models (e.g., $altModel)."
                            Log.w(TAG, "[Regular:$turnId] Soft-fail $reason: $msg")
                            callbacks.onSystem(msg)
                        } else {
                            // Propagate as error for clear user feedback (popup)
                            Log.e(TAG, "[Regular:$turnId] Hard error: ${e.message}")
                            callbacks.onError(e.message ?: "Model error")
                        }
                    }
                    else -> {
                        Log.e(TAG, "[Regular:$turnId] Unknown error: ${e.message}", e)
                        callbacks.onError(e.message ?: "Unknown error")
                    }
                }
            } finally {
                if (active) {
                    active = false
                    callbacks.onTurnFinish()
                }
            }
        }
    }

    override fun abort() {
        if (active) {
            Log.d(TAG, "[Regular] Aborted.")
            active = false
        }
    }

    // ---------- OpenAI ----------

    private fun effectiveOpenAiModel(name: String): String {
        return when (name.lowercase()) {
            "gpt-5-turbo" -> "gpt-4o"
            "gpt-5-mini" -> "gpt-4o-mini"
            else -> name
        }
    }

    private fun callOpenAiOnce(systemPrompt: String, history: List<Msg>, modelName: String, temperature: Double): String {
        val apiKey = openAiKeyProvider().trim().ifBlank { throw RuntimeException("OpenAI API key missing") }
        val effModel = effectiveOpenAiModel(modelName)
        val url = "https://api.openai.com/v1/chat/completions"

        val bodyObj = JSONObject().apply {
            put("model", effModel)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                history.forEach { m -> put(JSONObject().apply { put("role", m.role); put("content", m.text) }) }
            })
            put("temperature", temperature)
        }

        Log.i(TAG, "OpenAI request model=$effModel (from=$modelName) temp=$temperature")
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throwDescriptiveError(
                    code = resp.code,
                    body = payload,
                    service = "OpenAI",
                    model = effModel,
                    retryAfterSec = resp.header("Retry-After")?.toDoubleOrNull()
                )
            }
            return try {
                val root = JSONObject(payload)
                root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    .orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
    }

    // ---------- Gemini ----------

    private fun effectiveGeminiModel(name: String): String = when (name.lowercase()) {
        "gemini-pro-latest", "gemini-2.5-pro-latest" -> "gemini-2.5-pro"
        "gemini-flash-latest", "gemini-2.5-flash-latest" -> "gemini-2.5-flash"
        else -> name
    }

    private fun callGeminiOnce(systemPrompt: String, history: List<Msg>, modelName: String, temperature: Double): String {
        val apiKey = geminiKeyProvider().trim().ifBlank { throw RuntimeException("Gemini API key missing") }
        val effModel = effectiveGeminiModel(modelName)
        val url = "https://generativelanguage.googleapis.com/v1/models/$effModel:generateContent?key=$apiKey"

        // Compose contents sequence: emulate a "system" preface by placing it as first user turn
        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put("text", "Understood.")))
            })
            history.forEach { m ->
                val role = if (m.role == "assistant") "model" else "user"
                put(JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().put(JSONObject().put("text", m.text)))
                })
            }
        }

        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
            })
        }.toString().toRequestBody("application/json".toMediaType())

        Log.i(TAG, "Gemini request model=$effModel (from=$modelName) temp=$temperature")
        val req = Request.Builder().url(url).post(body).build()

        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throwDescriptiveError(
                    code = resp.code,
                    body = payload,
                    service = "Gemini",
                    model = effModel,
                    retryAfterSec = parseGeminiRetryAfterFromBodySeconds(payload)
                )
            }
            return extractGeminiText(payload)
        }
    }

    // ---------- Shared helpers ----------

    private fun extractGeminiText(payload: String): String {
        return try {
            val root = JSONObject(payload)
            root.optJSONObject("promptFeedback")?.optString("blockReason")?.let { if (it.isNotBlank()) return "Blocked: $it" }
            val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: return ""
            (0 until parts.length()).joinToString("") { i ->
                parts.optJSONObject(i)?.optString("text").orEmpty()
            }.trim()
        } catch (e: Exception) {
            Log.e(TAG, "[Regular] Error extracting Gemini text", e); ""
        }
    }

    private fun parseOpenAiErrorInfo(body: String): Pair<String?, String?> {
        return try {
            val err = JSONObject(body).optJSONObject("error") ?: return null to null
            val code = err.optString("code", null)
            val message = err.optString("message", null)
            code to message
        } catch (_: Exception) { null to null }
    }

    private fun parseGeminiRetryAfterFromBodySeconds(body: String): Double? {
        val p = Pattern.compile("retry in\\s+([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(body)
        return if (m.find()) m.group(1)?.toDoubleOrNull() else null
    }

    private fun buildUserMessage(code: Int, service: String, model: String, retryAfterSec: Double?, raw: String): String {
        val retryStr = retryAfterSec?.let { " Retry after ~${String.format(Locale.US, "%.1f", it)}s." } ?: ""
        return when (code) {
            503 -> "$service is temporarily overloaded/unavailable for $model. Please try again in a moment. [HTTP 503].$retryStr"
            429 -> "You have exceeded your API quota or hit a rate limit on $service for $model. Please check your plan/billing or slow down requests. [HTTP 429].$retryStr"
            401 -> "Invalid API Key for $service. Please check your credentials. [HTTP 401]."
            404 -> {
                if (service == "OpenAI") {
                    val (errCode, errMsg) = parseOpenAiErrorInfo(raw)
                    if (errCode == "model_not_found" || (errMsg ?: "").contains("does not exist", true)) {
                        "The selected OpenAI model ($model) was not found or is not available to your account. Please pick a model you have access to (e.g., gpt-4o or gpt-4o-mini). [HTTP 404]."
                    } else {
                        "The requested resource was not found on $service for $model. [HTTP 404]."
                    }
                } else {
                    "The requested resource was not found on $service for $model. [HTTP 404]."
                }
            }
            else -> "A $service error occurred for $model. [HTTP $code]."
        }
    }

    private fun throwDescriptiveError(code: Int, body: String, service: String, model: String, retryAfterSec: Double?): Nothing {
        val msg = buildUserMessage(code, service, model, retryAfterSec, body)
        Log.e(TAG, "[$service] Error - Code: $code, Model: $model, RetryAfter=${retryAfterSec ?: "-"}, Body: ${body.take(500)}")
        throw ModelServiceException(code, service, model, retryAfterSec, body, msg)
    }
}