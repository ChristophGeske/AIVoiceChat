// In: com/example/advancedvoice/Prompts.kt

package com.example.advancedvoice

object Prompts {

    // KORREKTUR: Stark verbesserter Prompt, der dem Modell den Prozess erklärt.
    fun getFastFirstPhase1Prompt(): String {
        return """
            You are the first, fast stage in a two-stage generation process.
            Your ONLY task is to provide a single, complete, and concise introductory sentence that directly answers the user's core question. This sentence will be spoken to the user immediately.
            After you provide this sentence, a second, more powerful model will be called to provide a full, detailed answer.
            Do not use conversational filler (e.g., "Certainly,"). Start directly with the information.
            Your response MUST be a single, complete sentence and it should be short and precise even if it would require a longer response make it short and precise.
        """.trimIndent()
    }

    fun getFastFirstPhase2Prompt(firstSentence: String, maxSentences: Int): String {
        val remainingSentenceCount = (maxSentences - 1).coerceAtLeast(1)
        return """
            === CONTEXT ===
            The user received this opening sentence: "$firstSentence"
            
            === YOUR TASK ===
            Continue the response with $remainingSentenceCount MORE sentence${if(remainingSentenceCount != 1) "s" else ""}.
            
            === STRICT RULES ===
            1. Do NOT repeat or rephrase the opening sentence shown above
            2. Do NOT start with "The latest...", "AI is...", or any phrase from the opening
            3. Start IMMEDIATELY with NEW information
            4. If the opening sentence already fully answers the question, respond ONLY with: "."
            
            === Example ===
            Opening: "Paris is the capital of France."
            Your response: "The city has a population of 2.1 million people. It is famous for the Eiffel Tower."
            WRONG: "Paris, the capital of France, has a population of..."
            
            Begin your continuation now:
        """.trimIndent()
    }

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