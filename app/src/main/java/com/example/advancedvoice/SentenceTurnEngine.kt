package com.example.advancedvoice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal sentence-by-sentence engine:
 * - Sends ONE sentence per request (no auto-retry, no provider fallback).
 * - If more text is needed and no user interrupt, call requestNext().
 * - Keeps bounded history (22 turns).
 */
class SentenceTurnEngine(
    private val uiScope: CoroutineScope,
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String,
    private val onSentence: (String) -> Unit,
    private val onTurnFinish: () -> Unit,
    private val onSystem: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    private data class Msg(val role: String, val text: String) // role: user|assistant

    private val history = ArrayList<Msg>()         // user/assistant turns
    private val maxHistoryTurns = 22               // like the python sample
    private val maxSentencesPerTurn = 3

    private var active = false
    private var hasMore = false
    private var model = "gemini-2.5-flash"
    private var sentencesThisTurn = 0
    private var lastAssistantSentence: String? = null

    fun isActive(): Boolean = active
    fun hasMoreToSay(): Boolean = hasMore

    fun abort() {
        active = false
        hasMore = false
        sentencesThisTurn = 0
        lastAssistantSentence = null
        // Do not clear history; we keep prior context
        onSystem("Generation interrupted.")
    }

    fun startTurn(userText: String, modelName: String) {
        if (userText.isBlank()) return
        model = modelName
        active = true
        hasMore = false
        sentencesThisTurn = 0
        lastAssistantSentence = null

        addUser(userText)
        requestSentence(initial = true)
    }

    fun requestNext() {
        if (!active) return
        if (!hasMore) return
        if (sentencesThisTurn >= maxSentencesPerTurn) {
            active = false
            onTurnFinish()
            return
        }
        requestSentence(initial = false)
    }

    // ---- Internals ----

    private fun addUser(text: String) {
        history.add(Msg("user", text))
        trimHistory()
    }

    private fun addAssistant(text: String) {
        history.add(Msg("assistant", text))
        trimHistory()
    }

    private fun trimHistory() {
        // keep last N user/assistant exchanges (2 * maxHistoryTurns messages)
        var userAssistantCount = history.count { it.role == "user" || it.role == "assistant" }
        while (userAssistantCount > 2 * maxHistoryTurns && history.isNotEmpty()) {
            val removed = history.removeAt(0)
            if (removed.role == "user" || removed.role == "assistant") {
                userAssistantCount--
            }
        }
    }

    private fun requestSentence(initial: Boolean) {
        val instruction = buildInstruction(initial)
        uiScope.launch(Dispatchers.IO) {
            try {
                val sentence = if (model.startsWith("gpt-", ignoreCase = true)) {
                    callOpenAiOnce(instruction)
                } else {
                    callGeminiOnce(instruction)
                }

                if (!isActive()) return@launch

                if (sentence.isBlank()) {
                    // No content received, end turn gracefully
                    hasMore = false
                    active = false
                    uiScope.launch { onSystem("No content received from the model.") }
                    uiScope.launch { onTurnFinish() }
                    return@launch
                }

                lastAssistantSentence = sentence
                sentencesThisTurn++
                addAssistant(sentence)
                uiScope.launch { onSentence(sentence) }

                // If model indicated more is needed AND sentence cap not reached, keep hasMore set
                if (hasMore && sentencesThisTurn < maxSentencesPerTurn) {
                    // Wait for TTS to call requestNext(); do nothing here.
                } else {
                    hasMore = false
                    active = false
                    uiScope.launch { onTurnFinish() }
                }
            } catch (e: Exception) {
                if (!isActive()) return@launch
                active = false
                hasMore = false
                uiScope.launch { onError(e.message ?: "Unexpected error") }
                uiScope.launch { onTurnFinish() }
            }
        }
    }

    private fun buildInstruction(initial: Boolean): String {
        // We enforce JSON output: {"sentence":"...","is_final_sentence":true|false}
        // For continuation, we provide last sentence as context.
        val continueHint = lastAssistantSentence?.let { " Continue from your last sentence: \"$it\"." } ?: ""
        val core =
            "Respond ONLY with a compact JSON object: {\"sentence\":\"<exactly one concise sentence>\",\"is_final_sentence\":<true|false>}." +
                    " Do not include any extra keys, no explanations or prose outside JSON." +
                    " If you can continue the answer with another sentence, set is_final_sentence=false; otherwise true."
        return if (initial) core else core + continueHint
    }

    // ---- Providers ----

    private fun callOpenAiOnce(instruction: String): String {
        val apiKey = openAiKeyProvider().trim()
        if (apiKey.isBlank()) throw RuntimeException("OpenAI API key missing.")

        val url = "https://api.openai.com/v1/chat/completions"
        val msgs = buildOpenAiMessages(instruction)

        val body = JSONObject().apply {
            put("model", model)
            put("messages", msgs)
            // For gpt-5 family, avoid temperature to reduce 400s
            if (!model.startsWith("gpt-5", ignoreCase = true)) {
                put("temperature", 0.7)
            }
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val parsed = parseError(payload)
                throw RuntimeException("OpenAI ${resp.code}: ${parsed ?: payload.take(300)}")
            }
            val root = JSONObject(payload)
            val content = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
                .orEmpty()
            return parseSentenceJsonOrFirstSentence(content)
        }
    }

    private fun callGeminiOnce(instruction: String): String {
        val apiKey = geminiKeyProvider().trim()
        if (apiKey.isBlank()) throw RuntimeException("Gemini API key missing.")

        val url = "https://generativelanguage.googleapis.com/v1/models/$model:generateContent?key=$apiKey"
        val contents = buildGeminiContents(instruction)

        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply { put("temperature", 0.7) })
        }

        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val parsed = parseError(payload)
                throw RuntimeException("Gemini ${resp.code}: ${parsed ?: payload.take(300)}")
            }
            val text = extractGeminiText(payload).trim()
            return parseSentenceJsonOrFirstSentence(text)
        }
    }

    // ---- Message builders ----

    private fun buildOpenAiMessages(instruction: String): JSONArray {
        val msgs = JSONArray()
        // System instruction first
        msgs.put(
            JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "You must obey formatting strictly. " +
                            instruction +
                            " If asked to continue, return the next single sentence."
                )
            }
        )
        // Add history
        for (m in history) {
            msgs.put(JSONObject().apply {
                put("role", if (m.role == "assistant") "assistant" else "user")
                put("content", m.text)
            })
        }
        return msgs
    }

    private fun buildGeminiContents(instruction: String): JSONArray {
        val contents = JSONArray()

        // Put instruction as a leading "user" message (Gemini doesn't have system role in v1 body)
        contents.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", "Instruction: $instruction") })
                })
            }
        )

        for (m in history) {
            val role = if (m.role == "assistant") "model" else "user"
            contents.put(
                JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", m.text) })
                    })
                }
            )
        }
        return contents
    }

    // ---- Parsing helpers ----

    private fun extractGeminiText(payload: String): String {
        return try {
            val root = JSONObject(payload)
            // safety block?
            root.optJSONObject("promptFeedback")?.optString("blockReason")?.let { br ->
                if (!br.isNullOrBlank()) return "The model blocked this request ($br)."
            }
            val parts = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
            if (parts == null || parts.length() == 0) return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text", null)
                if (!t.isNullOrEmpty()) sb.append(t)
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseError(body: String): String? {
        return try {
            val err = JSONObject(body).optJSONObject("error")
            err?.optString("message")
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSentenceJsonOrFirstSentence(text: String): String {
        // Try parse JSON object containing sentence + is_final_sentence
        val json = firstJsonObject(text)
        if (json != null) {
            val sentence = json.optString("sentence", "").trim()
            val isFinal = json.optBoolean("is_final_sentence", true)
            hasMore = !isFinal && sentencesThisTurn < maxSentencesPerTurn
            return sentence
        }
        // Fallback: take first sentence heuristically
        val first = firstSentence(text)
        // Without schema we set is_final_sentence=true (no more unless user asks)
        hasMore = false
        return first
    }

    private fun firstJsonObject(text: String): JSONObject? {
        val s = text.trim()
        if (s.startsWith("{") && s.endsWith("}")) {
            return try { JSONObject(s) } catch (_: Exception) { null }
        }
        // try to locate the first {...}
        val start = s.indexOf('{')
        if (start == -1) return null
        var depth = 0
        for (i in start until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = s.substring(start, i + 1)
                        return try { JSONObject(candidate) } catch (_: Exception) { null }
                    }
                }
            }
        }
        return null
    }

    private fun firstSentence(text: String): String {
        val t = text.trim().replace("\\s+".toRegex(), " ")
        if (t.isEmpty()) return ""
        val idx = t.indexOfFirst { it == '.' || it == '!' || it == '?' }
        return if (idx == -1) t else t.substring(0, idx + 1)
    }
}