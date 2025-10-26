package com.example.advancedvoice.data.openai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Extracts plain text and web sources from OpenAI Responses payloads.
 */
object OpenAiResponseParser {

    data class Parsed(val text: String, val sources: List<Pair<String, String?>>)

    fun parseResponsesPayload(payload: String): Parsed {
        val root = JSONObject(payload)

        // Prefer direct field if present
        val direct = root.optString("output_text", null)
        if (!direct.isNullOrBlank()) {
            val sources = extractSourcesFromIncludes(root)
            return Parsed(direct.trim(), sources)
        }

        // Fallback: traverse "output"
        val output = root.optJSONArray("output") ?: JSONArray()
        var text = ""
        val sources = ArrayList<Pair<String, String?>>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") == "message") {
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    text += part.optString("text", "")
                    // annotations may contain url_citation
                    val annotations = part.optJSONArray("annotations") ?: continue
                    for (k in 0 until annotations.length()) {
                        val ann = annotations.optJSONObject(k) ?: continue
                        if (ann.optString("type") == "url_citation") {
                            val url = ann.optString("url").orEmpty()
                            val title = ann.optString("title").takeIf { it.isNotBlank() }
                            if (url.isNotBlank()) sources.add(url to title)
                        }
                    }
                }
            }
        }

        // Merge with "include" sources if provided
        val incSources = extractSourcesFromIncludes(root)
        if (incSources.isNotEmpty()) {
            // Deduplicate by URL
            val seen = HashSet<String>()
            (sources + incSources).forEach {
                if (seen.add(it.first)) sources.add(it)
            }
        }
        return Parsed(text.trim(), sources)
    }

    private fun extractSourcesFromIncludes(root: JSONObject): List<Pair<String, String?>> {
        val includes = root.optJSONObject("output")?.optJSONArray("included") // older drafts
        // Official field: "web_search_call.action.sources" is delivered via include list requested
        val result = ArrayList<Pair<String, String?>>()

        // Newer Responses include shape
        val inc = root.optJSONArray("includes") ?: return result
        for (i in 0 until inc.length()) {
            val item = inc.optJSONObject(i) ?: continue
            val type = item.optString("type")
            if (type == "web_search_call.action.sources") {
                val arr = item.optJSONArray("sources") ?: continue
                for (j in 0 until arr.length()) {
                    val s = arr.optJSONObject(j) ?: continue
                    val url = s.optString("url").orEmpty()
                    val title = s.optString("title").takeIf { it.isNotBlank() }
                    if (url.isNotBlank()) result.add(url to title)
                }
            }
        }
        return result
    }
}
