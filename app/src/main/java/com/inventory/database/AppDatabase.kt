package com.inventory.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LocationEntity::class,
        BoxEntity::class,
        PhotoEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao

    abstract fun boxDao(): BoxDao

    abstract fun photoDao(): PhotoDao

    companion object {
        /**
         * Adds the nullable `coverImage` BLOB column to boxes and locations.
         * Existing rows keep NULL, so user data survives untouched.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE boxes ADD COLUMN coverImage BLOB")
                db.execSQL("ALTER TABLE locations ADD COLUMN coverImage BLOB")
            }
        }
    }
}
