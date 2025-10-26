package com.example.advancedvoice.domain.usecase

import com.example.advancedvoice.domain.engine.SentenceTurnEngine

class StartTurnUseCase(
    private val engine: SentenceTurnEngine
) {
    operator fun invoke(userText: String, modelName: String) {
        engine.startTurn(userText, modelName)
    }
}
