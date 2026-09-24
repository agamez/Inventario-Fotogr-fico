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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * AI tidy-up tests for the desktop controller: a real loopback mock server
 * vends distinct descriptions per request, the scan collects only
 * camera-generated names, applying renames the files on disk (EXIF
 * rewritten), and Skip/Cancel report their counts. The AI-off path must
 * never touch the network.
 */
class DesktopTidyTest {

    /** A stubbed OpenAI-compatible endpoint vending [descriptions] in order. */
    private class MockAiServer(private val descriptions: List<String>) : AutoCloseable {
        private val counter = AtomicInteger(0)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        /** How many requests reached the endpoint (network-call probe). */
        val requestCount = AtomicInteger(0)

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

        init {
            server.createContext(CHAT_PATH) { exchange: HttpExchange ->
                requestCount.incrementAndGet()
                exchange.requestBody.use { it.readBytes() }
                val index = counter.getAndIncrement().coerceAtMost(descriptions.lastIndex)
                val content = "{\"description\": \"${descriptions[index]}\", \"note\": \"\"}"
                val body = "{\"choices\":[{\"message\":{\"role\":\"assistant\"," +
                    "\"content\":\"${jsonEscape(content)}\"}}]}"
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    /** A controller over a temp root with a temp config file. */
    private suspend fun tidyController(): Triple<DesktopAppController, File, File> {
        val root = Files.createTempDirectory("pi-tidy").toFile()
        val configFile = Files.createTempFile("pi-tidy-config", ".properties").toFile()
        val app = DesktopAppController(root, config = DesktopConfig(configFile))
        app.refreshNow()
        return Triple(app, root, configFile)
    }

    private suspend fun awaitTidySuggestion(app: DesktopAppController, index: Int): VisionOutcome =
        withTimeout(AI_TIMEOUT_MS) {
            app.tidyState.first { it != null && it.index == index && it.suggestion != null }!!.suggestion!!
        }

    @Test
    fun tidyRenamesCameraNamesAndLeavesProperNames() = runBlocking {
        val (app, root, _) = tidyController()
        app.createFolder("", "Box")
        app.importPhoto("Box", syntheticJpeg(), "IMG_9991", null)
        app.importPhoto("Box", syntheticJpeg(), "IMG_9992", null)
        app.importPhoto("Box", syntheticJpeg(), "IMG_9993", null)
        app.importPhoto("Box", syntheticJpeg(), "Toolbox lid", null)

        MockAiServer(listOf("Tidied item 1", "Tidied item 2", "Tidied item 3")).use { mock ->
            app.setAiEnabled(true)
            app.setAiBaseUrl(mock.baseUrl)
            app.setAiModel("mock-vision-model")

            assertEquals(DesktopAppController.TidyStart.STARTED, app.startTidyUp())
            val state = app.tidyState.value
            assertNotNull(state)
            assertEquals(3, state.queue.size)
            assertEquals(
                setOf("Box/IMG_9991.jpg", "Box/IMG_9992.jpg", "Box/IMG_9993.jpg"),
                state.queue.map { it.relPath }.toSet(),
                "only the camera-named photos are queued",
            )

            // Apply each suggestion in sequence; the controller advances itself.
            var applied = 0
            while (true) {
                val current = app.tidyState.value ?: break
                if (current.index >= current.queue.size) break
                val suggestion = awaitTidySuggestion(app, current.index)
                assertTrue(suggestion is VisionOutcome.Suggested, "got $suggestion")
                assertEquals(
                    DesktopAppController.Outcome.Saved,
                    app.applyTidy(suggestion.description, suggestion.note),
                )
                applied++
            }
            assertEquals(3, applied)
            assertNull(app.tidyState.value, "the session closes after the last item")
            assertEquals(DesktopAppController.TidySummary(3, 0), app.tidySummary.value)
        }

        // Disk is truth: the files were renamed on disk with their new EXIF.
        val editor = DesktopExifEditor()
        listOf("Tidied item 1", "Tidied item 2", "Tidied item 3").forEach { description ->
            val file = File(root, "Box/$description.jpg")
            assertTrue(file.isFile, "expected $description.jpg on disk")
            assertEquals(description, editor.read(file)?.description)
        }
        listOf("IMG_9991", "IMG_9992", "IMG_9993").forEach { name ->
            assertFalse(File(root, "Box/$name.jpg").exists(), "$name.jpg should have been renamed away")
        }
        assertTrue(File(root, "Box/Toolbox lid.jpg").isFile, "the human-named photo must be untouched")
    }

    @Test
    fun tidyNeedsAiWithoutMakingRequests() = runBlocking {
        val (app, _, _) = tidyController()
        app.createFolder("", "Box")
        app.importPhoto("Box", syntheticJpeg(), "IMG_0001", null)

        MockAiServer(listOf("unused")).use { mock ->
            // AI is off by default: the action reports the gate and sends nothing.
            assertEquals(DesktopAppController.TidyStart.NEED_AI, app.startTidyUp())
            assertNull(app.tidyState.value)
            assertEquals(0, mock.requestCount.get(), "no network call while AI is off")

            // A base URL + model but the switch still off: still no request.
            app.setAiBaseUrl(mock.baseUrl)
            app.setAiModel("mock-vision-model")
            assertEquals(DesktopAppController.TidyStart.NEED_AI, app.startTidyUp())
            assertEquals(0, mock.requestCount.get())
        }
    }

    @Test
    fun tidyReportsNothingWhenAllNamesAreHuman() = runBlocking {
        val (app, _, _) = tidyController()
        app.createFolder("", "Box")
        app.importPhoto("Box", syntheticJpeg(), "Toolbox lid", null)
        app.setAiEnabled(true)
        app.setAiModel("mock-vision-model")

        // No mock server: nothing is vague so no request is ever made.
        assertEquals(DesktopAppController.TidyStart.NOTHING, app.startTidyUp())
        assertNull(app.tidyState.value)
    }

    @Test
    fun tidySkipAndCancelReportCounts() = runBlocking {
        val (app, root, _) = tidyController()
        app.createFolder("", "Box")
        app.importPhoto("Box", syntheticJpeg(), "IMG_0001", null)
        app.importPhoto("Box", syntheticJpeg(), "IMG_0002", null)

        MockAiServer(listOf("Tidied item 1", "Tidied item 2")).use { mock ->
            app.setAiEnabled(true)
            app.setAiBaseUrl(mock.baseUrl)
            app.setAiModel("mock-vision-model")

            assertEquals(DesktopAppController.TidyStart.STARTED, app.startTidyUp())
            awaitTidySuggestion(app, 0)
            app.skipTidy()
            assertEquals(1, app.tidyState.value?.index)

            app.cancelTidy()
            assertNull(app.tidyState.value)
            assertEquals(DesktopAppController.TidySummary(0, 1), app.tidySummary.value)

            app.acknowledgeTidySummary()
            assertNull(app.tidySummary.value)
        }

        // Skipped/cancelled photos keep their disk names.
        assertTrue(File(root, "Box/IMG_0001.jpg").isFile)
        assertTrue(File(root, "Box/IMG_0002.jpg").isFile)
    }

    private companion object {
        const val CHAT_PATH = "/v1/chat/completions"
        const val AI_TIMEOUT_MS = 10_000L
    }
}

/** Escapes a JSON string value embedded inside another JSON string. */
private fun jsonEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
