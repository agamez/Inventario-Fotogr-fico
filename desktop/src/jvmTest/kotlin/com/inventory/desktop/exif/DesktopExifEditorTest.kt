package com.inventory.desktop.exif

import com.inventory.storage.PhotoMeta
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants

/**
 * Round-trip and byte-contract tests for the desktop [DesktopExifEditor].
 * The fixtures are real-world payloads embedded as base64: one written by
 * the ANDROID app through androidx ExifInterface (the convention we must
 * mirror and stay readable against), one with an "ASCII\0\0\0"-prefixed
 * UserComment as produced by camera tools (read tolerance), and one bare
 * JPEG without any EXIF (tag creation).
 */
class DesktopExifEditorTest {

    private val editor = DesktopExifEditor()

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    /** Second-resolution EXIF stamps cannot survive sub-second millis. */
    private fun stampMillis(pattern: String): Long =
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(pattern)!!.time

    @Test
    fun readsTheTripleFromAnAndroidWrittenFile() {
        val meta: PhotoMeta = editor.read(decode(ANDROID_WRITTEN_JPEG)).assertNotNull()
        assertEquals("Winter jackets x2", meta.description)
        assertEquals("check hinges", meta.note)
        assertEquals(stampMillis("2026:09:03 14:00:00"), meta.dateTaken)
    }

    @Test
    fun stripsCharsetQueryPrefixesOnRead() {
        val meta: PhotoMeta = editor.read(decode(PREFIXED_JPEG)).assertNotNull()
        assertEquals("prefixed note", meta.note)
    }

    @Test
    fun createsAllThreeTagsOnAnExifLessJpeg() {
        val millis = stampMillis("2026:09:03 10:11:12")
        val stamped = editor.withExif(decode(PLAIN_JPEG), "Alpha item", "has hinge", millis)
        val meta: PhotoMeta = editor.read(stamped).assertNotNull()
        assertEquals("Alpha item", meta.description)
        assertEquals("has hinge", meta.note)
        assertEquals(millis, meta.dateTaken)
    }

    @Test
    fun overwritesExistingTagsAndRemovesTheNoteOnNull() {
        val original = decode(ANDROID_WRITTEN_JPEG)
        val stamped = editor.withExif(original, "New name", null, null)
        val meta: PhotoMeta = editor.read(stamped).assertNotNull()
        assertEquals("New name", meta.description)
        assertNull(meta.note)
        // The taken date was already present: setDateTakenIfMissing must not disturb it.
        assertEquals(stampMillis("2026:09:03 14:00:00"), meta.dateTaken)
    }

    @Test
    fun keepsExistingDateWhenSetDateTakenIfMissingIsGiven() {
        val millis = stampMillis("2001:02:03 04:05:06")
        val stamped = editor.withExif(decode(ANDROID_WRITTEN_JPEG), "Winter jackets x2", null, millis)
        val meta: PhotoMeta = editor.read(stamped).assertNotNull()
        assertEquals(stampMillis("2026:09:03 14:00:00"), meta.dateTaken)
    }

    @Test
    fun preservesOtherTagsAndPixelDataThroughTheRewrite() {
        val original = decode(ANDROID_WRITTEN_JPEG)
        val stamped = editor.withExif(original, "Renamed", "kept", stampMillis("2026:09:03 14:00:00"))
        // Orientation (274), DateTime (306) and ExposureBias (37384) come from the
        // fixture and must survive: Commons Imaging copies every non-APP1 segment
        // byte-identically and rebuilds the TIFF directories from the kept set.
        val metadata = Imaging.getMetadata(stamped) as JpegImageMetadata
        val outputSet = metadata.exif!!.outputSet
        assertNotNull(outputSet.getOrCreateRootDirectory().findField(TiffTagConstants.TIFF_TAG_DATE_TIME))
        assertNotNull(metadata.exif.findField(TiffTagConstants.TIFF_TAG_ORIENTATION))
        assertContentEquals(scanData(original), scanData(stamped))
    }

    @Test
    fun richCameraRewriteReplacesTheTripleAndKeepsEverythingElse() {
        // The real 60KB Google-camera fixture (Model, Orientation, ShutterSpeed,
        // ExposureTime, FNumber, MakerNote …): the desktop EXIF editor must
        // rewrite ONLY the three tags and leave the rest — and the pixels —
        // byte-identical (this is the same file Pillow was run against by hand).
        val original = javaClass.getResourceAsStream("/rich_camera.jpg")!!.readBytes()
        val stamped = editor.withExif(original, "Roof tiles repaired", null, null)
        val before = Imaging.getMetadata(original) as JpegImageMetadata
        val after = Imaging.getMetadata(stamped) as JpegImageMetadata

        // The triple: description changed, note REMOVED (was "attic only"),
        // DateTimeOriginal already present so setDateTakenIfMissing=null keeps it.
        val meta = editor.read(stamped)
        assertNotNull(meta)
        assertEquals("Roof tiles repaired", meta.description)
        assertNull(meta.note)
        assertEquals(stampMillis("2026:09:03 14:01:33"), meta.dateTaken)

        // Untouched camera tags survive (Model + Orientation + a shutter/AEB field).
        assertNotNull(after.exif.findField(TiffTagConstants.TIFF_TAG_MODEL))
        assertNotNull(after.exif.findField(TiffTagConstants.TIFF_TAG_ORIENTATION))
        assertNotNull(after.exif.findField(ExifTagConstants.EXIF_TAG_SHUTTER_SPEED_VALUE))
        // And the UserComment is now truly gone from the output set.
        assertNotNull(before.exif.findField(ExifTagConstants.EXIF_TAG_USER_COMMENT))
        assertNull(after.exif.findField(ExifTagConstants.EXIF_TAG_USER_COMMENT))

        // Pixels byte-identical: the entropy-coded scan is unchanged.
        assertContentEquals(scanData(original), scanData(stamped))
    }

    @Test
    fun writesUserCommentUnprefixedAndNullTerminatedLikeAndroid() {
        val stamped = editor.withExif(decode(PLAIN_JPEG), "Alpha item", "has hinge", null)
        val haystack = String(stamped, StandardCharsets.ISO_8859_1)
        assertTrue(haystack.contains("has hinge"))
        assertTrue(!haystack.contains("ASCII\u0000\u0000\u0000"))
        val at = stamped.indexOfBytes("has hinge".toByteArray(StandardCharsets.UTF_8))
        assertTrue(stamped[at + "has hinge".length] == 0.toByte())
        // And Android's own read of the same convention works back:
        val meta: PhotoMeta = editor.read(stamped).assertNotNull()
        assertEquals("has hinge", meta.note)
    }

    @Test
    fun streamContractReadsAllBytesThenParses() {
        val bytes = decode(ANDROID_WRITTEN_JPEG)
        val meta = editor.read(ByteArrayInputStream(bytes))
        assertEquals("Winter jackets x2", meta?.description)
    }

    @Test
    fun nonJpegPayloadsYieldNullReadsAndThrowingWrites() {
        assertNull(editor.read(byteArrayOf(1, 2, 3, 4, 5)))
        assertFailsWith<IOException> { editor.withExif(byteArrayOf(1, 2, 3), "x", null, null) }
        assertTrue(!editor.looksLikeJpeg(byteArrayOf(1, 2, 3)))
        assertTrue(editor.looksLikeJpeg(decode(PLAIN_JPEG)))
    }

    @Test
    fun doubleRewriteIsStable() {
        val first = editor.withExif(decode(PLAIN_JPEG), "A", "B", stampMillis("2026:01:01 01:01:01"))
        val second = editor.withExif(first, "A", "B", stampMillis("2099:01:01 01:01:01"))
        assertEquals(editor.read(first), editor.read(second))
        assertTrue(second.size > ANDROID_WRITTEN_JPEG.length / 2)
    }

    private fun <T> T?.assertNotNull(): T {
        assertNotNull(this)
        return this
    }

    /** The bytes between the SOS marker payload start and the EOI marker. */
    private fun scanData(bytes: ByteArray): ByteArray {
        val sos = bytes.indexOfBytes(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        val eoi = bytes.indexOfBytes(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return bytes.copyOfRange(sos, eoi)
    }

    private fun ByteArray.indexOfBytes(needle: ByteArray): Int {
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        error("needle not found")
    }

    /** Silence the unused-import checker for ByteArrayOutputStream in some IDEs. */
    @Suppress("unused")
    private val keep = ByteArrayOutputStream()

    companion object {
        /** A bare 12x16 JPEG without any EXIF segment (tag-creation fixture). */
        const val PLAIN_JPEG: String =
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
            "FRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
            "FBQUFBQUFBT/wAARCAAMABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF" +
            "BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW" +
            "V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi" +
            "4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC" +
            "AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm" +
            "Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq" +
            "8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDyqiiivij60//Z"

        /**
         * A photo written by the ANDROID app through androidx ExifInterface
         * (description, unprefixed ASCII UserComment "check hinges",
         * DateTimeOriginal + DateTime, orientation, AEB): the byte
         * convention this editor must mirror.
         */
        const val ANDROID_WRITTEN_JPEG: String =
            "/9j/4QDzRXhpZgAATU0AKgAAAAgABgEAAAQAAAABAAAAAQEBAAQAAAABAAAAAQEOAAIAAAASAAAAVodpAAQAAAABAAAAfAESAAQA" +
            "AAABAAAAAAEyAAIAAAAUAAAAaAAAAABXaW50ZXIgamFja2V0cyB4MgAyMDI2OjA5OjAzIDE0OjAwOjAwAAADkAMAAgAAABQAAACm" +
            "koYAAgAAAA0AAAC6kggABAAAAAEAAAAAAAAAADIwMjY6MDk6MDMgMTQ6MDA6MDAAY2hlY2sgaGluZ2VzAAABAQ4AAgAAABIAAADZ" +
            "AAAAAFdpbnRlciBqYWNrZXRzIHgyAP/gABBKRklGAAEBAAABAAEAAP/bAEMAAgEBAQEBAgEBAQICAgICBAMCAgICBQQEAwQGBQYG" +
            "BgUGBgYHCQgGBwkHBgYICwgJCgoKCgoGCAsMCwoMCQoKCv/bAEMBAgICAgICBQMDBQoHBgcKCgoKCgoKCgoKCgoKCgoKCgoKCgoK" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCv/AABEIAAEAAQMBIgACEQEDEQH/xAAVAAEBAAAAAAAAAAAAAAAAAAAAB//EABQQ" +
            "AQAAAAAAAAAAAAAAAAAAAAD/xAAUAQEAAAAAAAAAAAAAAAAAAAAJ/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8A" +
            "kYBUDWP/2Q=="

        /** A camera-style file whose UserComment carries the "ASCII\0\0\0" code. */
        const val PREFIXED_JPEG: String =
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QCWRXhpZgAATU0AKgAAAAgABQEOAAIAAAALAAAASgEPAAIAAAAIAAAAVgEQAAIAAAAIAAAA" +
            "XgESAAMAAAABAAEAAIdpAAQAAAABAAAAZgAAAABVbmkgY2FmPyA/AABUZXN0Q2FtAFByb3RvWDEAAAGShgABAAAAFQAAAHgAAAAA" +
            "QVNDSUkAAABwcmVmaXhlZCBub3RlAP/bAEMAAwICAwICAwMDAwQDAwQFCAUFBAQFCgcHBggMCgwMCwoLCw0OEhANDhEOCwsQFhAR" +
            "ExQVFRUMDxcYFhQYEhQVFP/bAEMBAwQEBQQFCQUFCRQNCw0UFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
            "FBQUFBQUFBQUFP/AABEIAAwAEAMBIgACEQEDEQH/xAAfAAABBQEBAQEBAQAAAAAAAAAAAQIDBAUGBwgJCgv/xAC1EAACAQMDAgQD" +
            "BQUEBAAAAX0BAgMABBEFEiExQQYTUWEHInEUMoGRoQgjQrHBFVLR8CQzYnKCCQoWFxgZGiUmJygpKjQ1Njc4OTpDREVGR0hJSlNU" +
            "VVZXWFlaY2RlZmdoaWpzdHV2d3h5eoOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna" +
            "4eLj5OXm5+jp6vHy8/T19vf4+fr/xAAfAQADAQEBAQEBAQEBAAAAAAAAAQIDBAUGBwgJCgv/xAC1EQACAQIEBAMEBwUEBAABAncA" +
            "AQIDEQQFITEGEkFRB2FxEyIygQgUQpGhscEJIzNS8BVictEKFiQ04SXxFxgZGiYnKCkqNTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNk" +
            "ZWZnaGlqc3R1dnd4eXqCg4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2dri4+Tl5ufo" +
            "6ery8/T19vf4+fr/2gAMAwEAAhEDEQA/APKqKKK+KPrT/9k="
    }
}
