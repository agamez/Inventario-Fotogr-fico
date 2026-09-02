package com.inventory.cache

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The disposable index database ("cache.db"): a rebuildable mirror of the
 * filesystem tree, never a source of truth, never migrated — wipe it and
 * let [InventoryScanner] rescan and everything is back. Version 1 starts
 * here precisely because no version will ever need migrating.
 */
@Database(
    entities = [CacheEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class CacheDatabase : RoomDatabase() {

    abstract fun cacheDao(): CacheDao
}
