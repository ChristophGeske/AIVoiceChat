package com.example.advancedvoice.data.gemini

import com.example.advancedvoice.core.network.HttpClientProvider
import com.example.advancedvoice.data.common.ChatMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin wrapper for Gemini v1beta generateContent.
 */
class GeminiService(
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient = HttpClientProvider.client
) {

    fun generateText(
        systemPrompt: String,
        history: List<ChatMessage>,
        modelName: String,
        temperature: Double = 0.7,
        enableGoogleSearch: Boolean = true
    ): String {
        val apiKey = apiKeyProvider().trim()
        require(apiKey.isNotEmpty()) { "Gemini API key missing" }

        val effModel = GeminiModels.effective(modelName)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$effModel:generateContent?key=$apiKey"

        val enforcedPrompt = if (enableGoogleSearch) {
            systemPrompt + "\n\nAlways use Google Search to ground your answer and include citations with source titles when available."
        } else systemPrompt

        val contents = JSONArray().apply {
            // System as a user content for v1beta
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", enforcedPrompt)))
            })
            // Map chat history
            for (msg: ChatMessage in history) {
                val role = if (msg.role == "assistant") "model" else "user"
                put(JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
                })
            }
        }

        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().put("temperature", temperature))
            if (enableGoogleSearch) {
                val tools = JSONArray().apply { put(JSONObject().put("googleSearch", JSONObject())) }
                put("tools", tools)
            }
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Gemini error: HTTP ${resp.code} ${resp.message}\n${payload.take(400)}")
            return extractText(payload)
        }
    }

    private fun extractText(payload: String): String {
        return try {
            val root = JSONObject(payload)
            root.optJSONObject("promptFeedback")?.optString("blockReason")?.let {
                if (it.isNotBlank()) return "Blocked: $it"
            }
            val parts = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: return ""
            buildString {
                for (i in 0 until parts.length()) {
                    append(parts.optJSONObject(i)?.optString("text").orEmpty())
                }
            }.trim()
        } catch (_: Exception) {
            ""
        }
    }
}
