package com.inventory.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One node of the inventory tree: a container (Building, Storage unit,
 * Shelf, box, …) at unbounded depth. `parentId == null` marks a top-level
 * container; deleting a container cascades its whole subtree (and their
 * photos) through the self-referencing FK.
 */
@Entity(
    tableName = "containers",
    foreignKeys = [
        ForeignKey(
            entity = ContainerEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("parentId")],
)
data class ContainerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long?,
    val coverImage: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ContainerEntity

        if (id != other.id) return false
        if (name != other.name) return false
        if (parentId != other.parentId) return false
        if (createdAt != other.createdAt) return false
        if (!coverImage.contentEquals(other.coverImage)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (parentId?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (coverImage?.contentHashCode() ?: 0)
        return result
    }
}
