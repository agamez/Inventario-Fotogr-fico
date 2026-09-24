package com.inventory.desktop

import com.inventory.desktop.ui.nextViewerIndex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The viewer's pure index-advance rule: deleting the current photo keeps
 * the same slot (the following photo slides in) and clamps when the last
 * photo of the list goes away; deleting the only photo closes the viewer.
 */
class PhotoViewerIndexTest {

    @Test
    fun deletesMiddleAdvancesToTheFollowingPhoto() {
        assertEquals(1, nextViewerIndex(deletedIndex = 1, previousCount = 3))
    }

    @Test
    fun deletesFirstShowsTheNewFirstPhoto() {
        assertEquals(0, nextViewerIndex(deletedIndex = 0, previousCount = 3))
    }

    @Test
    fun deletesLastClampsToTheNewLastIndex() {
        assertEquals(1, nextViewerIndex(deletedIndex = 2, previousCount = 3))
    }

    @Test
    fun deletesOnlyPhotoMeansClose() {
        assertEquals(-1, nextViewerIndex(deletedIndex = 0, previousCount = 1))
    }

    @Test
    fun singleRemainingPhotoIsClampedToZero() {
        assertEquals(0, nextViewerIndex(deletedIndex = 4, previousCount = 2))
    }
}
