package com.inventory.database

/**
 * One row of the global photo search: a light projection of a photo plus
 * the full ancestor path of the container it lives in. Deliberately carries
 * no image BLOB so search never hauls megabytes through the cursor.
 *
 * [containerPath] is null for a detached photo ([nodeId] == null) or for a
 * photo whose container is gone; [nodeId] is null in exactly those cases,
 * which is what the UI uses to decide whether the row can jump anywhere.
 */
data class PhotoSearchResult(
    val photoId: Long,
    val nodeId: Long?,
    val description: String,
    val note: String?,
    val createdAt: Long,
    val containerPath: String?,
)
