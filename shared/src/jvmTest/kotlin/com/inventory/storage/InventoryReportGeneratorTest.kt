package com.inventory.storage

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Behaviour tests for [InventoryReportGenerator] over the real [FileBackend]
 * on a plain JVM (EXIF is the deterministic [FakeExif]). Covers the two
 * documents landing in the inventory root, the deterministic ordering, the
 * HTML escaping / per-segment URL-encoding of image paths, the filename
 * fallback for an absent EXIF description, and RFC-4180 CSV quoting that
 * survives a comma, quote and newline round-trip.
 */
class InventoryReportGeneratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun backend(): FileBackend {
        val root = tmp.newFolder(ROOT_FOLDER_NAME)
        return FileBackend(root = root, accessCheck = { true }, exif = FakeExif())
    }

    private fun millis(year: Int, month: Int, day: Int): Long =
        java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    private fun expectedDate(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))

    @Test
    fun generateWritesBothDocumentsWithTheExpectedContentAndOrdering() = runTest {
        val backend = backend()
        val shelfA = "Building/Storage unit/Shelf A"
        val newPhoto = millis(2026, 3, 4)
        val oldPhoto = millis(2026, 1, 2)
        val plainPhoto = millis(2026, 2, 1)
        val oddDescription = "Desc <b> with, \"quotes\" & <tag>"
        val oddNote = "first\nsecond"

        backend.createFolder("", "Building")
        backend.createFolder("Building", "Storage unit")
        backend.createFolder("Building/Storage unit", "Shelf A")
        backend.setCover("Building/Storage unit", FakeExif.jpeg("cover", null))
        backend.writePhotoBytes("$shelfA/Widget 1.jpg", FakeExif.jpeg("Widget 1", "top shelf", oldPhoto))
        backend.writePhotoBytes("$shelfA/Widget 2.jpg", FakeExif.jpeg("Widget 2", null, newPhoto))
        backend.writePhotoBytes("$shelfA/Odd name.jpg", FakeExif.jpeg(oddDescription, oddNote, null))
        backend.writePhotoBytes("$shelfA/Plain photo.jpg", FakeExif.jpeg(null, null, plainPhoto))

        val files = InventoryReportGenerator(backend).generate()

        // 1. Both files are on disk in the inventory root.
        val htmlFile = File(backend.root, files.htmlRelPath)
        val csvFile = File(backend.root, files.csvRelPath)
        assertEquals("inventory-report.html", files.htmlRelPath)
        assertEquals("inventory-report.csv", files.csvRelPath)
        assertTrue(htmlFile.isFile, "HTML report missing at ${htmlFile.absolutePath}")
        assertTrue(csvFile.isFile, "CSV report missing at ${csvFile.absolutePath}")
        assertTrue(File(backend.root, "Building/Storage unit/inventory-report.html").exists() == false)

        // The report files are invisible to the app: the listings only match
        // directories and .jpg/.jpeg entries.
        assertFalse(backend.listContainers("").any { it.name == files.htmlRelPath })
        assertFalse(backend.listPhotos("").any { it.name == files.htmlRelPath })
        assertFalse(backend.listPhotos("").any { it.name == files.csvRelPath })

        val html = htmlFile.readText()
        // 2. Heading, container paths, generation timestamp.
        assertTrue(html.contains("<h1>Photo Inventory</h1>"), html)
        assertTrue(html.contains("Generated "), html)
        assertTrue(html.contains("<h2>Building</h2>"), html)
        assertTrue(html.contains("<h2>Building/Storage unit</h2>"), html)
        assertTrue(html.contains("<h2>Building/Storage unit/Shelf A</h2>"), html)

        // 3. Cover image + photo thumbnails use relative, URL-encoded paths.
        assertTrue(html.contains("src=\"Building/Storage%20unit/_cover.jpg\""), html)
        assertTrue(
            html.contains("src=\"Building/Storage%20unit/Shelf%20A/Widget%201.jpg\""),
            html,
        )

        // 4. User data is HTML-escaped, and a blank EXIF description falls back to the file name.
        assertTrue(
            html.contains("Desc &lt;b&gt; with, &quot;quotes&quot; &amp; &lt;tag&gt;"),
            html,
        )
        assertTrue(html.contains("<td class=\"desc\">Plain photo</td>"), html)

        // 5. Photos are date-descending: Widget 2 (March) before Widget 1 (January).
        assertTrue(
            html.indexOf("Widget 2</td>") < html.indexOf("Widget 1</td>"),
            "photo ordering not date-descending:\n$html",
        )

        // 6. CSV: header + flat rows with RFC-4180 escaping.
        val csv = csvFile.readText()
        assertTrue(csv.startsWith("containerPath,fileName,description,note,dateTaken\r\n"), csv)
        val rows = parseCsv(csv)
        assertEquals(
            listOf("containerPath", "fileName", "description", "note", "dateTaken"),
            rows.first(),
        )
        val body = rows.drop(1)
        assertEquals(4, body.size, "unexpected CSV body: $body")
        val names = body.map { it[1] }
        // Date-descending among the EXIF-dated photos: Widget 2 (Mar),
        // Plain photo (Feb), Widget 1 (Jan); Odd name has no EXIF date and
        // falls back to its last-modified time exactly like the scanners.
        assertTrue(names.indexOf("Widget 2.jpg") < names.indexOf("Plain photo.jpg"), "got $names")
        assertTrue(names.indexOf("Plain photo.jpg") < names.indexOf("Widget 1.jpg"), "got $names")
        assertTrue(names.contains("Odd name.jpg"), "got $names")

        val widget1 = body.first { it[1] == "Widget 1.jpg" }
        assertEquals(shelfA, widget1[0])
        assertEquals("Widget 1", widget1[2])
        assertEquals("top shelf", widget1[3])
        assertEquals(expectedDate(oldPhoto), widget1[4])

        val plain = body.first { it[1] == "Plain photo.jpg" }
        assertEquals("Plain photo", plain[2])
        assertEquals("", plain[3])
        assertEquals(expectedDate(plainPhoto), plain[4])

        // The comma+quote description and the newline note round-trip intact.
        val odd = body.first { it[1] == "Odd name.jpg" }
        assertEquals(oddDescription, odd[2])
        assertEquals(oddNote, odd[3])
        assertEquals("", odd[4])
    }

    /** Minimal RFC-4180 reader for the test's own assertions. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                inQuotes && character == '"' &&
                    index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                inQuotes && character == '"' -> inQuotes = false
                inQuotes -> field.append(character)
                character == '"' -> inQuotes = true
                character == ',' -> {
                    row += field.toString()
                    field.clear()
                }
                character == '\r' -> Unit
                character == '\n' -> {
                    row += field.toString()
                    field.clear()
                    rows += row.toList()
                    row.clear()
                }
                else -> field.append(character)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row.toList()
        }
        return rows
    }
}
