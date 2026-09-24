package com.inventory.di

import android.content.Context
import android.os.Environment
import com.inventory.storage.AndroidExifEditor
import com.inventory.storage.AndroidImageTranscoder
import com.inventory.storage.BackendEntry
import com.inventory.storage.ExifEditor
import com.inventory.storage.FileBackend
import com.inventory.storage.ImageTranscoder
import com.inventory.storage.InventoryReportGenerator
import com.inventory.storage.PhotoMeta
import com.inventory.storage.ROOT_FOLDER_NAME
import com.inventory.storage.SafBackend
import com.inventory.storage.StorageAccess
import com.inventory.storage.StorageBackend
import com.inventory.storage.StorageMode
import com.inventory.storage.StorageModePrefs
import com.inventory.storage.TrashManager
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

    /**
     * The image format converter behind the Android share target: non-JPEG
     * shared images (PNG/WebP) are transcoded to JPEG before they enter the
     * inventory, whose one photo format is JPEG.
     */
    @Provides
    @Singleton
    fun provideImageTranscoder(): ImageTranscoder = AndroidImageTranscoder()

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

    /**
     * The trash manager follows the active storage mode: it is built on a
     * [ModeRoutingBackend] that re-reads [StorageModePrefs] on every call,
     * so a mid-session mode switch never leaves it pointing at the other
     * door. Before a mode is chosen every operation is a harmless no-op.
     */
    @Provides
    @Singleton
    fun provideTrashManager(
        fileBackend: FileBackend,
        safBackend: SafBackend,
        prefs: StorageModePrefs,
    ): TrashManager = TrashManager(
        backend = ModeRoutingBackend(fileBackend, safBackend, prefs),
    )

    /**
     * The report generator follows the active storage mode exactly like the
     * trash manager: it is built on a [ModeRoutingBackend] so it reads and
     * writes through whichever door is currently active (and is a harmless
     * no-op before a mode is chosen).
     */
    @Provides
    @Singleton
    fun provideInventoryReportGenerator(
        fileBackend: FileBackend,
        safBackend: SafBackend,
        prefs: StorageModePrefs,
    ): InventoryReportGenerator = InventoryReportGenerator(
        backend = ModeRoutingBackend(fileBackend, safBackend, prefs),
    )
}

/**
 * A [StorageBackend] that forwards every call to the backend of the mode
 * currently persisted in [prefs] (null mode = safe no-op). It exists so
 * long-lived helpers such as [TrashManager] do not capture one door
 * across a storage-mode switch.
 */
private class ModeRoutingBackend(
    private val fileBackend: FileBackend,
    private val safBackend: SafBackend,
    private val prefs: StorageModePrefs,
) : StorageBackend {

    private fun active(): StorageBackend? = when (prefs.mode()) {
        StorageMode.FILE -> fileBackend
        StorageMode.SAF -> safBackend
        null -> null
    }

    private suspend fun <T> onActive(fallback: T, block: suspend (StorageBackend) -> T): T =
        active()?.let { block(it) } ?: fallback

    override suspend fun listContainers(relPath: String): List<BackendEntry> =
        onActive(emptyList()) { it.listContainers(relPath) }

    override suspend fun listPhotos(relPath: String): List<BackendEntry> =
        onActive(emptyList()) { it.listPhotos(relPath) }

    override suspend fun exists(relPath: String): Boolean =
        onActive(false) { it.exists(relPath) }

    override suspend fun readPhotoMeta(relPath: String): PhotoMeta? =
        onActive(null) { it.readPhotoMeta(relPath) }

    override suspend fun readPhotoBytes(relPath: String): ByteArray? =
        onActive(null) { it.readPhotoBytes(relPath) }

    override suspend fun writePhotoBytes(relPath: String, bytes: ByteArray): Unit =
        onActive(Unit) { it.writePhotoBytes(relPath, bytes) }

    override suspend fun createFolder(parentRel: String, name: String): String =
        onActive("") { it.createFolder(parentRel, name) }

    override suspend fun renameFolder(rel: String, newName: String): String =
        onActive(rel) { it.renameFolder(rel, newName) }

    override suspend fun moveFolder(rel: String, newParentRel: String): Boolean =
        onActive(false) { it.moveFolder(rel, newParentRel) }

    override suspend fun deleteFolderRecursive(rel: String): Unit =
        onActive(Unit) { it.deleteFolderRecursive(rel) }

    override suspend fun deleteFile(rel: String): Boolean =
        onActive(false) { it.deleteFile(rel) }

    override suspend fun coverModel(rel: String): Any? =
        onActive(null) { it.coverModel(rel) }

    override suspend fun photoModel(rel: String): Any? =
        onActive(null) { it.photoModel(rel) }

    override suspend fun setCover(folderRel: String, bytes: ByteArray): Unit =
        onActive(Unit) { it.setCover(folderRel, bytes) }

    override suspend fun removeCover(folderRel: String): Unit =
        onActive(Unit) { it.removeCover(folderRel) }

    override suspend fun hasCover(rel: String): Boolean =
        onActive(false) { it.hasCover(rel) }

    override suspend fun lastModifiedAndSize(rel: String): Pair<Long, Long>? =
        onActive(null) { it.lastModifiedAndSize(rel) }

    override suspend fun documentUriFor(rel: String): String? =
        onActive(null) { it.documentUriFor(rel) }
}
