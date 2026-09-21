package com.inventory.desktop

import com.inventory.ai.VisionOutcome
import com.inventory.desktop.exif.DesktopExifEditor
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * AI auto-label tests for the desktop controller and the pure pre-fill
 * guard: a real loopback mock server (the JDK's built-in
 * `com.sun.net.httpserver`, no extra dependency) vends suggestions, the
 * controller stages photos with stable ids, the suggestions land keyed by
 * id, and the blank-guarded save path writes the right names + EXIF.
 */
class DesktopAiLabelTest {

    /** A stubbed OpenAI-compatible endpoint vending [descriptions] in order. */
    private class MockAiServer(private val descriptions: List<String>) : AutoCloseable {
        private val counter = AtomicInteger(0)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

        init {
            server.createContext(CHAT_PATH) { exchange: HttpExchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val content = if (body.contains("Reply with the single word OK")) {
                    "OK"
                } else {
                    val index = counter.getAndIncrement().coerceAtMost(descriptions.lastIndex)
                    "{\"description\": \"${descriptions[index]}\", \"note\": \"$MOCK_NOTE\"}"
                }
                val bytes = envelope(content).toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    /** A controller over a temp root with a temp config file. */
    private suspend fun aiController(): Triple<DesktopAppController, File, File> {
        val root = Files.createTempDirectory("pi-ai").toFile()
        val configFile = Files.createTempFile("pi-ai-config", ".properties").toFile()
        val app = DesktopAppController(root, config = DesktopConfig(configFile))
        app.refreshNow()
        return Triple(app, root, configFile)
    }

    private suspend fun awaitSuggestion(app: DesktopAppController, id: Long): VisionOutcome =
        withTimeout(AI_TIMEOUT_MS) { app.aiSuggestions.first { it.containsKey(id) }[id]!! }

    @Test
    fun suggestionsPrefillTwoImportsAndWriteExif() = runBlocking {
        val (app, root, _) = aiController()
        MockAiServer(listOf("Mock item", "Second item")).use { mock ->
            app.setAiEnabled(true)
            app.setAiBaseUrl(mock.baseUrl)
            app.setAiApiKey("")
            app.setAiModel("mock-vision-model")
            app.createFolder("", "Box")

            app.queueForImport(listOf(syntheticJpeg(), syntheticJpeg()))
            val queue = app.pendingQueue.value
            assertEquals(2, queue.size)

            // Each reply lands keyed by its staged photo's stable id.
            val outcomes = queue.associate { it.id to awaitSuggestion(app, it.id) }
            assertEquals(2, outcomes.size)
            outcomes.values.forEach { assertTrue(it is VisionOutcome.Suggested, "got $it") }
            assertEquals(
                setOf("Mock item", "Second item"),
                outcomes.values.map { (it as VisionOutcome.Suggested).description }.toSet(),
            )

            // The pre-fill guard fills the still-blank dialog fields; saving
            // consumes the queue head exactly once.
            val fills = queue.map { photo -> mergedPreFill("", "", outcomes.getValue(photo.id)) }
            fills.forEach { fill ->
                assertEquals(
                    DesktopAppController.Outcome.Saved,
                    app.importPendingPhoto("Box", fill.description, fill.note),
                )
            }
        }

        val editor = DesktopExifEditor()
        listOf("Mock item", "Second item").forEach { description ->
            val file = File(root, "Box/$description.jpg")
            assertTrue(file.isFile, "expected $description.jpg on disk")
            val meta = editor.read(file) ?: throw AssertionError("no EXIF for $description.jpg")
            assertEquals(description, meta.description)
            assertEquals(MOCK_NOTE, meta.note)
        }
    }

    @Test
    fun typedTextSurvivesALateSuggestion() {
        // Pure blank-guard: a suggestion only fills still-blank fields.
        assertEquals(PreFill("", ""), mergedPreFill("", "", null))
        assertEquals(PreFill("", ""), mergedPreFill("", "", VisionOutcome.Failed("boom")))
        assertEquals(
            PreFill("AI name", "AI note"),
            mergedPreFill("", "", VisionOutcome.Suggested("AI name", "AI note")),
        )
        assertEquals(
            PreFill("Typed name", "AI note"),
            mergedPreFill("Typed name", "", VisionOutcome.Suggested("AI name", "AI note")),
        )
        assertEquals(
            PreFill("AI name", "Typed note"),
            mergedPreFill("", "Typed note", VisionOutcome.Suggested("AI name", "AI note")),
        )
        assertEquals(
            PreFill("Typed name", "Typed note"),
            mergedPreFill("Typed name", "Typed note", VisionOutcome.Suggested("AI name", "AI note")),
        )
        // A blank suggested note never clears or blank-fills the field.
        assertEquals(PreFill("AI name", ""), mergedPreFill("", "", VisionOutcome.Suggested("AI name", null)))
    }

    @Test
    fun typedTextSurvivesAnActualLateReply() = runBlocking {
        val (app, _, _) = aiController()
        MockAiServer(listOf("AI name")).use { mock ->
            app.setAiEnabled(true)
            app.setAiBaseUrl(mock.baseUrl)
            app.setAiModel("mock-vision-model")
            app.createFolder("", "Box")
            app.queueForImport(listOf(syntheticJpeg()))
            val head = app.pendingQueue.value.first()

            // The user types before the reply arrives: the guard is a no-op.
            val withTypedText = mergedPreFill("User typed", "User note", null)
            assertEquals(PreFill("User typed", "User note"), withTypedText)

            val suggestion = awaitSuggestion(app, head.id)
            assertEquals(withTypedText, mergedPreFill(withTypedText.description, withTypedText.note, suggestion))
            // A still-blank note would be filled; the typed description stays.
            assertEquals(
                PreFill("User typed", MOCK_NOTE),
                mergedPreFill("User typed", "", suggestion),
            )
        }
    }

    @Test
    fun aiConfigStaysNullUntilEnabledAndModeledAndPersists() = runBlocking {
        val (app, _, configFile) = aiController()
        assertEquals(AiSettings(), DesktopConfig(configFile).loadAiSettings())
        assertNull(app.aiConfig())

        app.setAiModel("gpt-4o-mini")
        assertNull(app.aiConfig(), "disabled feature yields no config")
        app.setAiEnabled(true)
        app.setAiBaseUrl("http://127.0.0.1:9/v1")
        assertNotNull(app.aiConfig())
        assertNull(app.aiConfig()?.apiKey, "blank key becomes null")

        // A fresh config over the same file reads the persisted values back.
        val reloaded = DesktopConfig(configFile).loadAiSettings()
        assertTrue(reloaded.enabled)
        assertEquals("gpt-4o-mini", reloaded.model)
        assertEquals("http://127.0.0.1:9/v1", reloaded.baseUrl)
    }

    @Test
    fun testConnectionPingsTheConfiguredEndpoint() = runBlocking {
        val (app, _, _) = aiController()
        MockAiServer(listOf("unused")).use { mock ->
            app.setAiEnabled(true)
            app.setAiBaseUrl(mock.baseUrl)
            app.setAiModel("mock-vision-model")
            assertEquals(VisionOutcome.Suggested("OK", null), app.testConnection())
        }
        app.setAiEnabled(false)
        assertTrue(app.testConnection() is VisionOutcome.Failed)
    }

    @Test
    fun disabledAiStagesWithoutSuggestions() = runBlocking {
        val (app, _, _) = aiController()
        app.createFolder("", "Box")
        app.queueForImport(listOf(syntheticJpeg()))
        assertEquals(1, app.pendingQueue.value.size)
        assertTrue(app.aiSuggestions.value.isEmpty())
        app.discardPendingImport()
        assertTrue(app.pendingQueue.value.isEmpty())
    }

    private companion object {
        const val CHAT_PATH = "/v1/chat/completions"
        const val MOCK_NOTE = "added by mock"
        const val AI_TIMEOUT_MS = 10_000L
    }
}

/** Wraps [content] in the minimal chat-completion envelope (JSON-escaped). */
private fun envelope(content: String): String =
    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"${jsonEscape(content)}\"}}]}"

/** Escapes a JSON string value embedded inside another JSON string. */
private fun jsonEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")