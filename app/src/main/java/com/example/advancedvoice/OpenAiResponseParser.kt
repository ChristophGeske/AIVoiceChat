package com.example.advancedvoice

import org.json.JSONObject

/**
 * Handles parsing and cleaning of OpenAI API responses.
 * Extracts text and citations while removing inline URL noise.
 */
object OpenAiResponseParser {

    /**
     * Extracts clean text and citations from OpenAI Responses API payload.
     * Returns Pair of (cleanText, List of url-title pairs)
     */
    fun extractMessageAndCitations(root: JSONObject): Pair<String, List<Pair<String, String?>>> {
        val sources = ArrayList<Pair<String, String?>>()
        var rawText = ""

        val output = root.optJSONArray("output")
        if (output != null) {
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                if (item.optString("type") == "message") {
                    val content = item.optJSONArray("content") ?: continue
                    val first = content.optJSONObject(0) ?: continue
                    rawText = first.optString("text", rawText)

                    val annotations = first.optJSONArray("annotations")
                    if (annotations != null) {
                        for (j in 0 until annotations.length()) {
                            val a = annotations.optJSONObject(j) ?: continue
                            if (a.optString("type") == "url_citation") {
                                val url = a.optString("url").orEmpty()
                                val title = a.optString("title").takeIf { it.isNotBlank() }
                                if (url.isNotBlank()) sources.add(url to title)
                            }
                        }
                    }
                }
            }
        }

        val cleanText = cleanCitationsFromText(rawText)
        return cleanText to sources
    }

    /**
     * Removes all forms of citation markers and URLs from text.
     */
    private fun cleanCitationsFromText(text: String): String {
        var cleaned = text

        // Remove numbered citations like [1], [2]
        cleaned = cleaned.replace(Regex("""```math
\d+```"""), "")

        // Remove ANY bracketed content that contains URLs, "source", "http", etc.
        cleaned = cleaned.replace(Regex("""```math
[^```]*(?:https?|source|\.com|\.org|\.gov)[^```]*```""", RegexOption.IGNORE_CASE), "")

        // Remove markdown-style links: [text](url)
        cleaned = cleaned.replace(Regex("""```math
[^```]+```KATEX_INLINE_OPENhttps?://[^KATEX_INLINE_CLOSE]+KATEX_INLINE_CLOSE"""), "")

        // Remove URLs in parentheses: (https://...)
        cleaned = cleaned.replace(Regex("""KATEX_INLINE_OPEN\s*https?://[^\s)]+\s*KATEX_INLINE_CLOSE?"""), "")

        // Remove URLs in brackets: [https://...]
        cleaned = cleaned.replace(Regex("""```math
\s*https?://[^```]+\s*```"""), "")

        // Remove bare URLs
        cleaned = cleaned.replace(Regex("""https?://\S+"""), "")

        // Remove "Source:" or "Sources:" labels with following content
        cleaned = cleaned.replace(Regex("""\s*KATEX_INLINE_OPEN?Sources?:[\s\S]*?KATEX_INLINE_CLOSE?""", RegexOption.IGNORE_CASE), "")

        // Remove parentheses containing "source" or domain-like text
        cleaned = cleaned.replace(Regex("""KATEX_INLINE_OPEN[^)]*(?:source|\.com|\.org)[^)]*KATEX_INLINE_CLOSE""", RegexOption.IGNORE_CASE), "")

        // Remove orphaned parentheses and brackets
        cleaned = cleaned.replace(Regex("""KATEX_INLINE_OPEN\s*KATEX_INLINE_CLOSE"""), "")
        cleaned = cleaned.replace(Regex("""```math
\s*```"""), "")

        // Remove multiple consecutive spaces
        cleaned = cleaned.replace(Regex("""\s{2,}"""), " ")

        // Remove spaces before punctuation
        cleaned = cleaned.replace(Regex("""\s+([.,;!?])"""), "$1")

        // Fix spacing after punctuation if missing
        cleaned = cleaned.replace(Regex("""([.,;!?])([A-Z])"""), "$1 $2")

        return cleaned.trim()
    }
}