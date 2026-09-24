package com.inventory.storage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Behaviour tests for [TrashManager] on a plain JVM over the real
 * [FileBackend]: moving containers/photos into `.trash/<stamp>`, the
 * `.origin` sidecar, listing/restoring (with collision suffixing),
 * deleting forever and clock-driven purging. EXIF is the deterministic
 * [FakeExif]; the clock is a mutable fake so 30-day aging is testable
 * without touching the host wall clock.
 */
class TrashManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun backend(): FileBackend {
        val root = tmp.newFolder(ROOT_FOLDER_NAME)
        return FileBackend(root = root, accessCheck = { true }, exif = FakeExif())
    }

    private fun originText(backend: FileBackend, stampId: String): String =
        File(backend.root, "${TrashManager.TRASH_DIR}/$stampId/${TrashManager.ORIGIN_FILE}").readText()

    @Test
    fun trashContainerMovesTheSubtreeAndWritesTheSidecar() = runTest {
        val backend = backend()
        val now = 1_700_000_000_000L
        val trash = TrashManager(backend) { now }
        backend.createFolder("", "Building")
        backend.createFolder("Building", "Box A")
        backend.writePhotoBytes("Building/Box A/Keep me.jpg", FakeExif.jpeg("Keep me", null))

        assertTrue(trash.trashContainer("Building/Box A"))

        assertFalse(backend.exists("Building/Box A"))
        assertTrue(backend.exists("Building"))
        val entry = trash.listEntries().single()
        assertEquals("Box A", entry.name)
        assertEquals("Building", entry.originalParentRel)
        assertEquals(TrashManager.KIND_CONTAINER, entry.kind)
        assertEquals(now, entry.deletedAt)
        // On disk: .trash/<stamp>/Box A/Keep me.jpg plus the sidecar.
        assertTrue(backend.exists("${TrashManager.TRASH_DIR}/${entry.id}/Box A/Keep me.jpg"))
        assertEquals("Building\ncontainer\n", originText(backend, entry.id))
    }

    @Test
    fun trashContainerAtTheRootRecordsAnEmptyParent() = runTest {
        val backend = backend()
        val trash = TrashManager(backend) { 1_700_000_000_000L }
        backend.createFolder("", "Loose")

        assertTrue(trash.trashContainer("Loose"))

        val entry = trash.listEntries().single()
        assertEquals("", entry.originalParentRel)
        assertEquals("\ncontainer\n", originText(backend, entry.id))
    }

    @Test
    fun restorePutsTheContainerBackAndSuffixesAnOccupiedName() = runTest {
        val backend = backend()
        val trash = TrashManager(backend) { 1_700_000_000_000L }
        backend.createFolder("", "Building")
        backend.createFolder("Building", "Box A")
        backend.writePhotoBytes("Building/Box A/Keep me.jpg", FakeExif.jpeg("Keep me", null))
        assertTrue(trash.trashContainer("Building/Box A"))
        val id = trash.listEntries().single().id
        // The original name is taken again by the time we restore.
        backend.createFolder("Building", "Box A")

        assertTrue(trash.restore(id))

        assertTrue(backend.exists("Building/Box A"))
        assertFalse(backend.exists("Building/Box A/Keep me.jpg"))
        assertTrue(backend.exists("Building/Box A (2)/Keep me.jpg"))
        assertFalse(backend.exists("${TrashManager.TRASH_DIR}/$id"))
        assertTrue(trash.listEntries().isEmpty())
    }

    @Test
    fun restoreRefusesWhenTheOriginalParentIsGone() = runTest {
        val backend = backend()
        val trash = TrashManager(backend) { 1_700_000_000_000L }
        backend.createFolder("", "Building")
        backend.createFolder("Building", "Box A")
        backend.writePhotoBytes("Building/Box A/x.jpg", FakeExif.jpeg("x", null))
        assertTrue(trash.trashContainer("Building/Box A"))
        val id = trash.listEntries().single().id
        backend.deleteFolderRecursive("Building")

        assertFalse(trash.restore(id))
        assertTrue(trash.listEntries().any { it.id == id })
    }

    @Test
    fun deleteForeverRemovesTheWholeStampFolder() = runTest {
        val backend = backend()
        val trash = TrashManager(backend) { 1_700_000_000_000L }
        backend.createFolder("", "Box A")
        assertTrue(trash.trashContainer("Box A"))
        val id = trash.listEntries().single().id

        assertTrue(trash.deleteForever(id))

        assertFalse(backend.exists("${TrashManager.TRASH_DIR}/$id"))
        assertTrue(trash.listEntries().isEmpty())
        // A vanished entry is still "deleted forever".
        assertTrue(trash.deleteForever(id))
    }

    @Test
    fun trashPhotosCreatesOneEntryPerPhotoWithItsOwnParent() = runTest {
        val backend = backend()
        val trash = TrashManager(backend) { 1_700_000_000_000L }
        backend.createFolder("", "A")
        backend.createFolder("", "B")
        backend.writePhotoBytes("A/p.jpg", FakeExif.jpeg("p", null))
        backend.writePhotoBytes("B/q.jpg", FakeExif.jpeg("q", null))

        assertTrue(trash.trashPhotos(listOf("A/p.jpg", "B/q.jpg")))

        assertFalse(backend.exists("A/p.jpg"))
        assertFalse(backend.exists("B/q.jpg"))
        val entries = trash.listEntries()
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.name == "p.jpg" && it.originalParentRel == "A" && it.kind == TrashManager.KIND_PHOTO })
        assertTrue(entries.any { it.name == "q.jpg" && it.originalParentRel == "B" && it.kind == TrashManager.KIND_PHOTO })
    }

    @Test
    fun restorePhotoSuffixesAnOccupiedName() = runTest {
        val backend = backend()
        val trash = TrashManager(backend) { 1_700_000_000_000L }
        backend.createFolder("", "A")
        val bytes = FakeExif.jpeg("p", null)
        backend.writePhotoBytes("A/p.jpg", bytes)
        assertTrue(trash.trashPhotos(listOf("A/p.jpg")))
        val id = trash.listEntries().single().id
        backend.writePhotoBytes("A/p.jpg", FakeExif.jpeg("new", null))

        assertTrue(trash.restore(id))

        assertTrue(backend.exists("A/p.jpg"))
        assertTrue(backend.exists("A/p (2).jpg"))
        assertTrue(trash.listEntries().isEmpty())
    }

    @Test
    fun purgeOlderThanKeepsFreshEntries() = runTest {
        val backend = backend()
        var now = 1_700_000_000_000L
        val trash = TrashManager(backend) { now }
        backend.createFolder("", "Old")
        backend.createFolder("", "New")
        assertTrue(trash.trashContainer("Old"))
        // 40 days later a second deletion lands in the trash.
        now += 40L * 24 * 60 * 60 * 1000
        assertTrue(trash.trashContainer("New"))

        val purged = trash.purgeOlderThan(30)

        assertEquals(1, purged)
        val entries = trash.listEntries()
        assertEquals(listOf("New"), entries.map { it.name })
    }

    @Test
    fun purgeOlderThanZeroDaysKeepsEverything() = runTest {
        val backend = backend()
        val trash = TrashManager(backend) { 0L }
        backend.createFolder("", "A")
        assertTrue(trash.trashContainer("A"))
        assertEquals(0, trash.purgeOlderThan(30))
        assertEquals(1, trash.listEntries().size)
    }

    @Test
    fun purgeOverSizeCapDropsTheOldestEntriesUntilUnderTheCap() = runTest {
        val backend = backend()
        var now = 1_700_000_000_000L
        val trash = TrashManager(backend) { now }
        backend.createFolder("", "A")
        backend.createFolder("", "B")
        // Distinct file sizes via distinct contents: two 400-byte photos
        // plus their .origin sidecars exceed the 500-byte test cap.
        backend.writePhotoBytes("A/old.jpg", ByteArray(400))
        backend.writePhotoBytes("B/new.jpg", ByteArray(400))
        assertTrue(trash.trashPhotos(listOf("A/old.jpg")))
        now += 24L * 60 * 60 * 1000
        assertTrue(trash.trashPhotos(listOf("B/new.jpg")))
        assertTrue(trash.totalTrashBytes() > 500L)

        val purged = trash.purgeOverSizeCap(500L)

        assertEquals(1, purged)
        assertEquals(listOf("new.jpg"), trash.listEntries().map { it.name })
        assertTrue(trash.totalTrashBytes() <= 500L)
    }
}
