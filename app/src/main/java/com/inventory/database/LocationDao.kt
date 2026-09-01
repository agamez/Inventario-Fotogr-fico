package com.inventory.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {

    @Query("SELECT * FROM locations ORDER BY name COLLATE NOCASE ASC")
    fun observeLocations(): Flow<List<LocationEntity>>

    @Transaction
    @Query("SELECT * FROM locations ORDER BY name COLLATE NOCASE ASC")
    fun observeLocationsWithBoxes(): Flow<List<LocationWithBoxes>>

    @Query("SELECT * FROM locations WHERE id = :id")
    fun observeLocation(id: Long): Flow<LocationEntity?>

    /** Duplicate-name check (case-insensitive); pass id = 0 when inserting. */
    @Query("SELECT COUNT(*) FROM locations WHERE name = :name COLLATE NOCASE AND id != :id")
    suspend fun countNamesLike(name: String, id: Long): Int

    /** Deletion guard: a location with boxes cannot be removed. */
    @Query("SELECT COUNT(*) FROM boxes WHERE locationId = :locationId")
    suspend fun boxCountIn(locationId: Long): Int

    /** Returns -1 when the unique name conflicts with an existing location. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(location: LocationEntity): Long

    @Update
    suspend fun update(location: LocationEntity)

    @Query("DELETE FROM locations WHERE id = :id")
    suspend fun deleteById(id: Long)
}
