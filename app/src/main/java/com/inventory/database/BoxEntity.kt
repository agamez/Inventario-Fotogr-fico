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
    val coverImage: ByteArray? = null,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BoxEntity

        if (id != other.id) return false
        if (name != other.name) return false
        if (locationId != other.locationId) return false
        if (createdAt != other.createdAt) return false
        if (!coverImage.contentEquals(other.coverImage)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + locationId.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (coverImage?.contentHashCode() ?: 0)
        return result
    }
}
