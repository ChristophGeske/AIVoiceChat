package com.example.advancedvoice

import android.util.Log

/**
Splits LLM responses into speakable sentences.
- Minimum 20 characters per sentence (prevents splitting lists into tiny fragments)
- Handles ., !, ? terminators
 */
object SentenceSplitter {
    private const val TAG = "SentenceSplitter"
    private const val MIN_SENTENCE_LENGTH = 20

    /**
    Splits text into sentences with minimum length constraint.
     */
    fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) {
            Log.d(TAG, "splitIntoSentences: empty input")
            return emptyList()
        }

        val normalized = text.trim().replace(Regex("\\s+"), " ")
        Log.d(TAG, "splitIntoSentences: input length=${normalized.length}, preview='${normalized.take(80)}...'")

        val sentences = mutableListOf<String>()
        val buffer = StringBuilder()

        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            buffer.append(c)

            if (c == '.' || c == '!' || c == '?') {
                val next = normalized.getOrNull(i + 1)
                val isEndOfSentence = when {
                    next == null -> true
                    next.isWhitespace() -> {
                        val afterSpace = normalized.getOrNull(i + 2)
                        afterSpace == null || afterSpace.isUpperCase() || afterSpace.isDigit()
                    }
                    else -> false
                }

                if (isEndOfSentence) {
                    val candidate = buffer.toString().trim()
                    if (candidate.length >= MIN_SENTENCE_LENGTH || sentences.isNotEmpty()) {
                        sentences.add(candidate)
                        Log.d(TAG, " -> sentence #${sentences.size}: '${candidate.take(60)}...'")
                        buffer.clear()
                    }
                }
            }
            i++
        }

        val remaining = buffer.toString().trim()
        if (remaining.isNotEmpty()) {
            if (sentences.isEmpty() || remaining.length >= MIN_SENTENCE_LENGTH) {
                sentences.add(remaining)
                Log.d(TAG, " -> added trailing: '${remaining.take(60)}...'")
            } else {
                // merge short tail with previous sentence if present
                val lastIdx = sentences.lastIndex
                if (lastIdx >= 0) {
                    val merged = sentences[lastIdx] + " " + remaining
                    sentences[lastIdx] = merged
                    Log.d(TAG, " -> merged short trailing with previous (len=${merged.length})")
                } else {
                    sentences.add(remaining)
                }
            }
        }

        Log.d(TAG, "splitIntoSentences: completed, total=${sentences.size}")
        return sentences
    }

    /**
    Extracts first sentence from text.
    Returns Pair(firstSentence, remainingText).
     */
    fun extractFirstSentence(text: String): Pair<String, String> {
        if (text.isBlank()) {
            Log.d(TAG, "extractFirstSentence: empty input")
            return "" to ""
        }

        val normalized = text.trim().replace(Regex("\\s+"), " ")
        Log.d(TAG, "extractFirstSentence: input length=${normalized.length}")

        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            if (c == '.' || c == '!' || c == '?') {
                val next = normalized.getOrNull(i + 1)
                val isEndOfSentence = when {
                    next == null -> true
                    next.isWhitespace() -> {
                        val afterSpace = normalized.getOrNull(i + 2)
                        afterSpace == null || afterSpace.isUpperCase() || afterSpace.isDigit()
                    }
                    else -> false
                }
                if (isEndOfSentence && i + 1 >= MIN_SENTENCE_LENGTH) {
                    val first = normalized.substring(0, i + 1).trim()
                    val rest = normalized.substring(i + 1).trim()
                    return first to rest
                }
            }
            i++
        }

        // No terminator found
        return normalized to ""
    }
}