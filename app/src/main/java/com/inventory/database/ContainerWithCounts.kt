package com.inventory.database

import androidx.room.Embedded

/**
 * A container plus the number of direct child containers and direct photos
 * it holds. The counts come from COUNT subqueries in [ContainerDao] so
 * neither child rows nor photo BLOBs are loaded when rendering lists.
 */
data class ContainerWithCounts(
    @Embedded val container: ContainerEntity,
    val childCount: Int,
    val photoCount: Int,
)
