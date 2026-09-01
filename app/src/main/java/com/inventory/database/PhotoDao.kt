package com.inventory.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    /** SQLite LIKE is case-insensitive for ASCII — acceptable for search. */
    @Query(
        "SELECT * FROM photos WHERE boxId = :boxId AND (:query = '' OR description LIKE '%' || :query || '%') " +
            "ORDER BY createdAt DESC",
    )
    fun observePhotosForBox(boxId: Long, query: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE id = :id")
    fun observePhoto(id: Long): Flow<PhotoEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: PhotoEntity): Long

    @Update
    suspend fun update(photo: PhotoEntity)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Atomic move — photos never linger detached from a box. */
    @Query("UPDATE photos SET boxId = :targetBoxId WHERE id IN (:photoIds)")
    suspend fun movePhotos(photoIds: List<Long>, targetBoxId: Long)

    @Query("SELECT COUNT(*) FROM photos WHERE boxId = :boxId")
    suspend fun countInBox(boxId: Long): Int
}
