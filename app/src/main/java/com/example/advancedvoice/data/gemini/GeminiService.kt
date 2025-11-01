package com.example.advancedvoice.data.gemini

import android.util.Log
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

    companion object {
        private const val TAG = "GeminiService"
    }

    /**
     * Generates text and extracts grounding sources.
     * Returns Pair of (text, sources).
     */
    fun generateTextWithSources(
        systemPrompt: String,
        history: List<ChatMessage>,
        modelName: String,
        temperature: Double = 0.7,
        enableGoogleSearch: Boolean = true
    ): Pair<String, List<Pair<String, String?>>> {
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

            val text = extractText(payload)
            val sources = extractGroundingSources(payload)

            return text to sources
        }
    }

    fun generateText(
        systemPrompt: String,
        history: List<ChatMessage>,
        modelName: String,
        temperature: Double = 0.7,
        enableGoogleSearch: Boolean = true
    ): String {
        return generateTextWithSources(systemPrompt, history, modelName, temperature, enableGoogleSearch).first
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

    private fun extractGroundingSources(payload: String): List<Pair<String, String?>> {
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
            Log.e(TAG, "Error extracting Gemini grounding", e)
            emptyList()
        }
    }
}