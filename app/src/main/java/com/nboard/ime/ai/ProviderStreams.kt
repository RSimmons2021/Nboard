package com.nboard.ime.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

internal enum class StreamProvider { OPENAI, ANTHROPIC, GEMINI }

internal suspend fun streamProvider(http: OkHttpClient, provider: StreamProvider, url: String, key: String,
    model: String, prompt: String, instructions: String?, limit: Int, onPartial: (String) -> Unit): Result<String> {
    if (key.isBlank() || model.isBlank()) return Result.failure(IllegalStateException("Configure the selected AI provider"))
    val body = when (provider) {
        StreamProvider.GEMINI -> JSONObject().put("contents", JSONArray().put(JSONObject().put("parts",
            JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))
            .apply { if (!instructions.isNullOrBlank()) put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", instructions)))) }
        else -> JSONObject().put("model", model).put("stream", true).put("max_tokens", 1024)
            .put("messages", JSONArray().apply {
                if (provider == StreamProvider.OPENAI && !instructions.isNullOrBlank())
                    put(JSONObject().put("role", "system").put("content", instructions))
                put(JSONObject().put("role", "user").put("content", prompt))
            }).apply { if (provider == StreamProvider.ANTHROPIC && !instructions.isNullOrBlank()) put("system", instructions) }
    }
    val request = Request.Builder().url(url).header("Accept", "text/event-stream")
        .post(body.toString().toRequestBody("application/json".toMediaType())).apply {
            when (provider) {
                StreamProvider.OPENAI -> header("Authorization", "Bearer $key")
                StreamProvider.ANTHROPIC -> { header("x-api-key", key); header("anthropic-version", "2023-06-01") }
                StreamProvider.GEMINI -> header("x-goog-api-key", key)
            }
        }.build()
    return http.newCall(request).readCancellable { response ->
        check(response.isSuccessful) { "AI request failed (HTTP ${response.code})" }
        val reader = response.body?.charStream()?.buffered() ?: error("AI returned no response")
        reader.use { readProviderStream(it, provider, limit, onPartial) }
    }
}

internal fun readProviderStream(reader: BufferedReader, provider: StreamProvider, limit: Int,
                                onPartial: (String) -> Unit): String {
    val output = StringBuilder()
    val data = StringBuilder()
    var completed = false
    var terminalText = false
    var clipped = false
    var lastPublished = 0L
    fun append(delta: String) {
        val remaining = if (limit > 0) (limit-output.length).coerceAtLeast(0) else delta.length
        output.append(delta.take(remaining))
        if (remaining < delta.length) clipped = true
        val now = System.nanoTime()
        if (now-lastPublished >= 80_000_000L && output.isNotEmpty()) { onPartial(output.toString()); lastPublished=now }
    }
    fun consume() {
        if (data.isEmpty()) return
        val raw = data.toString(); data.setLength(0)
        if (raw == "[DONE]") { completed = terminalText; return }
        val json = JSONObject(raw)
        check(!json.has("error") && json.optString("type") != "error") { "AI provider reported a stream error" }
        when (provider) {
            StreamProvider.OPENAI -> {
                val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
                append(choice.optJSONObject("delta")?.optString("content").orEmpty())
                val reason = choice.optString("finish_reason")
                if (reason == "stop") terminalText = true
                if (reason in setOf("length", "content_filter")) error("AI did not finish the response. Try a shorter request.")
            }
            StreamProvider.ANTHROPIC -> when (json.optString("type")) {
                "content_block_delta" -> if (json.optJSONObject("delta")?.optString("type") == "text_delta")
                    append(json.getJSONObject("delta").optString("text"))
                "message_delta" -> {
                    val reason = json.optJSONObject("delta")?.optString("stop_reason")
                    if (reason == "end_turn" || reason == "stop_sequence") terminalText = true
                    if (reason == "max_tokens") error("AI did not finish the response. Try a shorter request.")
                }
                "message_stop" -> completed = terminalText
            }
            StreamProvider.GEMINI -> {
                val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (!part.optBoolean("thought")) append(part.optString("text"))
                }
                val reason = candidate.optString("finishReason")
                if (reason == "STOP") completed = true
                if (reason.isNotEmpty() && reason != "STOP") error("AI did not finish the response. Try a shorter request.")
            }
        }
    }
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) consume()
        else if (line.startsWith("data:")) { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()) }
    }
    consume()
    check(completed) { "AI stream was interrupted. Try again." }
    check(output.isNotBlank()) { "AI returned no text" }
    return (output.toString() + if (clipped) "…" else "").also(onPartial)
}
