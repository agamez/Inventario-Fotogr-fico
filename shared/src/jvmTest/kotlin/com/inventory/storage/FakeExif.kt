package com.inventory.storage

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Deterministic stand-in for the androidx EXIF engine, used to verify
 * [FileBackend]'s file logic on a plain JVM without any EXIF library
 * (real EXIF correctness is covered by the Android harness). A "fake
 * JPEG" is the SOI marker FF D8 FF followed by a UTF-8 body of three
 * segments — description, note and date millis (an empty segment means
 * the value is absent, the date is always last) — separated by the unit
 * separator character U+001F and terminated by U+001E + newline.
 * [withExif] rewrites only that body, mirroring how the real engine
 * stamps tags without touching pixels.
 */
class FakeExif : ExifEditor {

    override fun read(bytes: ByteArray): PhotoMeta? {
        if (!looksLikeJpeg(bytes)) return null
        val body = String(bytes, HEADER.size, bytes.size - HEADER.size, Charsets.UTF_8)
        if (!body.endsWith(TRAILER)) return null
        val segments = body.removeSuffix(TRAILER).split(SEGMENT)
        if (segments.size != 3) return null
        return PhotoMeta(
            description = segments[0].ifEmpty { null },
            note = segments[1].ifEmpty { null },
            dateTaken = segments[2].ifEmpty { null }?.toLongOrNull(),
        )
    }

    override fun read(stream: InputStream): PhotoMeta? = read(stream.readBytes())

    override fun read(file: File): PhotoMeta? = read(file.readBytes())

    override fun withExif(
        bytes: ByteArray,
        description: String?,
        note: String?,
        setDateTakenIfMissing: Long?,
    ): ByteArray {
        val current = read(bytes) ?: throw IOException("FakeExif: payload is not a fake JPEG")
        val dateTaken = current.dateTaken ?: setDateTakenIfMissing
        return encode(description, note, dateTaken)
    }

    override fun looksLikeJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    companion object {

        private val HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val SEGMENT = Char(0x1F).toString()
        private val TRAILER = Char(0x1E).toString() + Char(0x0A)

        /** The [FakeExif] wire format: a SOI marker plus the tagged body. */
        fun jpeg(description: String?, note: String?, dateTaken: Long? = null): ByteArray =
            HEADER + encode(description, note, dateTaken)

        private fun encode(description: String?, note: String?, dateTaken: Long?): ByteArray =
            (
                listOf(description.orEmpty(), note.orEmpty(), dateTaken?.toString().orEmpty())
                    .joinToString(SEGMENT) + TRAILER
                ).toByteArray(Charsets.UTF_8)
    }
}
