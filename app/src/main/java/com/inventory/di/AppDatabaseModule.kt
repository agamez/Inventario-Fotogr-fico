package com.inventory.di

import android.content.Context
import androidx.room.Room
import com.inventory.database.AppDatabase
import com.inventory.database.BoxDao
import com.inventory.database.LocationDao
import com.inventory.database.PhotoDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppDatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "inventory.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

    @Provides
    @Singleton
    fun provideLocationDao(database: AppDatabase): LocationDao = database.locationDao()

    @Provides
    @Singleton
    fun provideBoxDao(database: AppDatabase): BoxDao = database.boxDao()

    @Provides
    @Singleton
    fun providePhotoDao(database: AppDatabase): PhotoDao = database.photoDao()
}
