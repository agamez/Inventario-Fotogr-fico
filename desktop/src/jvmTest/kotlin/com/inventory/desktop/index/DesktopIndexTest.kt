package com.inventory.desktop.index

import com.inventory.desktop.exif.DesktopExifEditor
import com.inventory.storage.ExifEditor
import com.inventory.storage.FileBackend
import com.inventory.storage.PhotoMeta
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Behaviour tests for the desktop [DesktopIndex]: tree walking, sorting,
 * per-folder photo filtering, global search, the delete-confirmation
 * subtree counts, conflict flagging, the EXIF-parse-skip cache and the
 * filename/date fallbacks — all through the real shared [FileBackend]
 * over a temp folder, with the real [DesktopExifEditor] stamps.
 */
class DesktopIndexTest {

    /** Counts EXIF reads so the cache behaviour is observable. */
    private class CountingExif : ExifEditor {
        var fileReads = 0
        private val delegate = DesktopExifEditor()

        override fun read(bytes: ByteArray): PhotoMeta? = delegate.read(bytes)

        override fun read(stream: InputStream): PhotoMeta? = delegate.read(stream)

        override fun read(file: File): PhotoMeta? {
            fileReads++
            return delegate.read(file)
        }

        override fun withExif(
            bytes: ByteArray,
            description: String?,
            note: String?,
            setDateTakenIfMissing: Long?,
        ): ByteArray = delegate.withExif(bytes, description, note, setDateTakenIfMissing)

        override fun looksLikeJpeg(bytes: ByteArray): Boolean = delegate.looksLikeJpeg(bytes)
    }

    private fun tempRoot(): File = Files.createTempDirectory("pi-index").toFile()

    private fun jpeg(description: String?, note: String?, date: Long? = null): ByteArray {
        val base = Base64.getDecoder().decode(BARE_JPEG)
        return if (description == null && note == null && date == null) {
            base
        } else {
            DesktopExifEditor().withExif(base, description, note, date)
        }
    }

    private suspend fun backend(root: File): Pair<FileBackend, CountingExif> {
        val exif = CountingExif()
        val backend = FileBackend(root = root, accessCheck = { true }, exif = exif)
        backend.ensureRoot()
        return backend to exif
    }

    private suspend fun populate(root: File) {
        val (backend, _) = backend(root)
        backend.createFolder("", "beta")
        backend.createFolder("", "Alpha")
        backend.createFolder("Alpha", "C")
        backend.setCover("Alpha", jpeg("cover", null))
        backend.writePhotoBytes("Alpha/anvil.jpg", jpeg("Anvil", "heavy"))
        backend.writePhotoBytes("Alpha/wishbone.jpg", jpeg("Wishbone", "has hinge"))
        backend.writePhotoBytes("Alpha/C/lantern.jpg", jpeg(null, null))
        backend.writePhotoBytes("beta/bike.jpg", jpeg("Bike", null))
        val conflict = "Alpha/map.sync-conflict-20260101-010101-XYZ.jpg"
        File(root, conflict).writeBytes(jpeg("Map", null))
    }

    @Test
    fun walksSortsAndCountsTheWholeTree() = runTest {
        val root = tempRoot()
        populate(root)
        val (backend, _) = backend(root)
        val index = DesktopIndex(backend)
        index.rebuild()
        val snapshot = index.snapshot.value

        assertEquals(listOf("Alpha", "beta"), snapshot.containersByParent[""]?.map { it.name })
        assertTrue(snapshot.containersByParent[""]?.first { it.name == "Alpha" }?.hasCover == true)
        assertEquals(3, snapshot.subtreeCounts[""]?.first)
        assertEquals(5, snapshot.subtreeCounts[""]?.second)
        assertEquals(1 to 4, snapshot.subtreeCounts["Alpha"])
        assertEquals(0 to 1, snapshot.subtreeCounts["Alpha/C"])
        assertEquals(0 to 1, snapshot.subtreeCounts["beta"])
        assertEquals(1, snapshot.conflictCount)
    }

    @Test
    fun filtersPhotosHereSubtreeAndByQuery() = runTest {
        val root = tempRoot()
        populate(root)
        val (backend, _) = backend(root)
        val index = DesktopIndex(backend)
        index.rebuild()
        val snapshot = index.snapshot.value

        assertEquals(
            listOf("Alpha/anvil.jpg", "Alpha/map.sync-conflict-20260101-010101-XYZ.jpg", "Alpha/wishbone.jpg"),
            index.photosIn(snapshot, "Alpha", "", includeSubtree = false).map { it.relPath },
        )
        assertEquals(
            listOf("Alpha/anvil.jpg", "Alpha/C/lantern.jpg", "Alpha/map.sync-conflict-20260101-010101-XYZ.jpg", "Alpha/wishbone.jpg"),
            index.photosIn(snapshot, "Alpha", "", includeSubtree = true).map { it.relPath },
        )
        assertEquals(
            listOf("Alpha/wishbone.jpg"),
            index.photosIn(snapshot, "", "hinge", includeSubtree = true).map { it.relPath },
        )
        assertEquals(
            listOf("Alpha/wishbone.jpg"),
            index.searchAll(snapshot, "HINGE").map { it.relPath },
        )
        assertEquals(
            listOf("Alpha/anvil.jpg"),
            index.searchAll(snapshot, "anvil.jpg").map { it.relPath },
        )
    }

    @Test
    fun photoRowFallsBackToFilenameAndFileDate() = runTest {
        val root = tempRoot()
        populate(root)
        val (backend, _) = backend(root)
        val index = DesktopIndex(backend)
        index.rebuild()
        val lantern = index.snapshot.value.allPhotos.first { it.relPath == "Alpha/C/lantern.jpg" }
        // A JPEG with no stamped description reads back as the file's base name.
        assertEquals("lantern", lantern.description)
        assertNotNull(lantern.dateTaken)
    }

    @Test
    fun rebuildSkipsUnchangedExifParses() = runTest {
        val root = tempRoot()
        populate(root)
        val (backend, exif) = backend(root)
        val index = DesktopIndex(backend)
        index.rebuild()
        val firstReads = exif.fileReads
        assertTrue(firstReads >= 5)
        index.rebuild()
        assertEquals(firstReads, exif.fileReads)
        // Only the rewritten file is re-parsed next time.
        backend.writePhotoBytes("beta/bike.jpg", jpeg("Bike fixed", null))
        index.rebuild()
        assertEquals(firstReads + 1, exif.fileReads)
        assertEquals(
            "Bike fixed",
            index.snapshot.value.allPhotos.first { it.relPath == "beta/bike.jpg" }.description,
        )
    }

    @Test
    fun rebuildDropsEntriesThatVanished() = runTest {
        val root = tempRoot()
        populate(root)
        val (backend, exif) = backend(root)
        val index = DesktopIndex(backend)
        index.rebuild()
        File(root, "Alpha/wishbone.jpg").delete()
        File(root, "beta").deleteRecursively()
        index.rebuild()
        val snapshot = index.snapshot.value
        assertNull(snapshot.allPhotos.firstOrNull { it.relPath == "Alpha/wishbone.jpg" })
        assertEquals(listOf("Alpha"), snapshot.containersByParent[""]?.map { it.name })
        assertEquals(3, snapshot.subtreeCounts[""]?.second)
        // Vanished photos leave the parse cache too.
        val readsBefore = exif.fileReads
        index.rebuild()
        assertEquals(readsBefore, exif.fileReads)
    }

    @Test
    fun emptyFoldersStillGetSubtreeCountRows() = runTest {
        val root = tempRoot()
        val (backend, _) = backend(root)
        backend.createFolder("", "hollow")
        val index = DesktopIndex(backend)
        index.rebuild()
        assertEquals(0 to 0, index.snapshot.value.subtreeCounts["hollow"])
        assertEquals(setOf("", "hollow"), index.snapshot.value.folders)
    }

    @Test
    fun watcherFiresOnExternalFileDrops() = runTest {
        val root = tempRoot()
        val fired = java.util.concurrent.CountDownLatch(1)
        val watcher = FolderWatcher(root.toPath()) { fired.countDown() }
        watcher.start()
        try {
            // A Syncthing-style drop from "outside the app".
            File(root, "dropped.jpg").writeBytes(jpeg("Dropped", null))
            assertTrue(fired.await(15, java.util.concurrent.TimeUnit.SECONDS), "watcher never fired")
        } finally {
            watcher.stop()
        }
    }

    companion object {
        /** A bare valid JPEG without EXIF (shared with the selftest). */
        val BARE_JPEG: String =
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
            "FRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
            "FBQUFBQUFBT/wAARCAAMABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF" +
            "BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW" +
            "V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi" +
            "4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC" +
            "AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm" +
            "Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq" +
            "8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDyqiiivij60//Z"
    }
}
