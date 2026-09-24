package com.inventory.desktop

import com.inventory.nav.BreadcrumbSegment
import com.inventory.nav.breadcrumbSegments
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure breadcrumb-splitting tests for the shared [breadcrumbSegments]
 * helper used by both the Android and the desktop app bars: root first,
 * the current folder last and marked current, relative paths preserved.
 */
class BreadcrumbTest {

    private val root = "Inventory"

    @Test
    fun rootIsASingleCurrentSegment() {
        assertEquals(
            listOf(BreadcrumbSegment(rel = "", label = root, isCurrent = true)),
            breadcrumbSegments("", root),
        )
    }

    @Test
    fun oneLevelHasRootAndCurrent() {
        assertEquals(
            listOf(
                BreadcrumbSegment(rel = "", label = root, isCurrent = false),
                BreadcrumbSegment(rel = "Building", label = "Building", isCurrent = true),
            ),
            breadcrumbSegments("Building", root),
        )
    }

    @Test
    fun deepPathKeepsEveryAncestorInOrder() {
        assertEquals(
            listOf(
                BreadcrumbSegment(rel = "", label = root, isCurrent = false),
                BreadcrumbSegment(rel = "Building", label = "Building", isCurrent = false),
                BreadcrumbSegment(rel = "Building/Storage unit", label = "Storage unit", isCurrent = false),
                BreadcrumbSegment(rel = "Building/Storage unit/Shelf A", label = "Shelf A", isCurrent = true),
            ),
            breadcrumbSegments("Building/Storage unit/Shelf A", root),
        )
    }

    @Test
    fun onlyTheLastSegmentIsCurrent() {
        val segments = breadcrumbSegments("A/B/C", root)
        assertEquals(listOf(false, false, false, true), segments.map { it.isCurrent })
        assertEquals("", segments.first().rel)
        assertEquals("A/B/C", segments.last().rel)
    }
}
