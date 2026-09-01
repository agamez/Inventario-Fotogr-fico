package com.inventory.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BoxDao {

    @Query(
        "SELECT boxes.*, (SELECT COUNT(*) FROM photos WHERE photos.boxId = boxes.id) AS photoCount " +
            "FROM boxes WHERE locationId = :locationId ORDER BY name COLLATE NOCASE ASC",
    )
    fun observeBoxesForLocation(locationId: Long): Flow<List<BoxWithPhotoCount>>

    @Query("SELECT * FROM boxes WHERE id = :id")
    fun observeBox(id: Long): Flow<BoxEntity?>

    /** Full list for the move-to-box picker. */
    @Query("SELECT * FROM boxes ORDER BY name COLLATE NOCASE ASC")
    fun observeAllBoxes(): Flow<List<BoxEntity>>

    /** Duplicate-name check (case-insensitive); pass id = 0 when inserting. */
    @Query("SELECT COUNT(*) FROM boxes WHERE name = :name COLLATE NOCASE AND id != :id")
    suspend fun countNamesLike(name: String, id: Long): Int

    /** Returns -1 when the unique name conflicts with an existing box. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(box: BoxEntity): Long

    @Update
    suspend fun update(box: BoxEntity)

    @Query("DELETE FROM boxes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE boxes SET locationId = :newLocationId WHERE id = :boxId")
    suspend fun moveToLocation(boxId: Long, newLocationId: Long)
}
