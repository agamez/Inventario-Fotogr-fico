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
            assertTrue(body.contains("\"max_tokens\":2048"), "token cap is sent")
        }
    }

    @Test
    fun describeAppendsRequestedOutputLanguage() = runTest {
        val suggestionJson = "{\"description\": \"Red toolbox\", \"note\": \"rusty handle\"}"
        withServer(responseBody = chatCompletion(suggestionJson)) { server ->
            val client = VisionClient(
                VisionConfig(
                    baseUrl = server.baseUrl(),
                    apiKey = TEST_KEY,
                    model = TEST_MODEL,
                    outputLanguage = "Spanish",
                ),
            )
            client.describe(byteArrayOf(1, 2, 3, 4))

            assertTrue(
                server.requestBody.contains("Write the description and note in Spanish."),
                "request carries the output-language instruction",
            )
        }
    }

    @Test
    fun describeTrimsTheRequestedOutputLanguage() = runTest {
        val suggestionJson = "{\"description\": \"Red toolbox\", \"note\": \"rusty handle\"}"
        withServer(responseBody = chatCompletion(suggestionJson)) { server ->
            val client = VisionClient(
                VisionConfig(
                    baseUrl = server.baseUrl(),
                    apiKey = TEST_KEY,
                    model = TEST_MODEL,
                    outputLanguage = "  Deutsch  ",
                ),
            )
            client.describe(byteArrayOf(1, 2, 3, 4))

            assertTrue(
                server.requestBody.contains("Write the description and note in Deutsch."),
                "the language is trimmed before it is embedded",
            )
        }
    }

    @Test
    fun describeOmitsOutputLanguageInstructionWhenNull() = runTest {
        val suggestionJson = "{\"description\": \"Red toolbox\", \"note\": \"rusty handle\"}"
        withServer(responseBody = chatCompletion(suggestionJson)) { server ->
            client(server).describe(byteArrayOf(1, 2, 3, 4))

            assertFalse(
                server.requestBody.contains("Write the description and note in"),
                "no output-language instruction without a language",
            )
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

    @Test
    fun pingSucceedsWhenReasoningModelTruncatesTheReply() = runTest {
        // The captured gemma4 shape: content is null and finish_reason is
        // "length" because reasoning consumed the whole token budget.
        withServer(responseBody = TRUNCATED_PING_REPLY) { server ->
            assertEquals(VisionOutcome.Suggested("OK", null), client(server).ping())
        }
    }

    @Test
    fun describeReportsTokenBudgetExhaustion() = runTest {
        withServer(responseBody = TRUNCATED_PING_REPLY) { server ->
            val outcome = client(server).describe(byteArrayOf(1, 2, 3))
            assertTrue(outcome is VisionOutcome.Failed, "got $outcome")
            assertTrue(outcome.reason.contains("tokens"), "reason was ${outcome.reason}")
        }
    }

    @Test
    fun describeParsesArrayOfTextParts() = runTest {
        // Some OpenAI-compatible servers return content as an array of
        // parts; only the "text" parts form the JSON contract.
        withServer(responseBody = ARRAY_CONTENT_REPLY) { server ->
            assertEquals(
                VisionOutcome.Suggested("Red", "A solid, uniform red color."),
                client(server).describe(byteArrayOf(4, 5, 6)),
            )
        }
    }

    @Test
    fun describeParsesSuggestionWithReasoningContentAlongside() = runTest {
        // The real successful fixture: a clean JSON string in content with
        // a reasoning_content field next to it.
        val content = "{\"description\": \"Red\", \"note\": \"A solid, uniform red color.\"}"
        withServer(responseBody = reasoningCompletion(content)) { server ->
            assertEquals(
                VisionOutcome.Suggested("Red", "A solid, uniform red color."),
                client(server).describe(byteArrayOf(7, 8, 9)),
            )
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

    /** The captured reasoning-model envelope: content plus reasoning_content. */
    private fun reasoningCompletion(content: String): String = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                put("finish_reason", "stop")
                putJsonObject("message") {
                    put("role", "assistant")
                    put("reasoning_content", "*")
                    putJsonObject("provider_specific_fields") {
                        put("reasoning", "*")
                        put("reasoning_content", "*")
                    }
                    put("content", content)
                }
            }
        }
    }.toString()

    private companion object {
        const val CHAT_PATH = "/v1/chat/completions"
        const val TEST_KEY = "test-secret-key"
        const val TEST_MODEL = "vision-test-model"

        /** Captured gemma4 reply truncated by reasoning (content is null). */
        val TRUNCATED_PING_REPLY = """
            {"id":"chatcmpl-truncated","created":1,"model":"gemma4","object":"chat.completion",
            "choices":[{"finish_reason":"length","index":0,
            "message":{"role":"assistant","reasoning_content":"*",
            "provider_specific_fields":{"refusal":null,"reasoning":"*","reasoning_content":"*"},
            "content":null},"provider_specific_fields":{}}],"usage":{}}
        """.trimIndent()

        /** Captured multi-part reply: the JSON contract split across text parts. */
        val ARRAY_CONTENT_REPLY = """
            {"id":"chatcmpl-array","created":2,"model":"gemma4","object":"chat.completion",
            "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":[
            {"type":"text","text":"{\"description\": \"Red\", "},
            {"type":"text","text":"\"note\": \"A solid, uniform red color.\"}"}]}}]}
        """.trimIndent()
    }
}
