package com.example.advancedvoice.data.gemini

object GeminiModels {
    const val PRO = "gemini-2.5-pro"
    const val FLASH = "gemini-2.5-flash"

    fun effective(name: String): String = when (name.lowercase()) {
        "gemini-pro-latest", "gemini-2.5-pro-latest" -> PRO
        "gemini-flash-latest", "gemini-2.5-flash-latest" -> FLASH
        else -> name
    }
}
