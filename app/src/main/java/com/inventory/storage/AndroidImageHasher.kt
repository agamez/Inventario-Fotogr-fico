package com.inventory.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import javax.inject.Inject

/**
 * The Android [ImageHasher]: decodes the payload with [BitmapFactory],
 * downscales it to the dHash grid with [Bitmap.createScaledBitmap] and
 * reads the resulting pixels back as Rec. 601 luminance. A payload the
 * platform cannot decode yields null.
 *
 * Only pixel data is inspected; the original bytes are never modified or
 * re-encoded.
 */
class AndroidImageHasher @Inject constructor() : ImageHasher {

    override fun grayGrid(bytes: ByteArray): IntArray? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = if (source.width == DHash.GRID_WIDTH && source.height == DHash.GRID_HEIGHT) {
            source
        } else {
            Bitmap.createScaledBitmap(source, DHash.GRID_WIDTH, DHash.GRID_HEIGHT, true)
        }
        try {
            val pixels = IntArray(DHash.GRID_SIZE)
            scaled.getPixels(
                pixels,
                0,
                DHash.GRID_WIDTH,
                0,
                0,
                DHash.GRID_WIDTH,
                DHash.GRID_HEIGHT,
            )
            val grid = IntArray(DHash.GRID_SIZE)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                grid[index] = (red * 299 + green * 587 + blue * 114) / 1000
            }
            return grid
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }
}
