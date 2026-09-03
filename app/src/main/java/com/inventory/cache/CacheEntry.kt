package com.inventory.cache

import androidx.room.Entity

/**
 * One row of the disposable storage index: a mirror of a filesystem entry
 * (folder or photo) in the inventory tree, keyed by its path RELATIVE to
 * the storage root (root = `""`, e.g. `"Building"`, `"Building/Shelf A"`)
 * so the same row is valid for every backend (SAF tree or
 * `/sdcard/PhotoInventory`). The row holds no image bytes — the UI loads
 * the entry through the active [com.inventory.storage.StorageBackend];
 * photos additionally carry the EXIF triple ([description], [note],
 * [dateTaken]) parsed by the scanner.
 *
 * [parentRel] is the relative path of the containing folder, or null for
 * entries that live directly in the inventory root (SQL NULL, so queries
 * compare it with `IS`). [documentUri] is the SAF locator captured during
 * the scan that found this entry — null in File mode and purely an
 * optional hint; the relative path stays the identity.
 */
@Entity(tableName = "index_entries", primaryKeys = ["relPath"])
data class CacheEntry(
    val relPath: String,
    val parentRel: String?,
    val name: String,
    val kind: Int,
    val isConflict: Boolean = false,
    val description: String? = null,
    val note: String? = null,
    val dateTaken: Long? = null,
    val lastModified: Long = 0L,
    val size: Long = 0L,
    val hasCover: Boolean = false,
    val documentUri: String? = null,
) {

    companion object {
        /** kind value: a container folder. */
        const val KIND_CONTAINER = 0

        /** kind value: a photo JPEG file. */
        const val KIND_PHOTO = 1
    }
}
