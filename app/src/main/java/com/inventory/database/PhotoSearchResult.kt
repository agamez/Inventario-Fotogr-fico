package com.inventory.database

/**
 * One row of the global photo search: a light projection of a photo plus
 * the names of the box and location it lives in. Deliberately carries no
 * image BLOB so search never hauls megabytes through the cursor.
 *
 * [boxName] and [locationName] are null for a detached photo (no parent
 * box) and for a box whose location is gone; [boxId] is null in exactly
 * those cases, which is what the UI uses to decide whether the row can
 * jump anywhere.
 */
data class PhotoSearchResult(
    val photoId: Long,
    val boxId: Long?,
    val description: String,
    val note: String?,
    val createdAt: Long,
    val boxName: String?,
    val locationName: String?,
)
