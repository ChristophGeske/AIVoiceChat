package com.example.advancedvoice

import android.util.Log
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Splits LLM responses into speakable sentences.
 * - [Restored] Minimum 20 characters per sentence (prevents splitting lists into tiny fragments).
 * - [Restored] Merges short trailing sentences with the previous one.
 * - Handles ., !, ? terminators robustly.
 */
object SentenceSplitter {
    private const val TAG = "SentenceSplitter"
    private const val MIN_SENTENCE_LENGTH = 20

    // This regex finds segments of text that end with a sentence terminator.
    // It's non-greedy to capture sentences one by one.
    private val sentenceMatcher: Pattern = Pattern.compile(".*?([.!?])")

    /**
     * Splits text into sentences with the minimum length constraint.
     */
    fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val normalized = text.trim().replace(Regex("\\s+"), " ")
        Log.d(TAG, "splitIntoSentences: input length=${normalized.length}, preview='${normalized.take(80)}...'")

        val sentences = mutableListOf<String>()
        val matcher: Matcher = sentenceMatcher.matcher(normalized)
        var lastEnd = 0

        while (matcher.find()) {
            val sentence = normalized.substring(lastEnd, matcher.end()).trim()
            if (sentence.isNotEmpty()) {
                // If the current list of sentences is not empty, or if the new sentence is long enough, add it.
                // This prevents the very first sentence from being discarded if it's too short.
                if (sentences.isNotEmpty() || sentence.length >= MIN_SENTENCE_LENGTH) {
                    sentences.add(sentence)
                    Log.d(TAG, " -> sentence #${sentences.size}: '${sentence.take(60)}...'")
                } else {
                    // If the first sentence is too short, we still hold onto it in 'lastEnd'
                    // so it can be merged with the next one.
                    Log.d(TAG, " -> short sentence ignored (will be prepended to next): '${sentence.take(60)}...'")
                }
            }
            lastEnd = matcher.end()
        }

        // Handle any remaining text after the last terminator
        if (lastEnd < normalized.length) {
            val remaining = normalized.substring(lastEnd).trim()
            if (remaining.isNotEmpty()) {
                // If the remainder is too short and there's a previous sentence, merge them.
                if (remaining.length < MIN_SENTENCE_LENGTH && sentences.isNotEmpty()) {
                    val lastIndex = sentences.lastIndex
                    sentences[lastIndex] = sentences[lastIndex] + " " + remaining
                    Log.d(TAG, " -> merged short trailing fragment with previous.")
                } else {
                    sentences.add(remaining)
                    Log.d(TAG, " -> added trailing fragment: '${remaining.take(60)}...'")
                }
            }
        }

        Log.d(TAG, "splitIntoSentences: completed, total=${sentences.size}")
        return sentences
    }

    /**
     * Extracts the first sentence from text.
     * Returns a Pair of (firstSentence, remainingText).
     */
    fun extractFirstSentence(text: String): Pair<String, String> {
        if (text.isBlank()) {
            return "" to ""
        }

        val sentences = splitIntoSentences(text)
        return if (sentences.isNotEmpty()) {
            val first = sentences.first()
            val rest = sentences.drop(1).joinToString(" ")
            first to rest
        } else {
            // If splitting results in no sentences (e.g., text with no terminators),
            // return the whole text as the first "sentence".
            text to ""
        }
    }
}