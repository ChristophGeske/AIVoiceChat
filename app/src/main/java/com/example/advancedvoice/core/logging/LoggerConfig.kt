package com.example.advancedvoice.core.logging

object LoggerConfig {
    // Live WS logging
    // FIX: Set both to false to eliminate noisy logs
    const val LIVE_WS_VERBOSE = false
    const val LIVE_FRAME_SAMPLING = 0         // 0 means never log audio frames

    // Tags (match your filters)
    const val TAG_LIVE = "GeminiLiveService"
    const val TAG_FRAGMENT = "FirstFragment"
    const val TAG_VM = "ConversationVM"
    const val TAG_ENGINE = "SentenceTurnEngine"
}