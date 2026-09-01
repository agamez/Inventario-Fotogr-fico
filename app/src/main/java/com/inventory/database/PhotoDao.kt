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

    /**
     * Global search across every box and location, newest first, capped at
     * 100 rows; a blank [query] browses everything. Matches the description
     * or the note — SQLite LIKE is case-insensitive for ASCII, consistent
     * with [observePhotosForBox], and a null note simply never matches.
     * The projection omits the image BLOB on purpose, and the LEFT JOINs
     * keep detached photos (null `boxId`) visible with null box/location
     * names.
     */
    @Query(
        "SELECT photos.id AS photoId, photos.boxId AS boxId, " +
            "photos.description AS description, photos.note AS note, " +
            "photos.createdAt AS createdAt, " +
            "boxes.name AS boxName, locations.name AS locationName " +
            "FROM photos " +
            "LEFT JOIN boxes ON photos.boxId = boxes.id " +
            "LEFT JOIN locations ON boxes.locationId = locations.id " +
            "WHERE :query = '' OR photos.description LIKE '%' || :query || '%' " +
            "OR photos.note LIKE '%' || :query || '%' " +
            "ORDER BY photos.createdAt DESC LIMIT 100",
    )
    fun searchAll(query: String): Flow<List<PhotoSearchResult>>

    /** The stored JPEG blob of photo [photoId], or null when the row is gone. */
    @Query("SELECT image FROM photos WHERE id = :photoId")
    suspend fun imageById(photoId: Long): ByteArray?

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
