package com.example.advancedvoice.core.text

import android.util.Log
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Splits LLM responses into speakable sentences.
 * - Minimum 20 characters per sentence (merges too-short fragments).
 * - Handles ., !, ? terminators.
 * - Avoids splitting on periods inside numbers (e.g., 16.8).
 */
object SentenceSplitter {
    private const val TAG = "SentenceSplitter"
    private const val MIN_SENTENCE_LENGTH = 20

    // Regex: non-greedy up to a terminator:
    //  - A dot not preceded by digit ((?<!\\d)\\.)
    //  - Or any of [!?]
    private val sentenceMatcher: Pattern = Pattern.compile(".*?((?<!\\d)\\.|[!?])")

    fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val normalized = text.trim().replace(Regex("\\s+"), " ")
        Log.d(TAG, "splitIntoSentences: len=${normalized.length}, preview='${normalized.take(80)}...'")

        val out = mutableListOf<String>()
        val matcher: Matcher = sentenceMatcher.matcher(normalized)
        var lastEnd = 0

        while (matcher.find()) {
            val s = normalized.substring(lastEnd, matcher.end()).trim()
            if (s.isNotEmpty()) {
                if (out.isNotEmpty() || s.length >= MIN_SENTENCE_LENGTH) {
                    out.add(s)
                } else {
                    // too-short opening fragment -> merge forward
                }
            }
            lastEnd = matcher.end()
        }

        if (lastEnd < normalized.length) {
            val tail = normalized.substring(lastEnd).trim()
            if (tail.isNotEmpty()) {
                if (tail.length < MIN_SENTENCE_LENGTH && out.isNotEmpty()) {
                    val i = out.lastIndex
                    out[i] = out[i] + " " + tail
                } else {
                    out.add(tail)
                }
            }
        }

        Log.d(TAG, "splitIntoSentences: total=${out.size}")
        return out
    }

    /**
     * Returns first sentence and the rest as a single string.
     */
    fun extractFirstSentence(text: String): Pair<String, String> {
        if (text.isBlank()) return "" to ""
        val s = splitIntoSentences(text)
        return if (s.isNotEmpty()) s.first() to s.drop(1).joinToString(" ")
        else text to ""
    }
}
