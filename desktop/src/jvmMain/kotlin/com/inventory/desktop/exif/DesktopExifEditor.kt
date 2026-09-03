package com.inventory.desktop.exif

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.inventory.storage.ExifEditor
import com.inventory.storage.PhotoMeta
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryType
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.fieldtypes.AbstractFieldType
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfo
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfoBytes
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet

/**
 * The desktop [ExifEditor]: reads the metadata triple with
 * `metadata-extractor` and rewrites it losslessly with Commons Imaging
 * (`ExifRewriter.updateExifMetadataLossless`), building a
 * [TiffOutputSet] copied from the payload's existing EXIF so every other
 * tag (camera Make/Model, GPS, thumbnails, color profiles) and all pixel
 * data survive the rewrite untouched.
 *
 * Tag mapping and byte conventions mirror the Android engine
 * (`androidx.exifinterface` in the app's `PhotoMetadata`) EXACTLY, so both
 * apps read and restamp each other's files:
 *
 * - description ↔ ImageDescription (0x010E, IFD0) — ASCII field,
 *   UTF-8 payload bytes, NUL-terminated.
 * - note ↔ UserComment (0x9286, Exif sub-IFD) — written the same way,
 *   with NO `"ASCII\0\0\0"` charset prefix (verified against files the
 *   Android app itself wrote: field type ASCII, value `text\0`).
 *   The read path still tolerates prefixed comments and strips the
 *   standard charset codes (ASCII / UNICODE(UTF-16LE) / JIS / UTF-8).
 * - date taken ↔ DateTimeOriginal (0x9003) — stamped only when absent,
 *   together with DateTime (0x0132, IFD0) like the Android engine, in
 *   the EXIF format "yyyy:MM:dd HH:mm:ss" in the local time zone.
 *
 * A null [withExif] description/note REMOVES the tag (Android semantics).
 */
class DesktopExifEditor : ExifEditor {

    override fun read(bytes: ByteArray): PhotoMeta? {
        if (!looksLikeJpeg(bytes)) return null
        val metadata: Metadata = try {
            ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
        } catch (exception: Exception) {
            // ImageProcessingException / IOException: nothing was parsed.
            return null
        }
        val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val exifIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        return PhotoMeta(
            description = decodeText(
                rawValueOf(ifd0, ExifIFD0Directory.TAG_IMAGE_DESCRIPTION),
                charsetCodeAware = false,
            ),
            note = decodeText(
                rawValueOf(exifIfd, ExifSubIFDDirectory.TAG_USER_COMMENT),
                charsetCodeAware = true,
            ),
            dateTaken = parseDate(
                exifIfd?.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
            ),
        )
    }

    /** Stream contract: read all bytes, then parse (the File variant too). */
    override fun read(stream: InputStream): PhotoMeta? =
        runCatching { read(stream.readBytes()) }.getOrNull()

    override fun read(file: File): PhotoMeta? =
        runCatching { read(file.readBytes()) }.getOrNull()

    /**
     * A tag's raw bytes however metadata-extractor happens to have stored
     * it: typed string values keep their charset, plain byte[] values
     * (camera-written UNDEFINED UserComments) come through as read.
     */
    private fun rawValueOf(directory: ExifDirectoryBase?, tag: Int): ByteArray? {
        directory ?: return null
        runCatching { directory.getStringValue(tag)?.bytes }.getOrNull()?.let { return it }
        runCatching { directory.getByteArray(tag) }.getOrNull()?.let { return it }
        return runCatching { directory.getString(tag)?.toByteArray(StandardCharsets.ISO_8859_1) }.getOrNull()
    }

    override fun withExif(
        bytes: ByteArray,
        description: String?,
        note: String?,
        setDateTakenIfMissing: Long?,
    ): ByteArray {
        if (!looksLikeJpeg(bytes)) throw IOException("Payload is not a JPEG")
        val outputSet = existingOutputSet(bytes)
        val root = outputSet.getOrCreateRootDirectory()
        val exif = outputSet.getOrCreateExifDirectory()

        root.removeField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION.tag)
        description?.trim()?.ifEmpty { null }?.let {
            root.add(asciiField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION, it))
        }

        exif.removeField(USER_COMMENT_TAG)
        note?.trim()?.ifEmpty { null }?.let {
            exif.add(asciiField(USER_COMMENT_TAG_INFO, it))
        }

        if (setDateTakenIfMissing != null && outputSet.findField(DATE_TIME_ORIGINAL_TAG) == null) {
            val stamp = SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US).format(Date(setDateTakenIfMissing))
            root.removeField(TiffTagConstants.TIFF_TAG_DATE_TIME.tag)
            root.add(asciiField(TiffTagConstants.TIFF_TAG_DATE_TIME, stamp))
            exif.removeField(DATE_TIME_ORIGINAL_TAG)
            exif.add(asciiField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, stamp))
        }

        val output = ByteArrayOutputStream()
        try {
            ExifRewriter().updateExifMetadataLossless(bytes, output, outputSet)
        } catch (exception: IOException) {
            throw exception
        } catch (exception: Exception) {
            // ImagingException and friends: the engine cannot restamp this payload.
            throw IOException("Could not rewrite EXIF: ${exception.message}", exception)
        }
        return output.toByteArray()
    }

    override fun looksLikeJpeg(bytes: ByteArray): Boolean = bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    /** The payload's existing EXIF output set, or an empty one when absent. */
    private fun existingOutputSet(bytes: ByteArray): TiffOutputSet = try {
        (Imaging.getMetadata(bytes) as? JpegImageMetadata)
            ?.exif
            ?.outputSet
            ?: TiffOutputSet()
    } catch (exception: Exception) {
        // A JPEG Commons Imaging cannot parse cannot be stamped either.
        if (exception is IOException) throw exception
        throw IOException("Could not parse EXIF: ${exception.message}", exception)
    }

    /** An NUL-terminated, UTF-8-encoded ASCII-type TIFF field (the Android convention). */
    private fun asciiField(tagInfo: TagInfo, value: String): TiffOutputField {
        val payload = value.toByteArray(StandardCharsets.UTF_8) + 0.toByte()
        return TiffOutputField(tagInfo, AbstractFieldType.ASCII, payload.size, payload)
    }

    /** Decodes raw tag bytes, optionally dropping a UserComment charset code. */
    private fun decodeText(raw: ByteArray?, charsetCodeAware: Boolean): String? {
        raw ?: return null
        var payload = raw
        var charset: Charset = StandardCharsets.UTF_8
        if (charsetCodeAware && payload.size >= 8) {
            val code = payload.copyOfRange(0, 8)
            when {
                code.contentEquals(CHARSET_ASCII) -> payload = payload.copyOfRange(8, payload.size)
                code.contentEquals(CHARSET_UTF8) -> payload = payload.copyOfRange(8, payload.size)
                code.contentEquals(CHARSET_JIS) -> payload = payload.copyOfRange(8, payload.size)
                code.contentEquals(CHARSET_UNICODE) -> {
                    charset = StandardCharsets.UTF_16LE
                    payload = payload.copyOfRange(8, payload.size)
                }
            }
        }
        var end = payload.size
        while (end > 0 && payload[end - 1] == 0.toByte()) end--
        return String(payload, 0, end, charset).trim().ifEmpty { null }
    }

    private fun parseDate(value: String?): Long? {
        val text = value?.trim()?.ifEmpty { null } ?: return null
        return try {
            SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US).parse(text)?.time
        } catch (exception: ParseException) {
            null
        }
    }

    companion object {
        private const val EXIF_DATE_PATTERN = "yyyy:MM:dd HH:mm:ss"
        private const val USER_COMMENT_TAG = 0x9286
        private const val DATE_TIME_ORIGINAL_TAG = 0x9003

        /**
         * UserComment declared as a plain ASCII tag so Commons Imaging writes
         * our bytes verbatim (its built-in constant is a GPS-text tag that
         * could charset-prefix the value).
         */
        private val USER_COMMENT_TAG_INFO: TagInfo = TagInfoBytes(
            "UserComment",
            USER_COMMENT_TAG,
            TagInfo.LENGTH_UNKNOWN,
            TiffDirectoryType.EXIF_DIRECTORY_EXIF_IFD,
        )

        private val CHARSET_ASCII = byteArrayOf(0x41, 0x53, 0x43, 0x49, 0x49, 0x00, 0x00, 0x00)
        private val CHARSET_UNICODE = byteArrayOf(0x55, 0x4E, 0x49, 0x43, 0x4F, 0x44, 0x45, 0x00)
        private val CHARSET_JIS = byteArrayOf(0x4A, 0x49, 0x53, 0x00, 0x00, 0x00, 0x00, 0x00)
        private val CHARSET_UTF8 = byteArrayOf(0x55, 0x54, 0x46, 0x2D, 0x38, 0x00, 0x00, 0x00)
    }
}
