package com.example.advancedvoice.core.logging

object LoggerConfig {
    const val TAG_VM = "ConversationVM"
    const val TAG_LIVE = "GeminiLive"

    // ✅ Control verbosity
    const val LIVE_WS_VERBOSE = false  // Set to false to hide WS messages
    const val TRANSCRIBER_VERBOSE = false  // Set to false to reduce transcription logs
}