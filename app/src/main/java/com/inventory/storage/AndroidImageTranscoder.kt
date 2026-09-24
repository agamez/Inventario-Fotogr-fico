package com.inventory.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * The Android [ImageTranscoder]: a payload that already starts with the
 * JPEG SOI marker passes through byte-identically, everything else is
 * decoded with [BitmapFactory] and re-encoded as JPEG at the requested
 * quality. This is a format conversion (PNG/WebP → JPEG), never a
 * re-encode of an existing JPEG.
 *
 * A payload the platform cannot decode raises [IllegalArgumentException],
 * which the import paths surface as the invalid-image message.
 */
class AndroidImageTranscoder @Inject constructor() : ImageTranscoder {

    override fun toJpeg(bytes: ByteArray, quality: Int): ByteArray {
        if (ImageTranscoder.isJpeg(bytes)) return bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Payload is not a decodable image")
        return try {
            ByteArrayOutputStream().use { output ->
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                if (!ok) throw IllegalArgumentException("Image could not be encoded as JPEG")
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
