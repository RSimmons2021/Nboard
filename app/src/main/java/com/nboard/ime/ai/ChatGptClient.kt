package com.nboard.ime.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

class ChatGptClient(private val session: ChatGptSession) : TextGenerationClient {
    override val isConfigured: Boolean get() = session.isConnected && session.model.isNotBlank()

    override suspend fun generateText(prompt: String, systemInstruction: String?, outputCharLimit: Int): Result<String> =
        generateStreaming(prompt, systemInstruction, outputCharLimit) { }

    override suspend fun generateStreaming(prompt: String, systemInstruction: String?, outputCharLimit: Int,
                                           onPartial: (String) -> Unit): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                check(isConfigured) { "Connect ChatGPT and choose a model in Nboard settings" }
                val request = Request.Builder().url("${ChatGptProtocol.RESOURCE}/responses")
                    .header("Authorization", "Bearer ${session.accessToken()}")
                    .post(requestBody(session.model, prompt, systemInstruction).toString()
                        .toRequestBody("application/json".toMediaType())).build()
                http.newCall(request).readCancellable { response ->
                    check(response.isSuccessful) { "ChatGPT plan request failed (HTTP ${response.code}). Check your plan permissions and usage limits." }
                    val body = response.body ?: error("ChatGPT returned no response")
                    body.charStream().buffered().use { readStream(it, outputCharLimit, onPartial) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { Result.failure(e)
            }
        }

    companion object {
        private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

        internal fun requestBody(model: String, prompt: String, instructions: String?): JSONObject = JSONObject()
            .put("model", model).put("store", false).put("stream", true)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .apply { if (!instructions.isNullOrBlank()) put("instructions", instructions) }

        internal fun readStream(reader: BufferedReader, limit: Int, onPartial: (String) -> Unit = {}): String {
            val output = StringBuilder()
            val data = StringBuilder()
            var completed = false
            var truncated = false
            var lastPublished = 0L
            fun consume() {
                if (data.isEmpty()) return
                val raw = data.toString()
                data.setLength(0)
                if (raw == "[DONE]") return
                val event = JSONObject(raw)
                when (event.optString("type")) {
                    "response.output_text.delta" -> {
                        val delta = event.optString("delta")
                        val remaining = if (limit > 0) (limit - output.length).coerceAtLeast(0) else delta.length
                        output.append(delta.take(remaining))
                        if (remaining < delta.length) truncated = true
                        val now = System.nanoTime()
                        if (now - lastPublished >= 80_000_000L) {
                            onPartial(output.toString())
                            lastPublished = now
                        }
                    }
                    "response.completed" -> completed = true
                    "response.failed", "response.incomplete", "error" -> {
                        val code = event.optJSONObject("response")?.optJSONObject("error")?.optString("code")
                            ?: event.optJSONObject("error")?.optString("code") ?: event.optString("code")
                        throw IOException("ChatGPT request did not complete${code.takeIf { it.matches(Regex("[a-z_]{1,100}")) }?.let { ": $it" }.orEmpty()}. Check plan usage in ChatGPT settings.")
                    }
                }
            }
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) consume()
                else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
            }
            consume()
            check(completed) { "ChatGPT stream was interrupted. Try again." }
            check(output.isNotBlank()) { "ChatGPT returned no text" }
            return output.toString().let { if (truncated) "$it…" else it }.also(onPartial)
        }
    }
}
