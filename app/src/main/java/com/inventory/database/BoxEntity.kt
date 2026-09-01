package com.inventory.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "boxes",
    foreignKeys = [
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("locationId"),
        Index(value = ["name"], unique = true),
    ],
)
data class BoxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val locationId: Long,
    val createdAt: Long = System.currentTimeMillis(),
)
