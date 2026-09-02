package com.inventory.storage

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The metadata contract of the filesystem model: everything the app knows
 * about a photo lives in the photo's own EXIF segment — the description,
 * the note and the taken date. Pixels are never re-encoded; only the EXIF
 * segment is rewritten, and always through a temp file that is atomically
 * renamed over the target (never edit the source in place — disk is the
 * only truth).
 *
 * **Tag mapping** — verified empirically against the resolved artifact
 * `androidx.exifinterface:exifinterface:1.3.7` (every constant below
 * confirmed present with `javap` on the AAR's classes.jar; write/read
 * round-trips confirmed on a host JVM against that same jar and with a
 * Pillow dump of the resulting file):
 *
 * - description ↔ [ExifInterface.TAG_IMAGE_DESCRIPTION] (0x010E, IFD0) —
 *   the first-choice mapping applies because that constant exists in the
 *   artifact; no fallback to [ExifInterface.TAG_USER_COMMENT] was needed.
 * - note ↔ [ExifInterface.TAG_USER_COMMENT] (0x9286, Exif sub-IFD).
 *   androidx stores/retrieves it as plain text; files written here were
 *   verified byte-for-byte to carry no "ASCII\0\0\0" charset prefix and
 *   both tools read the value back cleanly.
 * - date taken ↔ [ExifInterface.TAG_DATETIME_ORIGINAL] (0x9003) plus
 *   [ExifInterface.TAG_DATETIME] (0x0132), EXIF format
 *   "yyyy:MM:dd HH:mm:ss", local time zone.
 *
 * Clearing a value: [ExifInterface.setAttribute] with `null` removes the
 * tag; an externally-set empty string reads back as null here so blank
 * metadata never leaks into the cache or the UI.
 */
object PhotoMetadata {

    /** The EXIF triple this app reads from and writes to a photo file. */
    data class PhotoMeta(
        val description: String?,
        val note: String?,
        val dateTaken: Long?,
    )

    private const val EXIF_DATE_PATTERN = "yyyy:MM:dd HH:mm:ss"

    /** Reads description/note/date taken from [file]; all-null for unreadable files. */
    fun read(file: File): PhotoMeta = runCatching {
        val exif = ExifInterface(file.absolutePath)
        PhotoMeta(
            description = normalize(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)),
            note = normalize(exif.getAttribute(ExifInterface.TAG_USER_COMMENT)),
            dateTaken = parseDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)),
        )
    }.getOrDefault(PhotoMeta(null, null, null))

    /**
     * Writes description/note into [file]'s EXIF atomically (temp copy in
     * the same folder + rename over the original). When
     * [setDateTakenIfMissing] is non-null and the file carries no
     * DateTimeOriginal, that timestamp is stamped into both DATETIME and
     * DATETIME_ORIGINAL. Pass null [description]/[note] to remove those
     * tags. Throws [IOException] when the file cannot be rewritten.
     */
    fun write(file: File, description: String?, note: String?, setDateTakenIfMissing: Long?) {
        val parent = file.parentFile ?: throw IOException("Photo has no parent folder: $file")
        val temp = File(parent, ".exif-${UUID.randomUUID()}.tmp")
        try {
            file.copyTo(temp, overwrite = true)
            val exif = ExifInterface(temp.absolutePath)
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, note)
            if (setDateTakenIfMissing != null &&
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrEmpty()
            ) {
                val stamp = SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US)
                    .format(Date(setDateTakenIfMissing))
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
            }
            exif.saveAttributes()
            if (!temp.renameTo(file)) {
                throw IOException("Could not store updated metadata for ${file.path}")
            }
        } finally {
            temp.delete()
        }
    }

    /** True when [file] starts with the JPEG SOI marker — the only image format this app stores. */
    fun looksLikeJpeg(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            input.read() == 0xFF && input.read() == 0xD8 && input.read() == 0xFF
        }
    }.getOrDefault(false)

    private fun normalize(value: String?): String? = value?.trim()?.ifEmpty { null }

    private fun parseDate(value: String?): Long? {
        val text = value?.trim()?.ifEmpty { null } ?: return null
        return runCatching {
            SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US).parse(text)?.time
        }.getOrNull()
    }
}
