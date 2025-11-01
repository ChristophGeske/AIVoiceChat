package com.example.advancedvoice.data.common

import android.util.Log
import org.json.JSONObject

object GroundingExtractor {
    private const val TAG = "GroundingExtractor"

    /**
     * Extracts grounding sources from Gemini API response.
     * Returns list of (url, title?) pairs.
     */
    fun extractGeminiGrounding(payload: String): List<Pair<String, String?>> {
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

    /**
     * Extracts citations from OpenAI GPT-5 Responses API.
     * Returns list of (url, title?) pairs.
     */
    fun extractOpenAiCitations(root: JSONObject): List<Pair<String, String?>> {
        return try {
            // Check for web_search_call.action.sources
            val actions = root.optJSONArray("actions") ?: return emptyList()
            val sources = mutableListOf<Pair<String, String?>>()

            for (i in 0 until actions.length()) {
                val action = actions.optJSONObject(i)
                val type = action?.optString("type")
                if (type == "web_search_call") {
                    val sourcesArr = action.optJSONObject("action")
                        ?.optJSONArray("sources")
                        ?: continue

                    for (j in 0 until sourcesArr.length()) {
                        val source = sourcesArr.optJSONObject(j)
                        val url = source?.optString("url")
                        val title = source?.optString("title")
                        if (!url.isNullOrBlank()) {
                            sources.add(url to title?.takeIf { it.isNotBlank() })
                        }
                    }
                }
            }
            sources
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting OpenAI citations", e)
            emptyList()
        }
    }
}