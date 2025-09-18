package com.example.advancedvoice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

data class ApiError(
    val provider: String,
    val url: String,
    val code: Int?,
    val status: String?,
    val message: String?,
    val requestId: String?,
    val retryAfterSeconds: Long?
)

/**
 * Single-attempt orchestrator (no automatic retries or extra fallbacks).
 * - Maintains history and preemption (combine if no sentence emitted yet).
 * - Does one request per user turn:
 *   • OpenAI: stream if allowed for model; else non-stream once.
 *   • Gemini: stream once (no non-stream fallback).
 * - Applies provider cooldown on 429 to prevent quota storms.
 */
class ConversationOrchestrator(
    private val uiScope: CoroutineScope,
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String,
    private val onAssistantSentence: (String) -> Unit,
    private val onSystemMessage: (String) -> Unit,
    private val onThinking: () -> Unit,
    private val onDone: () -> Unit,
    private val onError: (ApiError) -> Unit
) {

    private data class Turn(val role: String, val text: String)

    // History
    private val finalHistory = mutableListOf<Turn>()
    private val currentTurnHistory = mutableListOf<Turn>()
    private val maxContextChars = 20000

    // In-flight
    private var inflightCall: Call? = null
    private val aborted = AtomicBoolean(false)
    private var inFlight = false
    private var sentencesDeliveredThisTurn = 0

    // Preempt-combine
    private var preemptCombineCandidateText: String? = null
    private var preemptCombineArmed: Boolean = false
    private var currentTurnFirstUserText: String? = null

    // Cooldown after rate limit
    private val cooldownUntilMs = mutableMapOf<String, Long>()
    private fun nowMs() = System.currentTimeMillis()
    private fun coolingMsLeft(provider: String): Long =
        (cooldownUntilMs[provider] ?: 0L).let { left -> (left - nowMs()).coerceAtLeast(0) }
    private fun setCooldown(provider: String, seconds: Long) {
        cooldownUntilMs[provider] = nowMs() + seconds * 1000
    }

    // OpenAI streaming policy (disable streaming for GPT‑5 family by default)
    private var openAiStreamAllowedGpt5 = false
    private fun isOpenAiStreamingAllowedFor(modelName: String): Boolean {
        return if (modelName.startsWith("gpt-5", ignoreCase = true)) {
            openAiStreamAllowedGpt5
        } else true
    }
    private fun disableOpenAiStreamingForGpt5() {
        openAiStreamAllowedGpt5 = false
    }

    fun submitUserText(userTextRaw: String, modelName: String): Boolean {
        val text = userTextRaw.trim()
        if (text.isEmpty()) return false

        val provider = if (modelName.startsWith("gpt-", ignoreCase = true)) "OpenAI" else "Gemini"
        val left = coolingMsLeft(provider)
        if (left > 0) {
            uiScope.launch {
                val sec = (left / 1000).coerceAtLeast(1)
                onSystemMessage("$provider is cooling down for ~${sec}s due to recent rate limits. Please wait...")
                onDone()
            }
            return false
        }

        val finalUserText = if (preemptCombineArmed && !preemptCombineCandidateText.isNullOrBlank()) {
            val combined = (preemptCombineCandidateText!! + " " + text).trim()
            preemptCombineArmed = false
            preemptCombineCandidateText = null
            uiScope.launch { onSystemMessage("Combined your new request with the previous unfinished one.") }
            combined
        } else text

        startNewTurn(finalUserText, modelName)
        return true
    }

    fun abortForPreempt() {
        if (inFlight && sentencesDeliveredThisTurn == 0) {
            preemptCombineArmed = true
            preemptCombineCandidateText = currentTurnFirstUserText
        } else {
            preemptCombineArmed = false
            preemptCombineCandidateText = null
        }
        cancelInFlight(silent = true)
    }

    fun stopAll() {
        preemptCombineArmed = false
        preemptCombineCandidateText = null
        cancelInFlight(silent = false)
    }

    private fun startNewTurn(userText: String, modelName: String) {
        cancelInFlight(silent = true)

        currentTurnHistory.clear()
        currentTurnHistory.add(Turn("user", userText))
        currentTurnFirstUserText = userText
        sentencesDeliveredThisTurn = 0
        inFlight = true
        aborted.set(false)

        onThinking()

        // IMPORTANT: run the blocking network call off the main thread
        uiScope.launch(Dispatchers.IO) {
            try {
                val produced = if (modelName.startsWith("gpt-", ignoreCase = true)) {
                    callOpenAiSingle(modelName)
                } else {
                    callGeminiSingle(modelName)
                }

                if (aborted.get()) return@launch
                if (!produced) uiScope.launch { onSystemMessage("No content received from the model.") }

                finalHistory.addAll(currentTurnHistory)
                currentTurnHistory.clear()
                inFlight = false
                uiScope.launch { onDone() }
            } catch (e: ApiHttpException) {
                if (aborted.get()) return@launch
                if (e.provider == "Gemini" && e.code == 429) {
                    setCooldown("Gemini", e.retryAfterSeconds ?: 60)
                }
                if (e.provider == "OpenAI" && e.code == 429) {
                    setCooldown("OpenAI", e.retryAfterSeconds ?: 60)
                }
                inFlight = false
                uiScope.launch {
                    onError(
                        ApiError(
                            provider = e.provider,
                            url = e.url,
                            code = e.code,
                            status = e.status,
                            message = e.messageDetail ?: e.bodySnippet,
                            requestId = e.requestId,
                            retryAfterSeconds = e.retryAfterSeconds
                        )
                    )
                    onDone()
                }
            } catch (e: ApiNetworkException) {
                if (aborted.get()) return@launch
                inFlight = false
                uiScope.launch {
                    onError(
                        ApiError(
                            provider = e.provider,
                            url = e.url,
                            code = null,
                            status = null,
                            message = e.cause?.message,
                            requestId = null,
                            retryAfterSeconds = null
                        )
                    )
                    onDone()
                }
            } catch (e: Exception) {
                if (aborted.get()) return@launch
                inFlight = false
                uiScope.launch {
                    onError(
                        ApiError(
                            provider = "Unknown",
                            url = "",
                            code = null,
                            status = null,
                            message = e.message ?: (e::class.java.simpleName),
                            requestId = null,
                            retryAfterSeconds = null
                        )
                    )
                    onDone()
                }
            }
        }
    }

    private fun cancelInFlight(silent: Boolean) {
        aborted.set(true)
        inflightCall?.cancel()
        inflightCall = null
        if (!silent && inFlight) {
            uiScope.launch { onSystemMessage("Generation interrupted.") }
        }
        if (inFlight && sentencesDeliveredThisTurn > 0) {
            finalHistory.addAll(currentTurnHistory)
        }
        currentTurnHistory.clear()
        inFlight = false
        sentencesDeliveredThisTurn = 0
    }

    // ===== Builders and helpers =====

    private fun buildGeminiContents(): JSONArray {
        pruneHistoryIfNeeded()
        val all = finalHistory + currentTurnHistory
        val contents = JSONArray()
        for (t in all) {
            val role = if (t.role.equals("assistant", true)) "model" else "user"
            contents.put(
                JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", t.text) })
                    })
                }
            )
        }
        return contents
    }

    private fun buildOpenAiMessages(): JSONArray {
        pruneHistoryIfNeeded()
        val all = finalHistory + currentTurnHistory
        val messages = JSONArray()
        for (t in all) {
            val role = if (t.role.equals("assistant", true)) "assistant" else "user"
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", t.text)
            })
        }
        return messages
    }

    private fun pruneHistoryIfNeeded() {
        var total = finalHistory.sumOf { it.text.length } + currentTurnHistory.sumOf { it.text.length }
        while (total > maxContextChars && finalHistory.isNotEmpty()) {
            val removed = finalHistory.removeAt(0)
            total -= removed.text.length
        }
    }

    private class SentenceAggregator(private val onSentence: (String) -> Unit) {
        private val buf = StringBuilder()
        fun feed(chunk: String) {
            if (chunk.isEmpty()) return
            buf.append(chunk)
            emitComplete()
        }
        fun flushEnd() {
            val rest = buf.toString().trim()
            buf.clear()
            if (rest.isNotEmpty()) onSentence(rest)
        }
        private fun emitComplete() {
            val text = buf.toString()
            var last = 0
            val out = mutableListOf<String>()
            for (i in text.indices) {
                val ch = text[i]
                if (ch == '.' || ch == '!' || ch == '?') {
                    val next = if (i + 1 < text.length) text[i + 1] else null
                    if (next == null || next.isWhitespace()) {
                        val s = text.substring(last, i + 1).trim()
                        if (s.isNotEmpty()) out.add(s)
                        var j = i + 1
                        while (j < text.length && text[j].isWhitespace()) j++
                        last = j
                    }
                }
            }
            if (out.isNotEmpty()) {
                val leftover = if (last < text.length) text.substring(last) else ""
                buf.clear()
                buf.append(leftover)
                out.forEach { onSentence(it) }
            }
        }
    }

    private class ApiHttpException(
        val provider: String,
        val url: String,
        val code: Int,
        val status: String?,
        val messageDetail: String?,
        val bodySnippet: String?,
        val requestId: String?,
        val retryAfterSeconds: Long?
    ) : RuntimeException()

    private class ApiNetworkException(
        val provider: String,
        val url: String,
        cause: Throwable
    ) : IOException(cause)

    private data class ParsedError(val status: String?, val message: String?)

    private fun parseRetryAfterSeconds(h: String?): Long? =
        h?.trim()?.toLongOrNull()

    private fun safeParseError(body: String): ParsedError {
        return try {
            val obj = JSONObject(body)
            val err = obj.optJSONObject("error")
            if (err != null) {
                ParsedError(
                    status = err.optString("status", null) ?: err.optString("type", null),
                    message = err.optString("message", null)
                )
            } else {
                ParsedError(status = null, message = obj.optString("message", body.take(300)))
            }
        } catch (_: Exception) {
            ParsedError(status = null, message = body.take(300))
        }
    }

    private fun readSseStream(source: BufferedSource, onData: (String) -> Boolean) {
        while (true) {
            val line = try { source.readUtf8Line() } catch (_: Exception) { null } ?: break
            if (line.startsWith("data:")) {
                val data = line.substringAfter("data:").trim()
                if (!onData(data)) break
            }
            if (aborted.get()) break
        }
    }

    // ===== Single-attempt calls =====

    private fun callOpenAiSingle(modelName: String): Boolean {
        return if (isOpenAiStreamingAllowedFor(modelName)) {
            streamOpenAI(modelName)
        } else {
            openAiOneShot(modelName)
        }
    }

    private fun streamOpenAI(modelName: String): Boolean {
        val apiKey = openAiKeyProvider().trim()
        if (apiKey.isEmpty()) throw ApiHttpException("OpenAI", "", 401, "UNAUTHORIZED", "OpenAI API key missing", null, null, null)

        val url = "https://api.openai.com/v1/chat/completions"
        val body = JSONObject().apply {
            put("model", modelName)
            put("messages", buildOpenAiMessages())
            put("stream", true)
            // For GPT‑5 family, we try streaming only if explicitly enabled
            if (!modelName.startsWith("gpt-5", true)) put("temperature", 0.7)
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = http.newCall(req)
        inflightCall = call

        val agg = SentenceAggregator {
            if (aborted.get()) return@SentenceAggregator
            currentTurnHistory.add(Turn("assistant", it))
            sentencesDeliveredThisTurn++
            uiScope.launch { onAssistantSentence(it) }
        }

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    val err = safeParseError(errBody)
                    val reqId = resp.header("x-request-id")
                    val retryAfter = parseRetryAfterSeconds(resp.header("Retry-After"))
                    val msg = (err.message ?: "").lowercase()
                    val typ = (err.status ?: "").lowercase()
                    if (resp.code == 400 && (typ.contains("invalid_request")) && (msg.contains("stream") || msg.contains("verify"))) {
                        disableOpenAiStreamingForGpt5()
                    }
                    throw ApiHttpException(
                        "OpenAI",
                        url,
                        resp.code,
                        err.status,
                        err.message,
                        errBody.take(800),
                        reqId,
                        retryAfter
                    )
                }
                val src = resp.body?.source() ?: return false
                readSseStream(src) { data ->
                    if (aborted.get()) return@readSseStream false
                    if (data == "[DONE]") return@readSseStream false
                    try {
                        val j = JSONObject(data)
                        val delta = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                        val content = delta?.optString("content", null)
                        if (!content.isNullOrEmpty()) agg.feed(content)
                    } catch (_: Exception) { /* ignore */ }
                    true
                }
                agg.flushEnd()
            }
        } catch (io: IOException) {
            if (aborted.get()) return false
            throw ApiNetworkException("OpenAI", url, io)
        }
        return sentencesDeliveredThisTurn > 0
    }

    private fun openAiOneShot(modelName: String): Boolean {
        val apiKey = openAiKeyProvider().trim()
        if (apiKey.isEmpty()) throw ApiHttpException("OpenAI", "", 401, "UNAUTHORIZED", "OpenAI API key missing", null, null, null)

        val url = "https://api.openai.com/v1/chat/completions"
        val body = JSONObject().apply {
            put("model", modelName)
            put("messages", buildOpenAiMessages())
            if (!modelName.startsWith("gpt-5", true)) put("temperature", 0.7)
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = http.newCall(req)
        inflightCall = call
        try {
            call.execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = safeParseError(bodyStr)
                    val reqId = resp.header("x-request-id")
                    val retryAfter = parseRetryAfterSeconds(resp.header("Retry-After"))
                    if (resp.code == 429) setCooldown("OpenAI", retryAfter ?: 60)
                    throw ApiHttpException(
                        "OpenAI",
                        url,
                        resp.code,
                        err.status,
                        err.message,
                        bodyStr.take(800),
                        reqId,
                        retryAfter
                    )
                }
                val root = JSONObject(bodyStr)
                val choices = root.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty()
                    if (content.isNotBlank()) {
                        val agg = SentenceAggregator {
                            if (aborted.get()) return@SentenceAggregator
                            currentTurnHistory.add(Turn("assistant", it))
                            sentencesDeliveredThisTurn++
                            uiScope.launch { onAssistantSentence(it) }
                        }
                        agg.feed(content)
                        agg.flushEnd()
                    }
                }
            }
        } catch (io: IOException) {
            if (aborted.get()) return false
            throw ApiNetworkException("OpenAI", url, io)
        }
        return sentencesDeliveredThisTurn > 0
    }

    private fun callGeminiSingle(modelName: String): Boolean {
        val apiKey = geminiKeyProvider().trim()
        if (apiKey.isEmpty()) throw ApiHttpException("Gemini", "", 401, "UNAUTHORIZED", "Gemini API key missing", null, null, null)

        val url = "https://generativelanguage.googleapis.com/v1/models/$modelName:streamGenerateContent?key=$apiKey"
        val body = JSONObject().apply {
            put("contents", buildGeminiContents())
            put("generationConfig", JSONObject().apply { put("temperature", 0.7) })
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = http.newCall(req)
        inflightCall = call

        val agg = SentenceAggregator {
            if (aborted.get()) return@SentenceAggregator
            currentTurnHistory.add(Turn("assistant", it))
            sentencesDeliveredThisTurn++
            uiScope.launch { onAssistantSentence(it) }
        }

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    val err = safeParseError(errBody)
                    val reqId = resp.header("x-goog-request-id") ?: resp.header("x-request-id")
                    val retryAfter = parseRetryAfterSeconds(resp.header("Retry-After"))
                    if (resp.code == 429) setCooldown("Gemini", retryAfter ?: 60)
                    throw ApiHttpException(
                        "Gemini",
                        url,
                        resp.code,
                        err.status,
                        err.message,
                        errBody.take(800),
                        reqId,
                        retryAfter
                    )
                }
                val src = resp.body?.source() ?: return false
                readSseStream(src) { dataLine ->
                    if (aborted.get()) return@readSseStream false
                    try {
                        val j = JSONObject(dataLine)
                        val text = j.optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.let { parts ->
                                val sb = StringBuilder()
                                for (i in 0 until parts.length()) {
                                    val part = parts.optJSONObject(i)?.optString("text", null)
                                    if (!part.isNullOrEmpty()) sb.append(part)
                                }
                                sb.toString()
                            }
                        if (!text.isNullOrEmpty()) {
                            agg.feed(text)
                        }
                    } catch (_: Exception) { /* ignore */ }
                    true
                }
                agg.flushEnd()
            }
        } catch (io: IOException) {
            if (aborted.get()) return false
            throw ApiNetworkException("Gemini", url, io)
        }
        return sentencesDeliveredThisTurn > 0
    }
}