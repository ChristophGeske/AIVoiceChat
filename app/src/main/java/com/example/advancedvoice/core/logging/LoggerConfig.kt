package com.example.advancedvoice.core.logging

object LoggerConfig {
    // Live WS logging
    // FIX: Set both to false to eliminate noisy logs
    const val LIVE_WS_VERBOSE = false         // if true, logs every frame and full JSON
    const val LIVE_FRAME_SAMPLING = 0         // log every Nth audio frame; 0 = never

    // Tags (match your filters)
    const val TAG_LIVE = "GeminiLiveService"
    const val TAG_FRAGMENT = "FirstFragment"
    const val TAG_VM = "ConversationVM"
    const val TAG_ENGINE = "SentenceTurnEngine"
}