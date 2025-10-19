package com.example.advancedvoice

import android.util.Log
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
import java.net.URI
import java.util.UUID
import java.util.regex.Pattern

// More informative exception used by UI/system messages (shared by strategies)
class ModelServiceException(
    val httpCode: Int,
    val service: String,
    val model: String,
    val retryAfterSec: Double?,
    val rawBody: String,
    message: String
) : RuntimeException(message)

/**
 * Fast-first strategy with:
 * - Gemini: Google Search grounding enabled on every call (v1beta).
 * - OpenAI GPT-5 (+ gpt-5-mini): Responses API with web_search tool; reasoning effort + verbosity.
 * - No retries. Soft-fail Phase 2 on 429/503.
 *
 * Accumulates web sources across Phase 1 and Phase 2, de-duplicates, and posts a single compact
 * system message with short labels and smaller font.
 */
class FastFirstSentenceStrategy(
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String,
    private val openAiOptionsProvider: () -> OpenAiOptions,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IGenerationStrategy {

    @Volatile private var active = false

    companion object {
        private const val TAG = "SentenceTurnEngine"
        private const val FAST_TAG = "FastFirst"
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
        val turnId = UUID.randomUUID().toString().substring(0, 8)
        val combinedSources = CombinedSources()

        val postSystemFn: (String) -> Unit = { msg ->
            scope.launch { callbacks.onSystem(msg) }
        }

        scope.launch(ioDispatcher) {
            try {
                Log.i(TAG, "[FastFirst:$turnId] Start execute model=$modelName maxSentences=$maxSentences")

                // Phase 1: accumulate sources; do not post yet
                val firstSentenceText = getFastFirstSentence(
                    turnId, history, modelName, systemPrompt,
                    onGrounding = { sources -> combinedSources.addAll(sources) },
                    postSystem = postSystemFn
                )
                if (!active) throw java.util.concurrent.CancellationException("Aborted during Phase 1")
                if (firstSentenceText.isNullOrBlank()) throw RuntimeException("Phase 1 failed to generate a first sentence.")
                callbacks.onFirstSentence(firstSentenceText)

                // Phase 2: also accumulate sources
                val remainingSentences = getRemainingSentences(
                    turnId, history, modelName, systemPrompt, maxSentences, firstSentenceText,
                    onGrounding = { sources -> combinedSources.addAll(sources) },
                    postSystem = postSystemFn
                )
                if (!active) throw java.util.concurrent.CancellationException("Aborted during Phase 2")

                if (remainingSentences.isNotEmpty()) {
                    callbacks.onRemainingSentences(remainingSentences)
                    callbacks.onFinalResponse(firstSentenceText + " " + remainingSentences.joinToString(" "))
                } else {
                    callbacks.onFinalResponse(firstSentenceText)
                }

                // Post combined sources once (smaller font, compact labels)
                combinedSources.toHtmlCompact()?.let { postSystemFn(it) }
            } catch (e: Exception) {
                if (active && e !is java.util.concurrent.CancellationException) {
                    Log.e(TAG, "[FastFirst:$turnId] Request failed: ${e.message}", e)
                    callbacks.onError(e.message ?: "Request failed")
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
            Log.d(TAG, "[FastFirst] Aborted.")
            active = false
        }
    }

    // Phase 1 (single attempt)
    private fun getFastFirstSentence(
        turnId: String,
        history: List<Msg>,
        originalModel: String,
        systemPrompt: String,
        onGrounding: (List<Pair<String, String?>>) -> Unit,
        postSystem: (String) -> Unit
    ): String? {
        val isGemini = originalModel.contains("gemini", ignoreCase = true)
        val fastModel = chooseFastModel(originalModel)
        val temp = 0.2
        val prompt = """
        Answer the user's previous question with ONLY the first sentence of your response.
        The sentence must be complete, factual, and concise. No greetings or slang.
        Plain text only.
    """.trimIndent()

        Log.i(TAG, "[FastFirst:$turnId] Phase1 model=$fastModel (from $originalModel) temp=$temp")

        val response = if (isGemini) {
            callGeminiNonStream(systemPrompt, history + Msg("user", prompt), fastModel, temp, onGrounding)
        } else {
            callOpenAi(systemPrompt, history + Msg("user", prompt), fastModel, temp, onGrounding, postSystem)
        }

        // Der SentenceSplitter wird nicht mehr verwendet.
        // Die komplette Antwort des schnellen Modells wird ohne Kürzung übernommen.
        val first = response.trim()

        return first.ifBlank { null }
    }

    // Phase 2 (single attempt), soft-fail on 429/503
    private fun getRemainingSentences(
        turnId: String,
        history: List<Msg>,
        qualityModel: String,
        systemPrompt: String,
        maxSentences: Int,
        firstSentence: String,
        onGrounding: (List<Pair<String, String?>>) -> Unit,
        postSystem: (String) -> Unit
    ): List<String> {
        val isGemini = qualityModel.contains("gemini", ignoreCase = true)
        val temp = 0.7
        val prompt = "You already provided the first sentence: \"$firstSentence\". Continue with up to ${maxSentences - 1} additional complete sentences. Do not repeat the first sentence."
        val updatedHistory = history + Msg("assistant", firstSentence) + Msg("user", prompt)

        return try {
            val fullResponse = if (isGemini) {
                callGeminiNonStream(systemPrompt, updatedHistory, qualityModel, temp, onGrounding)
            } else {
                callOpenAi(systemPrompt, updatedHistory, qualityModel, temp, onGrounding, postSystem)
            }
            if (fullResponse.isNotBlank()) SentenceSplitter.splitIntoSentences(fullResponse) else emptyList()
        } catch (e: Exception) {
            val sysMsg = when (e) {
                is ModelServiceException -> {
                    val reason = when (e.httpCode) {
                        429 -> "rate limit/quota exceeded"
                        503 -> "temporarily overloaded/unavailable"
                        404 -> "model not found or not accessible"
                        else -> "service error"
                    }
                    val retryStr = e.retryAfterSec?.let { "~${String.format(java.util.Locale.US, "%.1f", it)}s" } ?: "a moment"
                    val altModel = if (qualityModel.contains("gemini", true)) "gemini-2.5-flash" else "gpt-5"
                    "Service note: ${e.service} is $reason for ${e.model}. I already delivered the first sentence; the remainder was skipped. Try again in $retryStr or switch models (e.g., $altModel)."
                }
                else -> "Service note: An unknown model error occurred while composing the remainder. The first sentence was delivered; please try again shortly."
            }
            if (e is ModelServiceException && (e.httpCode == 429 || e.httpCode == 503)) {
                Log.w(TAG, "[FastFirst:$turnId] Phase2 soft-fail: $sysMsg")
                postSystem(sysMsg)
                emptyList()
            } else {
                postSystem(sysMsg)
                throw e
            }
        }
    }

    private fun chooseFastModel(originalModel: String): String {
        val lower = originalModel.lowercase()
        return when {
            lower.startsWith("gpt-5") -> "gpt-5" // do not silently downgrade to mini
            lower.startsWith("gemini-2.5-pro") || lower == "gemini-pro-latest" || lower == "gemini-2.5-pro-latest" -> "gemini-2.5-flash"
            else -> originalModel
        }
    }

    private fun effectiveGeminiModel(name: String): String = when (name.lowercase()) {
        "gemini-pro-latest", "gemini-2.5-pro-latest" -> "gemini-2.5-pro"
        "gemini-flash-latest", "gemini-2.5-flash-latest" -> "gemini-2.5-flash"
        else -> name
    }

    private fun buildGeminiToolsArray(@Suppress("UNUSED_PARAMETER") modelName: String): JSONArray {
        // For Gemini 2.x, googleSearch is supported in v1beta. We send it on every request.
        return JSONArray().apply { put(JSONObject().put("googleSearch", JSONObject())) }
    }

    // -- OpenAI routing: GPT-5 and GPT-5-mini via Responses API (+ web_search), others via Chat Completions
    private fun callOpenAi(
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

        // Upgrade minimal -> low when web_search is enabled (per OpenAI docs)
        val effectiveEffort = if (optsIn.effort.equals("minimal", true)) {
            postSystem("Note: Upgrading GPT‑5 reasoning effort from minimal to low to enable web search.")
            "low"
        } else optsIn.effort

        // Build a single input string
        val inputText = buildString {
            appendLine(systemPrompt)
            appendLine()
            history.forEach { m ->
                val role = if (m.role == "assistant") "Assistant" else "User"
                appendLine("$role: ${m.text}")
            }
        }.trim()

        // tools: web_search (optional filters, location)
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
            put("model", modelName) // "gpt-5" or "gpt-5-mini"
            put("input", inputText)
            put("reasoning", JSONObject().put("effort", effectiveEffort))
            put("text", JSONObject().put("verbosity", optsIn.verbosity))
            put("tools", toolsArr)
            put("tool_choice", "auto")
            put("include", includeArr)
        }

        Log.i(FAST_TAG, "OpenAI Responses request model=$modelName effort=$effectiveEffort verbosity=${optsIn.verbosity} (web_search enabled)")
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
                Log.e(TAG, "[FastFirst] Failed to parse OpenAI Responses payload", e)
                ""
            }
            return text
        }
    }

    private fun extractOpenAiMessageTextAndCitations(root: JSONObject): Pair<String, List<Pair<String, String?>>> {
        val outSources = ArrayList<Pair<String, String?>>()
        var outText = ""
        val output = root.optJSONArray("output")
        if (output != null) {
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                if (item.optString("type") == "message") {
                    val content = item.optJSONArray("content") ?: continue
                    val first = content.optJSONObject(0) ?: continue
                    outText = first.optString("text", outText)
                    val annotations = first.optJSONArray("annotations")
                    if (annotations != null) {
                        for (j in 0 until annotations.length()) {
                            val a = annotations.optJSONObject(j) ?: continue
                            if (a.optString("type") == "url_citation") {
                                val url = a.optString("url").orEmpty()
                                val title = a.optString("title").takeIf { it.isNotBlank() }
                                if (url.isNotBlank()) outSources.add(url to title)
                            }
                        }
                    }
                }
            }
        }
        return outText to outSources
    }

    private fun extractOpenAiCitationsFromMessage(root: JSONObject): List<Pair<String, String?>> {
        return extractOpenAiMessageTextAndCitations(root).second
    }

    private fun callOpenAiChat(systemPrompt: String, history: List<Msg>, modelName: String, temperature: Double): String {
        val apiKey = openAiKeyProvider().trim().ifBlank { throw RuntimeException("OpenAI API key missing") }
        val url = "https://api.openai.com/v1/chat/completions"
        val bodyObj = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                history.forEach { m -> put(JSONObject().apply { put("role", m.role); put("content", m.text) }) }
            })
            put("temperature", temperature)
        }
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throwDescriptiveError(resp.code, payload, "OpenAI", modelName, null)
            return JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "").orEmpty().trim()
        }
    }

    private fun callGeminiNonStream(
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

        Log.i(FAST_TAG, "Gemini request model=$effModel (from=$modelName) temp=$temperature (Google Search grounding enabled, v1beta)")
        val req = Request.Builder().url(url)
            .addHeader("Content-Type", "application/json")
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val retryAfter = parseGeminiRetryAfterFromBodySeconds(payload)
                throwDescriptiveError(resp.code, payload, "Gemini", effModel, retryAfter)
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
            val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            (0 until parts.length()).joinToString("") { i ->
                parts.optJSONObject(i)?.optString("text").orEmpty()
            }.trim()
        } catch (e: Exception) {
            Log.e(TAG, "[FastFirst] Error extracting Gemini text", e); ""
        }
    }

    // Returns list of Pair<uri, title?>
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
            Log.e(TAG, "[FastFirst] Error extracting Gemini grounding", e)
            emptyList()
        }
    }

    private fun parseGeminiRetryAfterFromBodySeconds(body: String): Double? {
        val p = Pattern.compile("retry in\\s+([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(body)
        return if (m.find()) m.group(1)?.toDoubleOrNull() else null
    }

    private fun buildUserMessage(code: Int, service: String, model: String, retryAfterSec: Double?, raw: String): String {
        val retryStr = retryAfterSec?.let { " Retry after ~${String.format(java.util.Locale.US, "%.1f", it)}s." } ?: ""
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
        val userMessage = buildUserMessage(code, service, model, retryAfterSec, body)
        Log.e(TAG, "[FastFirst] $service Error - Code: $code, Model: $model, RetryAfter=${retryAfterSec ?: "-"}, Body: ${body.take(500)}")
        Log.e(FAST_TAG, "$service error code=$code model=$model retryAfter=${retryAfterSec ?: "-"}")
        throw ModelServiceException(code, service, model, retryAfterSec, body, userMessage)
    }
}

/**
 * Accumulate and de-duplicate sources across phases by compact site label.
 * Prefer non-vertex redirect URLs if both are seen for the same label.
 * Outputs a compact HTML line with smaller font and clickable short labels.
 */
private class CombinedSources {
    private data class Entry(
        val url: String,
        val title: String?,
        val host: String,
        val brand: String,
        val isVertex: Boolean
    )

    private val byBrand = LinkedHashMap<String, Entry>() // preserve insertion order

    fun addAll(items: List<Pair<String, String?>>) {
        for ((url, title) in items) {
            val host = hostFromUrl(url)
            // If vertex redirect and title looks like a domain, use title to derive brand
            val labelHost = if (isVertexRedirectHost(host) && title != null && isDomainLike(title)) {
                title.lowercase()
            } else if (title != null && isDomainLike(title)) {
                // Prefer domain-like title when available (more accurate brand)
                title.lowercase()
            } else host.lowercase()

            val brand = baseBrandFromHost(labelHost)
            if (brand.isBlank()) continue

            val entry = Entry(
                url = url,
                title = title,
                host = host,
                brand = brand,
                isVertex = isVertexRedirectHost(host)
            )

            val existing = byBrand[brand]
            if (existing == null) {
                byBrand[brand] = entry
            } else {
                // Prefer non-vertex URL over vertex redirect for the same brand
                val keep = if (existing.isVertex && !entry.isVertex) entry else existing
                byBrand[brand] = keep
            }
        }
    }

    fun toHtmlCompact(maxItems: Int = 5): String? {
        if (byBrand.isEmpty()) return null
        val anchors = byBrand.values.take(maxItems).map { e ->
            val label = prettyLabel(e.brand)
            val safeLabel = escapeHtml(label)
            val safeHref = escapeHtml(e.url)
            "<a href=\"$safeHref\">$safeLabel</a>"
        }
        // Two steps smaller using nested <small>
        val inner = "Web Sources: ${anchors.joinToString(", ")}"
        return "<small><small>$inner</small></small>"
    }

    // ---- Local helpers (file-private) ----

    private fun hostFromUrl(u: String): String {
        return try {
            val h = URI(u).host ?: u
            if (h.startsWith("www.")) h.removePrefix("www.") else h
        } catch (_: Exception) {
            u
        }
    }

    private fun isVertexRedirectHost(host: String): Boolean {
        val h = host.lowercase()
        return h.contains("vertex") && h.contains("google")
    }

    private fun isDomainLike(text: String): Boolean {
        val t = text.lowercase().trim()
        // crude domain-ish check like "wikipedia.org", "uefa.com"
        return Regex("^[a-z0-9.-]+\\.[a-z]{2,}$").matches(t)
    }

    private fun baseBrandFromHost(host: String): String {
        val h = host.lowercase()
        if (h.endsWith(".gov")) return "usgovernment" // compact label for .gov
        var trimmed = h
        listOf("www.", "m.", "en.").forEach { p -> if (trimmed.startsWith(p)) trimmed = trimmed.removePrefix(p) }
        val parts = trimmed.split('.')
        return parts.firstOrNull().orEmpty().ifBlank { trimmed }
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