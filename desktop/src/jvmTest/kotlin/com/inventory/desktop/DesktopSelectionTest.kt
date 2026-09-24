package com.inventory.desktop

import com.inventory.desktop.exif.DesktopExifEditor
import com.inventory.storage.TrashManager
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Multi-select tests for [DesktopAppController]: Ctrl+click toggling,
 * Shift+click range selection over the caller-supplied grid order,
 * select-all / clear / folder-change reset, and deleteSelectedPhotos in
 * both trash modes. The tree is built through the controller's own ops over
 * a temp folder and the disk is the assertion.
 */
class DesktopSelectionTest {

    private fun photo(description: String): ByteArray =
        DesktopExifEditor().withExif(syntheticJpeg(), description, null, null)

    /** A controller with container "C" holding One/Three/Two, plus their grid order. */
    private suspend fun seeded(trashEnabled: Boolean = false): Triple<DesktopAppController, File, List<String>> {
        val root = Files.createTempDirectory("pi-selection").toFile()
        val configFile = File(Files.createTempDirectory("pi-selection-config").toFile(), "config.properties")
        val config = DesktopConfig(configFile)
        if (trashEnabled) config.setTrashEnabled(true)
        val app = DesktopAppController(root, config = config)
        app.refreshNow()
        app.createFolder("", "C")
        app.importPhoto("C", photo("One"), "One", null)
        app.importPhoto("C", photo("Two"), "Two", null)
        app.importPhoto("C", photo("Three"), "Three", null)
        val ordered = app.photosIn("C", "", includeSubtree = false).map { it.relPath }
        return Triple(app, root, ordered)
    }

    @Test
    fun toggleSelectAddsAndRemovesAPhoto() = runTest {
        val (app, _, ordered) = seeded()
        assertEquals(emptySet(), app.selectedPhotoRels.value)

        app.toggleSelect(ordered[0])
        assertEquals(setOf(ordered[0]), app.selectedPhotoRels.value)

        app.toggleSelect(ordered[0])
        assertTrue(app.selectedPhotoRels.value.isEmpty())
    }

    @Test
    fun rangeSelectUnionsFromTheLastAnchoredTile() = runTest {
        val (app, _, ordered) = seeded()

        // Ctrl+click anchors at the first tile, Shift+click extends to the last.
        app.toggleSelect(ordered[0])
        app.rangeSelectTo(ordered[2], ordered)
        assertEquals(ordered.toSet(), app.selectedPhotoRels.value)

        // A later shift-click still measures from the same anchor.
        app.rangeSelectTo(ordered[1], ordered)
        assertEquals(ordered.toSet(), app.selectedPhotoRels.value)
    }

    @Test
    fun rangeSelectWithNoAnchorSelectsOnlyTheTarget() = runTest {
        val (app, _, ordered) = seeded()
        app.rangeSelectTo(ordered[1], ordered)
        assertEquals(setOf(ordered[1]), app.selectedPhotoRels.value)
    }

    @Test
    fun selectAllClearAndFolderChangeResetTheSelection() = runTest {
        val (app, _, ordered) = seeded()

        app.selectAllInCurrentFolder(ordered)
        assertEquals(ordered.toSet(), app.selectedPhotoRels.value)

        app.clearSelection()
        assertTrue(app.selectedPhotoRels.value.isEmpty())

        app.toggleSelect(ordered[0])
        app.onFolderChanged()
        assertTrue(app.selectedPhotoRels.value.isEmpty())
    }

    @Test
    fun deleteSelectedMovesEveryPhotoToTrashWhenEnabled() = runTest {
        val (app, root, ordered) = seeded(trashEnabled = true)
        app.toggleSelect(ordered[0])
        app.toggleSelect(ordered[1])

        val outcome = app.deleteSelectedPhotos()

        assertEquals(DesktopAppController.Outcome.Deleted, outcome)
        assertFalse(File(root, ordered[0]).exists())
        assertFalse(File(root, ordered[1]).exists())
        assertTrue(File(root, ordered[2]).isFile)
        assertTrue(app.selectedPhotoRels.value.isEmpty())
        assertEquals(2, app.trashEntries.value.count { it.kind == TrashManager.KIND_PHOTO })
    }

    @Test
    fun deleteSelectedIsPermanentWhenTrashIsDisabled() = runTest {
        val (app, root, ordered) = seeded()
        app.selectAllInCurrentFolder(ordered)

        app.deleteSelectedPhotos()

        assertTrue(ordered.none { File(root, it).exists() })
        assertFalse(File(root, TrashManager.TRASH_DIR).exists())
        assertTrue(app.selectedPhotoRels.value.isEmpty())
    }
}
