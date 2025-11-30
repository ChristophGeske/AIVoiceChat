package com.example.advancedvoice.data.gemini

import android.util.Base64
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
 * Service for sending audio directly to Gemini models.
 * Supports multimodal input with audio + text history.
 */
class GeminiAudioService(
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient = HttpClientProvider.client
) {
    companion object {
        private const val TAG = "GeminiAudioService"
    }

    /**
     * Generate a response from audio input.
     *
     * @param systemPrompt System instructions
     * @param textHistory Previous conversation turns (as text)
     * @param audioSegments Current turn audio segments (WAV bytes)
     * @param modelName Model to use (e.g., "gemini-2.5-pro")
     * @param temperature Generation temperature
     * @param enableGoogleSearch Whether to enable grounding
     * @return Pair of (response text, grounding sources)
     */
    fun generateFromAudio(
        systemPrompt: String,
        textHistory: List<ChatMessage>,
        audioSegments: List<ByteArray>,
        modelName: String,
        temperature: Double = 0.7,
        enableGoogleSearch: Boolean = true
    ): Pair<String, List<Pair<String, String?>>> {
        val apiKey = apiKeyProvider().trim()
        require(apiKey.isNotEmpty()) { "Gemini API key missing" }
        require(audioSegments.isNotEmpty()) { "No audio segments provided" }

        val effModel = GeminiModels.effective(modelName)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$effModel:generateContent?key=$apiKey"

        Log.i(TAG, "Generating from audio: model=$effModel, segments=${audioSegments.size}, historySize=${textHistory.size}")

        val enforcedPrompt = if (enableGoogleSearch) {
            systemPrompt + "\n\nAlways use Google Search to ground your answer and include citations with source titles when available."
        } else systemPrompt

        val contents = JSONArray()

        // 1. System prompt as first user message
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", enforcedPrompt)))
        })

        // 2. Text history for previous turns
        for (msg in textHistory) {
            val role = if (msg.role == "assistant") "model" else "user"
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
            })
        }

        // 3. Current user turn with audio
        val currentTurnParts = JSONArray()

        // Add each audio segment
        audioSegments.forEachIndexed { index, audioBytes ->
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            Log.d(TAG, "Adding audio segment $index: ${audioBytes.size} bytes")

            currentTurnParts.put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", "audio/wav")
                    put("data", base64Audio)
                })
            })
        }

        // Add instruction text for the audio
        currentTurnParts.put(JSONObject().apply {
            put("text", "Listen carefully to the audio above. The user is speaking to you. Understand what they are saying and respond appropriately according to your system instructions.")
        })


        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", currentTurnParts)
        })

        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().put("temperature", temperature))
            if (enableGoogleSearch) {
                val tools = JSONArray().apply { put(JSONObject().put("googleSearch", JSONObject())) }
                put("tools", tools)
            }
        }

        Log.d(TAG, "Request body size: ${body.toString().length} chars")

        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "API error: ${resp.code} - ${payload.take(500)}")
                error("Gemini error: HTTP ${resp.code} ${resp.message}\n${payload.take(400)}")
            }

            val text = extractText(payload)
            val sources = extractGroundingSources(payload)

            Log.i(TAG, "Response received: ${text.length} chars, ${sources.size} sources")
            return text to sources
        }
    }

    /**
     * Transcribe audio to text (for background refinement).
     */
    fun transcribeAudio(
        audioSegments: List<ByteArray>,
        modelName: String = "gemini-2.5-pro"
    ): String {
        val apiKey = apiKeyProvider().trim()
        require(apiKey.isNotEmpty()) { "Gemini API key missing" }
        require(audioSegments.isNotEmpty()) { "No audio segments provided" }

        val effModel = GeminiModels.effective(modelName)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$effModel:generateContent?key=$apiKey"

        Log.i(TAG, "Transcribing audio: model=$effModel, segments=${audioSegments.size}")

        val parts = JSONArray()

        // Add audio segments
        audioSegments.forEach { audioBytes ->
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            parts.put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", "audio/wav")
                    put("data", base64Audio)
                })
            })
        }

        // Add transcription instruction
        parts.put(JSONObject().apply {
            put("text", "Transcribe this audio exactly as spoken. Return only the transcription, nothing else. If you cannot understand the audio, return an empty string.")
        })

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }))
            put("generationConfig", JSONObject().put("temperature", 0.0))
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Transcription API error: ${resp.code}")
                return ""
            }

            val text = extractText(payload)
            Log.i(TAG, "Transcription result: ${text.length} chars")
            return text.trim()
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
            Log.e(TAG, "Error extracting grounding sources", e)
            emptyList()
        }
    }
}