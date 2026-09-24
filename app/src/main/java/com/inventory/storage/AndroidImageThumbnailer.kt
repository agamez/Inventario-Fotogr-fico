package com.inventory.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * The Android [ImageThumbnailer], backed by [BitmapFactory]: decodes the
 * payload, applies the EXIF orientation ([ExifInterface] tag 0x0112) so
 * the preview is upright, downscales it with [Bitmap.createScaledBitmap]
 * and re-encodes it as a JPEG at [ImageThumbnailer.THUMBNAIL_QUALITY].
 *
 * The original bytes are only READ — never written back or re-encoded —
 * and a payload the platform cannot decode yields null so the report
 * falls back to a placeholder cell.
 */
class AndroidImageThumbnailer @Inject constructor() : ImageThumbnailer {

    override fun thumbnail(bytes: ByteArray, maxDim: Int): ByteArray? {
        if (maxDim <= 0) return null
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (exception: Exception) {
            null
        } ?: return null
        val oriented = applyOrientation(decoded, bytes)
        val scaled = scaleDown(oriented, maxDim)
        return try {
            ByteArrayOutputStream().use { output ->
                val ok = scaled.compress(
                    Bitmap.CompressFormat.JPEG,
                    ImageThumbnailer.THUMBNAIL_QUALITY,
                    output,
                )
                if (ok) output.toByteArray() else null
            }
        } finally {
            scaled.recycle()
        }
    }

    /** Orientation of the payload; defaults to normal when the tag is absent/unreadable. */
    private fun orientationOf(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (exception: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * Returns [source] rotated/flipped upright, recycling [source] when a
     * new bitmap had to be produced (never when it is returned as-is).
     *
     * The EXIF orientation codes map to these source→target affine
     * transforms (source W×H, target H×W for codes 5..8):
     * 2 flip-X, 3 rotate 180, 4 flip-Y, 5 transpose, 6 rotate 90 CW,
     * 7 transverse, 8 rotate 270 CW.
     */
    private fun applyOrientation(source: Bitmap, bytes: ByteArray): Bitmap {
        val width = source.width.toFloat()
        val height = source.height.toFloat()
        val values = when (orientationOf(bytes)) {
            2 -> floatArrayOf(-1f, 0f, width, 0f, 1f, 0f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, width, 0f, -1f, height, 0f, 0f, 1f)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, height, 0f, 0f, 1f)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            6 -> floatArrayOf(0f, -1f, height, 1f, 0f, 0f, 0f, 0f, 1f)
            7 -> floatArrayOf(0f, -1f, height, -1f, 0f, width, 0f, 0f, 1f)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width, 0f, 0f, 1f)
            else -> return source
        }
        val matrix = Matrix().apply { setValues(values) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    /** Downscales [source] so its longest side is at most [maxDim]. */
    private fun scaleDown(source: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDim) return source
        val scale = maxDim.toFloat() / longest
        val width = maxOf(1, (source.width * scale).roundToInt())
        val height = maxOf(1, (source.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }
}
