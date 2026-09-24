package com.inventory.nav

/**
 * One segment of a container breadcrumb.
 *
 * [rel] is the relative path of the container the segment addresses (`""`
 * is the storage root); [label] is its display name (the addressed folder's
 * last path element, or [rootLabel] for the root); [isCurrent] marks the
 * segment of the folder on screen — it is highlighted and not clickable.
 */
data class BreadcrumbSegment(
    val rel: String,
    val label: String,
    val isCurrent: Boolean,
)

/**
 * Splits a container's relative path into its breadcrumb segments, root
 * first and ending with the current folder.
 *
 * At the root this is a single current segment labelled [rootLabel];
 * otherwise the root segment precedes every ancestor and the addressed
 * folder, in order. The root label is caller-supplied so each platform
 * keeps its own resource for the user-visible "Inventory" string.
 */
fun breadcrumbSegments(folderRel: String, rootLabel: String): List<BreadcrumbSegment> {
    val names = folderRel.split('/').filter { it.isNotEmpty() }
    val segments = mutableListOf(
        BreadcrumbSegment(rel = "", label = rootLabel, isCurrent = names.isEmpty()),
    )
    var prefix = ""
    names.forEachIndexed { index, name ->
        prefix = if (prefix.isEmpty()) name else "$prefix/$name"
        segments += BreadcrumbSegment(
            rel = prefix,
            label = name,
            isCurrent = index == names.lastIndex,
        )
    }
    return segments
}
