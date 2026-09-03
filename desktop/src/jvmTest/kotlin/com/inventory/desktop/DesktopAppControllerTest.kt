package com.inventory.desktop

import com.inventory.desktop.exif.DesktopExifEditor
import com.inventory.desktop.ui.DesktopStrings
import com.inventory.storage.PhotoMeta
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Mutation-surface tests for the [DesktopAppController]: photo imports
 * landing under sanitized collision-safe names, the rename-when-the-
 * description-changes rule, byte-identical photo moves, folder
 * create/rename/move/delete with the subtree totals the confirm dialog
 * shows, the cycle guard, the cover round-trip and the import queue —
 * all through the real backend, index and desktop EXIF editor over a
 * temp folder.
 */
class DesktopAppControllerTest {

    private fun controller(): Pair<DesktopAppController, File> {
        val root = Files.createTempDirectory("pi-controller").toFile()
        return DesktopAppController(root) to root
    }

    private fun photo(description: String?, note: String? = null): ByteArray =
        DesktopExifEditor().withExif(syntheticJpeg(), description, note, null)

    @Test
    fun importNamesTheFileAndIndexesIt() = runTest {
        val (app, root) = controller()
        app.refreshNow()
        val outcome = app.importPhoto("", photo("Sunset at pier"), "Sunset at pier", "warm note")
        assertEquals(DesktopAppController.Outcome.Saved, outcome)
        assertTrue(File(root, "Sunset at pier.jpg").isFile)
        val row = app.snapshot.value.allPhotos.single()
        assertEquals("Sunset at pier", row.description)
        assertEquals("warm note", row.note)
        assertNotNull(row.dateTaken)
    }

    @Test
    fun sameDescriptionYieldsCollisionSuffix() = runTest {
        val (app, root) = controller()
        app.refreshNow()
        app.importPhoto("", photo("Twin"), "Twin", null)
        app.importPhoto("", photo("Twin"), "Twin", null)
        assertTrue(File(root, "Twin.jpg").isFile)
        assertTrue(File(root, "Twin (2).jpg").isFile)
    }

    @Test
    fun blankOrInvalidImportsAreRefusedWithoutScratch() = runTest {
        val (app, root) = controller()
        app.refreshNow()
        assertEquals(
            DesktopAppController.Outcome.BlankName,
            app.importPhoto("", photo("x"), "   ", null),
        )
        assertEquals(
            DesktopAppController.Outcome.InvalidImage,
            app.importPhoto("", byteArrayOf(1, 2, 3, 4), "Good name", null),
        )
        assertEquals(emptyList<String>(), root.list()?.sorted())
    }

    @Test
    fun metadataEditRenamesTheFileAndClearsTheNoteOnBlank() = runTest {
        val (app, root) = controller()
        app.refreshNow()
        app.importPhoto("", photo("Old name"), "Old name", "temp note")
        val outcome = app.updatePhotoMetadata("Old name.jpg", "New name", "   ")
        assertEquals(DesktopAppController.Outcome.Saved, outcome)
        assertFalse(File(root, "Old name.jpg").exists())
        assertTrue(File(root, "New name.jpg").isFile)
        val meta: PhotoMeta = app.readPhotoMeta("New name.jpg") ?: throw AssertionError("meta gone")
        assertEquals("New name", meta.description)
        assertNull(meta.note)
        assertEquals("New name", app.snapshot.value.allPhotos.single().description)
    }

    @Test
    fun movePhotosKeepsBytesIdenticalAndCollisionSuffixes() = runTest {
        val (app, root) = controller()
        app.refreshNow()
        app.createFolder("", "From")
        app.createFolder("", "To")
        app.importPhoto("From", photo("Vase"), "Vase", "porcelain")
        app.importPhoto("To", photo("Vase"), "Vase", null)
        val before = File(root, "From/Vase.jpg").readBytes()
        val outcome = app.movePhotosTo(listOf("From/Vase.jpg"), "To")
        assertEquals(DesktopAppController.Outcome.Moved, outcome)
        assertFalse(File(root, "From/Vase.jpg").exists())
        assertContentEquals(before, File(root, "To/Vase (2).jpg").readBytes())
        // The moved file keeps its EXIF triple (byte-identical relocation).
        assertEquals(
            "Vase",
            app.snapshot.value.allPhotos.first { it.relPath == "To/Vase (2).jpg" }.description,
        )
    }

    @Test
    fun folderLifecycleCoversCountsRenameMoveGuardAndDelete() = runTest {
        val (app, root) = controller()
        app.refreshNow()
        val (a, created) = app.createFolder("", "Alpha")
        assertEquals(DesktopAppController.Outcome.Created, created)
        assertEquals("Alpha", a)
        app.createFolder("Alpha", "Nested")
        app.importPhoto("Alpha", photo("Item"), "Item", null)
        assertEquals(1 to 1, app.subtreeCounts("Alpha"))

        val (renamed, renameOutcome) = app.renameFolder("Alpha", "Beta")
        assertEquals(DesktopAppController.Outcome.Saved, renameOutcome)
        assertEquals("Beta", renamed)
        assertTrue(File(root, "Beta/Nested").isDirectory)

        assertEquals(DesktopAppController.Outcome.InvalidMoveTarget, app.moveFolder("Beta", "Beta/Nested"))
        assertEquals(DesktopAppController.Outcome.InvalidMoveTarget, app.moveFolder("Beta", "Beta"))

        app.createFolder("", "Target")
        assertEquals(DesktopAppController.Outcome.Moved, app.moveFolder("Beta", "Target"))
        assertTrue(File(root, "Target/Beta/Item.jpg").isFile)

        app.deleteFolder("Target")
        assertFalse(File(root, "Target").exists())
        assertNull(app.snapshot.value.allPhotos.firstOrNull { it.relPath.endsWith("Item.jpg") })
    }

    @Test
    fun folderChoicesIncludeRootAndCutTheMovedSubtree() = runTest {
        val (app, _) = controller()
        app.refreshNow()
        app.createFolder("", "A")
        app.createFolder("A", "deep")
        app.createFolder("", "B")
        val all = app.folderChoices()
        assertEquals(listOf("", "A", "B", "A/deep"), all.map { it.relPath })
        assertEquals(DesktopStrings.ROOT_FOLDER_LABEL, all.first().label)
        assertEquals(
            listOf("", "B"),
            app.folderChoices(excludingSubtreeOf = "A").map { it.relPath },
        )
    }

    @Test
    fun coverRoundTripFlipsTheContainerRow() = runTest {
        val (app, root) = controller()
        app.refreshNow()
        app.createFolder("", "F")
        val cover = photo("cover")
        assertEquals(DesktopAppController.Outcome.InvalidImage, app.setCover("F", byteArrayOf(9, 9)))
        assertEquals(DesktopAppController.Outcome.Saved, app.setCover("F", cover))
        assertTrue(File(root, "F/_cover.jpg").isFile)
        assertTrue(app.children("").single().hasCover)
        val onDisk = app.coverFile("F")?.readBytes() ?: throw AssertionError("cover gone")
        assertContentEquals(cover.toList(), onDisk.toList())
        assertEquals(DesktopAppController.Outcome.Saved, app.removeCover("F"))
        assertFalse(app.children("").single().hasCover)
    }

    @Test
    fun pendingQueuePopsPerPhotoAndCancelDiscardsAll() = runTest {
        val (app, root) = controller()
        app.refreshNow()
        app.createFolder("", "Pit")
        app.queueForImport(listOf(photo("One"), photo("Two")))
        assertEquals(2, app.pendingQueue.value.size)
        assertEquals(
            DesktopAppController.Outcome.BlankName,
            app.importPendingPhoto("Pit", " ", null),
        )
        assertEquals(2, app.pendingQueue.value.size)
        assertEquals(
            DesktopAppController.Outcome.Saved,
            app.importPendingPhoto("Pit", "One", null),
        )
        assertEquals(1, app.pendingQueue.value.size)
        assertTrue(File(root, "Pit/One.jpg").isFile)
        app.discardPendingImport()
        assertEquals(0, app.pendingQueue.value.size)
        assertFalse(File(root, "Pit/Two.jpg").exists())
    }

    @Test
    fun conflictFilesAreCountedInSnapshot() = runTest {
        val (app, root) = controller()
        app.refreshNow()
        File(root, "note.sync-conflict-20260101-010101-DEV.jpg").writeBytes(photo("Note"))
        app.refreshNow()
        // The shared conflictCount flow is fed asynchronously; the
        // snapshot behind it is the synchronous source of truth.
        assertEquals(1, app.snapshot.value.conflictCount)
        assertTrue(app.snapshot.value.allPhotos.single().isConflict)
    }
}
