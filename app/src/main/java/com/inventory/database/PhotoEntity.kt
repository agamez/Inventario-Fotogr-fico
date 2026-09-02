package com.inventory.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = ContainerEntity::class,
            parentColumns = ["id"],
            childColumns = ["nodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("nodeId")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeId: Long?,
    val image: ByteArray,
    val description: String,
    val note: String?,
    val createdAt: Long = System.currentTimeMillis(),
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PhotoEntity

        if (id != other.id) return false
        if (nodeId != other.nodeId) return false
        if (!image.contentEquals(other.image)) return false
        if (description != other.description) return false
        if (note != other.note) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (nodeId?.hashCode() ?: 0)
        result = 31 * result + image.contentHashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + (note?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
