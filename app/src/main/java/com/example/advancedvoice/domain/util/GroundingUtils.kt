package com.example.advancedvoice.domain.util

import com.example.advancedvoice.domain.engine.SentenceTurnEngine

/**
 * A utility for handling and formatting grounding sources from the Gemini API.
 */
object GroundingUtils {

    /**
     * Formats a list of source pairs (URI to Title) into a compact HTML string
     * and sends it to the onSystem callback if any sources are present.
     *
     * @param sources A list of pairs, where each pair is a URI and an optional title.
     * @param onSystem The callback to send the formatted HTML string to.
     */
    fun processAndDisplaySources(sources: List<Pair<String, String?>>, onSystem: (String) -> Unit) {
        if (sources.isEmpty()) {
            return
        }

        val html = buildString {
            append("<small><small><b>Web Sources:</b> ")
            val links = sources.distinctBy { it.first }.mapIndexedNotNull { index, (uri, title) ->
                // Use title if available, otherwise format the URI to be more readable
                val linkText = title?.trim() ?: formatUriForDisplay(uri)
                // Ensure linkText is not empty
                if (linkText.isBlank()) null else {
                    " <a href=\"$uri\">${index + 1}. $linkText</a>"
                }
            }
            append(links.joinToString(" "))
            append("</small></small>")
        }

        if (html.isNotEmpty()) {
            onSystem(html)
        }
    }

    /**
     * Formats a URI into a more human-readable string for display.
     * Example: "https://www.example.com/some/path" -> "example.com"
     */
    private fun formatUriForDisplay(uri: String): String {
        return try {
            val host = java.net.URI(uri).host ?: uri
            host.removePrefix("www.")
        } catch (e: Exception) {
            // Fallback for malformed URIs
            uri.take(40)
        }
    }
}