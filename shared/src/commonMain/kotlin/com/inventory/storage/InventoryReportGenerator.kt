package com.inventory.storage

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds a self-contained visual inventory document from the filesystem
 * tree behind a [StorageBackend] and writes it into the inventory ROOT as
 * two normal files:
 *
 * - `inventory-report.html` — a single, printable, dependency-free file:
 *   a summary header (root name, timestamp, totals, photo date range), a
 *   tree outline with anchor links, and one flat section per container
 *   (document order = tree order). Each section header shows the
 *   container's `_cover.jpg` (when present) as a base64-embedded preview
 *   next to the name, and the body holds a photo grid whose thumbnails are
 *   base64-embedded JPEGs ([ImageThumbnailer]) — so the page renders with
 *   no sibling files at all. The cover is the container's identity and
 *   stays out of the content grid.
 * - `inventory-report.csv` — a flat spreadsheet twin, one row per photo:
 *   `containerPath,fileName,description,note,dateTaken,sizeBytes`
 *   (RFC-4180 quoting, UTF-8, no BOM). `dateTaken` keeps the EXIF
 *   `yyyy:MM:dd HH:mm:ss` shape it is stored with.
 *
 * The two files are ordinary entries in the root: the photo listings only
 * match `.jpg`/`.jpeg` and the container listings only directories, so the
 * app UI never shows them — the report is intentionally invisible to the
 * app (it exists for browsers and spreadsheets).
 *
 * All text that reaches the markup is HTML-escaped, every original-photo
 * link is URL-encoded per segment (keeping "/" separators), and all report
 * I/O plus thumbnailing runs on [Dispatchers.IO]. Originals are only ever
 * READ (never re-encoded); the report itself is a derived artifact written
 * through the backend's atomic byte-write primitive. Ordering matches the
 * app: containers by name (case-insensitive), photos by taken date
 * descending (falling back to the file's last-modified time exactly like
 * the scanners do).
 */
class InventoryReportGenerator(
    private val backend: StorageBackend,
    /** Downscales + orientation-corrects each photo into an embedded preview. */
    private val thumbnailer: ImageThumbnailer,
    /** Display name of the inventory root in the summary header. */
    private val rootName: String = ROOT_FOLDER_NAME,
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
        val root = walk("", depth = 0)
        val sections = collectSections(root)
        val generatedAt = DATE_TIME_FORMAT.format(Date(clock()))
        val html = renderHtml(root, sections, generatedAt)
        val csv = renderCsv(sections)
        backend.writePhotoBytes(HTML_FILE_NAME, html.toByteArray(Charsets.UTF_8))
        backend.writePhotoBytes(CSV_FILE_NAME, csv.toByteArray(Charsets.UTF_8))
        ReportFiles(HTML_FILE_NAME, CSV_FILE_NAME)
    }

    // region tree walk

    /** One photo as it appears in the report (thumbnail populated during the walk). */
    private data class ReportPhoto(
        val relPath: String,
        val name: String,
        val description: String,
        val note: String?,
        val dateTaken: Long?,
        /** File size in bytes, shown in the CSV twin. */
        val size: Long,
        /** Ordering key: EXIF date, else the file's last-modified millis. */
        val sortKey: Long,
        /** Embedded JPEG preview, or null when the photo could not be decoded. */
        val thumbnail: ByteArray?,
    )

    /** One container (or the root) with its already-sorted photos and children. */
    private data class ReportContainer(
        val relPath: String,
        val name: String,
        /** 0 for the root, then one deeper per nesting level. */
        val depth: Int,
        val hasCover: Boolean,
        /** Embedded cover preview (the header image), or null when absent/undecodable. */
        val coverThumbnail: ByteArray?,
        val photos: List<ReportPhoto>,
        val children: List<ReportContainer>,
    )

    /** Depth-first walk of [relPath]; children are name-sorted, photos date-descending. */
    private suspend fun walk(relPath: String, depth: Int): ReportContainer {
        val photos = backend.listPhotos(relPath)
            .map { readPhoto(it) }
            .sortedByDescending { it.sortKey }
        val children = backend.listContainers(relPath)
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { walk(it.relPath, depth + 1) }
        val hasCover = backend.hasCover(relPath)
        return ReportContainer(
            relPath = relPath,
            name = if (relPath.isEmpty()) ROOT_LABEL else nameOf(relPath),
            depth = depth,
            hasCover = hasCover,
            coverThumbnail = if (hasCover) readCoverThumbnail(relPath) else null,
            photos = photos,
            children = children,
        )
    }

    /**
     * The container's `_cover.jpg` as the same embedded preview the photo
     * grid uses, or null when it cannot be decoded. The cover is never a
     * content photo (the listings exclude it), so this is its only render
     * site — next to the container name.
     */
    private suspend fun readCoverThumbnail(relPath: String): ByteArray? =
        backend.readPhotoBytes(joinRel(relPath, COVER_FILE_NAME))?.let { bytes ->
            runCatching { thumbnailer.thumbnail(bytes, ImageThumbnailer.THUMBNAIL_MAX_DIM) }
                .getOrNull()
        }

    /**
     * The report row of one photo: EXIF triple with the app's filename
     * fallback, the file size, and the embedded preview. Reading the
     * original bytes never modifies them.
     */
    private suspend fun readPhoto(entry: BackendEntry): ReportPhoto {
        val meta = backend.readPhotoMeta(entry.relPath)
        val fallback = entry.name.substringBeforeLast('.', entry.name)
        val date = meta?.dateTaken
        val thumbnail = backend.readPhotoBytes(entry.relPath)?.let { bytes ->
            runCatching { thumbnailer.thumbnail(bytes, ImageThumbnailer.THUMBNAIL_MAX_DIM) }
                .getOrNull()
        }
        return ReportPhoto(
            relPath = entry.relPath,
            name = entry.name,
            description = meta?.description?.takeIf { it.isNotBlank() } ?: fallback,
            note = meta?.note,
            dateTaken = date,
            size = entry.size,
            sortKey = date ?: backend.lastModifiedAndSize(entry.relPath)?.first ?: 0L,
            thumbnail = thumbnail,
        )
    }

    /**
     * Pre-order flattening of the tree: the root first when it actually
     * holds content (it is not a container but can still carry photos or a
     * cover), then every descendant container parent-before-children. The
     * index in this list is the section's anchor id (`c1`, `c2`, …).
     */
    private fun collectSections(root: ReportContainer): List<ReportContainer> {
        val sections = mutableListOf<ReportContainer>()
        if (root.photos.isNotEmpty() || root.hasCover) sections += root
        fun addChildren(container: ReportContainer) {
            for (child in container.children) {
                sections += child
                addChildren(child)
            }
        }
        addChildren(root)
        return sections
    }

    // endregion

    // region HTML

    private fun renderHtml(
        root: ReportContainer,
        sections: List<ReportContainer>,
        generatedAt: String,
    ): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
        sb.append("<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(htmlEscape(TITLE)).append("</title>\n")
        sb.append("<style>\n").append(CSS).append("</style>\n")
        sb.append("</head>\n<body>\n")
        sb.append("<h1>").append(htmlEscape(TITLE)).append("</h1>\n")
        sb.append(renderSummary(sections, generatedAt))
        val ids = anchorIds(sections)
        sb.append(renderOutline(root, ids))
        for (section in sections) {
            sb.append(renderSection(section, ids.getValue(section.relPath)))
        }
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    /** `c1`, `c2`, … in document (pre-order) order. */
    private fun anchorIds(sections: List<ReportContainer>): Map<String, String> =
        sections.withIndex().associate { (index, section) -> section.relPath to "c${index + 1}" }

    private fun renderSummary(sections: List<ReportContainer>, generatedAt: String): String {
        val containerCount = sections.count { it.relPath.isNotEmpty() }
        val photoCount = sections.sumOf { it.photos.size }
        val totalBytes = sections.sumOf { container -> container.photos.sumOf { it.size } }
        val dates = sections.flatMap { it.photos }.mapNotNull { it.dateTaken }
        val earliest = dates.minOrNull()
        val latest = dates.maxOrNull()
        val range = if (earliest == null || latest == null) {
            NO_DATES
        } else {
            "${formatDate(earliest)} – ${formatDate(latest)}"
        }
        val sb = StringBuilder()
        sb.append("<section class=\"summary\">\n")
        sb.append("<p class=\"root\">Root: <strong>").append(htmlEscape(rootName)).append("</strong></p>\n")
        sb.append("<p class=\"generated\">Generated ").append(htmlEscape(generatedAt)).append("</p>\n")
        sb.append("<ul class=\"totals\">\n")
        sb.append("<li>Containers: ").append(containerCount).append("</li>\n")
        sb.append("<li>Photos: ").append(photoCount).append("</li>\n")
        sb.append("<li>Total photo bytes: ").append(totalBytes).append("</li>\n")
        sb.append("<li>Photo date range: ").append(htmlEscape(range)).append("</li>\n")
        sb.append("</ul>\n")
        sb.append("</section>\n")
        return sb.toString()
    }

    private fun renderOutline(root: ReportContainer, ids: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("<nav class=\"outline\">\n")
        sb.append("<h2 class=\"outline-title\">Containers</h2>\n")
        sb.append("<ul>\n")
        if (root.photos.isNotEmpty() || root.hasCover) {
            appendOutlineItem(sb, root, ids)
        } else {
            for (child in root.children) appendOutlineItem(sb, child, ids)
        }
        sb.append("</ul>\n</nav>\n")
        return sb.toString()
    }

    private fun appendOutlineItem(
        sb: StringBuilder,
        container: ReportContainer,
        ids: Map<String, String>,
    ) {
        val id = ids[container.relPath] ?: return
        sb.append("<li><a href=\"#").append(id).append("\">")
            .append(htmlEscape(container.name)).append("</a> <span class=\"count\">")
            .append(photoCountLabel(container.photos.size)).append("</span>")
        if (container.children.isNotEmpty()) {
            sb.append("\n<ul>\n")
            for (child in container.children) appendOutlineItem(sb, child, ids)
            sb.append("</ul>\n")
        }
        sb.append("</li>\n")
    }

    private fun renderSection(container: ReportContainer, id: String): String {
        val sb = StringBuilder()
        sb.append("<section class=\"container depth-").append(container.depth)
            .append("\" id=\"").append(id).append("\">\n")
        sb.append("<div class=\"section-head\">\n")
        val cover = container.coverThumbnail
        if (cover != null) {
            sb.append("<img class=\"cover\" src=\"data:image/jpeg;base64,").append(base64(cover))
                .append("\" alt=\"").append(htmlEscape(container.name)).append("\">\n")
        }
        sb.append("<h2>").append(htmlEscape(container.name)).append("</h2>\n")
        sb.append("</div>\n")
        sb.append("<p class=\"path\">")
            .append(htmlEscape(container.relPath.ifEmpty { ROOT_LABEL }))
            .append("</p>\n")
        sb.append("<p class=\"count\">").append(photoCountLabel(container.photos.size)).append("</p>\n")
        sb.append(renderGrid(container.photos))
        sb.append("</section>\n")
        return sb.toString()
    }

    private fun renderGrid(photos: List<ReportPhoto>): String {
        if (photos.isEmpty()) return "<p class=\"empty\">No photos.</p>\n"
        val sb = StringBuilder("<div class=\"grid\">\n")
        for (photo in photos) {
            sb.append("<figure class=\"photo\">\n")
            val thumbnail = photo.thumbnail
            if (thumbnail != null) {
                val dataUri = "data:image/jpeg;base64," + base64(thumbnail)
                sb.append("<a href=\"").append(htmlEscape(urlPath(photo.relPath)))
                    .append("\"><img class=\"thumb\" src=\"").append(dataUri)
                    .append("\" alt=\"").append(htmlEscape(photo.description))
                    .append("\" loading=\"lazy\"></a>\n")
            } else {
                sb.append("<div class=\"thumb placeholder\">No preview</div>\n")
            }
            sb.append("<figcaption>\n")
            sb.append("<span class=\"desc\">").append(htmlEscape(photo.description)).append("</span>\n")
            if (!photo.note.isNullOrBlank()) {
                sb.append("<span class=\"note\">").append(htmlEscape(photo.note.orEmpty())).append("</span>\n")
            }
            sb.append("<span class=\"date\">").append(htmlEscape(formatDate(photo.dateTaken))).append("</span>\n")
            sb.append("<span class=\"file\">").append(htmlEscape(photo.name)).append("</span>\n")
            sb.append("</figcaption>\n</figure>\n")
        }
        sb.append("</div>\n")
        return sb.toString()
    }

    // endregion

    // region CSV

    private fun renderCsv(sections: List<ReportContainer>): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append("\r\n")
        for (container in sections) {
            for (photo in container.photos) {
                sb.append(csvField(container.relPath)).append(',')
                    .append(csvField(photo.name)).append(',')
                    .append(csvField(photo.description)).append(',')
                    .append(csvField(photo.note.orEmpty())).append(',')
                    .append(csvField(formatExifDate(photo.dateTaken))).append(',')
                    .append(photo.size).append("\r\n")
            }
        }
        return sb.toString()
    }

    // endregion

    // region formatting

    private fun photoCountLabel(count: Int): String =
        if (count == 1) "1 photo" else "$count photos"

    private fun formatDate(dateTaken: Long?): String =
        dateTaken?.let { DATE_FORMAT.format(Date(it)) }.orEmpty()

    /** The CSV keeps the EXIF `yyyy:MM:dd HH:mm:ss` shape the tag is stored with. */
    private fun formatExifDate(dateTaken: Long?): String =
        dateTaken?.let { EXIF_DATE_FORMAT.format(Date(it)) }.orEmpty()

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
        private const val NO_DATES = "No dated photos"
        private const val CSV_HEADER = "containerPath,fileName,description,note,dateTaken,sizeBytes"
        private val DATE_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

        private const val BASE64_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /**
         * Standard (RFC-4648) Base64 encoding, hand-rolled so `:shared`
         * stays dependency-free and does not need `java.util.Base64`
         * (API 26+ on Android while `minSdk` is 24).
         */
        private fun base64(bytes: ByteArray): String {
            val encoded = StringBuilder(((bytes.size + 2) / 3) * 4)
            var index = 0
            while (index + 2 < bytes.size) {
                val a = bytes[index].toInt() and 0xFF
                val b = bytes[index + 1].toInt() and 0xFF
                val c = bytes[index + 2].toInt() and 0xFF
                val triple = (a shl 16) or (b shl 8) or c
                encoded.append(BASE64_CHARS[(triple ushr 18) and 0x3F])
                encoded.append(BASE64_CHARS[(triple ushr 12) and 0x3F])
                encoded.append(BASE64_CHARS[(triple ushr 6) and 0x3F])
                encoded.append(BASE64_CHARS[triple and 0x3F])
                index += 3
            }
            when (bytes.size - index) {
                1 -> {
                    val a = bytes[index].toInt() and 0xFF
                    val triple = a shl 16
                    encoded.append(BASE64_CHARS[(triple ushr 18) and 0x3F])
                    encoded.append(BASE64_CHARS[(triple ushr 12) and 0x3F])
                    encoded.append("==")
                }
                2 -> {
                    val a = bytes[index].toInt() and 0xFF
                    val b = bytes[index + 1].toInt() and 0xFF
                    val triple = (a shl 16) or (b shl 8)
                    encoded.append(BASE64_CHARS[(triple ushr 18) and 0x3F])
                    encoded.append(BASE64_CHARS[(triple ushr 12) and 0x3F])
                    encoded.append(BASE64_CHARS[(triple ushr 6) and 0x3F])
                    encoded.append('=')
                }
            }
            return encoded.toString()
        }

        private const val CSS = """
:root { color-scheme: light; }
* { box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  color: #1a1a1a;
  background: #fff;
  margin: 0 auto;
  max-width: 1100px;
  padding: 32px 24px 64px;
  line-height: 1.4;
}
h1 { font-size: 1.9rem; margin: 0 0 4px; }
.summary { border: 1px solid #e5e5e5; border-radius: 8px; padding: 12px 16px; margin: 0 0 24px; background: #fafafa; }
.summary .root { margin: 0 0 2px; }
.summary .generated { color: #666; font-size: 0.85rem; margin: 0 0 8px; }
.summary .totals { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 4px 24px; font-size: 0.9rem; }
.outline { border: 1px solid #e5e5e5; border-radius: 8px; padding: 12px 16px; margin: 0 0 28px; }
.outline-title { font-size: 1rem; margin: 0 0 8px; }
.outline ul { list-style: none; margin: 0; padding-left: 18px; }
.outline > ul { padding-left: 0; }
.outline li { margin: 2px 0; }
.outline .count { color: #666; font-size: 0.8rem; }
section.container { margin: 0 0 28px; padding: 0 0 8px; border-bottom: 1px solid #e5e5e5; }
.section-head { display: flex; align-items: flex-start; gap: 12px; margin: 0 0 2px; }
.section-head h2 { font-size: 1.25rem; margin: 0; word-break: break-word; }
section.container .path { color: #666; font-size: 0.8rem; margin: 0 0 2px; word-break: break-word; }
section.container .count { color: #666; font-size: 0.8rem; margin: 0 0 12px; }
section.container.depth-1 { margin-left: 20px; }
section.container.depth-2 { margin-left: 40px; }
section.container.depth-3 { margin-left: 60px; }
section.container.depth-4 { margin-left: 80px; }
section.container.depth-5 { margin-left: 100px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 12px; }
figure.photo { margin: 0; border: 1px solid #e5e5e5; border-radius: 6px; padding: 8px; display: flex; flex-direction: column; gap: 6px; }
figure.photo img.thumb { max-width: 100%; height: auto; display: block; border-radius: 4px; }
.thumb.placeholder { display: flex; align-items: center; justify-content: center; min-height: 120px; background: #f0f0f0; color: #999; font-size: 0.8rem; border-radius: 4px; }
figcaption { display: flex; flex-direction: column; gap: 2px; font-size: 0.82rem; }
figcaption .desc { font-weight: 600; word-break: break-word; }
figcaption .note { color: #444; word-break: break-word; }
figcaption .date { color: #666; }
figcaption .file { color: #888; word-break: break-word; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.75rem; }
img.cover { max-width: 128px; height: auto; display: block; flex: 0 0 auto; border-radius: 6px; border: 1px solid #e5e5e5; }
a { color: #1155cc; text-decoration: none; }
a:hover { text-decoration: underline; }
.empty { color: #888; font-style: italic; margin: 0 0 12px; }
@media print {
  body { max-width: none; padding: 0; }
  section.container, figure.photo, img.cover { page-break-inside: avoid; }
  a { color: inherit; }
}
"""
    }
}
