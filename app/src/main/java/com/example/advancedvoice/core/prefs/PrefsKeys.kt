package com.example.advancedvoice.core.prefs

object PrefsKeys {
    const val FILE = "ai_prefs"

    // API keys
    const val GEMINI_KEY = "gemini_key"
    const val OPENAI_KEY = "openai_key"

    // Models
    const val SELECTED_MODEL = "selected_model"
    const val GPT5_EFFORT = "gpt5_effort" // minimal|low|medium|high

    // STT
    const val STT_SYSTEM = "stt_system" // "STANDARD" | "GEMINI_LIVE"

    // Engine config
    const val FASTER_FIRST = "faster_first"            // Boolean
    const val MAX_SENTENCES = "max_sentences"          // Int
    const val LISTEN_SECONDS = "listen_seconds"        // Int

    // Prompts
    const val SYSTEM_PROMPT = "system_prompt"
    const val SYSTEM_PROMPT_EXT = "system_prompt_extensions"

    // Auto-listen
    const val AUTO_LISTEN = "auto_listen"              // Boolean
}