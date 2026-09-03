package com.inventory.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for the shared filename rules: [Filenames.sanitize] (illegal
 * characters, control characters, whitespace runs, dot/space trimming,
 * the byte cap with code-point-safe truncation and the Untitled
 * fallback) plus the collision-suffix generators [Filenames.uniqueName]
 * and [Filenames.uniqueNameIn].
 */
class FilenamesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun illegalCharactersBecomeUnderscores() {
        assertEquals(
            "a_b_c_d_e_f_g_h_i_j",
            Filenames.sanitize("""a\b/c:d*e?f"g<h>i|j"""),
        )
    }

    @Test
    fun controlCharactersAreStripped() {
        val raw = "a" + Char(0x00) + "b" + Char(0x01) + "c" + Char(0x1F) + "d"
        assertEquals("abcd", Filenames.sanitize(raw))
    }

    @Test
    fun whitespaceRunsCollapseToASingleSpace() {
        // Control characters (tab, newline) are dropped first; the space
        // runs that remain around them then collapse to a single space.
        val raw = "two" + Char(0x20) + Char(0x20) + "spaces" +
            Char(0x09) + Char(0x20) + Char(0x0A) + Char(0x20) + "and tabs"
        assertEquals("two spaces and tabs", Filenames.sanitize(raw))
    }

    @Test
    fun leadingAndTrailingDotsAndSpacesAreTrimmed() {
        assertEquals("Hello world", Filenames.sanitize("  ..Hello world..  "))
        assertEquals("name", Filenames.sanitize("...name"))
    }

    @Test
    fun nothingUsableFallsBackToUntitled() {
        assertEquals("Untitled", Filenames.sanitize(""))
        assertEquals("Untitled", Filenames.sanitize("     "))
        assertEquals("Untitled", Filenames.sanitize("... .. ."))
        assertEquals(Filenames.UNTITLED, Filenames.sanitize(".\u0001."))
    }

    @Test
    fun longAsciiNamesAreCappedForTheLongestCollisionSuffix() {
        val capped = Filenames.sanitize("x".repeat(400))
        assertEquals(239, capped.length)
        assertEquals("x".repeat(239), capped)
    }

    @Test
    fun multibyteTruncationNeverSplitsACodePoint() {
        val capped = Filenames.sanitize("é".repeat(250))
        assertTrue(capped.toByteArray(Charsets.UTF_8).size <= 239)
        assertEquals("é".repeat(119), capped)
    }

    @Test
    fun truncationNeverLeavesATrailingDotOrSpace() {
        val capped = Filenames.sanitize("a" + "é".repeat(120) + " .")
        assertTrue(capped.none { it == '.' || it == ' ' })
    }

    @Test
    fun uniqueNameGrowsCollisionSuffixes() {
        val folder = tmp.newFolder()
        assertTrue(folder.resolve("photo.jpg").createNewFile())
        assertEquals("photo (2).jpg", Filenames.uniqueName(folder, "photo", "jpg"))
        assertTrue(folder.resolve("photo (2).jpg").createNewFile())
        assertEquals("photo (3).jpg", Filenames.uniqueName(folder, "photo", "jpg"))
    }

    @Test
    fun uniqueNameIsCaseInsensitive() {
        val folder = tmp.newFolder()
        assertTrue(folder.resolve("Photo.JPG").createNewFile())
        assertEquals("photo (2).jpg", Filenames.uniqueName(folder, "photo", "jpg"))
    }

    @Test
    fun uniqueNameIgnoringNeverCollidesWithItself() {
        val taken = setOf("photo.jpg", "a.jpg")
        assertEquals("photo.jpg", Filenames.uniqueNameIn(taken, "photo", "jpg", ignoring = "PHOTO.JPG"))
        assertEquals("photo (2).jpg", Filenames.uniqueNameIn(taken, "photo", "jpg", ignoring = "a.jpg"))
    }

    @Test
    fun folderNamesCollideWithoutAnExtension() {
        assertEquals("Notes (2)", Filenames.uniqueNameIn(setOf("Notes"), "Notes", ""))
    }
}
