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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.inventory.desktop.ui.App
import com.inventory.desktop.ui.DesktopStrings
import com.inventory.desktop.ui.FolderGate
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * The desktop entry point. With `--headless-selftest <folder>` it runs
 * the scripted end-to-end scenario against that folder and exits 0/1 —
 * the verification path that needs no display. Otherwise it opens the
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
    application {
        // The config read is one synchronous properties file — done once
        // outside composition; the (possibly null) root decides whether the
        // gate or the browser opens first.
        var controller by remember {
            mutableStateOf(DesktopConfig.loadRoot()?.let { root ->
                DesktopAppController(root).also { it.start() }
            })
        }
        Window(
            onCloseRequest = {
                controller?.stop()
                exitApplication()
            },
            title = DesktopStrings.WINDOW_TITLE,
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
                            runCatching { DesktopConfig.saveRoot(File(path)) }
                            controller = DesktopAppController(File(path)).also { it.start() }
                        })
                    }
                }
            }
        }
    }
}
