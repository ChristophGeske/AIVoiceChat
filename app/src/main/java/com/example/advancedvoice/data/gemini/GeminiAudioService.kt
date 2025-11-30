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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Service for sending audio directly to Gemini models.
 * Supports multimodal input with audio + text history.
 *
 * IMPORTANT:
 * - Multiple audio segments are merged into ONE WAV file
 *   before sending, to ensure the model hears the full message
 *   as a single continuous clip.
 */
class GeminiAudioService(
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient = HttpClientProvider.client
) {
    companion object {
        private const val TAG = "GeminiAudioService"

        // Assuming our audio segments are 16kHz mono 16-bit WAVs
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_SIZE = 44
    }

    /**
     * Generate a response from audio input + text history.
     *
     * @param systemPrompt System instructions
     * @param textHistory Previous conversation turns (as text)
     * @param audioSegments Current turn audio segments (each a WAV byte array)
     * @param modelName Model to use (e.g., "gemini-2.5-pro" or "gemini-2.5-flash")
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

        // 1. Merge all segments into one WAV
        val mergedWav = mergeWavSegments(audioSegments)

        // 2. Build contents
        val contents = JSONArray()

        // 2a. System as first "user" content
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", enforcedPrompt)))
        })

        // 2b. Text history
        for (msg in textHistory) {
            val role = if (msg.role == "assistant") "model" else "user"
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
            })
        }

        // 2c. Current user turn with merged audio
        val currentTurnParts = JSONArray()

        val base64Audio = Base64.encodeToString(mergedWav, Base64.NO_WRAP)
        currentTurnParts.put(JSONObject().apply {
            put("inlineData", JSONObject().apply {
                put("mimeType", "audio/wav")
                put("data", base64Audio)
            })
        })

        currentTurnParts.put(JSONObject().apply {
            put("text", "Listen carefully to the audio above and respond to what the user is saying.")
        })

        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", currentTurnParts)
        })

        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
            })
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
     * ALL segments are merged into a single WAV first.
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

        // Merge all segments into a single WAV
        val mergedWav = mergeWavSegments(audioSegments)
        val base64Audio = Base64.encodeToString(mergedWav, Base64.NO_WRAP)

        val parts = JSONArray()

        // Single merged audio
        parts.put(JSONObject().apply {
            put("inlineData", JSONObject().apply {
                put("mimeType", "audio/wav")
                put("data", base64Audio)
            })
        })

        // Exact transcription instruction
        parts.put(JSONObject().apply {
            put("text", """
                Transcribe this audio exactly as spoken. 
                Include filler words like "uh", "um", and repetitions.
                Return only the transcription, nothing else.
                If you cannot understand the audio, return an empty string.
            """.trimIndent())
        })

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)
            })
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Transcription API error: ${resp.code} - ${payload.take(300)}")
                return ""
            }

            val text = extractText(payload)
            Log.i(TAG, "Transcription result: ${text.length} chars")
            return text.trim()
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // WAV MERGING HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Merge multiple WAV segments into a single WAV file.
     * Assumes all segments share the same format (16kHz mono 16-bit).
     */
    private fun mergeWavSegments(segments: List<ByteArray>): ByteArray {
        if (segments.isEmpty()) return ByteArray(0)

        val pcmChunks = ArrayList<ByteArray>()
        var totalPcmSize = 0

        for ((index, wav) in segments.withIndex()) {
            if (wav.size <= WAV_HEADER_SIZE) {
                Log.w(TAG, "Segment $index too small to be WAV, skipping")
                continue
            }
            val pcm = wav.copyOfRange(WAV_HEADER_SIZE, wav.size)
            pcmChunks.add(pcm)
            totalPcmSize += pcm.size
            Log.d(TAG, "Segment $index: wavSize=${wav.size}, pcmSize=${pcm.size}")
        }

        if (pcmChunks.isEmpty()) {
            Log.w(TAG, "No valid WAV segments, returning first raw segment")
            return segments.first()
        }

        val header = createWavHeader(totalPcmSize)
        val output = ByteArray(header.size + totalPcmSize)

        System.arraycopy(header, 0, output, 0, header.size)

        var offset = header.size
        for (pcm in pcmChunks) {
            System.arraycopy(pcm, 0, output, offset, pcm.size)
            offset += pcm.size
        }

        Log.i(TAG, "Merged ${pcmChunks.size} segments into WAV: totalBytes=${output.size}")

        saveDebugAudio(output)

        Log.i(TAG, "Merged ${pcmChunks.size} segments into WAV: totalBytes=${output.size}")

        return output
    }

    private fun createWavHeader(dataSize: Int): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8

        val buffer = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)              // ChunkSize
        buffer.put("WAVE".toByteArray())
        // fmt subchunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)                         // Subchunk1Size for PCM
        buffer.putShort(1)                        // AudioFormat (PCM = 1)
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        // data subchunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)

        return buffer.array()
    }

    // ════════════════════════════════════════════════════════════════════
    // RESPONSE PARSING HELPERS
    // ════════════════════════════════════════════════════════════════════

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

    private fun saveDebugAudio(wavBytes: ByteArray) {
        try {
            // Save to app cache directory
            val file = java.io.File("/data/data/com.example.advancedvoice/cache/debug_input.wav")
            java.io.FileOutputStream(file).use { it.write(wavBytes) }
            Log.w(TAG, "🔊 DEBUG AUDIO SAVED TO: ${file.absolutePath} (${wavBytes.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug audio: ${e.message}")
        }
    }


}