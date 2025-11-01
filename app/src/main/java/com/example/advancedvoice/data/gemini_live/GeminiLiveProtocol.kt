package com.example.advancedvoice.data.gemini_live

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builders for Gemini Live WS protocol messages.
 */
object GeminiLiveProtocol {

    // FIX: Keep AUDIO mode (required), but minimize response generation
    fun setup(
        model: String,
        responseModalities: List<String> = listOf("AUDIO")  // MUST stay AUDIO for native-audio model
    ): JSONObject {
        return JSONObject().put("setup", JSONObject().apply {
            put("model", model)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply {
                    responseModalities.forEach { put(it) }
                })
                // FIX: Minimize response length to reduce token usage
                put("maxOutputTokens", 50)  // Much smaller than default (probably 2048+)
                put("temperature", 0.1)     // Lower creativity = shorter responses
            })

            // Keep input transcription - this is what you actually use
            put("inputAudioTranscription", JSONObject())

            // FIX: Remove output audio transcription - you never use those "I'm sorry..." logs
            // put("outputAudioTranscription", JSONObject())  // REMOVED - saves processing

            // FIX: System instruction to minimize responses
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", "Respond with only 'Acknowledged' to every user message."))
                })
            })
        })
    }

    fun audioPcm16LeFrame(bytesBase64: String, sampleRateHz: Int = 16000): JSONObject {
        return JSONObject().put("realtimeInput", JSONObject().apply {
            put("audio", JSONObject().apply {
                put("mimeType", "audio/pcm;rate=$sampleRateHz")
                put("data", bytesBase64)
            })
        })
    }

    fun audioStreamEnd(): JSONObject {
        return JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true))
    }
}