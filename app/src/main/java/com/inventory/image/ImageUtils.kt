package com.inventory.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Normalize/compress pipeline for every captured or picked image.
 *
 * [normalizeToJpegBlob] is the single choke point all image bytes pass
 * through before being stored in the `photos.image` BLOB column, keeping
 * the database small and memory-friendly. Callers must run it off the
 * main thread (e.g. with `withContext(Dispatchers.IO)`).
 */
object ImageUtils {

    /** Largest allowed dimension (width or height) in pixels. */
    const val MAX_DIMENSION_PX = 1600

    /** JPEG quality the compression loop starts at. */
    const val INITIAL_JPEG_QUALITY = 85

    /** JPEG quality floor for the compression loop. */
    const val MIN_JPEG_QUALITY = 40

    /** Target maximum size of the stored JPEG blob, in bytes. */
    const val MAX_BLOB_BYTES = 2 * 1024 * 1024

    private const val JPEG_QUALITY_STEP = 15

    /**
     * Decodes [input], downsamples/scales it so neither dimension exceeds
     * [MAX_DIMENSION_PX], and re-encodes it as JPEG, lowering quality until
     * the output fits within [MAX_BLOB_BYTES] (or [MIN_JPEG_QUALITY] is hit).
     *
     * @throws IllegalArgumentException if [input] is empty or cannot be
     * decoded as an image (the feature layer converts this into a
     * user-facing error).
     */
    fun normalizeToJpegBlob(input: ByteArray): ByteArray {
        require(input.isNotEmpty()) { "Image data is empty; nothing to normalize." }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException(
                "Image data is corrupt or in an unsupported format; it could not be decoded.",
            )
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val sampled = BitmapFactory.decodeByteArray(input, 0, input.size, decodeOptions)
            ?: throw IllegalArgumentException(
                "Image data is corrupt or in an unsupported format; it could not be decoded.",
            )

        val largestSampledDimension = maxOf(sampled.width, sampled.height)
        val bitmap = if (largestSampledDimension > MAX_DIMENSION_PX) {
            val scale = MAX_DIMENSION_PX.toFloat() / largestSampledDimension
            val targetWidth = (sampled.width * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (sampled.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(sampled, targetWidth, targetHeight, true)
            if (scaled !== sampled) {
                sampled.recycle()
            }
            scaled
        } else {
            sampled
        }

        try {
            var quality = INITIAL_JPEG_QUALITY
            while (true) {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                val bytes = output.toByteArray()
                if (bytes.size <= MAX_BLOB_BYTES || quality <= MIN_JPEG_QUALITY) {
                    return bytes
                }
                quality = (quality - JPEG_QUALITY_STEP).coerceAtLeast(MIN_JPEG_QUALITY)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Largest power-of-two sample size keeping the decoded image within [MAX_DIMENSION_PX]. */
    private fun computeInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val largestDimension = maxOf(width, height)
        while (largestDimension / (sampleSize * 2) >= MAX_DIMENSION_PX) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
