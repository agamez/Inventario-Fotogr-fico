package com.inventory.di

import android.content.Context
import android.os.Environment
import com.inventory.storage.AndroidExifEditor
import com.inventory.storage.ExifEditor
import com.inventory.storage.FileBackend
import com.inventory.storage.ROOT_FOLDER_NAME
import com.inventory.storage.StorageAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Hilt wiring of the platform-neutral storage core in `:shared`. The
 * shared [FileBackend] is a plain constructor class (no DI annotations
 * survive the move), so the Android-only pieces it used to reach for
 * directly are injected here: the root folder
 * (`/sdcard/PhotoInventory`), the All-Files-Access check ([StorageAccess])
 * and the EXIF engine ([AndroidExifEditor] behind the [ExifEditor] seam).
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideExifEditor(): ExifEditor = AndroidExifEditor()

    @Provides
    @Singleton
    fun provideFileBackend(
        @ApplicationContext context: Context,
        exifEditor: ExifEditor,
    ): FileBackend = FileBackend(
        root = File(Environment.getExternalStorageDirectory(), ROOT_FOLDER_NAME),
        accessCheck = { StorageAccess.hasStorageAccess(context) },
        exif = exifEditor,
    )
}
