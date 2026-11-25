package com.inventory.storage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Tests for [ItemMarker]'s pure parsing/classification/serialization plus
 * the [StorageBackend.readAuxFile] round-trip it relies on, exercised over
 * the real [FileBackend] on a temp tree (EXIF stubbed by [FakeExif]).
 *
 * The contract under test is the domain rule from
 * `docs/ideas-multi-photo-items.md`: only a readable `_item.json` with
 * `type` "item" makes a folder an item; absent, corrupt or foreign markers
 * leave the folder a normal container and never abort a scan.
 */
class ItemMarkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun backend(): FileBackend {
        val root = tmp.newFolder(ROOT_FOLDER_NAME)
        assertTrue(root.isDirectory)
        return FileBackend(root = root, accessCheck = { true }, exif = FakeExif())
    }

    private fun markerText(backend: FileBackend, folder: String): String =
        File(backend.root, "$folder/$ITEM_MARKER").readText(Charsets.UTF_8)

    private fun writeMarker(backend: FileBackend, folder: String, content: String) {
        File(backend.root, folder).mkdirs()
        File(backend.root, "$folder/$ITEM_MARKER").writeText(content, Charsets.UTF_8)
    }

    @Test
    fun absentMarkerIsNotAnItem() = runTest {
        val backend = backend()
        backend.createFolder("", "Caja A")
        assertNull(backend.readAuxFile("Caja A/$ITEM_MARKER"))
        assertNull(ItemMarker.parse(null))
        assertNull(ItemMarker.parse(ByteArray(0)))
        assertNull(ItemMarker.parseText(null))
        assertNull(ItemMarker.parseText(""))
        assertNull(ItemMarker.parseText("   "))
        assertFalse(ItemMarker.isItemMarker(null))
        assertFalse(ItemMarker.isItem(null))
        assertFalse(backend.isItemFolder("Caja A"))
    }

    @Test
    fun validMarkerRoundTripsThroughCanonicalBytes() = runTest {
        val backend = backend()
        backend.createFolder("", "Caja A")
        backend.createFolder("Caja A", "tornillo")
        val bytes = ItemMarker.writeItemMarker()
        assertContentEquals(
            """{"type":"item","v":1}""".toByteArray(Charsets.UTF_8),
            bytes,
        )
        backend.writePhotoBytes("Caja A/tornillo/$ITEM_MARKER", bytes)
        assertContentEquals(bytes, backend.readAuxFile("Caja A/tornillo/$ITEM_MARKER"))
        val parsed = ItemMarker.parse(backend.readAuxFile("Caja A/tornillo/$ITEM_MARKER"))
        assertEquals(ItemMarkerData(type = ITEM_MARKER_TYPE, v = ITEM_MARKER_VERSION, note = null), parsed)
        assertTrue(ItemMarker.isItem(parsed))
        assertTrue(backend.isItemFolder("Caja A/tornillo"))
        // A sibling folder without the marker stays a normal container.
        assertFalse(backend.isItemFolder("Caja A"))
    }

    @Test
    fun noteIsOmittedWhenAbsentAndPreservedWhenPresent() = runTest {
        val backend = backend()
        backend.createFolder("", "tornillo")
        backend.writePhotoBytes("tornillo/$ITEM_MARKER", ItemMarker.writeItemMarker())
        assertFalse(markerText(backend, "tornillo").contains("note"))

        val withNote = ItemMarker.writeItemMarker("tornillos de acero")
        backend.writePhotoBytes("tornillo/$ITEM_MARKER", withNote)
        assertEquals(
            """{"type":"item","v":1,"note":"tornillos de acero"}""",
            markerText(backend, "tornillo"),
        )
        assertEquals("tornillos de acero", ItemMarker.parse(withNote)?.note)
        // Empty note is preserved verbatim (an explicit, cleared note).
        assertEquals("", ItemMarker.parse(ItemMarker.writeItemMarker(""))?.note)
    }

    @Test
    fun utf8NoteRoundTripsByteIdentically() = runTest {
        val backend = backend()
        backend.createFolder("", "item")
        val note = "nota ñ 测试"
        val bytes = ItemMarker.writeItemMarker(note)
        backend.writePhotoBytes("item/$ITEM_MARKER", bytes)
        assertContentEquals(bytes, backend.readAuxFile("item/$ITEM_MARKER"))
        assertEquals(note, ItemMarker.parseText(markerText(backend, "item"))?.note)
        assertTrue(backend.isItemFolder("item"))
    }

    @Test
    fun jsonSpecialCharactersInTheNoteRoundTrip() {
        val note = "linea 1\nlinea 2 \"citada\" \\ y tab\tfinal"
        val marker = ItemMarker.parse(ItemMarker.writeItemMarker(note))
        assertEquals(note, marker?.note)
        assertTrue(ItemMarker.isItem(marker))
    }

    @Test
    fun corruptJsonIsNotAnItem() = runTest {
        val corrupt = listOf(
            "no soy json",
            "{",
            "[]",
            "null",
            "\"item\"",
            "[{\"type\":\"item\",\"v\":1}]",
            "{}",
            """{"type":"item"}""",
            """{"v":1}""",
            """{"type":1,"v":1}""",
            """{"type":"item","v":true}""",
            """{"type":"item","v":"1"}""",
            """{"type":"item","v":1.5}""",
            """{"type":"item","v":1,"note":42}""",
            """{"type":"item","v":1,"note":["a"]}""",
        )
        val backend = backend()
        backend.createFolder("", "Caja A")
        backend.createFolder("Caja A", "roto")
        for (content in corrupt) {
            writeMarker(backend, "Caja A/roto", content)
            assertNull(ItemMarker.parseText(content), "should not parse: $content")
            assertNull(ItemMarker.parse(content.toByteArray(Charsets.UTF_8)), "should not parse: $content")
            assertFalse(ItemMarker.isItemMarker(content.toByteArray(Charsets.UTF_8)), "should not be an item: $content")
        }
        assertFalse(backend.isItemFolder("Caja A/roto"))
    }

    @Test
    fun nonUtf8BytesAreNotAnItem() = runTest {
        val backend = backend()
        backend.createFolder("", "binario")
        val bytes = byteArrayOf(0xC3.toByte(), 0x28, 0x7B, 0x7D)
        File(backend.root, "binario/$ITEM_MARKER").writeBytes(bytes)
        assertNull(ItemMarker.parse(bytes))
        assertFalse(backend.isItemFolder("binario"))
    }

    @Test
    fun unknownTypeIsIgnoredAndFolderStaysAContainer() = runTest {
        val backend = backend()
        backend.createFolder("", "Caja A")
        backend.createFolder("Caja A", "raro")
        writeMarker(backend, "Caja A/raro", """{"type":"box","v":1}""")
        val parsed = ItemMarker.parseText("""{"type":"box","v":1}""")
        // Parsing is structural; classification is what rejects the type.
        assertEquals("box", parsed?.type)
        assertFalse(ItemMarker.isItem(parsed))
        assertFalse(backend.isItemFolder("Caja A/raro"))
        assertFalse(ItemMarker.isItemMarker("""{"type":"ITEM","v":1}""".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun knownTypeWithFutureVersionStillCountsAsAnItem() {
        assertTrue(ItemMarker.isItemMarker("""{"type":"item","v":2}""".toByteArray(Charsets.UTF_8)))
        assertTrue(ItemMarker.isItemMarker("""{"type":"item","v":1,"extra":"ignored"}""".toByteArray(Charsets.UTF_8)))
        assertFalse(ItemMarker.isItemMarker("""{"type":"item","v":0}""".toByteArray(Charsets.UTF_8)))
        // JSON null means "no note", exactly like omitting the key.
        val marker = ItemMarker.parseText("""{"type":"item","v":1,"note":null}""")
        assertEquals(ItemMarkerData(type = ITEM_MARKER_TYPE, v = 1, note = null), marker)
    }

    @Test
    fun markerIsNeverListedAsPhotoOrContainer() = runTest {
        val backend = backend()
        backend.createFolder("", "Caja A")
        backend.createFolder("Caja A", "tornillo")
        backend.writePhotoBytes("Caja A/tornillo/$ITEM_MARKER", ItemMarker.writeItemMarker())
        backend.writePhotoBytes("Caja A/tornillo/tornillo_1.jpg", FakeExif.jpeg("tornillo", null))
        backend.writePhotoBytes("Caja A/tornillo/tornillo_2.jpg", FakeExif.jpeg("tornillo", null))
        assertEquals(listOf("tornillo_1.jpg", "tornillo_2.jpg"), backend.listPhotos("Caja A/tornillo").map { it.name })
        assertEquals(emptyList(), backend.listContainers("Caja A/tornillo").map { it.name })
        assertTrue(backend.isItemFolder("Caja A/tornillo"))
    }

    @Test
    fun nestedMarkerDoesNotChangeTheEnclosingItemClassification() = runTest {
        val backend = backend()
        backend.createFolder("", "Caja A")
        backend.createFolder("Caja A", "tornillo")
        backend.createFolder("Caja A/tornillo", "sospechoso")
        backend.writePhotoBytes("Caja A/tornillo/$ITEM_MARKER", ItemMarker.writeItemMarker())
        // Rule 4: items never nest. The inner marker reads as a marker too, but
        // the outer item's own classification and listings are unaffected; the
        // scanner (later phase) simply does not descend into an item.
        backend.writePhotoBytes("Caja A/tornillo/sospechoso/$ITEM_MARKER", ItemMarker.writeItemMarker())
        assertTrue(backend.isItemFolder("Caja A/tornillo"))
        assertTrue(backend.isItemFolder("Caja A/tornillo/sospechoso"))
        assertEquals(listOf("sospechoso"), backend.listContainers("Caja A/tornillo").map { it.name })
        assertEquals(emptyList(), backend.listPhotos("Caja A/tornillo").map { it.name })
    }

    @Test
    fun readAuxFileReturnsRawBytesAndNullWhenMissing() = runTest {
        val backend = backend()
        backend.createFolder("", "Caja A")
        val payload = ItemMarker.writeItemMarker("nota")
        backend.writePhotoBytes("Caja A/$ITEM_MARKER", payload)
        assertContentEquals(payload, backend.readAuxFile("Caja A/$ITEM_MARKER"))
        assertNull(backend.readAuxFile("Caja A/otro.json"))
        assertNull(backend.readAuxFile("Caja A"))
        assertNull(backend.readAuxFile(""))
        // Deleting the marker degrades the folder back to a container.
        assertTrue(backend.deleteFile("Caja A/$ITEM_MARKER"))
        assertFalse(backend.isItemFolder("Caja A"))
    }
}
