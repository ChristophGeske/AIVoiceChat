package com.example.advancedvoice.domain.util

import java.net.URI

/**
 * Collects and deduplicates web sources from multiple API calls.
 * Formats them as compact HTML links for display.
 */
class CombinedSources {
    private data class Entry(
        val url: String,
        val title: String?,
        val host: String,
        val brand: String,
        val isVertex: Boolean
    )

    private val byBrand = LinkedHashMap<String, Entry>()

    fun addAll(items: List<Pair<String, String?>>) {
        for ((url, title) in items) {
            val host = hostFromUrl(url)
            val labelHost = if (isVertexRedirectHost(host) && title != null && isDomainLike(title)) {
                title.lowercase()
            } else if (title != null && isDomainLike(title)) {
                title.lowercase()
            } else {
                host.lowercase()
            }
            val brand = baseBrandFromHost(labelHost)
            if (brand.isBlank()) continue

            val entry = Entry(
                url = url,
                title = title,
                host = host,
                brand = brand,
                isVertex = isVertexRedirectHost(host)
            )

            val existing = byBrand[brand]
            if (existing == null) {
                byBrand[brand] = entry
            } else {
                // Prefer non-vertex URLs if we already have one for this brand
                byBrand[brand] = if (existing.isVertex && !entry.isVertex) entry else existing
            }
        }
    }

    fun toHtmlCompact(maxItems: Int = 5): String? {
        if (byBrand.isEmpty()) return null
        val anchors = byBrand.values.take(maxItems).map { e ->
            val label = prettyLabel(e.brand)
            val safeLabel = escapeHtml(label)
            val safeHref = escapeHtml(e.url)
            "<a href=\"$safeHref\">$safeLabel</a>"
        }
        return "<small><small>Web Sources: ${anchors.joinToString(", ")}</small></small>"
    }

    private fun hostFromUrl(u: String): String {
        return try {
            URI(u).host?.removePrefix("www.") ?: u
        } catch (_: Exception) {
            u
        }
    }

    private fun isVertexRedirectHost(host: String): Boolean =
        host.lowercase().let { it.contains("vertex") && it.contains("google") }

    private fun isDomainLike(text: String): Boolean =
        Regex("^[a-z0-9.-]+\\.[a-z]{2,}$").matches(text.lowercase().trim())

    private fun baseBrandFromHost(host: String): String {
        val h = host.lowercase()
        if (h.endsWith(".gov")) return "usgovernment"
        var trimmed = h
        listOf("www.", "m.", "en.").forEach { p ->
            if (trimmed.startsWith(p)) trimmed = trimmed.removePrefix(p)
        }
        return trimmed.split('.').firstOrNull()?.takeIf { it.isNotBlank() } ?: trimmed
    }

    private fun prettyLabel(brand: String): String {
        return when (brand.lowercase()) {
            "usgovernment" -> "USGovernment"
            else -> brand.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}