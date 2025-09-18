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
 * Sentence-by-sentence engine (no auto-retry, no auto-fallback).
 * - Builds a strict JSON instruction so model returns exactly one sentence with is_final_sentence flag.
 * - Does not auto-pipeline; caller (UI) controls pacing via requestNext() to sync with TTS.
 * - Supports user-configured max sentences per turn (default 4).
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

    private data class Msg(val role: String, val text: String)

    private val history = ArrayList<Msg>()
    private var maxSentencesPerTurn = 4
    private var model = "gemini-2.5-flash"

    private var active = false
    private var hasMore = false
    private var sentencesThisTurn = 0
    private var lastAssistantSentence: String? = null

    fun setMaxSentences(n: Int) {
        maxSentencesPerTurn = n.coerceIn(1, 10)
    }

    fun isActive(): Boolean = active
    fun hasMoreToSay(): Boolean = hasMore

    fun abort() {
        active = false
        hasMore = false
        sentencesThisTurn = 0
        lastAssistantSentence = null
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
        if (!active || !hasMore) return
        if (sentencesThisTurn >= maxSentencesPerTurn) {
            active = false
            hasMore = false
            uiScope.launch { onTurnFinish() }
            return
        }
        requestSentence(initial = false)
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

                // Do not auto-call requestNext() here; UI/tts pacing controls it.
                // hasMore is already set in parseSentenceJsonOrFirstSentence(...)
                if (!hasMore || sentencesThisTurn >= maxSentencesPerTurn) {
                    active = false
                    hasMore = false
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
        val plan = "Plan up to $maxSentencesPerTurn concise sentences total for this turn."
        val format = "Respond ONLY with JSON: {\"sentence\":\"<one concise sentence>\",\"is_final_sentence\":<true|false>}."
        val rule = "Return exactly one sentence per response. Set is_final_sentence=false until you finish or you reach $maxSentencesPerTurn sentences for this turn."
        val continueHint = lastAssistantSentence?.let { " Continue from your last sentence: \"$it\"." } ?: ""
        return "$plan $format $rule$continueHint"
    }

    private fun addUser(text: String) {
        history.add(Msg("user", text))
        trimHistory()
    }

    private fun addAssistant(text: String) {
        history.add(Msg("assistant", text))
        trimHistory()
    }

    private fun trimHistory() {
        // keep last 22 user/assistant turns (≈ python sample)
        var count = history.count { it.role == "user" || it.role == "assistant" }
        while (count > 44 && history.isNotEmpty()) {
            val removed = history.removeAt(0)
            if (removed.role == "user" || removed.role == "assistant") count--
        }
    }

    private fun callOpenAiOnce(instruction: String): String {
        val apiKey = openAiKeyProvider().trim()
        if (apiKey.isBlank()) throw RuntimeException("OpenAI API key missing.")

        val url = "https://api.openai.com/v1/chat/completions"
        val msgs = buildOpenAiMessages(instruction)

        val body = JSONObject().apply {
            put("model", model)
            put("messages", msgs)
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

    private fun buildOpenAiMessages(instruction: String): JSONArray {
        val msgs = JSONArray()
        msgs.put(
            JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "You must obey formatting strictly. $instruction If asked to continue, return the next single sentence."
                )
            }
        )
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

    private fun extractGeminiText(payload: String): String {
        return try {
            val root = JSONObject(payload)
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
        return try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
    }

    private fun parseSentenceJsonOrFirstSentence(text: String): String {
        val json = firstJsonObject(text)
        if (json != null) {
            val sentence = json.optString("sentence", "").trim()
            val isFinal = json.optBoolean("is_final_sentence", true)
            hasMore = !isFinal && sentencesThisTurn < maxSentencesPerTurn
            return sentence
        }
        val first = firstSentence(text)
        hasMore = false
        return first
    }

    private fun firstJsonObject(text: String): JSONObject? {
        val s = text.trim()
        if (s.startsWith("{") && s.endsWith("}")) {
            return try { JSONObject(s) } catch (_: Exception) { null }
        }
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