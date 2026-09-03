package com.inventory.cache

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The disposable index database ("cache.db"): a rebuildable mirror of the
 * filesystem tree, never a source of truth, never migrated — wipe it and
 * let [InventoryScanner] rescan and everything is back. Version 2 keyed
 * the table on RELATIVE paths (dual-mode storage made absolute File paths
 * the wrong identity); the bump ships with
 * `fallbackToDestructiveMigration` BECAUSE the cache is disposable by
 * design — any future schema change may wipe and rescan, not migrate.
 */
@Database(
    entities = [CacheEntry::class],
    version = 2,
    exportSchema = false,
)
abstract class CacheDatabase : RoomDatabase() {

    abstract fun cacheDao(): CacheDao
}
