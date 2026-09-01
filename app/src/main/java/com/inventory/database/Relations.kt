package com.inventory.database

import androidx.room.Embedded
import androidx.room.Relation

/**
 * A location together with all boxes it contains.
 * Loaded transactionally by [LocationDao.observeLocationsWithBoxes].
 */
data class LocationWithBoxes(
    @Embedded val location: LocationEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "locationId",
    )
    val boxes: List<BoxEntity>,
)

/**
 * A box plus the number of photos it holds.
 * [photoCount] is fed by a COUNT subquery in [BoxDao] so photo BLOBs are
 * never loaded when rendering lists.
 */
data class BoxWithPhotoCount(
    @Embedded val box: BoxEntity,
    val photoCount: Int,
)
