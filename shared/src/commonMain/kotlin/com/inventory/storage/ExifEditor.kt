package com.inventory.storage

import java.io.File
import java.io.InputStream

/**
 * The EXIF seam of the platform-neutral core: [FileBackend] needs to
 * read the metadata triple of a photo and to check JPEG payloads, but
 * the only battle-tested tag writer lives in `androidx.exifinterface`
 * (Android-only). The app therefore injects an implementation (its
 * `AndroidExifEditor`) into the shared backend — plain constructor
 * injection, no DI framework in this module.
 *
 * Every reader returns null only for payloads that cannot be parsed at
 * all; a readable image without the tags yields an all-null [PhotoMeta].
 */
interface ExifEditor {

    /** Reads description/note/date taken from an in-memory JPEG. */
    fun read(bytes: ByteArray): PhotoMeta?

    /** Cheap stream-based read (pixel data is never decoded). */
    fun read(stream: InputStream): PhotoMeta?

    /** Reads description/note/date taken from a photo on disk. */
    fun read(file: File): PhotoMeta?

    /**
     * Returns [bytes] with description/note stamped into their EXIF tags
     * (pixels untouched); when [setDateTakenIfMissing] is non-null and
     * the payload carries no DateTimeOriginal, that timestamp is stamped.
     * Pass null [description]/[note] to remove those tags. Throws
     * [java.io.IOException]/[RuntimeException] for payloads the EXIF
     * engine cannot restamp — callers treat that as an invalid image.
     */
    fun withExif(
        bytes: ByteArray,
        description: String?,
        note: String?,
        setDateTakenIfMissing: Long?,
    ): ByteArray

    /** True when [bytes] starts with the JPEG SOI marker. */
    fun looksLikeJpeg(bytes: ByteArray): Boolean
}
