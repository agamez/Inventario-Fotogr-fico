package com.inventory.storage

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden tests for [DHash]: the bit order is contractual (row-major, the
 * first comparison in the most significant bit) and [DHash.hamming] must
 * be a plain pop-count of the XOR.
 */
class DHashTest {

    private fun grid(fill: (row: Int, column: Int) -> Int): IntArray {
        val cells = IntArray(DHash.GRID_SIZE)
        for (row in 0 until DHash.GRID_HEIGHT) {
            for (column in 0 until DHash.GRID_WIDTH) {
                cells[row * DHash.GRID_WIDTH + column] = fill(row, column)
            }
        }
        return cells
    }

    @Test
    fun alternatingGridPacksRowMajorMostSignificantBitFirst() {
        // Even rows descend then rise (comparisons 1,0,1,0…) → 10101010;
        // odd rows mirror it → 01010101. Eight rows of that pattern.
        val alternating = grid { row, column ->
            if ((row + column) % 2 == 0) 10 else 0
        }
        assertEquals(0xAA55AA55AA55AA55uL.toLong(), DHash.fromGrid(alternating))
    }

    @Test
    fun strictlyIncreasingGridIsAllZeroBits() {
        val increasing = grid { row, column -> row * DHash.GRID_WIDTH + column }
        assertEquals(0L, DHash.fromGrid(increasing))
    }

    @Test
    fun strictlyDecreasingGridIsAllOneBits() {
        val decreasing = grid { row, column ->
            DHash.GRID_SIZE - 1 - (row * DHash.GRID_WIDTH + column)
        }
        assertEquals(-1L, DHash.fromGrid(decreasing))
    }

    @Test
    fun singleFlippedComparisonSetsExactlyTheFirstBit() {
        val base = grid { row, column ->
            if (row == 0 && column == 0) 1 else if (row == 0 && column == 1) 0 else column
        }
        // Row 0 is 1,0,2,3,…: only comparison 0→1 flips, so the first
        // (most significant) bit is the only one set.
        assertEquals(Long.MIN_VALUE, DHash.fromGrid(base))
    }

    @Test
    fun hammingCountsDifferingBits() {
        assertEquals(0, DHash.hamming(0L, 0L))
        assertEquals(3, DHash.hamming(0L, 0b111L))
        assertEquals(64, DHash.hamming(0L, -1L))
        assertEquals(1, DHash.hamming(0L, Long.MIN_VALUE))
    }
}
