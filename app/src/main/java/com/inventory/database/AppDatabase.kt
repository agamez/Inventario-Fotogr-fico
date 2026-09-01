package com.inventory.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocationEntity::class,
        BoxEntity::class,
        PhotoEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao

    abstract fun boxDao(): BoxDao

    abstract fun photoDao(): PhotoDao
}
