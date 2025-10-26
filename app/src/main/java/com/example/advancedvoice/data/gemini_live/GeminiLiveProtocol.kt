package com.example.advancedvoice.data.gemini_live

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builders for Gemini Live WS protocol messages.
 */
object GeminiLiveProtocol {

    // IMPORTANT: Use AUDIO for native-audio live model sessions
    fun setup(
        model: String,
        responseModalities: List<String> = listOf("AUDIO")
    ): JSONObject {
        return JSONObject().put("setup", JSONObject().apply {
            put("model", model)
            put("generationConfig", JSONObject().put("responseModalities", JSONArray().apply {
                responseModalities.forEach { put(it) }
            }))
            // Enable server to transcribe inbound audio and (optionally) transcribe its own output audio
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
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
