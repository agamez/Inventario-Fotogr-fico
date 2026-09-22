package com.inventory.desktop

import com.inventory.desktop.ui.ANCESTOR_TARGET_PREFIX
import com.inventory.desktop.ui.DragKind
import com.inventory.desktop.ui.DragPayload
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Drop-resolution tests for [DesktopAppController.dropDrag] — the testable
 * half of the desktop drag-and-drop: a photo lands in the hovered container,
 * a container drag relocates its whole subtree, ancestor chips resolve to
 * their folder, and null / own-subtree targets are refused. The tree is
 * built through the controller's own ops over a temp folder; the disk is the
 * assertion (exactly what the UI performs on release).
 */
class DesktopDragTest {

    private class Tree(
        val app: DesktopAppController,
        val root: File,
        val building: String,
        val storage: String,
        val shelfA: String,
        val shelfB: String,
    )

    /** Building/Storage unit with Shelf A + Shelf B and one photo in Shelf A. */
    private suspend fun tree(): Tree {
        val root = Files.createTempDirectory("pi-drag").toFile()
        val app = DesktopAppController(root)
        app.refreshNow()
        val building = app.createFolder("", "Building").first!!
        val storage = app.createFolder(building, "Storage unit").first!!
        val shelfA = app.createFolder(storage, "Shelf A").first!!
        val shelfB = app.createFolder(storage, "Shelf B").first!!
        app.importPhoto(shelfA, syntheticJpeg(), "Item", null)
        return Tree(app, root, building, storage, shelfA, shelfB)
    }

    @Test
    fun photoDragMovesThePhotoIntoTheHoveredContainer() = runTest {
        val tree = tree()
        val photo = tree.app.snapshot.value.allPhotos.single()
        val outcome = tree.app.dropDrag(
            DragPayload(listOf(photo.relPath), photo.description, DragKind.PHOTO),
            tree.shelfB,
        )
        assertTrue(outcome)
        assertFalse(File(tree.root, photo.relPath).exists())
        assertTrue(File(tree.root, "${tree.shelfB}/Item.jpg").isFile)
        // The index converged on the new parent.
        assertEquals(tree.shelfB, tree.app.snapshot.value.allPhotos.single().parentRel)
    }

    @Test
    fun containerDragRelocatesTheWholeSubtree() = runTest {
        val tree = tree()
        val outcome = tree.app.dropDrag(
            DragPayload(listOf(tree.shelfA), "Shelf A", DragKind.CONTAINER),
            tree.shelfB,
        )
        assertTrue(outcome)
        assertFalse(File(tree.root, tree.shelfA).exists())
        assertTrue(File(tree.root, "${tree.shelfB}/Shelf A/Item.jpg").isFile)
        assertEquals(
            "${tree.shelfB}/Shelf A",
            tree.app.snapshot.value.allPhotos.single().parentRel,
        )
    }

    @Test
    fun ancestorChipTargetMovesIntoThatAncestor() = runTest {
        val tree = tree()
        val photo = tree.app.snapshot.value.allPhotos.single()
        val outcome = tree.app.dropDrag(
            DragPayload(listOf(photo.relPath), photo.description, DragKind.PHOTO),
            ANCESTOR_TARGET_PREFIX + tree.building,
        )
        assertTrue(outcome)
        assertTrue(File(tree.root, "${tree.building}/Item.jpg").isFile)
        assertFalse(File(tree.root, photo.relPath).exists())
    }

    @Test
    fun nullAndBlockedTargetsAreNoOps() = runTest {
        val tree = tree()
        val photo = tree.app.snapshot.value.allPhotos.single()
        val photoPayload = DragPayload(listOf(photo.relPath), photo.description, DragKind.PHOTO)
        assertFalse(tree.app.dropDrag(photoPayload, null))
        assertTrue(File(tree.root, photo.relPath).isFile)

        val containerPayload = DragPayload(listOf(tree.storage), "Storage unit", DragKind.CONTAINER)
        // Dropping a container into its own child (or itself) must be refused.
        assertFalse(tree.app.dropDrag(containerPayload, tree.shelfA))
        assertFalse(tree.app.dropDrag(containerPayload, tree.storage))
        assertTrue(File(tree.root, tree.storage).isDirectory)
        assertTrue(File(tree.root, "${tree.storage}/Shelf A/Item.jpg").isFile)
    }

    @Test
    fun descendantFoldersResolveSelfAndSubtreeFromDisk() = runTest {
        val tree = tree()
        assertEquals(
            setOf(tree.storage, tree.shelfA, tree.shelfB),
            tree.app.descendantFolders(tree.storage),
        )
        assertEquals(setOf(tree.shelfA), tree.app.descendantFolders(tree.shelfA))
        assertEquals(emptySet(), tree.app.descendantFolders(""))
    }
}
