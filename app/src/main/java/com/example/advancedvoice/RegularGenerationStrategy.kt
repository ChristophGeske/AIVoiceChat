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
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

class RegularGenerationStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String,
    private val openAiOptionsProvider: () -> OpenAiOptions,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IGenerationStrategy {

    @Volatile private var active = false

    companion object {
        private const val TAG = "RegularStrategy"
        private const val FORCE_GEMINI_SEARCH_ALWAYS = true
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
        val turnId = java.util.UUID.randomUUID().toString().substring(0, 8)

        fun postSystem(msg: String) {
            scope.launch { callbacks.onSystem(msg) }
        }

        scope.launch(ioDispatcher) {
            try {
                Log.i(TAG, "[Regular:$turnId] Start execute model=$modelName maxSentences=$maxSentences")

                val isGemini = modelName.contains("gemini", ignoreCase = true)

                val fullText = if (isGemini) {
                    callGeminiOnce(systemPrompt, history, modelName, 0.7) { items ->
                        if (items.isNotEmpty()) {
                            postSystem(formatWebSourcesCompact(items))
                        }
                    }
                } else {
                    callOpenAiOnce(systemPrompt, history, modelName, 0.7, { items ->
                        if (items.isNotEmpty()) {
                            postSystem(formatWebSourcesCompact(items))
                        }
                    }, ::postSystem)
                }

                val finalText = fullText.trim()
                if (finalText.isNotEmpty()) {
                    callbacks.onFinalResponse(finalText)
                } else {
                    postSystem("Service note: The model returned an empty response. Please try again.")
                }
            } catch (e: Exception) {
                when (e) {
                    is ModelServiceException -> {
                        val reason = when (e.httpCode) {
                            429 -> "rate limit/quota exceeded"
                            503 -> "temporarily overloaded/unavailable"
                            404 -> "model not found or not accessible"
                            401 -> "invalid API key"
                            400 -> "bad request"
                            else -> "service error"
                        }
                        val retryStr = e.retryAfterSec?.let { "~${String.format(Locale.US, "%.1f", it)}s" } ?: "a moment"
                        val altModel = if (modelName.contains("gemini", true)) "gemini-2.5-flash" else "gpt-5-mini"

                        if (e.httpCode == 429 || e.httpCode == 503) {
                            val msg = "Service note: ${e.service} is $reason for ${e.model}. Please try again in $retryStr or switch models (e.g., $altModel)."
                            Log.w(TAG, "[Regular:$turnId] Soft-fail $reason: $msg")
                            postSystem(msg)
                        } else {
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

    private fun callOpenAiOnce(
        systemPrompt: String,
        history: List<Msg>,
        modelName: String,
        temperature: Double,
        onGrounding: (List<Pair<String, String?>>) -> Unit,
        postSystem: (String) -> Unit
    ): String {
        val lower = modelName.lowercase()
        return if (lower.startsWith("gpt-5")) {
            callOpenAiResponses(systemPrompt, history, modelName, onGrounding, postSystem)
        } else {
            callOpenAiChat(systemPrompt, history, modelName, temperature)
        }
    }

    private fun callOpenAiResponses(
        systemPrompt: String,
        history: List<Msg>,
        modelName: String,
        onGrounding: (List<Pair<String, String?>>) -> Unit,
        postSystem: (String) -> Unit
    ): String {
        val apiKey = openAiKeyProvider().trim().ifBlank { throw RuntimeException("OpenAI API key missing") }
        val optsIn = openAiOptionsProvider()
        val url = "https://api.openai.com/v1/responses"

        val effectiveEffort = if (optsIn.effort.equals("minimal", true)) {
            postSystem("Note: Upgrading GPT‑5 reasoning effort from minimal to low to enable web search.")
            "low"
        } else optsIn.effort

        // Explicit instruction to not include citations in text
        val noCitationsInstruction = "\n\nIMPORTANT: Do NOT include any citations, source references, URLs, or bracketed references in your response text. Sources will be displayed separately."

        val inputText = buildString {
            appendLine(systemPrompt + noCitationsInstruction)  // ✅ CHANGED THIS LINE
            appendLine()
            history.forEach { m ->
                val role = if (m.role == "assistant") "Assistant" else "User"
                appendLine("$role: ${m.text}")
            }
        }.trim()

        val toolsArr = JSONArray().apply {
            val web = JSONObject().put("type", "web_search")
            optsIn.filters?.let { f ->
                if (f.allowedDomains.isNotEmpty()) {
                    val filters = JSONObject()
                    val allowed = JSONArray()
                    f.allowedDomains.forEach { allowed.put(it) }
                    filters.put("allowed_domains", allowed)
                    web.put("filters", filters)
                }
            }
            optsIn.userLocation?.let { loc ->
                val locObj = JSONObject().put("type", "approximate")
                loc.country?.let { locObj.put("country", it) }
                loc.city?.let { locObj.put("city", it) }
                loc.region?.let { locObj.put("region", it) }
                loc.timezone?.let { locObj.put("timezone", it) }
                web.put("user_location", locObj)
            }
            put(web)
        }

        val includeArr = JSONArray().put("web_search_call.action.sources")

        val bodyObj = JSONObject().apply {
            put("model", modelName)
            put("input", inputText)
            put("reasoning", JSONObject().put("effort", effectiveEffort))
            put("text", JSONObject().put("verbosity", optsIn.verbosity))
            put("tools", toolsArr)
            put("tool_choice", "auto")
            put("include", includeArr)
        }

        Log.i(TAG, "OpenAI Responses request model=$modelName effort=$effectiveEffort verbosity=${optsIn.verbosity} (web_search enabled)")
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val retryAfter: Double? = resp.header("Retry-After")?.toDoubleOrNull()
                throwDescriptiveError(resp.code, payload, "OpenAI", modelName, retryAfter)
            }
            val text = try {
                val root = JSONObject(payload)
                val direct = root.optString("output_text", null)
                if (!direct.isNullOrBlank()) {
                    val sources = extractOpenAiCitationsFromMessage(root)
                    if (sources.isNotEmpty()) onGrounding(sources)
                    direct.trim()
                } else {
                    val pair = extractOpenAiMessageTextAndCitations(root)
                    if (pair.first.isNotBlank() && pair.second.isNotEmpty()) onGrounding(pair.second)
                    pair.first
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Regular] Failed to parse OpenAI Responses payload", e)
                ""
            }
            return text
        }
    }

    private fun extractOpenAiMessageTextAndCitations(root: JSONObject): Pair<String, List<Pair<String, String?>>> {
        return OpenAiResponseParser.extractMessageAndCitations(root)
    }

    private fun extractOpenAiCitationsFromMessage(root: JSONObject): List<Pair<String, String?>> {
        return OpenAiResponseParser.extractMessageAndCitations(root).second
    }

    private fun callOpenAiChat(systemPrompt: String, history: List<Msg>, modelName: String, temperature: Double): String {
        val apiKey = openAiKeyProvider().trim().ifBlank { throw RuntimeException("OpenAI API key missing") }
        val url = "https://api.openai.com/v1/chat/completions"

        val bodyObj = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                history.forEach { m ->
                    put(JSONObject().apply { put("role", m.role); put("content", m.text) })
                }
            })
            put("temperature", temperature)
        }

        Log.i(TAG, "OpenAI Chat request model=$modelName temp=$temperature")
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
                    model = modelName,
                    retryAfterSec = resp.header("Retry-After")?.toDoubleOrNull()
                )
            }
            return try {
                val root = JSONObject(payload)
                root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                    .orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun effectiveGeminiModel(name: String): String = when (name.lowercase()) {
        "gemini-pro-latest", "gemini-2.5-pro-latest" -> "gemini-2.5-pro"
        "gemini-flash-latest", "gemini-2.5-flash-latest" -> "gemini-2.5-flash"
        else -> name
    }

    private fun buildGeminiToolsArray(modelName: String): JSONArray {
        return JSONArray().apply {
            put(JSONObject().put("googleSearch", JSONObject()))
        }
    }

    private fun callGeminiOnce(
        systemPrompt: String,
        history: List<Msg>,
        modelName: String,
        temperature: Double,
        onGrounding: (List<Pair<String, String?>>) -> Unit
    ): String {
        val apiKey = geminiKeyProvider().trim().ifBlank { throw RuntimeException("Gemini API key missing") }
        val effModel = effectiveGeminiModel(modelName)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$effModel:generateContent?key=$apiKey"

        val enforcedPrompt = if (FORCE_GEMINI_SEARCH_ALWAYS) {
            systemPrompt + "\n\nAlways use Google Search to ground your answer and include citations with source titles when available."
        } else systemPrompt

        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", enforcedPrompt)))
            })
            history.forEach { m ->
                val role = if (m.role == "assistant") "model" else "user"
                put(JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().put(JSONObject().put("text", m.text)))
                })
            }
        }

        val tools = buildGeminiToolsArray(effModel)

        val bodyObj = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply { put("temperature", temperature) })
            put("tools", tools)
        }

        Log.i(TAG, "Gemini request model=$effModel (from=$modelName) temp=$temperature (Google Search grounding enabled, v1beta)")
        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .build()

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
            val sources = extractGeminiGrounding(payload)
            if (sources.isNotEmpty()) onGrounding(sources)
            return extractGeminiText(payload)
        }
    }

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

    private fun extractGeminiGrounding(payload: String): List<Pair<String, String?>> {
        return try {
            val root = JSONObject(payload)
            val cand0 = root.optJSONArray("candidates")?.optJSONObject(0)
            val gm = cand0?.optJSONObject("groundingMetadata")
                ?: cand0?.optJSONObject("grounding_metadata")
            val chunks = gm?.optJSONArray("groundingChunks")
                ?: gm?.optJSONArray("grounding_chunks")
                ?: return emptyList()
            val out = ArrayList<Pair<String, String?>>()
            for (i in 0 until chunks.length()) {
                val web = chunks.optJSONObject(i)?.optJSONObject("web")
                val uri = web?.optString("uri").orEmpty()
                if (uri.isNotBlank()) {
                    val title = web?.optString("title")?.takeIf { it.isNotBlank() }
                    out.add(uri to title)
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "[Regular] Error extracting Gemini grounding", e)
            emptyList()
        }
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
            404 -> "The requested resource was not found on $service for $model. [HTTP 404]."
            400 -> "Bad request for $service and $model. Please update or try again. [HTTP 400]."
            else -> "A $service error occurred for $model. [HTTP $code]."
        }
    }

    private fun throwDescriptiveError(code: Int, body: String, service: String, model: String, retryAfterSec: Double?): Nothing {
        val msg = buildUserMessage(code, service, model, retryAfterSec, body)
        Log.e(TAG, "[$service] Error - Code: $code, Model: $model, RetryAfter=${retryAfterSec ?: "-"}, Body: ${body.take(500)}")
        throw ModelServiceException(code, service, model, retryAfterSec, body, msg)
    }

    private data class SourceEntry(val url: String, val title: String?, val host: String, val brand: String)

    private fun formatWebSourcesCompact(items: List<Pair<String, String?>>): String {
        if (items.isEmpty()) return ""

        val byBrand = LinkedHashMap<String, SourceEntry>()

        for ((url, title) in items) {
            val host = hostFromUrl(url)
            val brand = baseBrandFromHost(host)
            if (brand.isBlank()) continue

            val entry = SourceEntry(url = url, title = title, host = host, brand = brand)
            if (!byBrand.containsKey(brand)) {
                byBrand[brand] = entry
            }
        }

        val anchors = byBrand.values.take(5).map { e ->
            val label = prettyLabel(e.brand)
            val safeLabel = escapeHtml(label)
            val safeHref = escapeHtml(e.url)
            "<a href=\"$safeHref\">$safeLabel</a>"
        }

        return "<small><small>Web Sources: ${anchors.joinToString(", ")}</small></small>"
    }

    private fun hostFromUrl(u: String): String {
        return try {
            val h = URI(u).host ?: u
            if (h.startsWith("www.")) h.removePrefix("www.") else h
        } catch (_: Exception) {
            u
        }
    }

    private fun baseBrandFromHost(host: String): String {
        val h = host.lowercase()
        if (h.endsWith(".gov")) return "usgovernment"
        var trimmed = h
        listOf("www.", "m.", "en.").forEach { p -> if (trimmed.startsWith(p)) trimmed = trimmed.removePrefix(p) }
        return trimmed.split('.').firstOrNull()?.takeIf { it.isNotBlank() } ?: trimmed
    }

    private fun prettyLabel(brand: String): String {
        return when (brand.lowercase()) {
            "usgovernment" -> "USGovernment"
            else -> brand.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}