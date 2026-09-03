package com.inventory.storage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Behaviour tests for the shared [FileBackend] on a plain JVM: listing
 * and exclusion rules, atomic writes, collision-safe folder
 * create/rename/move, recursive delete, cover round-trips and the
 * conflict marker flag. EXIF is provided by the deterministic
 * [FakeExif], so only [FileBackend]'s own logic is under test.
 */
class FileBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun backend(): FileBackend {
        val root = tmp.newFolder(ROOT_FOLDER_NAME)
        assertTrue(root.isDirectory)
        return FileBackend(root = root, accessCheck = { true }, exif = FakeExif())
    }

    @Test
    fun createFolderNestsAndSuffixesCollisions() = runTest {
        val backend = backend()
        assertEquals("A", backend.createFolder("", "A"))
        assertEquals("A/B", backend.createFolder("A", "B"))
        assertEquals("A/B (2)", backend.createFolder("A", "B"))
        assertTrue(File(backend.root, "A/B").isDirectory)
        assertTrue(File(backend.root, "A/B (2)").isDirectory)
    }

    @Test
    fun listContainersReturnsSortedNonHiddenDirectories() = runTest {
        val backend = backend()
        File(backend.root, "beta").mkdirs()
        File(backend.root, "Alpha").mkdirs()
        File(backend.root, ".hidden").mkdirs()
        File(backend.root, "photo.jpg").createNewFile()
        val entries = backend.listContainers("")
        assertEquals(listOf("Alpha", "beta"), entries.map { it.name })
        assertEquals(listOf("Alpha", "beta"), entries.map { it.relPath })
        assertTrue(entries.none { it.isConflict })
    }

    @Test
    fun listPhotosKeepsJpegsAndDropsHiddenCoverAndNonImages() = runTest {
        val backend = backend()
        val photo = FakeExif.jpeg("d", "n")
        File(backend.root, "b.jpeg").writeBytes(photo)
        File(backend.root, "a.JPG").writeBytes(photo)
        File(backend.root, "notes.txt").createNewFile()
        File(backend.root, "_cover.jpg").writeBytes(photo)
        File(backend.root, ".pending-1.jpg").writeBytes(photo)
        val entries = backend.listPhotos("")
        assertEquals(listOf("a.JPG", "b.jpeg"), entries.map { it.name })
        assertTrue(entries.all { it.size == photo.size.toLong() })
    }

    @Test
    fun conflictMarkersAreFlaggedOnListedEntries() = runTest {
        val backend = backend()
        val conflictName = "party.sync-conflict-20260903-120000-ABCD"
        File(backend.root, "$conflictName.jpg").writeBytes(FakeExif.jpeg("d", null))
        File(backend.root, "plain.jpg").writeBytes(FakeExif.jpeg("d", null))
        val photos = backend.listPhotos("").associateBy { it.name }
        assertTrue(photos.getValue("$conflictName.jpg").isConflict)
        assertFalse(photos.getValue("plain.jpg").isConflict)
        // Folder names carry the same marker and are flagged as well.
        assertEquals("Archive", backend.createFolder("", "Archive"))
        assertEquals("Archive (2)", backend.createFolder("", "Archive"))
        assertTrue(isConflictName("snap.sync-conflict-20260903-120000-XYZ"))
        assertFalse(isConflictName("snap-conflict-20260903.jpg"))
    }

    @Test
    fun coverRoundTripWritesCoverFileAndRemovesIt() = runTest {
        val backend = backend()
        backend.createFolder("", "F")
        val bytes = FakeExif.jpeg("cover", null)
        assertFalse(backend.hasCover("F"))
        backend.setCover("F", bytes)
        assertTrue(backend.hasCover("F"))
        assertContentEquals(
            bytes,
            File(backend.root, "F/_cover.jpg").readBytes(),
        )
        assertIs<File>(backend.coverModel("F"))
        // The cover is never listed as a photo.
        assertEquals(emptyList(), backend.listPhotos("F"))
        backend.removeCover("F")
        assertFalse(backend.hasCover("F"))
        assertNull(backend.coverModel("F"))
    }

    @Test
    fun setCoverRejectsEmptyAndNonJpegPayloads() = runTest {
        val backend = backend()
        backend.createFolder("", "F")
        assertFailsWith<IllegalArgumentException> { backend.setCover("F", ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { backend.setCover("F", byteArrayOf(1, 2, 3, 4)) }
        assertFalse(backend.hasCover("F"))
    }

    @Test
    fun writeAndReadPhotoRoundTripsBytesAndMeta() = runTest {
        val backend = backend()
        backend.createFolder("", "F")
        val bytes = FakeExif.jpeg("Sunset at pier", "warm note", 1234567L)
        // A missing parent folder is created by the write itself.
        backend.writePhotoBytes("F/deep/sunset.jpg", bytes)
        assertContentEquals(bytes, backend.readPhotoBytes("F/deep/sunset.jpg"))
        val meta = backend.readPhotoMeta("F/deep/sunset.jpg")
        assertIs<PhotoMeta>(meta)
        assertEquals("Sunset at pier", meta.description)
        assertEquals("warm note", meta.note)
        assertEquals(1234567L, meta.dateTaken)
        // Atomic write leaves no scratch behind, only the final file.
        assertEquals(listOf("sunset.jpg"), File(backend.root, "F/deep").list()?.sorted())
        assertTrue(backend.exists("F/deep/sunset.jpg"))
        val stats = backend.lastModifiedAndSize("F/deep/sunset.jpg")
        assertEquals(bytes.size.toLong(), stats?.second)
        assertIs<File>(backend.photoModel("F/deep/sunset.jpg"))
        assertNull(backend.photoModel("F/deep/gone.jpg"))
    }

    @Test
    fun readingGoneOrUnparsablePhotosYieldsNull() = runTest {
        val backend = backend()
        backend.createFolder("", "F")
        assertNull(backend.readPhotoBytes("F/missing.jpg"))
        assertNull(backend.readPhotoMeta("F/missing.jpg"))
        File(backend.root, "F/junk.jpg").writeBytes(byteArrayOf(1, 2, 3, 4))
        assertNull(backend.readPhotoMeta("F/junk.jpg"))
        assertNull(backend.lastModifiedAndSize("F/missing.jpg"))
        assertNull(backend.documentUriFor("F/junk.jpg"))
    }

    @Test
    fun overwriteReplacesBytesWithoutScratchLeftBehind() = runTest {
        val backend = backend()
        backend.createFolder("", "F")
        backend.writePhotoBytes("F/p.jpg", FakeExif.jpeg("first", null))
        val second = FakeExif.jpeg("second", "note")
        backend.writePhotoBytes("F/p.jpg", second)
        assertContentEquals(second, backend.readPhotoBytes("F/p.jpg"))
        assertEquals(listOf("p.jpg"), File(backend.root, "F").list()?.sorted())
    }

    @Test
    fun moveFolderRelocatesAndFailsOnMissingParent() = runTest {
        val backend = backend()
        backend.createFolder("", "A")
        backend.createFolder("A", "sub")
        backend.createFolder("", "B")
        assertTrue(backend.moveFolder("A/sub", "B"))
        assertTrue(backend.exists("B/sub"))
        assertFalse(backend.exists("A/sub"))
        // A nonexistent target parent moves nothing.
        assertFalse(backend.moveFolder("B/sub", "Ghost"))
        assertTrue(backend.exists("B/sub"))
        // A vanished source cannot move either.
        assertFalse(backend.moveFolder("Gone", "B"))
    }

    @Test
    fun renameFolderIsCollisionSafeAndANoOpForSameName() = runTest {
        val backend = backend()
        backend.createFolder("", "Alpha")
        backend.createFolder("", "Beta")
        assertEquals("Gamma", backend.renameFolder("Alpha", "Gamma"))
        assertEquals("Gamma (2)", backend.renameFolder("Beta", "Gamma (2)"))
        // Renaming to its own name keeps identity.
        assertEquals("Gamma (2)", backend.renameFolder("Gamma (2)", "Gamma (2)"))
        assertEquals("Gamma", backend.renameFolder("Gamma", "Gamma"))
    }

    @Test
    fun deleteFolderRecursiveRemovesTheWholeSubtree() = runTest {
        val backend = backend()
        backend.createFolder("", "Top")
        backend.createFolder("Top", "nested")
        backend.writePhotoBytes("Top/x.jpg", FakeExif.jpeg("x", null))
        backend.writePhotoBytes("Top/nested/y.jpg", FakeExif.jpeg("y", null))
        File(backend.root, "Top/nested/_cover.jpg").writeBytes(FakeExif.jpeg(null, null))
        backend.deleteFolderRecursive("Top")
        assertFalse(File(backend.root, "Top").exists())
    }

    @Test
    fun deleteFileRemovesAndToleratesVanishedFiles() = runTest {
        val backend = backend()
        backend.createFolder("", "F")
        backend.writePhotoBytes("F/p.jpg", FakeExif.jpeg("p", null))
        assertTrue(backend.deleteFile("F/p.jpg"))
        assertFalse(backend.exists("F/p.jpg"))
        // A vanished file is a no-op that reports success.
        assertTrue(backend.deleteFile("F/p.jpg"))
    }

    @Test
    fun ensureRootCreatesTheFolderAndReportsAccess() = runTest {
        val root = File(tmp.root, "Fresh")
        val backend = FileBackend(root = root, accessCheck = { true }, exif = FakeExif())
        assertTrue(backend.hasAccess())
        assertTrue(backend.ensureRoot())
        assertTrue(root.isDirectory)
        val denied = FileBackend(root = root, accessCheck = { false }, exif = FakeExif())
        assertFalse(denied.hasAccess())
        assertFalse(denied.ensureRoot())
    }
}
