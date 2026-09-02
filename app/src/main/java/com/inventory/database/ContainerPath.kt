package com.inventory.database

/**
 * One container rendered as a flat "Root / Child / Leaf" path — the row
 * shape used by the move/pick lists where the hierarchy must be visible
 * inside an flat sheet.
 */
data class ContainerPath(
    val id: Long,
    val path: String,
)
