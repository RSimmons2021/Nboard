package com.nboard.ime.ai

interface TextGenerationClient {
    val isConfigured: Boolean

    suspend fun generateText(
        prompt: String,
        systemInstruction: String? = null,
        outputCharLimit: Int = 0
    ): Result<String>

    suspend fun generateStreaming(
        prompt: String,
        systemInstruction: String? = null,
        outputCharLimit: Int = 0,
        onPartial: (String) -> Unit
    ): Result<String> = generateText(prompt, systemInstruction, outputCharLimit).also { result ->
        result.onSuccess(onPartial)
    }
}
