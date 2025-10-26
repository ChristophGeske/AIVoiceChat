package com.example.advancedvoice.domain.usecase

import com.example.advancedvoice.domain.engine.SentenceTurnEngine

class ClearConversationUseCase(
    private val engine: SentenceTurnEngine
) {
    operator fun invoke() {
        engine.clearHistory()
    }
}
