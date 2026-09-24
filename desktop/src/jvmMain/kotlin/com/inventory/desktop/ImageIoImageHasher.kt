package com.inventory.desktop

import com.inventory.storage.DHash
import com.inventory.storage.ImageHasher
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * The desktop [ImageHasher], backed by `javax.imageio`: decodes the
 * payload with [ImageIO.read] and draws it into a
 * [BufferedImage.TYPE_BYTE_GRAY] at the dHash grid size, whose raster
 * samples are the luminance values. A payload that cannot be decoded
 * yields null.
 *
 * Only pixel data is inspected; the original bytes are never modified or
 * re-encoded.
 */
class ImageIoImageHasher : ImageHasher {

    override fun grayGrid(bytes: ByteArray): IntArray? {
        val source = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return null
        val gray = BufferedImage(DHash.GRID_WIDTH, DHash.GRID_HEIGHT, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = gray.createGraphics()
        try {
            graphics.drawImage(source, 0, 0, DHash.GRID_WIDTH, DHash.GRID_HEIGHT, null)
        } finally {
            graphics.dispose()
        }
        val raster = gray.raster
        val grid = IntArray(DHash.GRID_SIZE)
        var index = 0
        for (row in 0 until DHash.GRID_HEIGHT) {
            for (column in 0 until DHash.GRID_WIDTH) {
                grid[index++] = raster.getSample(column, row, 0)
            }
        }
        return grid
    }
}
