package com.example.advancedvoice.domain.usecase

import com.example.advancedvoice.domain.engine.SentenceTurnEngine

class ReplaceLastUserTurnUseCase(
    private val engine: SentenceTurnEngine
) {
    operator fun invoke(userText: String, modelName: String) {
        engine.replaceLastUserMessage(userText)
        engine.startTurnWithCurrentHistory(modelName)
    }
}
