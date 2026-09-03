package com.inventory.di

import android.content.Context
import androidx.room.Room
import com.inventory.cache.CacheDao
import com.inventory.cache.CacheDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring of the disposable index. The database file is "cache.db" —
 * a fresh name so the old image-carrying "inventory.db" of the pre-
 * filesystem build is simply ignored (and left behind for the OS to sweep
 * on reinstall); nothing is ever migrated INTO it.
 *
 * [CacheDatabase] schema changes bump the version and fall back to a
 * DESTRUCTIVE migration on purpose: the cache is rebuildable by rescan,
 * so no schema will ever need data migration — wipe, rescan, done.
 */
@Module
@InstallIn(SingletonComponent::class)
object CacheModule {

    @Provides
    @Singleton
    fun provideCacheDatabase(@ApplicationContext context: Context): CacheDatabase =
        Room.databaseBuilder(context, CacheDatabase::class.java, "cache.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideCacheDao(database: CacheDatabase): CacheDao = database.cacheDao()
}
