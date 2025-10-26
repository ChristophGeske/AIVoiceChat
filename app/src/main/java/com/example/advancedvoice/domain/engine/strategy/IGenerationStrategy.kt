package com.example.advancedvoice.domain.strategy

import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import kotlinx.coroutines.CoroutineScope

interface IGenerationStrategy {
    fun execute(
        scope: CoroutineScope,
        history: List<SentenceTurnEngine.Msg>,
        modelName: String,
        systemPrompt: String,
        maxSentences: Int,
        callbacks: SentenceTurnEngine.Callbacks
    )
    fun abort()
}
