package com.inventory.storage

/**
 * Decodes image bytes to the tiny luminance grid the difference hash
 * needs, or null when the payload is not a decodable image.
 *
 * The interface lives in `:shared` (no Android imports); each platform
 * injects its native codec ([android.graphics.BitmapFactory] on Android,
 * `javax.imageio` on the desktop), mirroring [ImageTranscoder].
 */
interface ImageHasher {

    /**
     * Decodes [bytes] into a [DHash.GRID_WIDTH] × [DHash.GRID_HEIGHT]
     * luminance grid, row-major, one value per cell in `0..255`, or null
     * when the payload cannot be decoded.
     */
    fun grayGrid(bytes: ByteArray): IntArray?
}

/**
 * Difference hash (dHash) over a 9x8 luminance grid: 8 horizontal
 * comparisons per row, 64 bits total.
 *
 * Bit order is contractual: walking the grid row by row, each comparison
 * `grid[r * 9 + c] > grid[r * 9 + c + 1]` (strictly brighter than the
 * pixel to its right) appends one bit — 1 when true, 0 otherwise — with
 * `hash = (hash shl 1) or bit`. The first comparison therefore lands in
 * bit 63 (the most significant bit) and the last in bit 0, packed
 * row-major.
 */
object DHash {

    /** Columns of the luminance grid (one more than the comparisons per row). */
    const val GRID_WIDTH = 9

    /** Rows of the luminance grid (one per output hash row). */
    const val GRID_HEIGHT = 8

    /** Cells of a full grid: [GRID_WIDTH] * [GRID_HEIGHT]. */
    const val GRID_SIZE = GRID_WIDTH * GRID_HEIGHT

    /**
     * Maximum Hamming distance still treated as "identical-looking". A
     * small allowance absorbs JPEG/scale jitter between resaves of the
     * same shot, while staying far below the distance of unrelated images.
     */
    const val HAMMING_THRESHOLD = 4

    /**
     * Hashes a row-major [GRID_SIZE] luminance grid; see the class
     * comment for the exact bit order. Throws [IllegalArgumentException]
     * when [grid] is too short.
     */
    fun fromGrid(grid: IntArray): Long {
        require(grid.size >= GRID_SIZE) { "grid must have at least $GRID_SIZE cells" }
        var hash = 0L
        for (row in 0 until GRID_HEIGHT) {
            val base = row * GRID_WIDTH
            for (column in 0 until GRID_WIDTH - 1) {
                val bit = if (grid[base + column] > grid[base + column + 1]) 1L else 0L
                hash = (hash shl 1) or bit
            }
        }
        return hash
    }

    /** Number of differing bits between two hashes (0..64). */
    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
