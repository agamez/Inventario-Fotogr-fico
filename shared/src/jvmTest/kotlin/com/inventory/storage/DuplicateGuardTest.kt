package com.inventory.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Tests for [DuplicateGuard] over the real [FileBackend] on a temp tree
 * (EXIF stubbed by [FakeExif]) with a marker-driven [ImageHasher], so the
 * hash comparisons and the threshold are exercised without any real image
 * codec.
 */
class DuplicateGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Hashes by the first payload byte, so tests can name grids cheaply. */
    private class MarkerHasher(private val grids: Map<Int, IntArray>) : ImageHasher {
        override fun grayGrid(bytes: ByteArray): IntArray? =
            if (bytes.isEmpty()) null else grids[bytes[0].toInt()]
    }

    private fun ascending(): IntArray = IntArray(DHash.GRID_SIZE) { it }

    /** Same grid as [ascending] but with exactly one comparison flipped. */
    private fun oneBitDifferent(): IntArray = ascending().also {
        it[0] = 1
        it[1] = 0
    }

    /** The inverse grid: every bit differs (Hamming distance 64). */
    private fun descending(): IntArray = IntArray(DHash.GRID_SIZE) { DHash.GRID_SIZE - 1 - it }

    private fun backend(): FileBackend {
        val root = tmp.newFolder(ROOT_FOLDER_NAME)
        return FileBackend(root = root, accessCheck = { true }, exif = FakeExif())
    }

    private fun guard(
        backend: FileBackend,
        markerGrid: Map<Int, IntArray> = emptyMap(),
    ): DuplicateGuard = DuplicateGuard(backend, MarkerHasher(markerGrid))

    @Test
    fun identicalImageInTheTargetIsAHitWithItsDescription() = runTest {
        val backend = backend()
        backend.createFolder("", "Box")
        val existing = FakeExif.jpeg("Box A", null)
        backend.writePhotoBytes("Box/Box A.jpg", existing)
        val guard = guard(backend, mapOf(existing[0].toInt() to ascending()))

        val hit = guard.findDuplicate("Box", existing)
        assertNotNull(hit)
        assertEquals("Box/Box A.jpg", hit.relPath)
        assertEquals("Box A", hit.description)
    }

    @Test
    fun aLoneCoverIsNeverADuplicate() = runTest {
        val backend = backend()
        backend.createFolder("", "Box")
        val cover = FakeExif.jpeg("Cover", null)
        backend.writePhotoBytes("Box/$COVER_FILE_NAME", cover)
        val guard = guard(backend, mapOf(cover[0].toInt() to ascending()))

        assertNull(guard.findDuplicate("Box", cover))
    }

    @Test
    fun aVeryDifferentImageIsNotADuplicate() = runTest {
        val backend = backend()
        backend.createFolder("", "Box")
        val existing = FakeExif.jpeg("Box A", null)
        backend.writePhotoBytes("Box/Box A.jpg", existing)
        val guard = guard(
            backend,
            mapOf(existing[0].toInt() to ascending(), 1 to descending()),
        )
        val candidate = byteArrayOf(1) // every bit differs: distance 64 > 4

        assertNull(guard.findDuplicate("Box", candidate))
    }

    @Test
    fun oneBitApartIsInsideTheThreshold() = runTest {
        val backend = backend()
        backend.createFolder("", "Box")
        val existing = FakeExif.jpeg("Box A", null)
        backend.writePhotoBytes("Box/Box A.jpg", existing)
        val guard = guard(
            backend,
            mapOf(existing[0].toInt() to ascending(), 2 to oneBitDifferent()),
        )
        val candidate = byteArrayOf(2) // one comparison flipped: distance 1 <= 4

        val hit = guard.findDuplicate("Box", candidate)
        assertNotNull(hit)
        assertEquals("Box/Box A.jpg", hit.relPath)
    }

    @Test
    fun anUndecodableCandidateIsNeverADuplicate() = runTest {
        val backend = backend()
        backend.createFolder("", "Box")
        val existing = FakeExif.jpeg("Box A", null)
        backend.writePhotoBytes("Box/Box A.jpg", existing)
        val guard = guard(backend, mapOf(existing[0].toInt() to ascending()))

        assertNull(guard.findDuplicate("Box", ByteArray(0)))
    }

    @Test
    fun anEmptyTargetFolderHasNoDuplicate() = runTest {
        val backend = backend()
        backend.createFolder("", "Box")
        val guard = guard(backend, mapOf(-1 to ascending()))

        assertNull(guard.findDuplicate("Box", FakeExif.jpeg("Box A", null)))
    }
}
