package com.example.advancedvoice.data.openai

import com.example.advancedvoice.core.network.HttpClientProvider
import com.example.advancedvoice.data.common.ChatMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAiService(
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient = HttpClientProvider.client
) {

    data class Result(val text: String, val sources: List<Pair<String, String?>>)

    fun generateResponses(
        systemPrompt: String,
        history: List<ChatMessage>,
        model: String,
        effort: String = "low",
        verbosity: String = "low",
        enableWebSearch: Boolean = true
    ): Result {
        val apiKey = apiKeyProvider().trim()
        require(apiKey.isNotEmpty()) { "OpenAI API key missing" }

        val url = "https://api.openai.com/v1/responses"

        val inputText = buildString {
            appendLine(systemPrompt)
            appendLine()
            for (msg: ChatMessage in history) {
                val role = if (msg.role == "assistant") "Assistant" else "User"
                appendLine("$role: ${msg.text}")
            }
        }.trim()

        val toolsArr = JSONArray().apply {
            if (enableWebSearch) {
                put(JSONObject().put("type", "web_search"))
            }
        }
        val includeArr = JSONArray().apply {
            if (enableWebSearch) put("web_search_call.action.sources")
        }

        val body = JSONObject().apply {
            put("model", model)
            put("input", inputText)
            put("reasoning", JSONObject().put("effort", effort))
            put("text", JSONObject().put("verbosity", verbosity))
            if (enableWebSearch) {
                put("tools", toolsArr)
                put("tool_choice", "auto")
                put("include", includeArr)
            }
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("OpenAI error: HTTP ${resp.code} ${resp.message}\n${payload.take(400)}")
            }
            val parsed = OpenAiResponseParser.parseResponsesPayload(payload)
            return Result(parsed.text, parsed.sources)
        }
    }

    fun generateChat(
        systemPrompt: String,
        history: List<ChatMessage>,
        model: String,
        temperature: Double = 0.7
    ): String {
        val apiKey = apiKeyProvider().trim()
        require(apiKey.isNotEmpty()) { "OpenAI API key missing" }

        val url = "https://api.openai.com/v1/chat/completions"

        val msgs = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            for (msg: ChatMessage in history) {
                put(JSONObject().put("role", msg.role).put("content", msg.text))
            }
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", msgs)
            put("temperature", temperature)
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("OpenAI error: HTTP ${resp.code} ${resp.message}\n${payload.take(400)}")
            val root = JSONObject(payload)
            return root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
                .orEmpty()
        }
    }
}
