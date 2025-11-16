package com.example.advancedvoice.data.openai

object OpenAiModels {
    const val GPT5_1 = "gpt-5.1"
    const val GPT5_MINI = "gpt-5-mini"

    fun isResponsesModel(model: String): Boolean =
        model.startsWith("gpt-5", ignoreCase = true)
}
