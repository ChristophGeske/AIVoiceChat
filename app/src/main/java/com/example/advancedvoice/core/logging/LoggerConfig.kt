package com.example.advancedvoice.core.logging

object LoggerConfig {
    // Live WS logging
    const val LIVE_WS_VERBOSE = false         // if true, logs every frame and full JSON (don’t enable on prod)
    const val LIVE_JSON_PREVIEW = 160         // preview length for JSON logs
    const val LIVE_FRAME_SAMPLING = 50        // log every Nth audio frame; 0 = never

    // Tags (match your filters)
    const val TAG_LIVE = "GeminiLiveService"
    const val TAG_FRAGMENT = "FirstFragment"
    const val TAG_VM = "ConversationVM"
    const val TAG_ENGINE = "SentenceTurnEngine"
}
