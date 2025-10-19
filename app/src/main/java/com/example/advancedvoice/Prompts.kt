package com.example.advancedvoice

import android.content.SharedPreferences

object Prompts {

    private const val DEFAULT_PHASE1 = """You are the first, fast stage in a two-stage generation process.
Your ONLY task is to provide a single, complete, and concise introductory sentence that directly answers the user's core question. This sentence will be spoken to the user immediately.
After you provide this sentence, a second, more powerful model will be called to provide a full, detailed answer.
Do not use conversational filler (e.g., "Certainly,"). Start directly with the information.
Your response MUST be a single, complete sentence and it should be short and precise even if it would require a longer response make it short and precise."""

    private const val DEFAULT_PHASE2_TEMPLATE = """=== CONTEXT ===
The user received this opening sentence: "{FIRST_SENTENCE}"

=== YOUR TASK ===
Continue the response with {REMAINING_COUNT} MORE sentence{PLURAL}.

=== STRICT RULES ===
1. Do NOT repeat or rephrase the opening sentence shown above
2. Do NOT start with "The latest...", "AI is...", or any phrase from the opening
3. Start IMMEDIATELY with NEW information
4. If the opening sentence already fully answers the question, respond ONLY with: "."

=== Example ===
Opening: "Paris is the capital of France."
Your response: "The city has a population of 2.1 million people. It is famous for the Eiffel Tower."
WRONG: "Paris, the capital of France, has a population of..."

Begin your continuation now:"""

    fun getFastFirstPhase1Prompt(prefs: SharedPreferences?): String {
        return prefs?.getString("phase1_prompt", null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PHASE1
    }

    fun getFastFirstPhase2Prompt(
        firstSentence: String,
        maxSentences: Int,
        prefs: SharedPreferences?
    ): String {
        val remainingSentenceCount = (maxSentences - 1).coerceAtLeast(1)
        val plural = if (remainingSentenceCount != 1) "s" else ""

        val template = prefs?.getString("phase2_prompt", null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PHASE2_TEMPLATE

        return template
            .replace("{FIRST_SENTENCE}", firstSentence)
            .replace("{REMAINING_COUNT}", remainingSentenceCount.toString())
            .replace("{PLURAL}", plural)
    }

    fun getDefaultPhase1Prompt(): String = DEFAULT_PHASE1
    fun getDefaultPhase2Prompt(): String = DEFAULT_PHASE2_TEMPLATE

    fun getEffectiveSystemPrompt(basePrompt: String, maxSentences: Int, useFastFirst: Boolean): String {
        val extras = buildList {
            add("Return plain text only.")
            add("IMPORTANT: Your total response MUST be AT MOST $maxSentences sentences long, unless the user's query explicitly asks for a longer response (e.g., 'in detail', 'explain thoroughly').")
            if (useFastFirst) {
                add("You are part of a system that generates a first sentence quickly. Begin your response directly and promptly.")
            }
        }.joinToString(" ")

        return if (extras.isBlank()) basePrompt else "$basePrompt\n\n$extras"
    }
}