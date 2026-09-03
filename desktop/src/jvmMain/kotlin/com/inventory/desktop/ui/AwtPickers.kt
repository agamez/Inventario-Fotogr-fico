package com.inventory.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import java.io.FilenameFilter
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Native pickers on the AWT toolkit: Compose Desktop windows are Skia
 * surfaces without a heavyweight [Frame] of their own, so dialogs are
 * driven through a throwaway owner frame (photo picking, with multiple
 * selection and a JPEG filter) and Swing's directory-mode chooser (the
 * root-folder gate). Both run on the EDT via `invokeAndWait` from
 * [Dispatchers.IO] and return after the dialog closes.
 */

/** One folder picked through a DIRECTORIES_ONLY chooser, or null on cancel. */
suspend fun pickFolder(title: String, initial: String? = null): String? =
    withContext(Dispatchers.IO) {
        val result = AtomicReference<String?>(null)
        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser(initial ?: System.getProperty("user.home")).apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                result.set(chooser.selectedFile.absolutePath)
            }
        }
        result.get()
    }

/** JPEGs picked through a multiple-mode FileDialog (empty on cancel). */
suspend fun pickJpegFiles(title: String, initial: String? = null): List<File> =
    withContext(Dispatchers.IO) {
        val result = AtomicReference(emptyList<File>())
        SwingUtilities.invokeAndWait {
            val owner = Frame().apply { isLocationByPlatform = true }
            try {
                val dialog = FileDialog(owner, title, FileDialog.LOAD).apply {
                    isMultipleMode = true
                    setFilenameFilter(JPEG_FILTER)
                    setDirectory(initial ?: System.getProperty("user.home"))
                    isVisible = true
                }
                result.set(dialog.files?.filter { it.isFile } ?: emptyList())
                dialog.dispose()
            } finally {
                owner.dispose()
            }
        }
        result.get()
    }

private val JPEG_FILTER = FilenameFilter { _, name ->
    name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)
}
