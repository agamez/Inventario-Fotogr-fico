package com.inventory.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.inventory.ai.VisionClient
import com.inventory.ai.VisionConfig
import com.inventory.ai.VisionOutcome
import com.inventory.desktop.ui.App
import com.inventory.desktop.ui.DesktopStrings
import com.inventory.desktop.ui.FolderGate
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.jetbrains.skiko.toBitmap

/**
 * The window/taskbar icon: `icon.png` is packaged at the JAR root by the
 * desktop build. Read once via ImageIO, converted to a Skia bitmap
 * (skiko `toBitmap`) and wrapped as a Compose `Painter` for the 1.6.x
 * `Window(icon = …)` parameter. Returns null if the resource is missing
 * so the window simply falls back to the platform default.
 */
private val windowIconPainter: Painter? by lazy {
    runCatching {
        DesktopStrings::class.java.getResourceAsStream("/icon.png")?.use { png ->
            ImageIO.read(png)?.let { bitmap ->
                BitmapPainter(bitmap.toBitmap().asComposeImageBitmap())
            }
        }
    }.getOrNull()
}

/**
 * The desktop entry point. With `--headless-selftest <folder>` it runs
 * the scripted end-to-end scenario against that folder and exits 0/1 —
 * the verification path that needs no display. With
 * `--ai-test <baseUrl> <apiKey> <model> [imagePath] [language]` it
 * exercises the shared AI client against a live OpenAI-compatible server
 * and exits 0/1. Otherwise it opens the
 * 1100x750 window: it loads the saved root from [DesktopConfig], shows
 * the folder gate when none is usable, and otherwise builds the
 * [DesktopAppController] (watcher + initial index rebuild) and hands it
 * to the [App] UI.
 *
 * Theme: light/dark follow the system `apple.awt.brushBase` … not a
 * thing on JVM Linux — the trivial `isSystemInDarkTheme()` (which reads
 * GNOME/GTK accent hints on skiko) picks the color scheme; if the
 * desktop environment reports nothing, this lands on light.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--headless-selftest") {
        val folder = args.getOrNull(1)
        if (folder == null) {
            System.err.println("usage: --headless-selftest <folder>")
            System.exit(2)
        }
        val passed = runBlocking { runHeadlessSelfTest(File(folder)) }
        System.exit(if (passed) 0 else 1)
    }
    if (args.firstOrNull() == "--ai-test") {
        System.exit(runAiTest(args.drop(1)))
    }
    application {
        // The config read is one synchronous properties file — done once
        // outside composition; the (possibly null) root decides whether the
        // gate or the browser opens first.
        var controller by remember {
            mutableStateOf(DesktopConfig.default.loadRoot()?.let { root ->
                DesktopAppController(root).also { it.start() }
            })
        }
        Window(
            onCloseRequest = {
                controller?.stop()
                exitApplication()
            },
            title = DesktopStrings.WINDOW_TITLE,
            icon = windowIconPainter,
            state = rememberWindowState(width = 1100.dp, height = 750.dp),
        ) {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val current = controller
                    if (current != null) {
                        App(current)
                    } else {
                        FolderGate(onPicked = { path ->
                            runCatching { DesktopConfig.default.saveRoot(File(path)) }
                            controller = DesktopAppController(File(path)).also { it.start() }
                        })
                    }
                }
            }
        }
    }
}

/**
 * `--ai-test <baseUrl> <apiKey> <model> [imagePath] [language]` — validates
 * a live OpenAI-compatible AI configuration through the shared
 * [VisionClient]: always [VisionClient.ping], plus [VisionClient.describe]
 * when an image path is given. The optional trailing `language` is passed
 * as [VisionConfig.outputLanguage], so a call can be tested in a specific
 * output language. Prints one human-readable line per check and returns the
 * process exit code (0 when every requested check succeeded, 1 otherwise).
 * The API key is sent as a bearer token but never printed; [arguments]
 * carries the raw CLI values with the flag already removed.
 */
private fun runAiTest(arguments: List<String>): Int {
    if (arguments.size !in 3..5) {
        System.err.println("usage: --ai-test <baseUrl> <apiKey> <model> [imagePath] [language]")
        return 1
    }
    val client = VisionClient(
        VisionConfig(
            baseUrl = arguments[0],
            apiKey = arguments[1],
            model = arguments[2],
            outputLanguage = arguments.getOrNull(4),
        ),
    )
    var allPassed = true

    when (val ping = runBlocking { client.ping() }) {
        is VisionOutcome.Suggested -> println("ping: OK")
        is VisionOutcome.Failed -> {
            println("ping: FAILED — ${ping.reason}")
            allPassed = false
        }
    }

    val imagePath = arguments.getOrNull(3)
    if (imagePath != null) {
        val bytes = runCatching { File(imagePath).readBytes() }.getOrNull()
        if (bytes == null) {
            println("describe: FAILED — cannot read image at $imagePath")
            allPassed = false
        } else {
            when (val describe = runBlocking { client.describe(bytes) }) {
                is VisionOutcome.Suggested -> {
                    val note = describe.note?.let { "\"$it\"" } ?: "none"
                    println("describe: OK — description=\"${describe.description}\" note=$note")
                }
                is VisionOutcome.Failed -> {
                    println("describe: FAILED — ${describe.reason}")
                    allPassed = false
                }
            }
        }
    }

    return if (allPassed) 0 else 1
}
