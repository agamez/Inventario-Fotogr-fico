package com.inventory.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Behaviour tests for [VisionClient] against a real loopback HTTP server
 * (the JDK's built-in `com.sun.net.httpserver`, no extra dependency):
 * request shape, lenient reply parsing, non-200 handling, malformed
 * replies and the ping round trip.
 */
class VisionClientTest {

    /** A stubbed OpenAI-compatible endpoint on an ephemeral loopback port. */
    private class MockServer(responseCode: Int, responseBody: String) {
        private val recorded = AtomicReference("")
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val requestBody: String get() = recorded.get()

        init {
            server.createContext(CHAT_PATH) { exchange: HttpExchange ->
                recorded.set(
                    exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) },
                )
                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(
                    responseCode,
                    if (bytes.isEmpty()) -1L else bytes.size.toLong(),
                )
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun baseUrl(): String = "http://127.0.0.1:${server.address.port}/v1"

        fun stop() = server.stop(0)
    }

    private suspend fun <T> withServer(
        responseCode: Int = 200,
        responseBody: String,
        block: suspend (MockServer) -> T,
    ): T {
        val server = MockServer(responseCode, responseBody)
        return try {
            block(server)
        } finally {
            server.stop()
        }
    }

    private fun client(server: MockServer) = VisionClient(
        VisionConfig(baseUrl = server.baseUrl(), apiKey = TEST_KEY, model = TEST_MODEL),
    )

    @Test
    fun describeParsesSuggestionAndSendsExpectedRequest() = runTest {
        val suggestionJson = "{\"description\": \"Red toolbox\", \"note\": \"rusty handle\"}"
        withServer(responseBody = chatCompletion(suggestionJson)) { server ->
            val outcome = client(server).describe(byteArrayOf(1, 2, 3, 4))

            assertEquals(VisionOutcome.Suggested("Red toolbox", "rusty handle"), outcome)

            val body = server.requestBody
            assertTrue(body.contains(TEST_MODEL), "request carries the model name")
            assertTrue(body.contains("data:image/jpeg;base64,"), "image is inlined as a data URL")
            assertTrue(
                body.contains("short item name, 2-6 words"),
                "request carries the labeling prompt",
            )
            assertTrue(body.contains("\"max_tokens\":300"), "token cap is sent")
        }
    }

    @Test
    fun describeAcceptsMarkdownFencedReply() = runTest {
        val fenced = "```json\n{\"description\": \"Red toolbox\", \"note\": \"rusty handle\"}\n```"
        withServer(responseBody = chatCompletion(fenced)) { server ->
            assertEquals(
                VisionOutcome.Suggested("Red toolbox", "rusty handle"),
                client(server).describe(byteArrayOf(9)),
            )
        }
    }

    @Test
    fun describeReportsHttpErrorWithoutLeakingKey() = runTest {
        withServer(responseCode = 401, responseBody = "") { server ->
            val outcome = client(server).describe(byteArrayOf(1))
            assertTrue(outcome is VisionOutcome.Failed)
            assertEquals("HTTP 401", outcome.reason)
            assertFalse(outcome.reason.contains(TEST_KEY), "the API key never appears in errors")
        }
    }

    @Test
    fun describeRejectsMalformedContent() = runTest {
        withServer(responseBody = chatCompletion("not json at all")) { server ->
            assertEquals(
                VisionOutcome.Failed("unexpected reply format"),
                client(server).describe(byteArrayOf(7)),
            )
        }
    }

    @Test
    fun pingReachesChatCompletionsAndSucceeds() = runTest {
        withServer(responseBody = chatCompletion("OK")) { server ->
            assertEquals(VisionOutcome.Suggested("OK", null), client(server).ping())
            assertTrue(server.requestBody.contains("Reply with the single word OK"))
        }
    }

    /** Wraps [content] in the minimal chat-completion envelope. */
    private fun chatCompletion(content: String): String = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", content)
                }
            }
        }
    }.toString()

    private companion object {
        const val CHAT_PATH = "/v1/chat/completions"
        const val TEST_KEY = "test-secret-key"
        const val TEST_MODEL = "vision-test-model"
    }
}
