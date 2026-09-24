package com.inventory.storage

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds a human-readable, printable inventory report from the filesystem
 * tree behind a [StorageBackend] and writes it into the inventory ROOT as
 * two normal files:
 *
 * - `inventory-report.html` — a self-contained, print-friendly document:
 *   one nested section per container (depth-first, name-sorted), the
 *   container's full relative path as its heading, its `_cover.jpg`
 *   referenced by RELATIVE URL-encoded path (so the page renders correctly
 *   when opened straight from the synced folder), and a table of the
 *   container's photos (thumbnail, description, note, date taken).
 * - `inventory-report.csv` — a flat spreadsheet twin with the columns
 *   `containerPath,fileName,description,note,dateTaken` (RFC-4180 quoting,
 *   UTF-8, no BOM).
 *
 * The two files are ordinary entries in the root: the photo listings only
 * match `.jpg`/`.jpeg` and the container listings only directories, so the
 * app UI never shows them — the report is intentionally invisible to the
 * app (it exists for browsers and spreadsheets).
 *
 * All text that reaches the markup is HTML-escaped and every image path is
 * URL-encoded per segment (keeping "/" separators); all report I/O runs on
 * [Dispatchers.IO]. Ordering matches the app: containers by name
 * (case-insensitive), photos by taken date descending (falling back to the
 * file's last-modified time exactly like the scanners do).
 */
class InventoryReportGenerator(
    private val backend: StorageBackend,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** The two files [generate] wrote, as paths relative to the inventory root. */
    data class ReportFiles(val htmlRelPath: String, val csvRelPath: String)

    /**
     * Walks the tree from the root and writes both documents into the
     * inventory root; returns their relative paths. The writes go through
     * the backend's generic byte-write primitive, which is NOT JPEG-gated
     * (the same one [TrashManager] uses for its `.origin` sidecars, and
     * both [FileBackend] and the Android `SafBackend` store arbitrary
     * bytes there).
     */
    suspend fun generate(): ReportFiles = withContext(Dispatchers.IO) {
        val root = walk("")
        val generatedAt = DATE_TIME_FORMAT.format(Date(clock()))
        val html = renderHtml(root, generatedAt)
        val csv = renderCsv(root)
        backend.writePhotoBytes(HTML_FILE_NAME, html.toByteArray(Charsets.UTF_8))
        backend.writePhotoBytes(CSV_FILE_NAME, csv.toByteArray(Charsets.UTF_8))
        ReportFiles(HTML_FILE_NAME, CSV_FILE_NAME)
    }

    // region tree walk

    /** One photo as it appears in the report. */
    private data class ReportPhoto(
        val relPath: String,
        val name: String,
        val description: String,
        val note: String?,
        val dateTaken: Long?,
        /** Ordering key: EXIF date, else the file's last-modified millis. */
        val sortKey: Long,
    )

    /** One container (or the root) with its already-sorted photos and children. */
    private data class ReportContainer(
        val relPath: String,
        val name: String,
        val hasCover: Boolean,
        val photos: List<ReportPhoto>,
        val children: List<ReportContainer>,
    )

    /** Depth-first walk of [relPath]; children are name-sorted, photos date-descending. */
    private suspend fun walk(relPath: String): ReportContainer {
        val photos = backend.listPhotos(relPath)
            .map { readPhoto(it) }
            .sortedByDescending { it.sortKey }
        val children = backend.listContainers(relPath)
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { walk(it.relPath) }
        return ReportContainer(
            relPath = relPath,
            name = if (relPath.isEmpty()) ROOT_LABEL else nameOf(relPath),
            hasCover = backend.hasCover(relPath),
            photos = photos,
            children = children,
        )
    }

    /** The report row of one photo: EXIF triple with the app's filename fallback. */
    private suspend fun readPhoto(entry: BackendEntry): ReportPhoto {
        val meta = backend.readPhotoMeta(entry.relPath)
        val fallback = entry.name.substringBeforeLast('.', entry.name)
        val date = meta?.dateTaken
        return ReportPhoto(
            relPath = entry.relPath,
            name = entry.name,
            description = meta?.description?.takeIf { it.isNotBlank() } ?: fallback,
            note = meta?.note,
            dateTaken = date,
            sortKey = date ?: backend.lastModifiedAndSize(entry.relPath)?.first ?: 0L,
        )
    }

    // endregion

    // region HTML

    private fun renderHtml(root: ReportContainer, generatedAt: String): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
        sb.append("<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(htmlEscape(TITLE)).append("</title>\n")
        sb.append("<style>\n").append(CSS).append("</style>\n")
        sb.append("</head>\n<body>\n")
        sb.append("<h1>").append(htmlEscape(TITLE)).append("</h1>\n")
        sb.append("<p class=\"generated\">Generated ").append(htmlEscape(generatedAt)).append("</p>\n")
        // The root is not a container, but it can still hold photos (e.g. a
        // file dropped by a file manager); report them under a root heading
        // rather than silently dropping them.
        if (root.photos.isNotEmpty() || root.hasCover) {
            sb.append(renderSection(root))
        }
        for (child in root.children) {
            sb.append(renderSection(child))
        }
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    private fun renderSection(container: ReportContainer): String {
        val sb = StringBuilder()
        sb.append("<section class=\"container\">\n")
        sb.append("<h2>")
            .append(htmlEscape(container.relPath.ifEmpty { ROOT_LABEL }))
            .append("</h2>\n")
        if (container.hasCover) {
            val cover = urlPath(joinRel(container.relPath, COVER_FILE_NAME))
            sb.append("<img class=\"cover\" src=\"").append(htmlEscape(cover))
                .append("\" alt=\"").append(htmlEscape(container.name)).append(" cover\">\n")
        }
        sb.append(renderTable(container.photos))
        for (child in container.children) {
            sb.append(renderSection(child))
        }
        sb.append("</section>\n")
        return sb.toString()
    }

    private fun renderTable(photos: List<ReportPhoto>): String {
        if (photos.isEmpty()) return "<p class=\"empty\">No photos.</p>\n"
        val sb = StringBuilder()
        sb.append("<table>\n")
        sb.append("<thead><tr><th>Photo</th><th>Description</th><th>Note</th><th>Date taken</th></tr></thead>\n")
        sb.append("<tbody>\n")
        for (photo in photos) {
            val url = urlPath(photo.relPath)
            sb.append("<tr>")
            sb.append("<td><a href=\"").append(htmlEscape(url))
                .append("\"><img class=\"thumb\" src=\"").append(htmlEscape(url))
                .append("\" alt=\"").append(htmlEscape(photo.name)).append("\" loading=\"lazy\"></a></td>")
            sb.append("<td class=\"desc\">").append(htmlEscape(photo.description)).append("</td>")
            sb.append("<td>").append(htmlEscape(photo.note.orEmpty())).append("</td>")
            sb.append("<td>").append(htmlEscape(formatDate(photo.dateTaken))).append("</td>")
            sb.append("</tr>\n")
        }
        sb.append("</tbody>\n</table>\n")
        return sb.toString()
    }

    // endregion

    // region CSV

    private fun renderCsv(root: ReportContainer): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append("\r\n")
        appendCsv(sb, root)
        return sb.toString()
    }

    /** Depth-first flat rows, one per photo (matches the HTML section order). */
    private fun appendCsv(sb: StringBuilder, container: ReportContainer) {
        for (photo in container.photos) {
            sb.append(csvField(container.relPath)).append(',')
                .append(csvField(photo.name)).append(',')
                .append(csvField(photo.description)).append(',')
                .append(csvField(photo.note.orEmpty())).append(',')
                .append(csvField(formatDate(photo.dateTaken))).append("\r\n")
        }
        for (child in container.children) {
            appendCsv(sb, child)
        }
    }

    // endregion

    // region formatting

    private fun formatDate(dateTaken: Long?): String =
        dateTaken?.let { DATE_FORMAT.format(Date(it)) }.orEmpty()

    /** RFC-4180 field: quote when it holds a comma, quote, CR or LF; double inner quotes. */
    private fun csvField(value: String): String {
        val mustQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (mustQuote) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun htmlEscape(value: String): String {
        val sb = StringBuilder(value.length)
        for (character in value) {
            when (character) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(character)
            }
        }
        return sb.toString()
    }

    /** URL-encode each path segment (keeping "/" separators): "A B/c.jpg" → "A%20B/c.jpg". */
    private fun urlPath(relPath: String): String =
        relPath.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }

    // endregion

    companion object {

        /** The HTML report's file name inside the inventory root. */
        const val HTML_FILE_NAME = "inventory-report.html"

        /** The CSV report's file name inside the inventory root. */
        const val CSV_FILE_NAME = "inventory-report.csv"

        private const val TITLE = "Photo Inventory"
        private const val ROOT_LABEL = "Inventory (root)"
        private const val CSV_HEADER = "containerPath,fileName,description,note,dateTaken"
        private val DATE_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private const val CSS = """
:root { color-scheme: light; }
* { box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  color: #1a1a1a;
  background: #fff;
  margin: 0 auto;
  max-width: 960px;
  padding: 32px 24px 64px;
  line-height: 1.4;
}
h1 { font-size: 1.9rem; margin: 0 0 4px; }
.generated { color: #666; font-size: 0.85rem; margin: 0 0 32px; }
section.container { margin: 0 0 28px; padding: 0 0 8px; border-bottom: 1px solid #e5e5e5; }
section.container > h2 { font-size: 1.25rem; margin: 0 0 10px; word-break: break-word; }
section.container section.container {
  margin: 16px 0 8px 24px;
  padding-left: 16px;
  border-bottom: none;
  border-left: 2px solid #e5e5e5;
}
img.cover { max-width: 220px; height: auto; display: block; margin: 0 0 12px; border-radius: 6px; border: 1px solid #e5e5e5; }
img.thumb { max-width: 120px; height: auto; display: block; border-radius: 4px; }
table { width: 100%; border-collapse: collapse; margin: 0 0 12px; font-size: 0.9rem; }
th, td { text-align: left; vertical-align: top; padding: 6px 8px; border-bottom: 1px solid #eee; }
th { background: #f7f7f7; font-weight: 600; }
.desc { font-weight: 600; }
a { color: #1155cc; text-decoration: none; }
a:hover { text-decoration: underline; }
.empty { color: #888; font-style: italic; margin: 0 0 12px; }
@media print {
  body { max-width: none; padding: 0; }
  section.container, tr, img.cover { page-break-inside: avoid; }
  a { color: inherit; }
}
"""
    }
}
