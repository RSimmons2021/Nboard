package com.nboard.ime

import com.nboard.ime.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class ProviderStreamingTest {
    @Test fun chatGptPublishesBeforeTheTerminalEventAndKeepsFormatting() {
        val partial = mutableListOf<String>()
        val source = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"  hello\\n\"}\n\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"world  \"}\n\ndata: {\"type\":\"response.completed\"}\n\n"
        val result = ChatGptClient.readStream(StringReader(source).buffered(), 100, partial::add)
        assertEquals("  hello\nworld  ", result)
        assertEquals("  hello\n", partial.first())
        assertEquals(result, partial.last())
    }
    @Test fun interruptedStreamDoesNotBecomeAnApplyableResult() {
        val source = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
        val partial = mutableListOf<String>()
        assertTrue(runCatching { readProviderStream(StringReader(source).buffered(), StreamProvider.OPENAI, 100, partial::add) }.isFailure)
        assertEquals(listOf("partial"), partial)
    }
    @Test fun supportedProviderStreamsCompleteAndPreserveWhitespace() {
        val cases = listOf(
            StreamProvider.OPENAI to "data: {\"choices\":[{\"delta\":{\"content\":\"text\\n\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
            StreamProvider.ANTHROPIC to "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"text\\n\"}}\n\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n",
            StreamProvider.GEMINI to "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"text\\n\"}]},\"finishReason\":\"STOP\"}]}\n\n"
        )
        cases.forEach { (provider, source) -> assertEquals("text\n", readProviderStream(StringReader(source).buffered(), provider, 100) {}) }
    }
    @Test(timeout = 10000) fun stopCancelsAWaitingNetworkRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val client = OpenAiCompatibleClient(server.url("/").toString(), "model", "key")
            val request = launch { client.generateStreaming("hello", null, 100) {} }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)) }
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
        }
    }
}
