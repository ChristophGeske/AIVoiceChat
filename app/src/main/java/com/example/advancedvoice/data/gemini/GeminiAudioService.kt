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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GeminiAudioService(
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient = HttpClientProvider.client
) {
    companion object {
        private const val TAG = "GeminiAudioService"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_SIZE = 44

        // Flash model for fast transcription - always used for Path A
        private const val FLASH_TRANSCRIPTION_MODEL = "gemini-2.0-flash-lite"
    }

    /**
     * Generate response from audio using the specified model.
     * This is PATH B - Response Generation.
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

        Log.i(TAG, "[PATH-B] Generating from audio: model=$effModel, segments=${audioSegments.size}, historySize=${textHistory.size}")

        val enforcedPrompt = if (enableGoogleSearch) {
            systemPrompt + "\n\nAlways use Google Search to ground your answer and include citations with source titles when available."
        } else systemPrompt

        val mergedWav = mergeWavSegments(audioSegments)

        val contents = JSONArray()

        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", enforcedPrompt)))
        })

        for (msg in textHistory) {
            val role = if (msg.role == "assistant") "model" else "user"
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
            })
        }

        val currentTurnParts = JSONArray()
        val base64Audio = Base64.encodeToString(mergedWav, Base64.NO_WRAP)

        currentTurnParts.put(JSONObject().apply {
            put("inlineData", JSONObject().apply {
                put("mimeType", "audio/wav")
                put("data", base64Audio)
            })
        })

        currentTurnParts.put(JSONObject().apply {
            put("text", "Listen carefully to the audio above. The user is speaking to you. Understand what they are saying and respond appropriately according to your system instructions.")
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
                Log.e(TAG, "[PATH-B] API error: ${resp.code} - ${payload.take(500)}")
                error("Gemini error: HTTP ${resp.code} ${resp.message}\n${payload.take(400)}")
            }

            val text = extractText(payload)
            val sources = extractGroundingSources(payload)

            Log.i(TAG, "[PATH-B] Response received: ${text.length} chars, ${sources.size} sources")
            return text to sources
        }
    }

    /**
     * Fast transcription using Flash model.
     * This is PATH A - Always uses Flash for speed, regardless of user's model choice.
     */
    fun transcribeAudioFast(audioSegments: List<ByteArray>): String {
        Log.i(TAG, "[PATH-A] 🚀 Starting FAST transcription with $FLASH_TRANSCRIPTION_MODEL")
        return transcribeAudioInternal(audioSegments, FLASH_TRANSCRIPTION_MODEL)
    }

    /**
     * Transcribe audio with a specific model (for backward compatibility).
     */
    fun transcribeAudio(
        audioSegments: List<ByteArray>,
        modelName: String = FLASH_TRANSCRIPTION_MODEL
    ): String {
        return transcribeAudioInternal(audioSegments, modelName)
    }

    private fun transcribeAudioInternal(
        audioSegments: List<ByteArray>,
        modelName: String
    ): String {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[PATH-A] transcribeAudio: Starting for ${audioSegments.size} segments with model=$modelName")

        val apiKey = apiKeyProvider().trim()
        require(apiKey.isNotEmpty()) { "Gemini API key missing" }
        require(audioSegments.isNotEmpty()) { "No audio segments provided" }

        val effModel = GeminiModels.effective(modelName)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$effModel:generateContent?key=$apiKey"

        Log.d(TAG, "[PATH-A] Merging WAVs...")
        val mergedWav = mergeWavSegments(audioSegments)
        val base64Audio = Base64.encodeToString(mergedWav, Base64.NO_WRAP)

        Log.d(TAG, "[PATH-A] Building request...")
        val parts = JSONArray()
        parts.put(JSONObject().apply {
            put("inlineData", JSONObject().apply {
                put("mimeType", "audio/wav")
                put("data", base64Audio)
            })
        })

        parts.put(JSONObject().apply {
            put("text", """
                Listen to this audio and provide a clean, accurate transcription.
                
                Instructions:
                - Transcribe exactly what the user said
                - Remove filler words like "uh", "um", "like", "you know"
                - Remove false starts and repetitions
                - Fix obvious grammar issues
                - Keep the original meaning and intent intact
                - Return only the cleaned transcription, nothing else
                - If you cannot understand the audio, return an empty string
            """.trimIndent())
        })

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)  // Deterministic for transcription
            })
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        Log.d(TAG, "[PATH-A] Executing network request...")
        client.newCall(req).execute().use { resp ->
            val elapsedMs = System.currentTimeMillis() - startTime
            Log.d(TAG, "[PATH-A] Got response code ${resp.code} in ${elapsedMs}ms")

            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "[PATH-A] Transcription API error: ${resp.code} - ${payload.take(300)}")
                return ""
            }

            val text = extractText(payload)
            Log.i(TAG, "[PATH-A] ✅ Transcription complete: ${text.length} chars in ${elapsedMs}ms")
            return text.trim()
        }
    }

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

        // Debug audio saving (can be removed in production)
        saveDebugAudio(output)

        return output
    }

    private fun saveDebugAudio(wavBytes: ByteArray) {
        try {
            val file = File("/data/data/com.example.advancedvoice/cache/debug_input.wav")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(wavBytes) }
            Log.d(TAG, "🔊 DEBUG AUDIO SAVED: ${file.absolutePath} (${wavBytes.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug audio: ${e.message}")
        }
    }

    private fun createWavHeader(dataSize: Int): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8

        val buffer = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)

        return buffer.array()
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