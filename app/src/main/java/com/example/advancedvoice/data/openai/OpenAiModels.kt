package com.example.advancedvoice.data.openai

object OpenAiModels {
    const val GPT5 = "gpt-5"
    const val GPT5_MINI = "gpt-5-mini"

    fun isResponsesModel(model: String): Boolean =
        model.startsWith("gpt-5", ignoreCase = true)
}
